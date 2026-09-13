package com.batterykeeper.app.battery

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.glance.appwidget.updateAll
import com.batterykeeper.app.MainActivity
import com.batterykeeper.app.data.*
import com.batterykeeper.app.settings.AppSettings
import com.batterykeeper.app.widget.BatteryWidget
import kotlinx.coroutines.*
import kotlin.math.abs

class BatteryMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: BatteryDatabase
    private lateinit var settings: AppSettings
    private lateinit var nativeIsland: XiaomiIslandController
    private var previous: BatterySnapshot? = null
    private var previousElapsed = 0L
    private var sessionId = 0L
    private var sessionStart: BatterySnapshot? = null
    private var energy = 0.0
    private var weightedPower = 0.0
    private var duration = 0L
    private var peak = 0f
    private var chargeCounterBaselineMah = -1
    private var counterEnergyBaseMah = 0.0
    private var disconnectSinceElapsed = 0L
    private var lastStored = 0L
    private var lastNotification = 0L
    private var lastWidget = 0L
    private var lastCheckpoint = 0L
    @Volatile private var pendingStop = false
    private val stopSignal = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    override fun onCreate() {
        super.onCreate()
        db = BatteryDatabase.get(this)
        settings = AppSettings(this)
        settings.monitorServiceStartedAt = System.currentTimeMillis()
        settings.monitorHeartbeatTime = settings.monitorServiceStartedAt
        nativeIsland = XiaomiIslandController(this)
        try {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification("正在读取电池状态"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } catch (e: Exception) {
            android.util.Log.e("BatteryKeeper", "Unable to start monitoring", e)
            stopSelf(); return
        }
        scope.launch {
            while (isActive && !pendingStop) {
                try { sample() } catch (e: CancellationException) { throw e }
                catch (e: Exception) { android.util.Log.e("BatteryKeeper", "Sampling failed", e) }
                val screenOn = getSystemService(PowerManager::class.java).isInteractive
                val seconds = when {
                    previous?.plugged != 0 -> settings.chargingIntervalSec
                    screenOn -> settings.idleIntervalSec
                    else -> settings.screenOffIntervalSec
                }
                withTimeoutOrNull(seconds.coerceIn(1, 60) * 1000L) { stopSignal.receive() }
            }
            try { previous?.let { checkpoint(it, true) } }
            catch (e: Exception) { android.util.Log.e("BatteryKeeper", "Final checkpoint failed", e) }
            finally { stopSelf() }
        }
    }

    private suspend fun sample() {
        val s = BatterySampler.sample(this)
        val wallNow = System.currentTimeMillis()
        settings.monitorHeartbeatTime = wallNow
        settings.monitorLastPlugged = s.plugged
        settings.monitorLastStatus = s.status
        val now = SystemClock.elapsedRealtime()
        val prev = previous
        val dt = if (prev != null) MeasurementMath.durationMs(previousElapsed, now, if (s.plugged != 0) 15 * 60_000L else 120_000L) else 0L
        val connected = s.plugged != 0
        val changed = prev == null || s.status != prev.status || s.plugged != prev.plugged

        // v1.6.0: 断电采用 30 秒宽限，过滤 HyperOS 瞬时 plugged/status 抖动造成的碎片会话。
        if (sessionStart != null && !connected) {
            if (disconnectSinceElapsed == 0L) disconnectSinceElapsed = now
            if (now - disconnectSinceElapsed >= SESSION_DISCONNECT_GRACE_MS) {
                checkpoint(prev ?: s, true)
                clearSession()
            }
        } else if (connected) {
            disconnectSinceElapsed = 0L
        }

        if (connected && sessionStart == null) {
            startOrResumeSession(s, allowResume = prev == null)
        } else if (connected && prev != null && dt > 0) {
            val prevA = prev.currentA.takeIf { it.isFinite() }?.toDouble() ?: 0.0
            val nowA = s.currentA.takeIf { it.isFinite() }?.toDouble() ?: 0.0
            energy += MeasurementMath.chargeMah(prevA, nowA, dt)

            // CURRENT_NOW 在系统休眠/进程暂停时会产生采样空洞。CHARGE_COUNTER 是当前电池电量计，
            // 在支持设备上用其增量兜底，避免 44%→77% 却只统计几十 mAh。
            if (chargeCounterBaselineMah > 0 && s.chargeCounterMah >= chargeCounterBaselineMah) {
                val counterEnergy = counterEnergyBaseMah + (s.chargeCounterMah - chargeCounterBaselineMah)
                if (counterEnergy.isFinite()) energy = maxOf(energy, counterEnergy)
            }

            val prevPower = prev.powerW.takeIf { it.isFinite() }?.let { abs(it) } ?: 0f
            val nowPower = s.powerW.takeIf { it.isFinite() }?.let { abs(it) } ?: 0f
            weightedPower += (prevPower + nowPower) / 2.0 * dt
            duration += dt
        }

        if (sessionStart != null) {
            if (s.powerW.isFinite()) peak = maxOf(peak, abs(s.powerW))
            BatteryStateHolder.updateSession(
                BatteryStateHolder.SessionInfo(
                    sessionStart!!.timestamp,
                    sessionStart!!.level,
                    energy.toInt(),
                    peak,
                    if (duration > 0) (weightedPower / duration).toFloat() else 0f,
                ),
            )
        }

        if (prev != null && dt > 0 && prev.powerW < 0 && s.powerW < 0) {
            val drained = MeasurementMath.chargeMah(prev.currentA.toDouble(), s.currentA.toDouble(), dt)
            val total = settings.dischargedRemainder + drained
            val capacity = settings.designCapacityMah.coerceAtLeast(1)
            settings.selfCycleCount += (total / capacity).toInt()
            settings.dischargedRemainder = (total % capacity).toFloat()
        }
        if (s.isFull && s.chargeCounterMah > 0 && prev?.isFull != true) {
            settings.fullChargeMah = s.chargeCounterMah
            settings.fullChargeTime = s.timestamp
        }

        val protocol = ProtocolDetector.detect(s)
        BatteryStateHolder.update(s, protocol)
        val islandShown = if (settings.nativeIslandEnabled && connected) {
            nativeIsland.update(s, protocol.displayName, force = changed)
        } else {
            nativeIsland.dismiss(if (!settings.nativeIslandEnabled) "设置中已关闭超级岛" else "拔掉电源")
            false
        }

        previous = s
        previousElapsed = now
        if (changed || now - lastStored >= 10_000) {
            if (s.powerW.isFinite() && s.tempC.isFinite() && s.level >= 0) {
                db.sampleDao().insert(
                    Sample(
                        timestamp = s.timestamp, level = s.level, powerW = s.powerW,
                        currentA = s.currentA, voltageV = s.voltageV, tempC = s.tempC,
                        status = s.status, plugged = s.plugged,
                    ),
                )
            }
            lastStored = now
        }
        if (sessionStart != null && (changed || now - lastCheckpoint >= 15_000)) {
            checkpoint(s, false)
            lastCheckpoint = now
        }
        if (!islandShown && (changed || now - lastNotification >= 15_000)) {
            getSystemService(NotificationManager::class.java).notify(
                FOREGROUND_NOTIFICATION_ID,
                notification("${s.stateName} · ${s.level}% · ${s.powerW.display()} W（电池侧）"),
            )
            lastNotification = now
        } else if (islandShown) lastNotification = now

        if (changed || now - lastWidget >= 60_000) {
            BatteryWidget().updateAll(this)
            lastWidget = now
        }
    }

    private suspend fun startOrResumeSession(s: BatterySnapshot, allowResume: Boolean) {
        val recent = db.chargeSessionDao().latest(1).firstOrNull()
        val recentEnd = recent?.endTime
        val canResume = allowResume && recent != null && recentEnd != null &&
            s.timestamp - recentEnd in 0..SESSION_RESUME_WINDOW_MS &&
            recent.pluggedType != 0 &&
            (recent.endLevel ?: recent.startLevel) <= s.level + 2

        if (canResume) {
            sessionStart = s.copy(timestamp = recent!!.startTime, level = recent.startLevel, plugged = recent.pluggedType)
            sessionId = recent.id
            energy = recent.energyMah.toDouble()
            duration = (recentEnd!! - recent.startTime).coerceAtLeast(0L)
            weightedPower = recent.avgPowerW.toDouble() * duration
            peak = recent.peakPowerW
        } else {
            sessionStart = s
            sessionId = 0L
            energy = 0.0
            weightedPower = 0.0
            duration = 0L
            peak = 0f
        }
        chargeCounterBaselineMah = s.chargeCounterMah.takeIf { it > 0 } ?: -1
        counterEnergyBaseMah = energy
        disconnectSinceElapsed = 0L
    }

    private fun clearSession() {
        sessionStart = null
        sessionId = 0L
        energy = 0.0
        weightedPower = 0.0
        duration = 0L
        peak = 0f
        chargeCounterBaselineMah = -1
        counterEnergyBaseMah = 0.0
        disconnectSinceElapsed = 0L
        BatteryStateHolder.updateSession(null)
    }

    private suspend fun checkpoint(s: BatterySnapshot, finished: Boolean) {
        val start = sessionStart ?: return
        val id = db.chargeSessionDao().save(
            ChargeSession(
                id = sessionId,
                startTime = start.timestamp,
                endTime = s.timestamp,
                startLevel = start.level,
                endLevel = s.level,
                energyMah = energy.toInt(),
                peakPowerW = peak,
                avgPowerW = if (duration > 0) (weightedPower / duration).toFloat() else 0f,
                protocol = if (finished) "已结束" else "记录至最近采样",
                pluggedType = start.plugged,
            ),
        )
        if (sessionId == 0L) sessionId = id
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, XiaomiIslandController.CHANNEL_ID)
            .setSmallIcon(com.batterykeeper.app.R.drawable.ic_island_battery)
            .setContentTitle("电池管家 · 监测运行中").setContentText(text)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).setOnlyAlertOnce(true).build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { pendingStop = true; stopSignal.trySend(Unit) }
        return if (pendingStop) START_NOT_STICKY else START_STICKY
    }

    override fun onDestroy() {
        settings.monitorServiceStoppedAt = System.currentTimeMillis()
        runCatching { nativeIsland.dismiss("监测服务销毁") }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        scope.cancel()
        BatteryStateHolder.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val FOREGROUND_NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.batterykeeper.app.STOP_MONITOR"
        private const val SESSION_DISCONNECT_GRACE_MS = 30_000L
        private const val SESSION_RESUME_WINDOW_MS = 5 * 60_000L
        fun start(context: Context) { context.startForegroundService(Intent(context, BatteryMonitorService::class.java)) }
        fun stop(context: Context) { context.startService(Intent(context, BatteryMonitorService::class.java).setAction(ACTION_STOP)) }
    }
}

fun Float.display(decimals: Int = 1): String = if (isFinite()) "%.${decimals}f".format(abs(this)) else "—"
