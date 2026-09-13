package com.batterykeeper.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.batterykeeper.app.battery.BatteryStateHolder
import com.batterykeeper.app.battery.ProtocolDetector
import com.batterykeeper.app.data.BatteryDatabase
import com.batterykeeper.app.data.CycleRecord
import com.batterykeeper.app.data.DailyStats
import com.batterykeeper.app.data.HealthReport
import com.batterykeeper.app.report.ReportImport
import com.batterykeeper.app.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class BatteryViewModel(app: Application) : AndroidViewModel(app) {

    private val db = BatteryDatabase.get(app)
    val settings = AppSettings(app)

    val latest = BatteryStateHolder.latest
    val protocol = BatteryStateHolder.protocol
    val liveBuffer = BatteryStateHolder.liveBuffer
    val session = BatteryStateHolder.session

    val cycleRecords: StateFlow<List<CycleRecord>> = db.cycleRecordDao().all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dailyStats: StateFlow<List<DailyStats>> = db.dailyStatsDao().all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 澎湃OS 检测报告历史 */
    val reports: StateFlow<List<HealthReport>> = db.healthReportDao().all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 最近一条有效检测报告 */
    val latestReport: HealthReport?
        get() = reports.value.lastOrNull { it.fullChargeMah > 0 || it.cycleCount > 0 }

    /** 估算健康度 %（检测报告 > 满充估算 > 无） */
    private var reportHealth: Float? = null
    private val _healthPct = MutableStateFlow<Float?>(null)
    val healthPct: StateFlow<Float?> = _healthPct.asStateFlow()

    init {
        viewModelScope.launch {
            // 检测报告数据变化时刷新健康度（报告优先于满充估算）
            db.healthReportDao().all().collect { list ->
                val fromReport = list.lastOrNull { it.healthPct > 0 }
                reportHealth = fromReport?.healthPct
                if (fromReport != null) {
                    _healthPct.value = fromReport.healthPct
                } else {
                    refreshHealthFromLocal()
                }
            }
        }
    }

    private suspend fun refreshHealthFromLocal() {
        val est = settings.fullChargeMah
        _healthPct.value = if (est > 0) est * 100f / settings.designCapacityMah else null
    }

    /** 展示用循环次数：系统上报 > 检测报告 > 自算累计 */
    fun displayCycleCount(systemCount: Int, reportCycle: Int? = null): Int =
        systemCount.takeIf { it >= 0 }
            ?: reportCycle?.takeIf { it >= 0 }
            ?: settings.selfCycleCount

    /** 报告导入（Bug 报告 ZIP） */
    suspend fun importBugReport(context: Context, uri: Uri): Result<HealthReport> =
        runCatching {
            val r = ReportImport.fromBugReport(context, uri)
            r
        }

    /** 报告导入（截图识别，单条兼容接口） */
    suspend fun importScreenshot(context: Context, uri: Uri): Result<HealthReport> =
        runCatching { ReportImport.fromScreenshot(context, uri) }

    /** 报告导入（支持图2样式的一图多条历史记录） */
    suspend fun importScreenshotReports(context: Context, uri: Uri): Result<List<HealthReport>> =
        runCatching { ReportImport.fromScreenshotReports(context, uri) }

    private fun validateReport(r: HealthReport) {
        require(r.healthPct == -1f || r.healthPct in 1f..110f) { "健康度应在 1–110% 之间" }
        require(r.fullChargeMah == -1 || r.fullChargeMah in 100..30000) { "请检查满充容量" }
        require(r.cycleCount == -1 || r.cycleCount in 0..100000) { "请检查循环次数" }
    }

    suspend fun saveReport(r: HealthReport) {
        validateReport(r)
        val latest = db.healthReportDao().latest()
        require(latest == null || latest.timestamp != r.timestamp || latest.fullChargeMah != r.fullChargeMah || latest.cycleCount != r.cycleCount || latest.healthPct != r.healthPct) { "这条记录已导入" }
        db.healthReportDao().insert(r)
    }

    /** 批量保存截图历史，自动跳过已存在的同分钟/容量/循环记录。 */
    suspend fun saveReports(list: List<HealthReport>): Pair<Int, Int> {
        val existing = db.healthReportDao().snapshot().map {
            Triple(it.timestamp / 60_000L, it.fullChargeMah, it.cycleCount)
        }.toMutableSet()
        var saved = 0
        var skipped = 0
        list.sortedBy { it.timestamp }.forEach { r ->
            validateReport(r)
            val key = Triple(r.timestamp / 60_000L, r.fullChargeMah, r.cycleCount)
            if (key in existing) {
                skipped++
            } else {
                db.healthReportDao().insert(r.copy(id = 0))
                existing += key
                saved++
            }
        }
        return saved to skipped
    }

    fun deleteReport(id: Long) = viewModelScope.launch { db.healthReportDao().delete(id) }

    /** 今日统计 */
    data class TodayStats(val chargedMah: Int, val sessions: Int)

    private val _todayStats = MutableStateFlow(TodayStats(0, 0))
    val todayStats: StateFlow<TodayStats> = _todayStats.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                refreshToday().join()
                if (reportHealth == null) refreshHealthFromLocal()
                delay(2000)
            }
        }
    }

    fun refreshToday() = viewModelScope.launch(Dispatchers.IO) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val dayStart = cal.timeInMillis
        val activeStart = session.value?.startTime
        val sessions = db.chargeSessionDao().since(dayStart)
            .filter { it.startTime != activeStart }
            .filter(::isMeaningfulSession)
        _todayStats.value = TodayStats(
            chargedMah = sessions.sumOf { it.energyMah },
            sessions = sessions.size,
        )
    }

    /** 历史区间采样（供曲线页） */
    suspend fun samplesBetween(from: Long, to: Long) =
        db.sampleDao().between(from, to)

    suspend fun latestSessions() = db.chargeSessionDao().latest(60).filter(::isMeaningfulSession).take(30)

    private fun isMeaningfulSession(s: com.batterykeeper.app.data.ChargeSession): Boolean {
        val levelGain = ((s.endLevel ?: s.startLevel) - s.startLevel).coerceAtLeast(0)
        val durationMs = ((s.endTime ?: s.startTime) - s.startTime).coerceAtLeast(0L)
        return s.energyMah >= 5 || levelGain >= 2 || durationMs >= 3 * 60_000L
    }

    suspend fun sampleCount() = db.sampleDao().count()

    companion object {
        fun fmtDate(ms: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ms))
        fun fmtDay(ms: Long): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(ms))
    }
}
