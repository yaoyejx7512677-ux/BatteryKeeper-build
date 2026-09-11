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
import java.io.BufferedReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 澎湃OS 电池检测报告导入：
 *  - Bug 报告 ZIP（拨号盘 *#*#284#*#* 生成）：解析其中的 POWER_SUPPLY_* uevent 与 batterystats 字段
 *  - 截图识别（ML Kit 中文 OCR）：解析检测报告截图中的中文标签
 */
object ReportImport {

    // ---------- Bug 报告 ZIP ----------

    private val reCycle = Regex("POWER_SUPPLY_CYCLE_COUNT=(\\d+)", RegexOption.IGNORE_CASE)
    private val reFull = Regex("POWER_SUPPLY_CHARGE_FULL=(\\d+)", RegexOption.IGNORE_CASE)
    private val reDesign = Regex("POWER_SUPPLY_CHARGE_FULL_DESIGN=(\\d+)", RegexOption.IGNORE_CASE)
    private val reEstimated = Regex("Estimated battery capacity[:\\s]*(\\d+)", RegexOption.IGNORE_CASE)
    private val reLearnedMin = Regex("Min learned battery capacity[:\\s]*(\\d+)", RegexOption.IGNORE_CASE)
    private val reLearnedMax = Regex("Max learned battery capacity[:\\s]*(\\d+)", RegexOption.IGNORE_CASE)
    private val reLearnedLast = Regex("Last learned battery capacity[:\\s]*(\\d+)", RegexOption.IGNORE_CASE)

    // Android 14+/HyperOS 4 AIDL health dump. Example:
    // getHealthInfo -> HealthInfo{..., batteryCycleCount: 316,
    // batteryFullChargeUah: 6791000, batteryFullChargeDesignCapacityUah: 7000000, ...}
    private val reHealthCycle = Regex("batteryCycleCount[:\\s]*(\\d+)", RegexOption.IGNORE_CASE)
    private val reHealthFull = Regex("batteryFullChargeUah[:\\s]*(\\d+)", RegexOption.IGNORE_CASE)
    private val reHealthDesign = Regex("batteryFullChargeDesignCapacityUah[:\\s]*(\\d+)", RegexOption.IGNORE_CASE)
    private val reHealthCycleLine = Regex("^\\s*cycle count[:\\s]+(\\d+)\\s*$", RegexOption.IGNORE_CASE)
    private val reHealthFullLine = Regex("^\\s*Full charge[:\\s]+(\\d+)\\s*$", RegexOption.IGNORE_CASE)
    private val reTimeFromName = Regex("(\\d{4})-(\\d{2})-(\\d{2})[-_](\\d{2})[-_](\\d{2})")

    private const val MAX_MAIN_REPORT_CHARS = 256_000_000
    private const val MAX_OTHER_TEXT_CHARS = 1_000_000

    private fun uahToMah(raw: String): Int =
        raw.toLongOrNull()?.let { value ->
            when {
                value <= 0L -> -1
                value >= 100_000L -> (value / 1000L).toInt()
                else -> value.toInt()
            }
        } ?: -1

    suspend fun fromBugReport(context: Context, uri: Uri): HealthReport =
        withContext(Dispatchers.IO) {
            var full = -1
            var design = -1
            var cycle = -1
            var estimated = -1
            var learnedMin = -1
            var learnedMax = -1
            var learnedLast = -1
            var reportTime = queryDisplayName(context, uri)?.let(::parseTimeFromName) ?: 0L
            var sawZipEntry = false

            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        sawZipEntry = true
                        if (!entry.isDirectory) {
                            val name = entry.name
                            val baseName = name.substringAfterLast('/')
                            val isText = name.endsWith(".txt", true) || name.endsWith(".log", true)
                            val isMainBugReport = baseName.startsWith("bugreport-", true) && baseName.endsWith(".txt", true)
                            if (reportTime == 0L && isMainBugReport) {
                                reportTime = parseTimeFromName(baseName) ?: 0L
                            }
                            val shouldScan = isText && (
                                isMainBugReport ||
                                    baseName.equals("dumpstate_board.txt", true) ||
                                    baseName.equals("dumpstate_log.txt", true)
                                )

                            if (shouldScan) {
                                val maxChars = if (isMainBugReport) MAX_MAIN_REPORT_CHARS else MAX_OTHER_TEXT_CHARS
                                val reader = zis.bufferedReader(Charsets.UTF_8)
                                var inHealthDump = false
                                var line = reader.readLine()
                                var scanned = 0
                                while (line != null && scanned < maxChars) {
                                    scanned += line.length

                                    if (line.contains("DUMP OF SERVICE android.hardware.health.IHealth/default", true)) {
                                        inHealthDump = true
                                    } else if (inHealthDump && line.startsWith("---------") && line.contains("duration of dumpsys", true)) {
                                        inHealthDump = false
                                    }

                                    if (full <= 0) {
                                        reFull.find(line)?.let { full = uahToMah(it.groupValues[1]) }
                                        reHealthFull.find(line)?.let { full = uahToMah(it.groupValues[1]) }
                                        if (inHealthDump) reHealthFullLine.find(line)?.let { full = uahToMah(it.groupValues[1]) }
                                    }
                                    if (design <= 0) {
                                        reDesign.find(line)?.let { design = uahToMah(it.groupValues[1]) }
                                        reHealthDesign.find(line)?.let { design = uahToMah(it.groupValues[1]) }
                                    }
                                    if (cycle < 0) {
                                        reCycle.find(line)?.let { cycle = it.groupValues[1].toInt() }
                                        reHealthCycle.find(line)?.let { cycle = it.groupValues[1].toInt() }
                                        if (inHealthDump) reHealthCycleLine.find(line)?.let { cycle = it.groupValues[1].toInt() }
                                    }
                                    if (estimated <= 0) reEstimated.find(line)?.let { estimated = it.groupValues[1].toInt() }
                                    if (learnedMin <= 0) reLearnedMin.find(line)?.let { learnedMin = it.groupValues[1].toInt() }
                                    if (learnedMax <= 0) reLearnedMax.find(line)?.let { learnedMax = it.groupValues[1].toInt() }
                                    if (learnedLast <= 0) reLearnedLast.find(line)?.let { learnedLast = it.groupValues[1].toInt() }

                                    // 关键字段和常见附加字段都拿到后即可提前结束，避免继续扫描超大报告。
                                    if (full > 0 && design > 0 && cycle >= 0 &&
                                        estimated > 0 && learnedMin > 0 && learnedMax > 0 && learnedLast > 0
                                    ) break

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
            if (full <= 0 && cycle < 0 && estimated <= 0 && learnedLast <= 0) {
                throw IllegalStateException("报告中未找到电池数据，请确认选择的是 *#*#284#*#* 生成的 Bug 报告 ZIP")
            }

            buildReport(
                source = "bugreport",
                timestamp = if (reportTime > 0) reportTime else System.currentTimeMillis(),
                full = full, design = design, cycle = cycle,
                estimated = estimated, learnedMin = learnedMin,
                learnedMax = learnedMax, learnedLast = learnedLast,
            )
        }

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            }
        }.getOrNull()

    private fun parseTimeFromName(name: String): Long? { return try {
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
                full = full, design = design, cycle = cycle,
                estimated = estimated, learnedMin = -1, learnedMax = -1, learnedLast = -1,
                healthOverride = health,
            )
        }

    private suspend fun recognizeText(image: InputImage): String =
        suspendCancellableCoroutine { cont ->
            val recognizer = TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build()
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
        full: Int, design: Int, cycle: Int,
        estimated: Int, learnedMin: Int, learnedMax: Int, learnedLast: Int,
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
