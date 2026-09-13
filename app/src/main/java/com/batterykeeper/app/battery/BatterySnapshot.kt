package com.batterykeeper.app.battery

import android.os.BatteryManager

/** 一次采样得到的电池全量快照 */
data class BatterySnapshot(
    val timestamp: Long = System.currentTimeMillis(),
    val level: Int,               // 0-100（整数）
    val levelExact: Float,        // 精确电量 %（如 54.37）
    val status: Int,              // BATTERY_STATUS_*
    val plugged: Int,             // 0 / AC / USB / WIRELESS
    val voltageV: Float,          // V
    val currentA: Float,          // A（幅值）
    val powerW: Float,            // W（充电为正，放电为负）
    val tempC: Float,             // ℃
    val health: Int,              // BATTERY_HEALTH_*
    val chargeCounterMah: Int,    // 当前电量 μAh→mAh（不支持时 -1）
    val cycleCount: Int,          // 系统循环次数（不支持时 -1）
    val chargeTimeRemainingMs: Long, // 系统预计充满剩余时间 ms（不支持时 -1）
) {
    val isCharging: Boolean
        get() = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    val isFull: Boolean get() = status == BatteryManager.BATTERY_STATUS_FULL
    val pluggedName: String
        get() = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC 供电"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB 供电"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线供电"
            else -> "电池供电"
        }
    val healthName: String
        get() = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
            BatteryManager.BATTERY_HEALTH_DEAD -> "损坏"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "过压"
            BatteryManager.BATTERY_HEALTH_COLD -> "过冷"
            else -> "未知"
        }
    val stateName: String get() = when {
        isFull -> "已充满"
        status == BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
        plugged != 0 -> "已连接 · 未充电"
        status == BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
        else -> "状态未知"
    }
    /** 原始系统电量，不额外宣称精度 */
    val levelExactText: String get() = "%.2f".format(levelExact)
}
