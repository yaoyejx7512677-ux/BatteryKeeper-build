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
                withTimeoutOrNull(seconds.coerceIn(1,60) * 1000L) { stopSignal.receive() }
            }
            try { previous?.let { checkpoint(it, true) } }
            catch(e: Exception) { android.util.Log.e("BatteryKeeper", "Final checkpoint failed", e) }
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
        val dt = if (prev != null) MeasurementMath.durationMs(previousElapsed, now) else 0L
        val connected = s.plugged != 0
        val changed = prev == null || s.status != prev.status || s.plugged != prev.plugged
        if (sessionStart != null && (!connected || prev?.plugged != s.plugged)) {
            checkpoint(prev ?: s, true)
            sessionStart = null; sessionId = 0L
            BatteryStateHolder.updateSession(null)
        }
        if (connected && sessionStart == null) {
            sessionStart = s; energy = 0.0; weightedPower = 0.0; duration = 0L; peak = 0f
        } else if (connected && prev != null && dt > 0) {
            // currentA 在采样器中已经统一为幅值。部分 Xiaomi 内核的 CURRENT_NOW 充电时为负数，
            // 因此不能再用 powerW > 0 判断“是否计入充电会话”，否则会出现实时功率正常但
            // 已充入/平均功率/峰值全部为 0 的情况。只要物理电源仍连接，就按电流幅值积分。
            val prevA = prev.currentA.takeIf { it.isFinite() }?.toDouble() ?: 0.0
            val nowA = s.currentA.takeIf { it.isFinite() }?.toDouble() ?: 0.0
            energy += MeasurementMath.chargeMah(prevA, nowA, dt)

            val prevPower = prev.powerW.takeIf { it.isFinite() }?.let { abs(it) } ?: 0f
            val nowPower = s.powerW.takeIf { it.isFinite() }?.let { abs(it) } ?: 0f
            weightedPower += (prevPower + nowPower) / 2.0 * dt
            duration += dt
        }
        if (sessionStart != null) {
            if (s.powerW.isFinite()) peak = maxOf(peak, abs(s.powerW))
            BatteryStateHolder.updateSession(BatteryStateHolder.SessionInfo(
                sessionStart!!.timestamp,sessionStart!!.level,energy.toInt(),peak,
                if (duration > 0) (weightedPower/duration).toFloat() else 0f))
        }
        if (prev != null && dt > 0 && prev.powerW < 0 && s.powerW < 0) {
            val drained = MeasurementMath.chargeMah(prev.currentA.toDouble(),s.currentA.toDouble(),dt)
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
            nativeIsland.dismiss(
                if (!settings.nativeIslandEnabled) "设置中已关闭超级岛" else "拔掉电源",
            )
            false
        }
        previous=s; previousElapsed=now
        // Keep one sample per ten seconds (or a state transition). UI can sample faster.
        if (changed || now-lastStored >= 10_000) {
            if (s.powerW.isFinite() && s.tempC.isFinite() && s.level >= 0) {
                db.sampleDao().insert(Sample(timestamp=s.timestamp,level=s.level,powerW=s.powerW,
                    currentA=s.currentA,voltageV=s.voltageV,tempC=s.tempC,status=s.status,plugged=s.plugged))
            }
            lastStored = now
        }
        if (changed || now-lastCheckpoint >= 15_000) { checkpoint(s,false); lastCheckpoint=now }
        // v1.5.3：前台服务通知与超级岛合并为同一个 #1。
        // 插电并成功上岛时由 XiaomiIslandController 更新 #1；否则用普通监测内容覆盖同一个 #1。
        if (!islandShown && (changed || now-lastNotification >= 15_000)) {
            getSystemService(NotificationManager::class.java).notify(
                FOREGROUND_NOTIFICATION_ID,
                notification("${s.stateName} · ${s.level}% · ${s.powerW.display()} W（电池侧）"),
            )
            lastNotification = now
        } else if (islandShown) {
            lastNotification = now
        }
        if (changed || now-lastWidget >= 60_000) {
            BatteryWidget().updateAll(this); lastWidget=now
        }
        previous=s; previousElapsed=now
    }

    private suspend fun checkpoint(s: BatterySnapshot, finished: Boolean) {
        val start = sessionStart ?: return
        val id = db.chargeSessionDao().save(ChargeSession(id=sessionId,startTime=start.timestamp,
            endTime=s.timestamp,startLevel=start.level,endLevel=s.level,energyMah=energy.toInt(),
            peakPowerW=peak,avgPowerW=if(duration>0)(weightedPower/duration).toFloat() else 0f,
            protocol=if(finished) "已结束" else "记录至最近采样",pluggedType=start.plugged))
        if(sessionId==0L) sessionId=id
    }
    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, XiaomiIslandController.CHANNEL_ID)
        .setSmallIcon(com.batterykeeper.app.R.drawable.ic_island_battery)
        .setContentTitle("电池管家 · 监测运行中").setContentText(text)
        .setContentIntent(PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE))
        .setOngoing(true).setOnlyAlertOnce(true).build()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action==ACTION_STOP) { pendingStop=true; stopSignal.trySend(Unit) }
        return if(pendingStop) START_NOT_STICKY else START_STICKY
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
        const val ACTION_STOP="com.batterykeeper.app.STOP_MONITOR"
        fun start(context: Context) { context.startForegroundService(Intent(context,BatteryMonitorService::class.java)) }
        fun stop(context: Context) { context.startService(Intent(context,BatteryMonitorService::class.java).setAction(ACTION_STOP)) }
    }
}
fun Float.display(decimals: Int = 1): String = if(isFinite()) "%.${decimals}f".format(abs(this)) else "—"
