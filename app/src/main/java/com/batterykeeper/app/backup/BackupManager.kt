package com.batterykeeper.app.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import com.batterykeeper.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupManager {
    private const val BACKUP_ENTRY = "batterykeeper-backup.json"
    private const val BACKUP_VERSION = 1

    suspend fun exportToUri(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val json = buildBackupJson(context).toString()
        context.contentResolver.openOutputStream(uri, "w")?.use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(BACKUP_ENTRY))
                zip.write(json.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        } ?: error("无法写入备份文件")
    }

    suspend fun exportToCache(context: Context): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "BatteryKeeper-backup-${System.currentTimeMillis()}.bkbak")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(BACKUP_ENTRY))
            zip.write(buildBackupJson(context).toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    suspend fun restoreFromUri(context: Context, uri: Uri): RestoreResult = withContext(Dispatchers.IO) {
        val raw = context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == BACKUP_ENTRY) return@use zip.readBytes().toString(Charsets.UTF_8)
                    entry = zip.nextEntry
                }
                null
            }
        } ?: error("无法读取备份文件")
        val root = JSONObject(raw)
        require(root.optInt("backupVersion") == BACKUP_VERSION) { "不支持的备份版本" }
        val db = BatteryDatabase.get(context)
        db.withTransactionCompat {
            db.sampleDao().clearAll(); db.cycleRecordDao().clearAll(); db.chargeSessionDao().clearAll(); db.dailyStatsDao().clearAll(); db.healthReportDao().clearAll()
            root.getJSONArray("samples").forEachObject { o -> db.sampleDao().insert(Sample(o.optLong("id"), o.getLong("timestamp"), o.getInt("level"), o.getDouble("powerW").toFloat(), o.getDouble("currentA").toFloat(), o.getDouble("voltageV").toFloat(), o.getDouble("tempC").toFloat(), o.getInt("status"), o.getInt("plugged"))) }
            root.getJSONArray("cycles").forEachObject { o -> db.cycleRecordDao().insert(CycleRecord(o.optLong("id"), o.getString("date"), o.getInt("cycleCount"), o.getInt("estimatedCapacityMah"))) }
            root.getJSONArray("sessions").forEachObject { o -> db.chargeSessionDao().insert(ChargeSession(o.optLong("id"), o.getLong("startTime"), o.optNullableLong("endTime"), o.getInt("startLevel"), o.optNullableInt("endLevel"), o.getInt("energyMah"), o.getDouble("peakPowerW").toFloat(), o.getDouble("avgPowerW").toFloat(), o.getString("protocol"), o.getInt("pluggedType"))) }
            root.getJSONArray("daily").forEachObject { o -> db.dailyStatsDao().insert(DailyStats(o.getString("date"), o.getInt("cycleCountEnd"), o.getInt("chargedMah"), o.getInt("drainedMah"), o.getInt("chargeSessions"), o.getDouble("avgTempC").toFloat())) }
            root.getJSONArray("reports").forEachObject { o -> db.healthReportDao().insert(HealthReport(o.optLong("id"), o.getLong("timestamp"), o.getString("source"), o.getInt("fullChargeMah"), o.getInt("designMah"), o.getInt("cycleCount"), o.getDouble("healthPct").toFloat(), o.getInt("estimatedMah"), o.getInt("learnedMinMah"), o.getInt("learnedMaxMah"), o.getInt("learnedLastMah"))) }
        }
        restoreSettings(context, root.getJSONObject("settings"))
        RestoreResult(
            samples = root.getJSONArray("samples").length(),
            sessions = root.getJSONArray("sessions").length(),
            reports = root.getJSONArray("reports").length(),
        )
    }

    fun emailIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_EMAIL, arrayOf("Ben0102@qq.com"))
        putExtra(Intent.EXTRA_SUBJECT, "BatteryKeeper 数据备份")
        putExtra(Intent.EXTRA_TEXT, "BatteryKeeper 完整数据备份，请妥善保存。")
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private suspend fun buildBackupJson(context: Context): JSONObject {
        val db = BatteryDatabase.get(context)
        return JSONObject().apply {
            put("backupVersion", BACKUP_VERSION)
            put("createdAt", System.currentTimeMillis())
            put("settings", settingsJson(context))
            put("samples", JSONArray().apply { db.sampleDao().snapshot().forEach { s -> put(JSONObject().apply { put("id", s.id); put("timestamp", s.timestamp); put("level", s.level); put("powerW", s.powerW); put("currentA", s.currentA); put("voltageV", s.voltageV); put("tempC", s.tempC); put("status", s.status); put("plugged", s.plugged) }) } })
            put("cycles", JSONArray().apply { db.cycleRecordDao().snapshot().forEach { r -> put(JSONObject().apply { put("id", r.id); put("date", r.date); put("cycleCount", r.cycleCount); put("estimatedCapacityMah", r.estimatedCapacityMah) }) } })
            put("sessions", JSONArray().apply { db.chargeSessionDao().snapshot().forEach { r -> put(JSONObject().apply { put("id", r.id); put("startTime", r.startTime); putNullable("endTime", r.endTime); put("startLevel", r.startLevel); putNullable("endLevel", r.endLevel); put("energyMah", r.energyMah); put("peakPowerW", r.peakPowerW); put("avgPowerW", r.avgPowerW); put("protocol", r.protocol); put("pluggedType", r.pluggedType) }) } })
            put("daily", JSONArray().apply { db.dailyStatsDao().snapshot().forEach { r -> put(JSONObject().apply { put("date", r.date); put("cycleCountEnd", r.cycleCountEnd); put("chargedMah", r.chargedMah); put("drainedMah", r.drainedMah); put("chargeSessions", r.chargeSessions); put("avgTempC", r.avgTempC) }) } })
            put("reports", JSONArray().apply { db.healthReportDao().snapshot().forEach { r -> put(JSONObject().apply { put("id", r.id); put("timestamp", r.timestamp); put("source", r.source); put("fullChargeMah", r.fullChargeMah); put("designMah", r.designMah); put("cycleCount", r.cycleCount); put("healthPct", r.healthPct); put("estimatedMah", r.estimatedMah); put("learnedMinMah", r.learnedMinMah); put("learnedMaxMah", r.learnedMaxMah); put("learnedLastMah", r.learnedLastMah) }) } })
        }
    }

    private fun settingsJson(context: Context): JSONObject {
        val all = context.getSharedPreferences("settings", Context.MODE_PRIVATE).all
        return JSONObject().apply { all.forEach { (k, v) -> when (v) { is Boolean, is Int, is Long, is Float, is String -> put(k, v) } } }
    }

    private fun restoreSettings(context: Context, json: JSONObject) {
        val editor = context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear()
        json.keys().forEach { k -> when (val v = json.get(k)) {
            is Boolean -> editor.putBoolean(k, v)
            is Int -> editor.putInt(k, v)
            is Long -> editor.putLong(k, v)
            is Double -> editor.putFloat(k, v.toFloat())
            is String -> editor.putString(k, v)
        } }
        editor.apply()
    }

    data class RestoreResult(val samples: Int, val sessions: Int, val reports: Int)

    private suspend inline fun JSONArray.forEachObject(crossinline block: suspend (JSONObject) -> Unit) { for (i in 0 until length()) block(getJSONObject(i)) }
    private fun JSONObject.putNullable(key: String, value: Any?) { put(key, value ?: JSONObject.NULL) }
    private fun JSONObject.optNullableLong(key: String): Long? = if (isNull(key)) null else getLong(key)
    private fun JSONObject.optNullableInt(key: String): Int? = if (isNull(key)) null else getInt(key)
}

private suspend inline fun BatteryDatabase.withTransactionCompat(crossinline block: suspend () -> Unit) {
    this.withTransaction { block() }
}
