package com.batterykeeper.app.battery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.batterykeeper.app.MainActivity
import com.batterykeeper.app.R
import org.json.JSONObject
import kotlin.math.abs

/** Xiaomi HyperOS 原生超级岛控制器。 */
class XiaomiIslandController(private val context: Context) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private var lastLevel: Int? = null
    private var lastPowerW: Float? = null
    private var lastTempC: Float? = null
    private var lastTier: String? = null
    private var lastUpdateElapsed = 0L
    private var visible = false

    init { ensureChannel() }

    /** 官方定义：3 = HyperOS 3，支持小米超级岛。 */
    fun protocolVersion(): Int = runCatching {
        Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)
    }.getOrDefault(0)

    fun systemSupportsIsland(): Boolean {
        val byProtocol = protocolVersion() >= 3
        val byProperty = runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getDeclaredMethod(
                "getBoolean", String::class.java, java.lang.Boolean.TYPE,
            )
            method.invoke(null, "persist.sys.feature.island", false) as? Boolean ?: false
        }.getOrDefault(false)
        return byProtocol || byProperty
    }

    /** HyperOS SystemUI 提供的焦点通知权限查询。 */
    fun hasFocusPermission(): Boolean = runCatching {
        val uri = Uri.parse("content://miui.statusbar.notification.public")
        val extras = Bundle().apply { putString("package", context.packageName) }
        context.contentResolver.call(uri, "canShowFocus", null, extras)
            ?.getBoolean("canShowFocus", false) == true
    }.getOrDefault(false)

    fun status() = IslandStatus(protocolVersion(), systemSupportsIsland(), hasFocusPermission())

    /** 充电时创建/更新；非充电时立即下岛。 */
    fun update(snapshot: BatterySnapshot, tierName: String, force: Boolean = false) {
        if (!snapshot.isCharging) {
            dismiss()
            return
        }
        // 非 HyperOS 3 不额外生成一条普通通知。
        if (!systemSupportsIsland()) {
            dismiss()
            return
        }

        val now = android.os.SystemClock.elapsedRealtime()
        val levelChanged = lastLevel != snapshot.level
        val tierChanged = lastTier != tierName
        val powerChanged = lastPowerW?.let { abs(it - snapshot.powerW) >= 1f } ?: true
        val tempChanged = lastTempC?.let { abs(it - snapshot.tempC) >= 1f } ?: true
        val intervalPassed = now - lastUpdateElapsed >= MIN_UPDATE_MS

        if (!force && visible && !levelChanged && !tierChanged && !(intervalPassed && (powerChanged || tempChanged))) return

        notificationManager.notify(NOTIFICATION_ID, buildNotification(snapshot, tierName))
        visible = true
        lastLevel = snapshot.level
        lastPowerW = snapshot.powerW
        lastTempC = snapshot.tempC
        lastTier = tierName
        lastUpdateElapsed = now
    }

    fun dismiss() {
        notificationManager.cancel(NOTIFICATION_ID)
        visible = false
        lastLevel = null
        lastPowerW = null
        lastTempC = null
        lastTier = null
        lastUpdateElapsed = 0L
    }

    private fun ensureChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "原生超级岛", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "充电时通过 Xiaomi HyperOS 原生超级岛显示实时电池状态"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(snapshot: BatterySnapshot, tierName: String): Notification {
        val power = snapshot.powerW.display()
        val temp = if (snapshot.tempC.isFinite()) "%.1f℃".format(snapshot.tempC) else "—℃"
        val title = tierName.ifBlank { "充电中" }
        val ticker = "$title ${snapshot.level}% · $power W"

        val openApp = PendingIntent.getActivity(
            context,
            1500,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val extras = Bundle().apply {
            putBundle("miui.focus.pics", Bundle().apply {
                putParcelable(PIC_BATTERY, Icon.createWithResource(context, R.drawable.ic_island_battery))
            })
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_island_battery)
            .setContentTitle("$title · ${snapshot.level}%")
            .setContentText("电池侧 $power W · $temp")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .addExtras(extras)
            .build()

        notification.extras.putString(
            FOCUS_PARAM_KEY,
            buildIslandJson(title, snapshot.level, "$power W", temp, ticker),
        )
        return notification
    }

    /**
     * 使用小米开发指南公开示例的 imageTextInfoLeft + picInfo 模板结构。
     * 不添加 actions/hintInfo；点击沿用 Notification.contentIntent，直接进入主界面。
     */
    private fun buildIslandJson(
        tierName: String,
        level: Int,
        powerText: String,
        tempText: String,
        ticker: String,
    ): String {
        val picInfo = JSONObject().put("type", 1).put("pic", PIC_BATTERY)
        val textInfo = JSONObject()
            .put("frontTitle", tierName)
            .put("title", "$level%")
            .put("content", "$powerText · $tempText")
            .put("useHighLight", false)

        val bigIslandArea = JSONObject()
            .put(
                "imageTextInfoLeft",
                JSONObject()
                    .put("type", 1)
                    .put("picInfo", JSONObject(picInfo.toString()))
                    .put("miui.focus.paramtextInfo", textInfo),
            )
            .put("picInfo", JSONObject(picInfo.toString()))

        val smallIslandArea = JSONObject().put("picInfo", JSONObject(picInfo.toString()))

        val paramIsland = JSONObject()
            .put("islandProperty", 1)
            .put("islandTimeout", ISLAND_TIMEOUT_SEC)
            .put("bigIslandArea", bigIslandArea)
            .put("smallIslandArea", smallIslandArea)

        val baseInfo = JSONObject()
            .put("title", "$tierName · $level%")
            .put("content", "$powerText · $tempText")
            .put("type", 2)

        val paramV2 = JSONObject()
            .put("protocol", 1)
            .put("business", BUSINESS)
            .put("islandFirstFloat", false)
            .put("enableFloat", false)
            .put("updatable", true)
            .put("reopen", "reopen")
            .put("filterWhenNoPermission", true)
            .put("ticker", ticker)
            .put("aodTitle", "$tierName $level% · $powerText")
            .put("param_island", paramIsland)
            .put("baseInfo", baseInfo)

        return JSONObject().put("param_v2", paramV2).toString()
    }

    data class IslandStatus(
        val protocolVersion: Int,
        val systemSupported: Boolean,
        val focusPermission: Boolean,
    )

    companion object {
        const val CHANNEL_ID = "xiaomi_native_island"
        const val NOTIFICATION_ID = 1501
        private const val FOCUS_PARAM_KEY = "miui.focus.param"
        private const val PIC_BATTERY = "miui.focus.pic_batterykeeper"
        private const val BUSINESS = "battery_charging"
        private const val MIN_UPDATE_MS = 4_000L
        private const val ISLAND_TIMEOUT_SEC = 12 * 60 * 60
    }
}
