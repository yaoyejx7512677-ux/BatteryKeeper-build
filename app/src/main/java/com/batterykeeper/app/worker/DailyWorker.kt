package com.batterykeeper.app.worker

import android.content.Context
import androidx.work.*
import com.batterykeeper.app.battery.*
import com.batterykeeper.app.data.*
import com.batterykeeper.app.settings.AppSettings
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class DailyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = try {
        val db=BatteryDatabase.get(applicationContext)
        val settings=AppSettings(applicationContext)
        val today=LocalDate.now()
        val zone=ZoneId.systemDefault()
        val snapshot=BatterySampler.sample(applicationContext)
        // Never mix report/system totals with an installation-relative estimate.
        if(snapshot.cycleCount>=0 && db.cycleRecordDao().byDate(today.toString())==null) {
            db.cycleRecordDao().insert(CycleRecord(date=today.toString(),cycleCount=snapshot.cycleCount,
                estimatedCapacityMah=settings.fullChargeMah))
        }
        val first=db.sampleDao().firstTime()
        if(first!=null) {
            val firstDate=java.time.Instant.ofEpochMilli(first).atZone(zone).toLocalDate()
            var day=maxOf(firstDate,today.minusDays(settings.retentionDays.toLong()))
            while(day<today) {
                run {
                    val from=day.atStartOfDay(zone).toInstant().toEpochMilli()
                    val to=day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    val points=db.sampleDao().between(from-120000,to+120000).map {
                        HistoryMath.Point(it.timestamp,it.currentA.toDouble(),it.powerW.toDouble(),it.tempC.toDouble()) }
                    val summary=HistoryMath.summarize(points,from,to)
                    if(summary.coverageMs>0) db.dailyStatsDao().save(DailyStats(day.toString(),
                        db.cycleRecordDao().byDate(day.toString())?.cycleCount ?: -1,
                        summary.chargedMah.toInt(),summary.drainedMah.toInt(),
                        db.chargeSessionDao().since(from).count { it.startTime < to },
                        summary.avgTempC?.toFloat() ?: 0f))
                }
                day=day.plusDays(1)
            }
        }
        db.sampleDao().deleteBefore(today.minusDays(settings.retentionDays.toLong()).atStartOfDay(zone).toInstant().toEpochMilli())
        Result.success()
    } catch(e: kotlinx.coroutines.CancellationException) { throw e }
      catch(e: Exception) { android.util.Log.e("BatteryKeeper","Daily summary failed",e); Result.retry() }
    companion object {
        fun dateStr(ms: Long)=java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        fun schedule(context: Context) {
            val request=PeriodicWorkRequestBuilder<DailyWorker>(24,TimeUnit.HOURS)
                .setInitialDelay(15,TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("daily-maintenance",ExistingPeriodicWorkPolicy.UPDATE,request)
        }
    }
}
