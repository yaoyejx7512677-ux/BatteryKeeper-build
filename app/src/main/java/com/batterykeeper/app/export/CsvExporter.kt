package com.batterykeeper.app.export
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.batterykeeper.app.data.BatteryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.Locale

object CsvExporter {
 suspend fun export(context: Context): Uri = withContext(Dispatchers.IO) {
   val db=BatteryDatabase.get(context)
   val dir=File(context.cacheDir,"exports").apply { mkdirs() }
   val file=File(dir,"battery_export_${System.currentTimeMillis()}.csv")
   val fmt=java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.CHINA)
   file.bufferedWriter(Charsets.UTF_8).use { out ->
     out.write("\uFEFF类型,时间,电量%,功率W,电流A,电压V,温度C,状态,供电方式,循环次数,充入mAh,放出mAh,充电次数\n")
     var cursor=0L
     while(true) {
       val page=db.sampleDao().page(cursor)
       if(page.isEmpty()) break
       page.forEach { s ->
         out.write("采样,${fmt.format(java.util.Date(s.timestamp))},${s.level},${"%.2f".format(Locale.US, s.powerW)},${s.currentA},${s.voltageV},${s.tempC},${s.status},${s.plugged},,,,\n")
       }
       cursor=page.last().id
     }
     db.dailyStatsDao().all().first().forEach { d ->
       out.write("日统计,${d.date},,,,,${d.avgTempC},,,${d.cycleCountEnd.takeIf { it>=0 } ?: ""},${"%.1f".format(Locale.US, d.chargedMah.toDouble())},${"%.1f".format(Locale.US, d.drainedMah.toDouble())},${d.chargeSessions}\n")
     }
   }
   FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file)
 }
 fun shareIntent(context: Context,uri: Uri)=Intent(Intent.ACTION_SEND).apply {
   type="text/csv"; putExtra(Intent.EXTRA_STREAM,uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
 }
}
