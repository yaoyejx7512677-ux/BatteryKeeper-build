package com.batterykeeper.app.settings

import android.content.Context
import android.content.SharedPreferences

/** 应用设置（SharedPreferences 封装） */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 充电时采样间隔（秒） */
    var chargingIntervalSec: Int
        get() = prefs.getInt(KEY_CHARGING_INTERVAL, 1)
        set(v) = prefs.edit().putInt(KEY_CHARGING_INTERVAL, v).apply()

    /** 平时采样间隔（秒） */
    var idleIntervalSec: Int
        get() = prefs.getInt(KEY_IDLE_INTERVAL, 10)
        set(v) = prefs.edit().putInt(KEY_IDLE_INTERVAL, v).apply()

    /** 熄屏时采样间隔（秒） */
    var screenOffIntervalSec: Int
        get() = prefs.getInt(KEY_SCREEN_OFF_INTERVAL, 60)
        set(v) = prefs.edit().putInt(KEY_SCREEN_OFF_INTERVAL, v).apply()

    /** 电池设计容量 mAh */
    var designCapacityMah: Int
        get() = prefs.getInt(KEY_DESIGN_CAPACITY, 7000)
        set(v) = prefs.edit().putInt(KEY_DESIGN_CAPACITY, v).apply()

    /** 原始采样保留天数 */
    var retentionDays: Int
        get() = prefs.getInt(KEY_RETENTION_DAYS, 30)
        set(v) = prefs.edit().putInt(KEY_RETENTION_DAYS, v).apply()

    /** 监测服务是否启用 */
    var monitorEnabled: Boolean
        get() = prefs.getBoolean(KEY_MONITOR_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_MONITOR_ENABLED, v).apply()

    /** Xiaomi HyperOS 原生超级岛是否启用 */
    var nativeIslandEnabled: Boolean
        get() = if (prefs.contains(KEY_NATIVE_ISLAND_ENABLED)) {
            prefs.getBoolean(KEY_NATIVE_ISLAND_ENABLED, true)
        } else {
            // v1.4 用户如果曾开启旧悬浮岛，升级后自动沿用为“原生超级岛开启”。
            // 全新安装默认开启，由系统能力/小米授权决定是否真正上岛。
            prefs.getBoolean(KEY_OVERLAY_ENABLED_LEGACY, true)
        }
        set(v) = prefs.edit().putBoolean(KEY_NATIVE_ISLAND_ENABLED, v).apply()

    /** 自算循环次数（系统不上报循环数时的降级方案） */
    var selfCycleCount: Int
        get() = prefs.getInt(KEY_SELF_CYCLES, 0)
        set(v) = prefs.edit().putInt(KEY_SELF_CYCLES, v).apply()

    /** 自算累计放电量 mAh（未满一循环的余数） */
    var selfDischargedMah: Int
        get() = prefs.getInt(KEY_SELF_DISCHARGED, 0)
        set(v) = prefs.edit().putInt(KEY_SELF_DISCHARGED, v).apply()

    /** 上次报告导入的文件位置（文件选择器记住位置用） */
    var lastImportUri: String
        get() = prefs.getString(KEY_LAST_IMPORT_URI, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LAST_IMPORT_URI, v).apply()

    var dischargedRemainder: Float
        get() = prefs.getFloat("discharged_remainder", selfDischargedMah.toFloat())
        set(v) = prefs.edit().putFloat("discharged_remainder", v).apply()
    var fullChargeMah: Int
        get() = prefs.getInt("full_charge_mah", -1)
        set(v) = prefs.edit().putInt("full_charge_mah", v).apply()
    var fullChargeTime: Long
        get() = prefs.getLong("full_charge_time", 0)
        set(v) = prefs.edit().putLong("full_charge_time", v).apply()

    private companion object {
        const val KEY_CHARGING_INTERVAL = "charging_interval"
        const val KEY_IDLE_INTERVAL = "idle_interval"
        const val KEY_SCREEN_OFF_INTERVAL = "screen_off_interval"
        const val KEY_DESIGN_CAPACITY = "design_capacity"
        const val KEY_RETENTION_DAYS = "retention_days"
        const val KEY_MONITOR_ENABLED = "monitor_enabled"
        const val KEY_NATIVE_ISLAND_ENABLED = "native_island_enabled"
        const val KEY_OVERLAY_ENABLED_LEGACY = "overlay_enabled"
        const val KEY_SELF_CYCLES = "self_cycles"
        const val KEY_SELF_DISCHARGED = "self_discharged"
        const val KEY_LAST_IMPORT_URI = "last_import_uri"
    }
}
