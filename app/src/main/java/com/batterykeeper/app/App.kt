package com.batterykeeper.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.batterykeeper.app.battery.BatteryMonitorService
import com.batterykeeper.app.settings.AppSettings
import com.batterykeeper.app.worker.DailyWorker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // WorkManager 初始化失败（厂商裁剪等）不影响 App 启动
        runCatching { DailyWorker.schedule(this) }
    }
}

/** 开机自启恢复监测服务；原生超级岛由监测服务在充电时创建。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val settings = AppSettings(context)
            if (settings.monitorEnabled) {
                runCatching { BatteryMonitorService.start(context) }
            }
        }
    }
}
