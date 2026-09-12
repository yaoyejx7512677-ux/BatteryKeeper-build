package com.batterykeeper.app.battery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.batterykeeper.app.MainActivity
import com.batterykeeper.app.R
import com.batterykeeper.app.settings.AppSettings
import org.json.JSONObject
import kotlin.math.abs
import java.security.MessageDigest
import java.util.Locale

/** Xiaomi HyperOS 原生超级岛控制器。 */
class XiaomiIslandController(private val context: Context) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val settings = AppSettings(context)
    private var lastLevel: Int? = null
    private var lastPowerW: Float? = null
    private var lastTempC: Float? = null
    private var lastTier: String? = null
    private var lastPlugged: Int? = null
    private var lastUpdateElapsed = 0L
    private var visible = false

    init {
        ensureChannel()
        // v1.5.3 及更早版本使用 #1501 作为独立岛通知。升级到统一通知后主动清理旧卡片。
        notificationManager.cancel(LEGACY_ISLAND_NOTIFICATION_ID)
    }

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

    fun status(): IslandStatus {
        val now = System.currentTimeMillis()
        val heartbeat = settings.monitorHeartbeatTime
        return IslandStatus(
            protocolVersion = protocolVersion(),
            systemSupported = systemSupportsIsland(),
            focusPermission = hasFocusPermission(),
            notificationsEnabled = notificationManager.areNotificationsEnabled(),
            unifiedNotificationActive = isNotificationActive(NOTIFICATION_ID),
            focusPayloadActive = activeNotificationHasFocusPayload(),
            monitorHeartbeatTime = heartbeat,
            monitorHeartbeatFresh = heartbeat > 0L && now - heartbeat < HEARTBEAT_STALE_MS,
            monitorLastPlugged = settings.monitorLastPlugged,
            monitorLastStatus = settings.monitorLastStatus,
            lastPostTime = settings.islandLastPostTime,
            lastPostReason = settings.islandLastPostReason,
            postCount = settings.islandPostCount,
            recoveryCount = settings.islandRecoveryCount,
            lastDismissTime = settings.islandLastDismissTime,
            lastDismissReason = settings.islandLastDismissReason,
            signingCertSha256 = signingCertificateSha256(),
            officialAppIdConfigured = officialAppIdConfigured(),
            debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
    }

    /**
     * 只要仍连接电源就保持超级岛；只有真正拔掉电源时才下岛。
     * HyperOS 可能在热保护、满充保护、旁路供电等情况下短暂把 status 变为 NOT_CHARGING，
     * 因此不能用 isCharging 作为岛生命周期条件。
     */
    fun update(snapshot: BatterySnapshot, tierName: String, force: Boolean = false): Boolean {
        if (snapshot.plugged == 0) {
            dismiss("拔掉电源")
            return false
        }
        if (!systemSupportsIsland()) {
            dismiss("系统未检测到超级岛能力")
            return false
        }

        val now = android.os.SystemClock.elapsedRealtime()
        val levelChanged = lastLevel != snapshot.level
        val tierChanged = lastTier != tierName
        val plugChanged = lastPlugged != snapshot.plugged
        val powerChanged = lastPowerW?.let { abs(it - snapshot.powerW) >= POWER_DELTA_W } ?: true
        val tempChanged = lastTempC?.let { abs(it - snapshot.tempC) >= TEMP_DELTA_C } ?: true
        val sinceLastUpdate = now - lastUpdateElapsed
        val intervalPassed = sinceLastUpdate >= MIN_UPDATE_MS
        val significantTelemetryChange = powerChanged || tempChanged
        val earlyTelemetryRefresh = significantTelemetryChange && sinceLastUpdate >= EARLY_UPDATE_MIN_MS
        val notificationActive = isNotificationActive(NOTIFICATION_ID)
        // v1.5.4：只在统一通知 #1 真正消失时自动恢复。NotificationManager 对 extras 的
        // 瞬时查询失败不再触发补发，避免高频重投递被 SystemUI 限流/降级。
        val recoveringMissingNotification = visible && !notificationActive

        if (!force && visible && !recoveringMissingNotification && !levelChanged && !tierChanged && !plugChanged &&
            !intervalPassed && !earlyTelemetryRefresh) return true

        notificationManager.notify(NOTIFICATION_ID, buildNotification(snapshot, tierName))
        settings.islandLastPostTime = System.currentTimeMillis()
        settings.islandLastPostReason = when {
            recoveringMissingNotification -> "检测到统一通知 #1 不存在，自动恢复"
            force -> "电源/状态变化，强制更新"
            !visible -> "首次上岛"
            else -> "温度/功率更新"
        }
        settings.islandPostCount = settings.islandPostCount + 1
        if (recoveringMissingNotification) {
            settings.islandRecoveryCount = settings.islandRecoveryCount + 1
        }
        visible = true
        lastLevel = snapshot.level
        lastPowerW = snapshot.powerW
        lastTempC = snapshot.tempC
        lastTier = tierName
        lastPlugged = snapshot.plugged
        lastUpdateElapsed = now
        return true
    }

    /**
     * 只清理“岛状态”，不取消通知。
     * v1.5.3 起超级岛通知与前台监测通知合并为同一个 #1；前台服务运行期间必须保留该通知。
     * 退出岛时由 BatteryMonitorService 用普通监测内容覆盖同一个 #1。
     */
    fun dismiss(reason: String = "主动取消") {
        val wasActive = visible || activeNotificationHasFocusPayload()
        if (wasActive) {
            settings.islandLastDismissTime = System.currentTimeMillis()
            settings.islandLastDismissReason = reason
        }
        visible = false
        lastLevel = null
        lastPowerW = null
        lastTempC = null
        lastTier = null
        lastPlugged = null
        lastUpdateElapsed = 0L
    }

    private fun isNotificationActive(id: Int): Boolean = runCatching {
        notificationManager.activeNotifications.any { it.id == id }
    }.getOrDefault(false)

    private fun activeNotificationHasFocusPayload(): Boolean = runCatching {
        notificationManager.activeNotifications
            .firstOrNull { it.id == NOTIFICATION_ID }
            ?.notification
            ?.extras
            ?.getString(FOCUS_PARAM_KEY)
            ?.isNotBlank() == true
    }.getOrDefault(false)

    private fun officialAppIdConfigured(): Boolean = runCatching {
        val info = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
        )
        !info.metaData?.getString("com.xiaomi.xms.APP_ID").isNullOrBlank()
    }.getOrDefault(false)

    private fun signingCertificateSha256(): String = runCatching {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
        val cert = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            ?: return@runCatching "未知"
        MessageDigest.getInstance("SHA-256").digest(cert)
            .joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
    }.getOrDefault("未知")

    private fun ensureChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "原生超级岛", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "连接电源时通过 Xiaomi HyperOS 原生超级岛显示实时电池状态"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(snapshot: BatterySnapshot, tierName: String): Notification {
        val power = snapshot.powerW.display()
        val temp = if (snapshot.tempC.isFinite()) "%.1f℃".format(snapshot.tempC) else "—℃"
        val voltage = if (snapshot.voltageV.isFinite()) "%.2fV".format(snapshot.voltageV) else "—V"
        val current = if (snapshot.currentA.isFinite()) "%.2fA".format(abs(snapshot.currentA)) else "—A"
        val title = tierName.ifBlank { snapshot.stateName }
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
            .setContentText("$power W · $temp · $voltage")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .addExtras(extras)
            .build()

        notification.extras.putString(
            FOCUS_PARAM_KEY,
            buildIslandJson(
                tierName = title,
                level = snapshot.level,
                powerText = "$power W",
                tempText = temp,
                voltageText = voltage,
                currentText = current,
                ticker = ticker,
                powerCompact = compactPower(snapshot.powerW),
                tempCompact = compactTemp(snapshot.tempC),
            ),
        )
        return notification
    }

    /**
     * 摘要态：大岛 A 区显示温度，B 区显示功率；小岛保留电池图标兜底。
     * 展开态：baseInfo 展示充电档位、电量、功率、温度、电压、电流。
     * 不添加按钮。HyperOS 原生交互为：点击摘要态 -> 展开态，再点击展开态 -> contentIntent 打开主界面。
     */
    private fun buildIslandJson(
        tierName: String,
        level: Int,
        powerText: String,
        tempText: String,
        voltageText: String,
        currentText: String,
        ticker: String,
        powerCompact: String,
        tempCompact: String,
    ): String {
        // 大岛 A 区：温度。图文组件1允许不传图标，只用大字。
        val leftTextInfo = JSONObject()
            .put("title", tempCompact)
            .put("content", "温度")
            .put("narrowFont", true)
            .put("useHighLight", false)

        val leftArea = JSONObject()
            .put("type", 1)
            .put("miui.focus.paramtextInfo", leftTextInfo)

        // 大岛 B 区：使用纯文本组件，显示实时功率。
        val rightTextInfo = JSONObject()
            .put("title", powerCompact)
            .put("content", "功率")
            .put("narrowFont", true)
            .put("useHighLight", false)

        val bigIslandArea = JSONObject()
            .put("imageTextInfoLeft", leftArea)
            .put("textInfo", rightTextInfo)

        // 当系统把大岛压缩成小岛时，至少仍显示 BatteryKeeper 电池图标。
        val smallIslandArea = JSONObject().put(
            "picInfo",
            JSONObject().put("type", 1).put("pic", PIC_BATTERY),
        )

        val paramIsland = JSONObject()
            .put("islandProperty", 1)
            .put("islandOrder", true)
            .put("islandTimeout", ISLAND_TIMEOUT_SEC)
            .put("dismissIsland", false)
            .put("bigIslandArea", bigIslandArea)
            .put("smallIslandArea", smallIslandArea)

        val baseInfo = JSONObject()
            .put("type", 2)
            .put("title", "$tierName · $level%")
            .put("subTitle", tempText)
            .put("content", powerText)
            .put("subContent", "$voltageText · $currentText")
            .put("showDivider", true)
            .put("showContentDivider", true)

        val paramV2 = JSONObject()
            .put("protocol", 1)
            .put("business", BUSINESS)
            .put("islandFirstFloat", false)
            .put("enableFloat", false)
            .put("timeout", NOTIFICATION_TIMEOUT_MIN)
            .put("updatable", true)
            .put("reopen", "reopen")
            .put("filterWhenNoPermission", true)
            .put("ticker", ticker)
            .put("aodTitle", "$tierName $level% · $powerText")
            .put("param_island", paramIsland)
            .put("baseInfo", baseInfo)

        return JSONObject().put("param_v2", paramV2).toString()
    }

    private fun compactTemp(tempC: Float): String =
        if (tempC.isFinite()) String.format(Locale.US, "%.1f℃", tempC) else "—℃"

    private fun compactPower(powerW: Float): String =
        if (powerW.isFinite()) String.format(Locale.US, "%.1fW", abs(powerW)) else "—W"

    data class IslandStatus(
        val protocolVersion: Int,
        val systemSupported: Boolean,
        val focusPermission: Boolean,
        val notificationsEnabled: Boolean,
        val unifiedNotificationActive: Boolean,
        val focusPayloadActive: Boolean,
        val monitorHeartbeatTime: Long,
        val monitorHeartbeatFresh: Boolean,
        val monitorLastPlugged: Int,
        val monitorLastStatus: Int,
        val lastPostTime: Long,
        val lastPostReason: String,
        val postCount: Int,
        val recoveryCount: Int,
        val lastDismissTime: Long,
        val lastDismissReason: String,
        val signingCertSha256: String,
        val officialAppIdConfigured: Boolean,
        val debuggable: Boolean,
    )

    companion object {
        const val CHANNEL_ID = "xiaomi_native_island"
        // 与前台监测服务共用同一个通知 ID，避免通知栏出现两张 BatteryKeeper 卡片。
        const val NOTIFICATION_ID = 1
        private const val LEGACY_ISLAND_NOTIFICATION_ID = 1501
        private const val FOCUS_PARAM_KEY = "miui.focus.param"
        private const val PIC_BATTERY = "miui.focus.pic_batterykeeper"
        private const val BUSINESS = "battery_charging"
        private const val MIN_UPDATE_MS = 10_000L
        private const val EARLY_UPDATE_MIN_MS = 3_000L
        private const val POWER_DELTA_W = 3f
        private const val TEMP_DELTA_C = 1f
        private const val HEARTBEAT_STALE_MS = 90_000L
        // 小米准入原则要求单次服务生命周期不超过 12 小时。
        private const val NOTIFICATION_TIMEOUT_MIN = 12 * 60
        private const val ISLAND_TIMEOUT_SEC = 12 * 60 * 60
    }
}
