package com.batterykeeper.app.battery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局电池状态容器：前台服务写入，UI 读取。
 * liveBuffer 保存近 10 分钟功率数据用于实时曲线。
 */
object BatteryStateHolder {

    data class LivePoint(val t: Long, val powerW: Float)

    private val _latest = MutableStateFlow<BatterySnapshot?>(null)
    val latest: StateFlow<BatterySnapshot?> = _latest.asStateFlow()

    private val _protocol = MutableStateFlow(ProtocolDetector.ProtocolInfo("放电中"))
    val protocol: StateFlow<ProtocolDetector.ProtocolInfo> = _protocol.asStateFlow()

    private val _liveBuffer = MutableStateFlow<List<LivePoint>>(emptyList())
    val liveBuffer: StateFlow<List<LivePoint>> = _liveBuffer.asStateFlow()

    /** 当前充电会话累计信息（由 Service 维护） */
    data class SessionInfo(
        val startTime: Long,
        val startLevel: Int,
        val energyMah: Double,
        val peakPowerW: Float,
        val avgPowerW: Float,
    )

    private val _session = MutableStateFlow<SessionInfo?>(null)
    val session: StateFlow<SessionInfo?> = _session.asStateFlow()

    private const val WINDOW_MS = 10 * 60 * 1000L

    fun update(snapshot: BatterySnapshot, protocol: ProtocolDetector.ProtocolInfo) {
        _latest.value = snapshot
        _protocol.value = protocol
        val now = snapshot.timestamp
        val buf = _liveBuffer.value.toMutableList()
        if (snapshot.powerW.isFinite()) buf.add(LivePoint(now, snapshot.powerW))
        val cutoff = now - WINDOW_MS
        while (buf.isNotEmpty() && buf.first().t < cutoff) buf.removeAt(0)
        _liveBuffer.value = buf
    }

    fun updateSession(info: SessionInfo?) {
        _session.value = info
    }

    fun clear() {
        _latest.value = null
        _protocol.value = ProtocolDetector.ProtocolInfo("放电中")
        _liveBuffer.value = emptyList()
        _session.value = null
    }
}
