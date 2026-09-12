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

/** HyperOS 3/4 电池报告导入。 */
object ReportImport {
    private const val P_AIDL = 40
    private const val P_HAL = 35
    private const val P_POWER = 30
    private const val P_STATS = 10
    private const val MAIN_LIMIT = 256_000_000
    private const val OTHER_LIMIT = 2_000_000
    private const val TOTAL_LIMIT = 300_000_000

    private data class Candidate(val value: Int, val priority: Int, val origin: String)
    private fun pick(old: Candidate?, next: Candidate) = if (old == null || next.priority > old.priority) next else old
    private fun capacity(raw: String): Int = raw.toLongOrNull()?.let {
        when {
            it <= 0 -> -1
            it >= 100_000 -> (it / 1000).toInt()
            else -> it.toInt()
        }
    } ?: -1

    private val powerCycle = Regex("POWER_SUPPLY_CYCLE_COUNT\\s*[=:]\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val powerFull = Regex("POWER_SUPPLY_CHARGE_FULL\\s*[=:]\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val powerDesign = Regex("POWER_SUPPLY_CHARGE_FULL_DESIGN\\s*[=:]\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val aidlCycle = Regex("batteryCycleCount[:=\\s]*(\\d+)", RegexOption.IGNORE_CASE)
    private val aidlFull = Regex("batteryFullChargeUah[:=\\s]*(\\d+)", RegexOption.IGNORE_CASE)
    private val aidlDesign = Regex("batteryFullChargeDesignCapacityUah[:=\\s]*(\\d+)", RegexOption.IGNORE_CASE)
    private val halCycle = Regex("^\\s*(?:battery )?cycle count[:=\\s]+(\\d+)\\s*$", RegexOption.IGNORE_CASE)
    private val halFull = Regex("^\\s*(?:battery )?full charge[:=\\s]+(\\d+)\\s*$", RegexOption.IGNORE_CASE)
    private val halDesign = Regex("^\\s*(?:battery )?(?:full charge design capacity|design capacity)[:=\\s]+(\\d+)\\s*$", RegexOption.IGNORE_CASE)
    private val estimated = Regex("Estimated battery capacity[:\\s]*(\\d+)", RegexOption.IGNORE_CASE)
    private val learnedMin = Regex("Min learned battery capacity[:\\s]*(\\d+)", RegexOption.IGNORE_CASE)
    private val learnedMax = Regex("Max learned battery capacity[:\\s]*(\\d+)", RegexOption.IGNORE_CASE)
    private val learnedLast = Regex("Last learned battery capacity[:\\s]*(\\d+)", RegexOption.IGNORE_CASE)
    private val reportTime = Regex("(\\d{4})-(\\d{2})-(\\d{2})[-_](\\d{2})[-_](\\d{2})")

    suspend fun fromBugReport(context: Context, uri: Uri): HealthReport = withContext(Dispatchers.IO) {
        var full: Candidate? = null
        var design: Candidate? = null
        var cycle: Candidate? = null
        var est: Candidate? = null
        var lMin: Candidate? = null
        var lMax: Candidate? = null
        var lLast: Candidate? = null
        var sawEntry = false
        var sawAidl = false
        var sawHal = false
        var sawPower = false
        var sawStats = false
        var scannedTotal = 0
        var time = queryDisplayName(context, uri)?.let(::timeFromName) ?: 0L

        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null && scannedTotal < TOTAL_LIMIT) {
                    sawEntry = true
                    if (!entry.isDirectory) {
                        val name = entry.name
                        val base = name.substringAfterLast('/')
                        val lower = name.lowercase(Locale.US)
                        val lowerBase = base.lowercase(Locale.US)
                        val main = lowerBase.startsWith("bugreport-") && lowerBase.endsWith(".txt")
                        if (time == 0L && main) time = timeFromName(base) ?: 0L
                        val textLike = lowerBase.endsWith(".txt") || lowerBase.endsWith(".log") || lowerBase.endsWith(".dump") || !lowerBase.contains('.')
                        val related = main || lower.contains("dumpstate") || lower.contains("health") || lower.contains("battery") || lower.contains("power_supply")
                        if (textLike && related) {
                            val limit = if (main) MAIN_LIMIT else OTHER_LIMIT
                            val reader = zis.bufferedReader(Charsets.UTF_8)
                            var inHal = false
                            var local = 0
                            var line = reader.readLine()
                            while (line != null && local < limit && scannedTotal < TOTAL_LIMIT) {
                                local += line.length
                                scannedTotal += line.length
                                if (line.contains("DUMP OF SERVICE android.hardware.health.IHealth", true) || line.contains("DUMP OF SERVICE vendor.hardware.health", true)) {
                                    inHal = true; sawHal = true
                                } else if (inHal && line.startsWith("---------") && line.contains("DUMP OF SERVICE", true)) {
                                    inHal = false
                                }

                                aidlFull.find(line)?.let { capacity(it.groupValues[1]).takeIf { v -> v > 0 }?.let { v -> full = pick(full, Candidate(v, P_AIDL, "AIDL HealthInfo")); sawAidl = true } }
                                aidlDesign.find(line)?.let { capacity(it.groupValues[1]).takeIf { v -> v > 0 }?.let { v -> design = pick(design, Candidate(v, P_AIDL, "AIDL HealthInfo")); sawAidl = true } }
                                aidlCycle.find(line)?.let { it.groupValues[1].toIntOrNull()?.let { v -> cycle = pick(cycle, Candidate(v, P_AIDL, "AIDL HealthInfo")); sawAidl = true } }

                                if (inHal) {
                                    halFull.find(line)?.let { capacity(it.groupValues[1]).takeIf { v -> v > 0 }?.let { v -> full = pick(full, Candidate(v, P_HAL, "Health HAL")) } }
                                    halDesign.find(line)?.let { capacity(it.groupValues[1]).takeIf { v -> v > 0 }?.let { v -> design = pick(design, Candidate(v, P_HAL, "Health HAL")) } }
                                    halCycle.find(line)?.let { it.groupValues[1].toIntOrNull()?.let { v -> cycle = pick(cycle, Candidate(v, P_HAL, "Health HAL")) } }
                                }

                                powerFull.find(line)?.let { capacity(it.groupValues[1]).takeIf { v -> v > 0 }?.let { v -> full = pick(full, Candidate(v, P_POWER, "power_supply")); sawPower = true } }
                                powerDesign.find(line)?.let { capacity(it.groupValues[1]).takeIf { v -> v > 0 }?.let { v -> design = pick(design, Candidate(v, P_POWER, "power_supply")); sawPower = true } }
                                powerCycle.find(line)?.let { it.groupValues[1].toIntOrNull()?.let { v -> cycle = pick(cycle, Candidate(v, P_POWER, "power_supply")); sawPower = true } }
                                estimated.find(line)?.let { capacity(it.groupValues[1]).takeIf { v -> v > 0 }?.let { v -> est = pick(est, Candidate(v, P_STATS, "batterystats")); sawStats = true } }
                                learnedMin.find(line)?.let { capacity(it.groupValues[1]).takeIf { v -> v > 0 }?.let { v -> lMin = pick(lMin, Candidate(v, P_STATS, "batterystats")); sawStats = true } }
                                learnedMax.find(line)?.let { capacity(it.groupValues[1]).takeIf { v -> v > 0 }?.let { v -> lMax = pick(lMax, Candidate(v, P_STATS, "batterystats")); sawStats = true } }
                                learnedLast.find(line)?.let { capacity(it.groupValues[1]).takeIf { v -> v > 0 }?.let { v -> lLast = pick(lLast, Candidate(v, P_STATS, "batterystats")); sawStats = true } }
                                line = reader.readLine()
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } ?: error("无法读取所选文件")

        require(sawEntry) { "所选文件不是有效的 Bug 报告 ZIP" }
        val f = full?.value ?: -1
        val d = design?.value ?: -1
        val c = cycle?.value ?: -1
        val e = est?.value ?: -1
        require(f > 0 || c >= 0 || e > 0 || (lLast?.value ?: -1) > 0) {
            "报告中未找到电池数据，请确认选择完整的 HyperOS 错误报告 ZIP"
        }
        val format = when {
            sawAidl -> "HyperOS 3/4 / AIDL Health"
            sawHal -> "HyperOS 3/4 / Health HAL"
            sawPower -> "HyperOS 3/4 / power_supply"
            sawStats -> "Android batterystats"
            else -> "HyperOS Bugreport"
        }
        val keyCount = listOf(f > 0, d > 0, c >= 0).count { it }
        val confidence = when { keyCount >= 3 -> "高"; keyCount >= 2 -> "中"; else -> "低" }
        val origins = listOfNotNull(
            full?.let { "满充=${it.origin}" }, design?.let { "设计=${it.origin}" },
            cycle?.let { "循环=${it.origin}" }, est?.let { "估算=${it.origin}" },
        ).joinToString("；")
        buildReport(
            source = "bugreport|$format|$confidence|$origins",
            timestamp = time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            full = f, design = d, cycle = c, estimated = e,
            learnedMin = lMin?.value ?: -1, learnedMax = lMax?.value ?: -1, learnedLast = lLast?.value ?: -1,
        )
    }

    data class SourceMeta(val kind: String, val format: String, val confidence: String, val origins: String)
    fun sourceMeta(source: String): SourceMeta {
        if (source == "screenshot") return SourceMeta("screenshot", "截图识别", "—", "OCR")
        if (source.startsWith("screenshot|")) return SourceMeta("screenshot", source.substringAfter('|'), "—", "OCR")
        if (source == "bugreport") return SourceMeta("bugreport", "旧版 Bug 报告", "—", "旧版记录")
        val p = source.split('|', limit = 4)
        return if (p.firstOrNull() == "bugreport") SourceMeta("bugreport", p.getOrNull(1).orEmpty(), p.getOrNull(2).orEmpty(), p.getOrNull(3).orEmpty())
        else SourceMeta(source, source, "—", "")
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (!c.moveToFirst()) null else c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(c::getString)
        }
    }.getOrNull()

    private fun timeFromName(name: String): Long? = runCatching {
        val m = reportTime.find(name) ?: return null
        val (y, mo, d, h, mi) = m.destructured
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).parse("$y-$mo-$d $h:$mi")?.time
    }.getOrNull()

    private val ocrFullAfter = Regex("(?<!估算)满充容量[^0-9]{0,16}(\\d{4,5})", RegexOption.IGNORE_CASE)
    private val ocrFullBefore = Regex("(\\d{4,5})\\s*(?:m\\s*A\\s*h)?\\s*满充容量", RegexOption.IGNORE_CASE)
    private val ocrDesignAfter = Regex("设计容量[^0-9]{0,16}(\\d{4,5})", RegexOption.IGNORE_CASE)
    private val ocrDesignBefore = Regex("(\\d{4,5})\\s*(?:m\\s*A\\s*h)?\\s*设计容量", RegexOption.IGNORE_CASE)
    private val ocrEstimatedAfter = Regex("估算满充容量[^0-9]{0,16}(\\d{4,5})", RegexOption.IGNORE_CASE)
    private val ocrEstimatedBefore = Regex("(\\d{4,5})\\s*(?:m\\s*A\\s*h)?\\s*估算满充容量", RegexOption.IGNORE_CASE)
    private val ocrCycleAfter = Regex("(?:充电循环|循环次数)[^0-9]{0,16}(\\d+)", RegexOption.IGNORE_CASE)
    private val ocrCycleBefore = Regex("(\\d{1,6})\\s*(?:次)?\\s*(?:充电循环|循环次数)", RegexOption.IGNORE_CASE)
    private val ocrHealthAfter = Regex("健康度[^0-9]{0,12}(\\d{1,3}(?:[.．]\\d+)?)", RegexOption.IGNORE_CASE)
    private val ocrHealthBefore = Regex("(\\d{1,3}(?:[.．]\\d+)?)\\s*%?\\s*健康度", RegexOption.IGNORE_CASE)
    private val ocrDate = Regex("(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2})")
    private val ocrOs = Regex("OS[34](?:\\.\\d+){2,4}(?:\\.[A-Z0-9]+)?", RegexOption.IGNORE_CASE)

    suspend fun fromScreenshot(context: Context, uri: Uri): HealthReport =
        fromScreenshotReports(context, uri).maxByOrNull { it.timestamp }
            ?: error("截图中未识别到电池数据")

    suspend fun fromScreenshotReports(context: Context, uri: Uri): List<HealthReport> = withContext(Dispatchers.IO) {
        val text = recognize(InputImage.fromFilePath(context, uri))
        fun intOf(src: String, before: Regex, after: Regex) =
            (before.find(src) ?: after.find(src))?.groupValues?.get(1)?.toIntOrNull() ?: -1
        fun floatOf(src: String, before: Regex, after: Regex) =
            (before.find(src) ?: after.find(src))?.groupValues?.get(1)?.replace("．", ".")?.toFloatOrNull() ?: -1f
        fun dateOf(raw: String) = runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).parse(raw)?.time }.getOrNull()

        val globalDesign = intOf(text, ocrDesignBefore, ocrDesignAfter)
        val globalOs = ocrOs.find(text)?.value?.uppercase(Locale.US)
        val dates = ocrDate.findAll(text).toList()
        val result = mutableListOf<HealthReport>()
        dates.forEachIndexed { i, dm ->
            val end = dates.getOrNull(i + 1)?.range?.first ?: text.length
            val segment = text.substring(dm.range.first, end)
            val f = intOf(segment, ocrFullBefore, ocrFullAfter)
            val c = intOf(segment, ocrCycleBefore, ocrCycleAfter)
            val h = floatOf(segment, ocrHealthBefore, ocrHealthAfter)
            if (f <= 0 && c < 0 && h <= 0) return@forEachIndexed
            val localDesign = intOf(segment, ocrDesignBefore, ocrDesignAfter)
            val e = intOf(segment, ocrEstimatedBefore, ocrEstimatedAfter)
            val os = ocrOs.find(segment)?.value?.uppercase(Locale.US) ?: globalOs
            result += buildReport(
                source = if (os.isNullOrBlank()) "screenshot" else "screenshot|$os",
                timestamp = dateOf(dm.groupValues[1]) ?: System.currentTimeMillis(),
                full = f, design = localDesign.takeIf { it > 0 } ?: globalDesign, cycle = c,
                estimated = e, learnedMin = -1, learnedMax = -1, learnedLast = -1, healthOverride = h,
            )
        }
        if (result.isEmpty()) {
            val f = intOf(text, ocrFullBefore, ocrFullAfter)
            val d = intOf(text, ocrDesignBefore, ocrDesignAfter)
            val c = intOf(text, ocrCycleBefore, ocrCycleAfter)
            val h = floatOf(text, ocrHealthBefore, ocrHealthAfter)
            val e = intOf(text, ocrEstimatedBefore, ocrEstimatedAfter)
            require(f > 0 || c >= 0 || h > 0) { "截图中未识别到电池数据（满充容量/循环次数/健康度）" }
            result += buildReport(
                source = if (globalOs.isNullOrBlank()) "screenshot" else "screenshot|$globalOs",
                timestamp = ocrDate.find(text)?.groupValues?.get(1)?.let(::dateOf) ?: System.currentTimeMillis(),
                full = f, design = d, cycle = c, estimated = e,
                learnedMin = -1, learnedMax = -1, learnedLast = -1, healthOverride = h,
            )
        }
        result.distinctBy { Triple(it.timestamp / 60_000L, it.fullChargeMah, it.cycleCount) }.sortedBy { it.timestamp }
    }

    private suspend fun recognize(image: InputImage): String = suspendCancellableCoroutine { cont ->
        val r = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        r.process(image)
            .addOnSuccessListener { if (cont.isActive) cont.resume(it.text) }
            .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
            .addOnCompleteListener { r.close() }
    }

    private fun buildReport(
        source: String, timestamp: Long, full: Int, design: Int, cycle: Int, estimated: Int,
        learnedMin: Int, learnedMax: Int, learnedLast: Int, healthOverride: Float = -1f,
    ): HealthReport {
        require(healthOverride <= 110f) { "健康度超出合理范围" }
        require(full <= 30000 && design <= 30000 && cycle <= 100000) { "电池数据超出合理范围" }
        val health = when {
            healthOverride > 0 -> healthOverride
            full > 0 && design > 0 -> full * 100f / design
            else -> -1f
        }
        return HealthReport(
            timestamp = timestamp, source = source, fullChargeMah = full, designMah = design,
            cycleCount = cycle, healthPct = health, estimatedMah = estimated,
            learnedMinMah = learnedMin, learnedMaxMah = learnedMax, learnedLastMah = learnedLast,
        )
    }

    fun fmt(ts: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ts))
}
