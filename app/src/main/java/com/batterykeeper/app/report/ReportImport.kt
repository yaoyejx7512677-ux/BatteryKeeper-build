package com.batterykeeper.app.report

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.batterykeeper.app.data.HealthReport
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 澎湃OS 电池检测报告导入。
 *
 * v1.5.6：继续兼容 HyperOS 3/4 Bugreport，并新增系统版本识别；截图 OCR 改为
 * 轻量 Latin 模型 + 坐标分行解析，避免历史列表中跨行串值和系统版本错配。
 */
object ReportImport {

    // ---------- Bug 报告 ZIP ----------

    private val reCycle = Regex("POWER_SUPPLY_CYCLE_COUNT\\s*[=:]\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val reFull = Regex("POWER_SUPPLY_CHARGE_FULL\\s*[=:]\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val reDesign = Regex("POWER_SUPPLY_CHARGE_FULL_DESIGN\\s*[=:]\\s*(\\d+)", RegexOption.IGNORE_CASE)

    private val reEstimated = Regex("Estimated battery capacity[:\\s]*(\\d+)", RegexOption.IGNORE_CASE)
    private val reLearnedMin = Regex("Min learned battery capacity[:\\s]*(\\d+)", RegexOption.IGNORE_CASE)
    private val reLearnedMax = Regex("Max learned battery capacity[:\\s]*(\\d+)", RegexOption.IGNORE_CASE)
    private val reLearnedLast = Regex("Last learned battery capacity[:\\s]*(\\d+)", RegexOption.IGNORE_CASE)

    // HyperOS 3/4 / Android 新版 AIDL HealthInfo。
    private val reHealthCycle = Regex("batteryCycleCount[:=\\s]*(\\d+)", RegexOption.IGNORE_CASE)
    private val reHealthFull = Regex("batteryFullChargeUah[:=\\s]*(\\d+)", RegexOption.IGNORE_CASE)
    private val reHealthDesign = Regex("batteryFullChargeDesignCapacityUah[:=\\s]*(\\d+)", RegexOption.IGNORE_CASE)

    // dumpsys android.hardware.health.IHealth/default 等多行 Health HAL 输出。
    private val reHealthCycleLine = Regex("^\\s*(?:battery )?cycle count[:=\\s]+(\\d+)\\s*$", RegexOption.IGNORE_CASE)
    private val reHealthFullLine = Regex("^\\s*(?:battery )?Full charge[:=\\s]+(\\d+)\\s*$", RegexOption.IGNORE_CASE)
    private val reHealthDesignLine = Regex(
        "^\\s*(?:battery )?(?:Full charge design capacity|Design capacity)[:=\\s]+(\\d+)\\s*$",
        RegexOption.IGNORE_CASE,
    )

    private val reTimeFromName = Regex("(\\d{4})-(\\d{2})-(\\d{2})[-_](\\d{2})[-_](\\d{2})")
    private val reSystemVersion = Regex("\\bOS[234](?:\\.\\d+){2,4}(?:\\.[A-Z0-9]+)?\\b", RegexOption.IGNORE_CASE)
    private val reBugreportOsProperty = Regex(
        "(?:ro\\.mi\\.os\\.version\\.incremental|ro\\.system\\.build\\.version\\.incremental)\\s*[=:]\\s*([^\\s]+)",
        RegexOption.IGNORE_CASE,
    )

    private const val MAX_MAIN_REPORT_CHARS = 256_000_000
    private const val MAX_RELATED_TEXT_CHARS = 2_000_000
    private const val MAX_TOTAL_SCAN_CHARS = 300_000_000

    private const val P_AIDL = 40
    private const val P_HEALTH_HAL = 35
    private const val P_POWER_SUPPLY = 30
    private const val P_BATTERYSTATS = 10

    private data class Candidate(val value: Int, val priority: Int, val origin: String)

    private fun choose(current: Candidate?, candidate: Candidate): Candidate =
        if (current == null || candidate.priority > current.priority) candidate else current

    /** 容量字段统一为 mAh；兼容 uAh 与已经是 mAh 的文本。 */
    private fun capacityToMah(raw: String): Int =
        raw.toLongOrNull()?.let { value ->
            when {
                value <= 0L -> -1
                value >= 100_000L -> (value / 1000L).toInt()
                else -> value.toInt()
            }
        } ?: -1

    suspend fun fromBugReport(context: Context, uri: Uri): HealthReport =
        withContext(Dispatchers.IO) {
            var full: Candidate? = null
            var design: Candidate? = null
            var cycle: Candidate? = null
            var estimated: Candidate? = null
            var learnedMin: Candidate? = null
            var learnedMax: Candidate? = null
            var learnedLast: Candidate? = null

            var reportTime = queryDisplayName(context, uri)?.let(::parseTimeFromName) ?: 0L
            var reportSystemVersion = ""
            var sawZipEntry = false
            var sawAidl = false
            var sawHealthHal = false
            var sawPowerSupply = false
            var sawBatteryStats = false
            var totalScanned = 0

            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null && totalScanned < MAX_TOTAL_SCAN_CHARS) {
                        sawZipEntry = true
                        if (!entry.isDirectory) {
                            val name = entry.name
                            val lowerName = name.lowercase(Locale.US)
                            val baseName = name.substringAfterLast('/')
                            val lowerBase = baseName.lowercase(Locale.US)
                            val isMainBugReport = lowerBase.startsWith("bugreport-") && lowerBase.endsWith(".txt")
                            if (reportTime == 0L && isMainBugReport) {
                                reportTime = parseTimeFromName(baseName) ?: 0L
                            }

                            val textLike = lowerBase.endsWith(".txt") || lowerBase.endsWith(".log") ||
                                lowerBase.endsWith(".dump") || !lowerBase.contains('.')
                            val relevantName = isMainBugReport ||
                                lowerName.contains("dumpstate") ||
                                lowerName.contains("health") ||
                                lowerName.contains("battery") ||
                                lowerName.contains("power_supply")
                            val shouldScan = textLike && relevantName

                            if (shouldScan) {
                                val entryLimit = if (isMainBugReport) MAX_MAIN_REPORT_CHARS else MAX_RELATED_TEXT_CHARS
                                val reader = zis.bufferedReader(Charsets.UTF_8)
                                var inHealthDump = false
                                var line = reader.readLine()
                                var entryScanned = 0
                                while (line != null && entryScanned < entryLimit && totalScanned < MAX_TOTAL_SCAN_CHARS) {
                                    entryScanned += line.length
                                    totalScanned += line.length

                                    if (reportSystemVersion.isBlank()) {
                                        val propertyValue = reBugreportOsProperty.find(line)?.groupValues?.getOrNull(1)
                                        val propertyOs = propertyValue?.let { reSystemVersion.find(it)?.value }
                                        val mainReportOs = if (isMainBugReport) reSystemVersion.find(line)?.value else null
                                        reportSystemVersion = (propertyOs ?: mainReportOs)?.uppercase(Locale.US).orEmpty()
                                    }

                                    if (
                                        line.contains("DUMP OF SERVICE android.hardware.health.IHealth", true) ||
                                        line.contains("DUMP OF SERVICE vendor.hardware.health", true)
                                    ) {
                                        inHealthDump = true
                                        sawHealthHal = true
                                    } else if (
                                        inHealthDump && line.startsWith("---------") &&
                                        (line.contains("duration of dumpsys", true) || line.contains("DUMP OF SERVICE", true))
                                    ) {
                                        inHealthDump = false
                                    }

                                    reHealthFull.find(line)?.let {
                                        val v = capacityToMah(it.groupValues[1])
                                        if (v > 0) full = choose(full, Candidate(v, P_AIDL, "AIDL HealthInfo"))
                                        sawAidl = true
                                    }
                                    reHealthDesign.find(line)?.let {
                                        val v = capacityToMah(it.groupValues[1])
                                        if (v > 0) design = choose(design, Candidate(v, P_AIDL, "AIDL HealthInfo"))
                                        sawAidl = true
                                    }
                                    reHealthCycle.find(line)?.let {
                                        val v = it.groupValues[1].toIntOrNull() ?: -1
                                        if (v >= 0) cycle = choose(cycle, Candidate(v, P_AIDL, "AIDL HealthInfo"))
                                        sawAidl = true
                                    }

                                    if (inHealthDump) {
                                        reHealthFullLine.find(line)?.let {
                                            val v = capacityToMah(it.groupValues[1])
                                            if (v > 0) full = choose(full, Candidate(v, P_HEALTH_HAL, "Health HAL"))
                                            sawHealthHal = true
                                        }
                                        reHealthDesignLine.find(line)?.let {
                                            val v = capacityToMah(it.groupValues[1])
                                            if (v > 0) design = choose(design, Candidate(v, P_HEALTH_HAL, "Health HAL"))
                                            sawHealthHal = true
                                        }
                                        reHealthCycleLine.find(line)?.let {
                                            val v = it.groupValues[1].toIntOrNull() ?: -1
                                            if (v >= 0) cycle = choose(cycle, Candidate(v, P_HEALTH_HAL, "Health HAL"))
                                            sawHealthHal = true
                                        }
                                    }

                                    reFull.find(line)?.let {
                                        val v = capacityToMah(it.groupValues[1])
                                        if (v > 0) full = choose(full, Candidate(v, P_POWER_SUPPLY, "power_supply"))
                                        sawPowerSupply = true
                                    }
                                    reDesign.find(line)?.let {
                                        val v = capacityToMah(it.groupValues[1])
                                        if (v > 0) design = choose(design, Candidate(v, P_POWER_SUPPLY, "power_supply"))
                                        sawPowerSupply = true
                                    }
                                    reCycle.find(line)?.let {
                                        val v = it.groupValues[1].toIntOrNull() ?: -1
                                        if (v >= 0) cycle = choose(cycle, Candidate(v, P_POWER_SUPPLY, "power_supply"))
                                        sawPowerSupply = true
                                    }

                                    reEstimated.find(line)?.let {
                                        val v = capacityToMah(it.groupValues[1])
                                        if (v > 0) estimated = choose(estimated, Candidate(v, P_BATTERYSTATS, "batterystats"))
                                        sawBatteryStats = true
                                    }
                                    reLearnedMin.find(line)?.let {
                                        val v = capacityToMah(it.groupValues[1])
                                        if (v > 0) learnedMin = choose(learnedMin, Candidate(v, P_BATTERYSTATS, "batterystats"))
                                        sawBatteryStats = true
                                    }
                                    reLearnedMax.find(line)?.let {
                                        val v = capacityToMah(it.groupValues[1])
                                        if (v > 0) learnedMax = choose(learnedMax, Candidate(v, P_BATTERYSTATS, "batterystats"))
                                        sawBatteryStats = true
                                    }
                                    reLearnedLast.find(line)?.let {
                                        val v = capacityToMah(it.groupValues[1])
                                        if (v > 0) learnedLast = choose(learnedLast, Candidate(v, P_BATTERYSTATS, "batterystats"))
                                        sawBatteryStats = true
                                    }

                                    line = reader.readLine()
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } ?: throw IllegalStateException("无法读取所选文件")

            if (!sawZipEntry) {
                throw IllegalStateException("所选文件不是有效的 Bug 报告 ZIP")
            }

            val fullValue = full?.value ?: -1
            val designValue = design?.value ?: -1
            val cycleValue = cycle?.value ?: -1
            val estimatedValue = estimated?.value ?: -1
            val learnedMinValue = learnedMin?.value ?: -1
            val learnedMaxValue = learnedMax?.value ?: -1
            val learnedLastValue = learnedLast?.value ?: -1

            if (fullValue <= 0 && cycleValue < 0 && estimatedValue <= 0 && learnedLastValue <= 0) {
                throw IllegalStateException(
                    "报告中未找到电池数据。已兼容 HyperOS 3/4 常见 Health HAL、AIDL HealthInfo、power_supply 与 batterystats 格式；请确认选择的是完整 Bug 报告 ZIP。",
                )
            }

            val format = when {
                sawAidl -> "HyperOS 3/4 / AIDL Health"
                sawHealthHal -> "HyperOS 3/4 / Health HAL"
                sawPowerSupply -> "HyperOS 3/4 / power_supply"
                sawBatteryStats -> "Android batterystats"
                else -> "HyperOS Bugreport"
            }
            val keyCount = listOf(fullValue > 0, designValue > 0, cycleValue >= 0).count { it }
            val confidence = when {
                keyCount >= 3 -> "高"
                keyCount >= 2 || (fullValue > 0 && estimatedValue > 0) -> "中"
                else -> "低"
            }
            val origins = listOfNotNull(
                full?.let { "满充=${it.origin}" },
                design?.let { "设计=${it.origin}" },
                cycle?.let { "循环=${it.origin}" },
                estimated?.let { "估算=${it.origin}" },
            ).joinToString("；")

            buildReport(
                source = encodeBugReportSource(format, confidence, origins, reportSystemVersion),
                timestamp = if (reportTime > 0) reportTime else System.currentTimeMillis(),
                full = fullValue,
                design = designValue,
                cycle = cycleValue,
                estimated = estimatedValue,
                learnedMin = learnedMinValue,
                learnedMax = learnedMaxValue,
                learnedLast = learnedLastValue,
            )
        }

    private fun encodeBugReportSource(
        format: String,
        confidence: String,
        origins: String,
        systemVersion: String,
    ): String =
        listOf("bugreport", format, confidence, origins, systemVersion).joinToString("|")

    data class SourceMeta(
        val kind: String,
        val format: String,
        val confidence: String,
        val origins: String,
        val systemVersion: String = "",
    )

    /** 兼容 v1.5.5 及更早的 source 编码。 */
    fun sourceMeta(source: String): SourceMeta {
        if (source == "screenshot") return SourceMeta("screenshot", "截图识别", "—", "OCR")
        if (source.startsWith("screenshot|")) {
            val version = source.substringAfter('|').substringBefore('|').trim()
            return SourceMeta("screenshot", "截图识别", "—", "OCR", version)
        }
        if (source == "bugreport") return SourceMeta("bugreport", "旧版 Bug 报告", "—", "旧版记录")
        val parts = source.split('|', limit = 5)
        return if (parts.firstOrNull() == "bugreport") {
            SourceMeta(
                kind = "bugreport",
                format = parts.getOrNull(1).orEmpty().ifBlank { "HyperOS Bugreport" },
                confidence = parts.getOrNull(2).orEmpty().ifBlank { "—" },
                origins = parts.getOrNull(3).orEmpty(),
                systemVersion = parts.getOrNull(4).orEmpty(),
            )
        } else {
            SourceMeta(source, source, "—", "")
        }
    }

    /** 当前手机系统版本，优先提取 Xiaomi/HyperOS 的 OSx.x 字串。 */
    fun currentSystemVersion(): String {
        val candidates = listOf(Build.VERSION.INCREMENTAL, Build.DISPLAY)
        candidates.forEach { raw ->
            reSystemVersion.find(raw.orEmpty())?.value?.let { return it.uppercase(Locale.US) }
        }
        return Build.VERSION.INCREMENTAL.orEmpty().ifBlank { Build.DISPLAY.orEmpty() }.ifBlank { "未知" }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            }
        }.getOrNull()

    private fun parseTimeFromName(name: String): Long? {
        return try {
            val m = reTimeFromName.find(name) ?: return null
            val (y, mo, d, h, mi) = m.destructured
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .parse("$y-$mo-$d $h:$mi")?.time
        } catch (_: Exception) {
            null
        }
    }

    // ---------- 截图识别 ----------

    // v1.5.6 使用轻量 Latin OCR。图片中的关键数据均包含数字 / % / mAh / OS 字样，
    // 再结合 OCR 坐标按“日期所在行”分组，不依赖中文标签识别，因此显著减小 APK。
    private val reOcrDate = Regex("(20\\d{2})[-/.](\\d{1,2})[-/.](\\d{1,2})\\s+(\\d{1,2})[:：](\\d{2})")
    private val reOcrOs = reSystemVersion
    private val reOcrHealthValue = Regex("(\\d{1,3}(?:[.．,]\\d{1,2})?)\\s*%")
    private val reOcrMahValue = Regex("(\\d{4,5})\\s*m\\s*A\\s*h", RegexOption.IGNORE_CASE)
    private val reOcrCycleExplicit = Regex("(\\d{1,6})\\s*(?:次|times?)", RegexOption.IGNORE_CASE)
    private val reNumericOnly = Regex("^\\s*(\\d{1,6})\\s*$")

    private data class OcrBox(val text: String, val rect: Rect) {
        val centerX: Int get() = (rect.left + rect.right) / 2
        val centerY: Int get() = (rect.top + rect.bottom) / 2
    }

    suspend fun fromScreenshot(context: Context, uri: Uri): HealthReport =
        fromScreenshotReports(context, uri).maxByOrNull { it.timestamp }
            ?: throw IllegalStateException("截图中未识别到电池数据")

    /**
     * 小米电池健康历史截图按视觉行识别：日期是锚点，同一横向记录里的健康度、满充容量、
     * 循环次数和 OS 版本只允许在本行取值，彻底避免上一版按纯文本顺序切段导致的串行错配。
     */
    suspend fun fromScreenshotReports(context: Context, uri: Uri): List<HealthReport> =
        withContext(Dispatchers.IO) {
            val image = InputImage.fromFilePath(context, uri)
            val recognized = recognizeText(image)
            val plainText = recognized.text

            val lines = recognized.textBlocks
                .flatMap { it.lines }
                .mapNotNull { line -> line.boundingBox?.let { OcrBox(line.text, it) } }
            val tokens = recognized.textBlocks
                .flatMap { block -> block.lines }
                .flatMap { line -> line.elements }
                .mapNotNull { element -> element.boundingBox?.let { OcrBox(element.text, it) } }

            fun parseDate(match: MatchResult): Long? = runCatching {
                val y = match.groupValues[1].toInt()
                val mo = match.groupValues[2].toInt()
                val d = match.groupValues[3].toInt()
                val h = match.groupValues[4].toInt()
                val mi = match.groupValues[5].toInt()
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).parse(
                    "%04d-%02d-%02d %02d:%02d".format(Locale.US, y, mo, d, h, mi),
                )?.time
            }.getOrNull()

            fun boxHealth(boxes: List<OcrBox>): Pair<Float, OcrBox?> {
                boxes.forEach { box ->
                    reOcrHealthValue.find(box.text)?.let { m ->
                        val value = m.groupValues[1].replace('．', '.').replace(',', '.').toFloatOrNull()
                        if (value != null && value in 1f..110f) return value to box
                    }
                }
                return -1f to null
            }

            fun boxMah(boxes: List<OcrBox>): Pair<Int, OcrBox?> {
                boxes.forEach { box ->
                    reOcrMahValue.find(box.text)?.let { m ->
                        val value = m.groupValues[1].toIntOrNull()
                        if (value != null && value in 100..30000) return value to box
                    }
                }
                return -1 to null
            }

            fun boxCycle(boxes: List<OcrBox>, docRight: Int, metricY: Int?): Int {
                boxes.forEach { box ->
                    reOcrCycleExplicit.find(box.text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { value ->
                        if (value in 0..100000) return value
                    }
                }
                val rightThreshold = (docRight * 0.66f).toInt()
                return boxes.mapNotNull { box ->
                    val value = reNumericOnly.matchEntire(box.text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    if (value == null || value !in 0..100000 || box.centerX < rightThreshold) null
                    else value to box
                }.minByOrNull { (_, box) ->
                    metricY?.let { kotlin.math.abs(box.centerY - it) } ?: -box.centerX
                }?.first ?: -1
            }

            fun localOs(boxes: List<OcrBox>): String =
                boxes.asSequence()
                    .mapNotNull { reOcrOs.find(it.text)?.value }
                    .firstOrNull()
                    ?.uppercase(Locale.US)
                    .orEmpty()

            val docRight = (lines.maxOfOrNull { it.rect.right }
                ?: tokens.maxOfOrNull { it.rect.right }
                ?: image.width).coerceAtLeast(1)

            val dateAnchors = lines.mapNotNull { box ->
                reOcrDate.find(box.text)?.let { match -> Triple(box, match, parseDate(match)) }
            }.filter { it.third != null }.sortedBy { it.first.centerY }

            val results = mutableListOf<HealthReport>()
            if (dateAnchors.isNotEmpty()) {
                val gaps = dateAnchors.zipWithNext { a, b -> b.first.centerY - a.first.centerY }
                    .filter { it > 20 }
                    .sorted()
                val typicalGap = if (gaps.isNotEmpty()) gaps[gaps.size / 2] else 220
                val firstDateY = dateAnchors.first().first.centerY

                // “设计容量 7000”通常在历史列表上方右侧。即使 Latin 模型不识别中文，数字位置仍可判断。
                val globalDesign = tokens.mapNotNull { box ->
                    val value = reNumericOnly.matchEntire(box.text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    if (value != null && value in 1000..30000 && box.centerY < firstDateY - 20 && box.centerX > docRight * 0.55f) value else null
                }.lastOrNull() ?: -1

                dateAnchors.forEachIndexed { index, (dateBox, dateMatch, parsedTime) ->
                    val top = if (index == 0) dateBox.centerY - typicalGap / 2
                    else (dateAnchors[index - 1].first.centerY + dateBox.centerY) / 2
                    val bottom = if (index == dateAnchors.lastIndex) dateBox.centerY + typicalGap / 2
                    else (dateBox.centerY + dateAnchors[index + 1].first.centerY) / 2

                    val rowTokens = tokens.filter { it.centerY in top..bottom }
                    val rowLines = lines.filter { it.centerY in top..bottom }
                    val metricBoxes = rowTokens + rowLines
                    val (health, healthBox) = boxHealth(metricBoxes)
                    val (full, fullBox) = boxMah(metricBoxes)
                    val metricY = listOfNotNull(healthBox?.centerY, fullBox?.centerY).takeIf { it.isNotEmpty() }?.average()?.toInt()
                    val cycle = boxCycle(metricBoxes, docRight, metricY)
                    val osVersion = localOs(rowLines + rowTokens)

                    if (full <= 0 && cycle < 0 && health <= 0) return@forEachIndexed
                    results += buildReport(
                        source = if (osVersion.isBlank()) "screenshot" else "screenshot|$osVersion",
                        timestamp = parsedTime ?: parseDate(dateMatch) ?: System.currentTimeMillis(),
                        full = full,
                        design = globalDesign,
                        cycle = cycle,
                        estimated = -1,
                        learnedMin = -1,
                        learnedMax = -1,
                        learnedLast = -1,
                        healthOverride = health,
                    )
                }
            }

            // 普通单条截图：优先读取带单位的数值；系统版本可直接从整个图片中识别。
            if (results.isEmpty()) {
                val health = reOcrHealthValue.find(plainText)?.groupValues?.getOrNull(1)
                    ?.replace('．', '.')?.replace(',', '.')?.toFloatOrNull() ?: -1f
                val full = reOcrMahValue.find(plainText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
                val osVersion = reOcrOs.find(plainText)?.value?.uppercase(Locale.US).orEmpty()
                val dateMatch = reOcrDate.find(plainText)
                val time = dateMatch?.let(::parseDate) ?: System.currentTimeMillis()
                val cycle = boxCycle(tokens.ifEmpty { lines }, docRight, null)
                if (full <= 0 && cycle < 0 && health <= 0) {
                    throw IllegalStateException(
                        "截图中未识别到电池数据。建议完整截取每条记录的日期、百分比、mAh、循环次数和系统版本。",
                    )
                }
                results += buildReport(
                    source = if (osVersion.isBlank()) "screenshot" else "screenshot|$osVersion",
                    timestamp = time,
                    full = full,
                    design = -1,
                    cycle = cycle,
                    estimated = -1,
                    learnedMin = -1,
                    learnedMax = -1,
                    learnedLast = -1,
                    healthOverride = health,
                )
            }

            results
                .distinctBy { listOf(it.timestamp / 60_000L, it.fullChargeMah.toLong(), it.cycleCount.toLong()) }
                .sortedBy { it.timestamp }
        }

    private suspend fun recognizeText(image: InputImage): Text =
        suspendCancellableCoroutine { cont ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
                .addOnCompleteListener { recognizer.close() }
        }

    // ---------- 公共 ----------

    private fun buildReport(
        source: String,
        timestamp: Long,
        full: Int,
        design: Int,
        cycle: Int,
        estimated: Int,
        learnedMin: Int,
        learnedMax: Int,
        learnedLast: Int,
        healthOverride: Float = -1f,
    ): HealthReport {
        require(healthOverride <= 110f) { "健康度超出合理范围，请检查截图" }
        require(full <= 30000 && design <= 30000 && cycle <= 100000) { "电池数据超出合理范围" }
        val health = when {
            healthOverride > 0 -> healthOverride
            full > 0 && design > 0 -> full * 100f / design
            else -> -1f
        }
        return HealthReport(
            timestamp = timestamp,
            source = source,
            fullChargeMah = full,
            designMah = design,
            cycleCount = cycle,
            healthPct = health,
            estimatedMah = estimated,
            learnedMinMah = learnedMin,
            learnedMaxMah = learnedMax,
            learnedLastMah = learnedLast,
        )
    }

    fun fmt(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ts))
}
