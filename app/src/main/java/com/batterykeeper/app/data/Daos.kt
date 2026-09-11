package com.batterykeeper.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SampleDao {
    @Query("SELECT * FROM samples WHERE id > :afterId ORDER BY id LIMIT 2000")
    suspend fun page(afterId: Long): List<Sample>

    @Insert
    suspend fun insert(sample: Sample)

    @Insert
    suspend fun insertAll(samples: List<Sample>)

    @Query("SELECT * FROM samples WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    suspend fun between(from: Long, to: Long): List<Sample>

    @Query("SELECT MIN(timestamp) FROM samples")
    suspend fun firstTime(): Long?

    @Query("SELECT COUNT(*) FROM samples WHERE timestamp >= :since")
    suspend fun countSince(since: Long): Int

    @Query("SELECT AVG(tempC) FROM samples WHERE timestamp >= :since")
    suspend fun avgTempSince(since: Long): Float?

    @Query("DELETE FROM samples WHERE timestamp < :before")
    suspend fun deleteBefore(before: Long)

    @Query("SELECT COUNT(*) FROM samples")
    suspend fun count(): Long
}

@Dao
interface CycleRecordDao {
    @Insert
    suspend fun insert(record: CycleRecord)

    @Query("SELECT * FROM cycle_records ORDER BY date ASC")
    fun all(): Flow<List<CycleRecord>>

    @Query("SELECT * FROM cycle_records WHERE date = :date LIMIT 1")
    suspend fun byDate(date: String): CycleRecord?

    @Query("SELECT * FROM cycle_records ORDER BY date ASC LIMIT 1")
    suspend fun first(): CycleRecord?

    @Query("SELECT COUNT(*) FROM cycle_records")
    suspend fun count(): Long
}

@Dao
interface ChargeSessionDao {
    @androidx.room.Upsert
    suspend fun save(session: ChargeSession): Long

    @Query("SELECT * FROM charge_sessions WHERE startTime >= :since ORDER BY startTime DESC")
    fun observeSince(since: Long): Flow<List<ChargeSession>>

    @Insert
    suspend fun insert(session: ChargeSession)

    @Query("SELECT * FROM charge_sessions WHERE startTime >= :since ORDER BY startTime DESC")
    suspend fun since(since: Long): List<ChargeSession>

    @Query("SELECT * FROM charge_sessions ORDER BY startTime DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<ChargeSession>

    @Query("SELECT COUNT(*) FROM charge_sessions WHERE startTime >= :since")
    suspend fun countSince(since: Long): Int

    @Query("SELECT COALESCE(SUM(energyMah),0) FROM charge_sessions WHERE startTime >= :since")
    suspend fun energySince(since: Long): Long
}

@Dao
interface DailyStatsDao {
    @androidx.room.Upsert
    suspend fun save(stats: DailyStats)

    @Insert
    suspend fun insert(stats: DailyStats)

    @Query("SELECT * FROM daily_stats ORDER BY date ASC")
    fun all(): Flow<List<DailyStats>>

    @Query("SELECT * FROM daily_stats WHERE date = :date LIMIT 1")
    suspend fun byDate(date: String): DailyStats?
}

@Dao
interface HealthReportDao {
    @Query("DELETE FROM health_reports WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insert(report: HealthReport)

    @Query("SELECT * FROM health_reports ORDER BY timestamp ASC")
    fun all(): Flow<List<HealthReport>>

    @Query("SELECT * FROM health_reports ORDER BY timestamp DESC LIMIT 1")
    suspend fun latest(): HealthReport?

    @Query("SELECT COUNT(*) FROM health_reports")
    suspend fun count(): Long
}
