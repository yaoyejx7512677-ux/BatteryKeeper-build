package com.batterykeeper.app.battery

import android.os.BatteryManager
import kotlin.math.abs

/**
 * 充电档位推断：澎湃OS 不向第三方应用开放实时充电协议（sysfs/dumpsys 均不可读），
 * 按实测功率分档展示：普通充电 / 快充 / 超级快充 / 无线充电。
 */
object ProtocolDetector {

    data class ProtocolInfo(val displayName: String)

    /** 满充时缓存一次估算容量（由 Service 调用） */
    @Volatile var lastFullChargeMah: Int = -1

    fun detect(snapshot: BatterySnapshot): ProtocolInfo {
        if (!snapshot.isCharging || snapshot.plugged == 0) {
            return ProtocolInfo(snapshot.stateName)
        }
        if (snapshot.isFull) return ProtocolInfo("已充满")
        if (!snapshot.powerW.isFinite()) return ProtocolInfo("功率不可用")
        val w = abs(snapshot.powerW)
        val name = when {
            snapshot.plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ->
                if (w >= 30f) "无线快充" else "无线充电"
            w >= 50f -> "超级快充"
            w >= 15f -> "快充"
            else -> "普通充电"
        }
        return ProtocolInfo(name)
    }
}
