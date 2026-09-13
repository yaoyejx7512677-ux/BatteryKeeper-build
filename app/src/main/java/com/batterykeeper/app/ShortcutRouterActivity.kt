package com.batterykeeper.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import com.batterykeeper.app.battery.BatteryMonitorService
import com.batterykeeper.app.settings.AppSettings

/**
 * 桌面长按快捷入口路由。没有自己的 UI，执行系统跳转/监测开关后立即退出。
 */
class ShortcutRouterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            ACTION_SYSTEM_BATTERY -> openSystemBattery()
            ACTION_TOGGLE_MONITOR -> toggleMonitor()
            else -> openApp()
        }
    }

    private fun openSystemBattery() {
        val candidates = listOf(
            Intent("android.intent.action.POWER_USAGE_SUMMARY"),
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        val target = candidates.firstOrNull { it.resolveActivity(packageManager) != null }
        if (target != null) startActivity(target) else openApp()
        finish()
    }

    private fun toggleMonitor() {
        val settings = AppSettings(this)
        val enabled = !settings.monitorEnabled
        settings.monitorEnabled = enabled
        if (enabled) {
            runCatching { BatteryMonitorService.start(this) }
            Toast.makeText(this, "电池监测已开启", Toast.LENGTH_SHORT).show()
        } else {
            runCatching { BatteryMonitorService.stop(this) }
            Toast.makeText(this, "电池监测已停止", Toast.LENGTH_SHORT).show()
        }
        openApp()
        finish()
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
        )
    }

    companion object {
        const val ACTION_SYSTEM_BATTERY = "com.batterykeeper.app.action.SYSTEM_BATTERY"
        const val ACTION_TOGGLE_MONITOR = "com.batterykeeper.app.action.TOGGLE_MONITOR"
    }
}
