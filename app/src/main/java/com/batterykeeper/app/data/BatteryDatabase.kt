package com.batterykeeper.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Sample::class, CycleRecord::class, ChargeSession::class, DailyStats::class, HealthReport::class],
    version = 2,
    exportSchema = false,
)
abstract class BatteryDatabase : RoomDatabase() {
    abstract fun sampleDao(): SampleDao
    abstract fun cycleRecordDao(): CycleRecordDao
    abstract fun chargeSessionDao(): ChargeSessionDao
    abstract fun dailyStatsDao(): DailyStatsDao
    abstract fun healthReportDao(): HealthReportDao

    companion object {
        @Volatile private var instance: BatteryDatabase? = null

        /** v1 → v2：新增 health_reports 表 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `health_reports` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `fullChargeMah` INTEGER NOT NULL,
                        `designMah` INTEGER NOT NULL,
                        `cycleCount` INTEGER NOT NULL,
                        `healthPct` REAL NOT NULL,
                        `estimatedMah` INTEGER NOT NULL,
                        `learnedMinMah` INTEGER NOT NULL,
                        `learnedMaxMah` INTEGER NOT NULL,
                        `learnedLastMah` INTEGER NOT NULL
                    )"""
                )
            }
        }

        fun get(context: Context): BatteryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BatteryDatabase::class.java,
                    "battery.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
