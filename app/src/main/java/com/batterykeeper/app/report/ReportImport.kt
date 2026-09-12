package com.batterykeeper.app.report

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.batterykeeper.app.data.HealthReport
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
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
 * v1.5.4：Bugreport 不再先假定 OS 版本，而是同时识别 HyperOS 3/4 常见来源，
 * 再按可信度选择字段：AIDL HealthInfo > Health HAL dump > POWER_SUPPLY_* > batterystats。
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
                source = encodeBugReportSource(format, confidence, origins),
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

    private fun encodeBugReportSource(format: String, confidence: String, origins: String): String =
        "bugreport|$format|$confidence|$origins"

    data class SourceMeta(
        val kind: String,
        val format: String,
        val confidence: String,
        val origins: String,
    )

    /** 兼容历史记录的 source="bugreport" / "screenshot"。 */
    fun sourceMeta(source: String): SourceMeta {
        if (source == "screenshot") return SourceMeta("screenshot", "截图识别", "—", "OCR")
        if (source == "bugreport") return SourceMeta("bugreport", "旧版 Bug 报告", "—", "旧版记录")
        val parts = source.split('|', limit = 4)
        return if (parts.firstOrNull() == "bugreport") {
            SourceMeta(
                kind = "bugreport",
                format = parts.getOrNull(1).orEmpty().ifBlank { "HyperOS Bugreport" },
                confidence = parts.getOrNull(2).orEmpty().ifBlank { "—" },
                origins = parts.getOrNull(3).orEmpty(),
            )
        } else {
            SourceMeta(source, source, "—", "")
        }
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

    private val reOcrFull = Regex("(?<!估算)满充容量[^0-9]{0,12}(\\d{4,5})")
    private val reOcrEstimated = Regex("估算满充容量[^0-9]{0,12}(\\d{4,5})")
    private val reOcrDesign = Regex("设计容量[^0-9]{0,12}(\\d{4,5})")
    private val reOcrCycle = Regex("(?:充电循环|循环次数)[^0-9]{0,12}(\\d+)")
    private val reOcrHealth = Regex("健康度[^0-9]{0,8}(\\d{1,3}(?:[.．]\\d+)?)")
    private val reOcrDate = Regex("(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2})")

    suspend fun fromScreenshot(context: Context, uri: Uri): HealthReport =
        withContext(Dispatchers.IO) {
            val image = InputImage.fromFilePath(context, uri)
            val text = recognizeText(image)

            val estimated = reOcrEstimated.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val full = reOcrFull.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val design = reOcrDesign.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val cycle = reOcrCycle.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val health = reOcrHealth.find(text)?.groupValues?.get(1)?.replace("．", ".")?.toFloatOrNull() ?: -1f
            val time = reOcrDate.find(text)?.groupValues?.get(1)?.let {
                runCatching {
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).parse(it)?.time
                }.getOrNull()
            } ?: System.currentTimeMillis()

            if (full <= 0 && cycle < 0 && health <= 0) {
                throw IllegalStateException("截图中未识别到电池数据（满充容量/循环次数/健康度），请使用清晰的检测报告截图")
            }

            buildReport(
                source = "screenshot",
                timestamp = time,
                full = full,
                design = design,
                cycle = cycle,
                estimated = estimated,
                learnedMin = -1,
                learnedMax = -1,
                learnedLast = -1,
                healthOverride = health,
            )
        }

    private suspend fun recognizeText(image: InputImage): String =
        suspendCancellableCoroutine { cont ->
            val recognizer = TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build(),
            )
            recognizer.process(image)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it.text) }
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
