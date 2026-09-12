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
            prefs.getBoolean(KEY_OVERLAY_ENABLED_LEGACY, true)
        }
        set(v) = prefs.edit().putBoolean(KEY_NATIVE_ISLAND_ENABLED, v).apply()

    var selfCycleCount: Int
        get() = prefs.getInt(KEY_SELF_CYCLES, 0)
        set(v) = prefs.edit().putInt(KEY_SELF_CYCLES, v).apply()

    var selfDischargedMah: Int
        get() = prefs.getInt(KEY_SELF_DISCHARGED, 0)
        set(v) = prefs.edit().putInt(KEY_SELF_DISCHARGED, v).apply()

    var lastImportUri: String
        get() = prefs.getString(KEY_LAST_IMPORT_URI, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LAST_IMPORT_URI, v).apply()

    /** 已授权的系统错误报告目录（SAF tree URI），用于一键查找最新 Bugreport。 */
    var reportFolderUri: String
        get() = prefs.getString(KEY_REPORT_FOLDER_URI, "") ?: ""
        set(v) = prefs.edit().putString(KEY_REPORT_FOLDER_URI, v).apply()

    var dischargedRemainder: Float
        get() = prefs.getFloat("discharged_remainder", selfDischargedMah.toFloat())
        set(v) = prefs.edit().putFloat("discharged_remainder", v).apply()
    var fullChargeMah: Int
        get() = prefs.getInt("full_charge_mah", -1)
        set(v) = prefs.edit().putInt("full_charge_mah", v).apply()
    var fullChargeTime: Long
        get() = prefs.getLong("full_charge_time", 0)
        set(v) = prefs.edit().putLong("full_charge_time", v).apply()

    var monitorHeartbeatTime: Long
        get() = prefs.getLong(KEY_MONITOR_HEARTBEAT, 0L)
        set(v) = prefs.edit().putLong(KEY_MONITOR_HEARTBEAT, v).apply()

    var monitorServiceStartedAt: Long
        get() = prefs.getLong(KEY_MONITOR_STARTED, 0L)
        set(v) = prefs.edit().putLong(KEY_MONITOR_STARTED, v).apply()

    var monitorServiceStoppedAt: Long
        get() = prefs.getLong(KEY_MONITOR_STOPPED, 0L)
        set(v) = prefs.edit().putLong(KEY_MONITOR_STOPPED, v).apply()

    var monitorLastPlugged: Int
        get() = prefs.getInt(KEY_MONITOR_LAST_PLUGGED, 0)
        set(v) = prefs.edit().putInt(KEY_MONITOR_LAST_PLUGGED, v).apply()

    var monitorLastStatus: Int
        get() = prefs.getInt(KEY_MONITOR_LAST_STATUS, 0)
        set(v) = prefs.edit().putInt(KEY_MONITOR_LAST_STATUS, v).apply()

    var islandLastPostTime: Long
        get() = prefs.getLong(KEY_ISLAND_LAST_POST, 0L)
        set(v) = prefs.edit().putLong(KEY_ISLAND_LAST_POST, v).apply()

    var islandLastPostReason: String
        get() = prefs.getString(KEY_ISLAND_LAST_POST_REASON, "尚未投递") ?: "尚未投递"
        set(v) = prefs.edit().putString(KEY_ISLAND_LAST_POST_REASON, v).apply()

    var islandPostCount: Int
        get() = prefs.getInt(KEY_ISLAND_POST_COUNT, 0)
        set(v) = prefs.edit().putInt(KEY_ISLAND_POST_COUNT, v).apply()

    var islandRecoveryCount: Int
        get() = prefs.getInt(KEY_ISLAND_RECOVERY_COUNT, 0)
        set(v) = prefs.edit().putInt(KEY_ISLAND_RECOVERY_COUNT, v).apply()

    var islandLastDismissTime: Long
        get() = prefs.getLong(KEY_ISLAND_LAST_DISMISS, 0L)
        set(v) = prefs.edit().putLong(KEY_ISLAND_LAST_DISMISS, v).apply()

    var islandLastDismissReason: String
        get() = prefs.getString(KEY_ISLAND_LAST_DISMISS_REASON, "从未主动取消") ?: "从未主动取消"
        set(v) = prefs.edit().putString(KEY_ISLAND_LAST_DISMISS_REASON, v).apply()

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
        const val KEY_REPORT_FOLDER_URI = "report_folder_uri"

        const val KEY_MONITOR_HEARTBEAT = "diag_monitor_heartbeat"
        const val KEY_MONITOR_STARTED = "diag_monitor_started"
        const val KEY_MONITOR_STOPPED = "diag_monitor_stopped"
        const val KEY_MONITOR_LAST_PLUGGED = "diag_monitor_last_plugged"
        const val KEY_MONITOR_LAST_STATUS = "diag_monitor_last_status"
        const val KEY_ISLAND_LAST_POST = "diag_island_last_post"
        const val KEY_ISLAND_LAST_POST_REASON = "diag_island_last_post_reason"
        const val KEY_ISLAND_POST_COUNT = "diag_island_post_count"
        const val KEY_ISLAND_RECOVERY_COUNT = "diag_island_recovery_count"
        const val KEY_ISLAND_LAST_DISMISS = "diag_island_last_dismiss"
        const val KEY_ISLAND_LAST_DISMISS_REASON = "diag_island_last_dismiss_reason"
    }
}
