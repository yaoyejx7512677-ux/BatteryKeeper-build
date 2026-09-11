package com.batterykeeper.app.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.math.abs

/**
 * 电池采样器：从 ACTION_BATTERY_CHANGED 粘性广播 + BatteryManager 属性读取数据。
 * 全部使用公开 API，无需任何权限。
 */
object BatterySampler {

    fun sample(context: Context): BatterySnapshot {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
            ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE

        val voltageV = if (voltageMv > 0) voltageMv / 1000f else Float.NaN
        val tempC = if (tempTenths != Int.MIN_VALUE) tempTenths / 10f else Float.NaN

        // 电流：μA。不同内核符号约定不同，取幅值，方向由 status 决定。
        val currentUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentA = if (currentUa != Int.MIN_VALUE && currentUa != 0) {
            // 公开 API 单位为 μA；异常量级不推测换算
            val a = abs(currentUa.toLong()) / 1_000_000f
            if (a <= 100f) a else Float.NaN
        } else if (currentUa == 0) 0f else Float.NaN

        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val powerW = if (currentA.isFinite() && voltageV.isFinite()) currentUa / 1_000_000f * voltageV else Float.NaN

        val chargeCounterUah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val chargeCounterMah = if (chargeCounterUah != Int.MIN_VALUE && chargeCounterUah > 0)
            chargeCounterUah / 1000 else -1

        // Android 14+ 公开广播字段；设备未上报时保留未知
        val cycles = intent?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1) ?: -1

        val levelPct = if (level >= 0 && scale > 0) level * 100 / scale else -1

        return BatterySnapshot(
            level = levelPct,
            levelExact = exactLevel(level, scale),
            status = status,
            plugged = plugged,
            voltageV = voltageV,
            currentA = currentA,
            powerW = powerW,
            tempC = tempC,
            health = health,
            chargeCounterMah = chargeCounterMah,
            cycleCount = cycles,
        )
    }

}

/** 精确电量百分比（两位小数） */
private fun exactLevel(level: Int, scale: Int): Float =
    if (level >= 0 && scale > 0) level * 10000f / scale / 100f else 0f
