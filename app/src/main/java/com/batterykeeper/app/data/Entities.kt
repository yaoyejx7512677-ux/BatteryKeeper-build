package com.batterykeeper.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 原始采样 */
@Entity(tableName = "samples")
data class Sample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,      // epoch ms
    val level: Int,           // 电量 %
    val powerW: Float,        // 功率 W（充+/放-）
    val currentA: Float,      // 电流 A
    val voltageV: Float,      // 电压 V
    val tempC: Float,         // 温度 ℃
    val status: Int,          // charging / discharging / full / not-charging
    val plugged: Int,         // 供电方式
)

/** 循环次数每日快照 */
@Entity(tableName = "cycle_records")
data class CycleRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,                // yyyy-MM-dd
    val cycleCount: Int,             // 系统报告的总循环数
    val estimatedCapacityMah: Int,  // 满充估算容量
)

/** 充电会话 */
@Entity(tableName = "charge_sessions")
data class ChargeSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long?,
    val startLevel: Int,
    val endLevel: Int?,
    val energyMah: Int,       // 累计充入
    val peakPowerW: Float,
    val avgPowerW: Float,
    val protocol: String,     // 协议识别结果
    val pluggedType: Int,     // AC / USB / 无线
)

/** 日结统计 */
@Entity(tableName = "daily_stats")
data class DailyStats(
    @PrimaryKey val date: String,   // yyyy-MM-dd
    val cycleCountEnd: Int,
    val chargedMah: Int,
    val drainedMah: Int,
    val chargeSessions: Int,
    val avgTempC: Float,
)

/** 澎湃OS 电池检测报告（Bug报告 ZIP / 截图识别导入） */
@Entity(tableName = "health_reports")
data class HealthReport(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,        // 报告生成时间（无法解析时用导入时间）
    val source: String,         // bugreport / screenshot
    val fullChargeMah: Int,     // 满充容量 mAh（-1 未知）
    val designMah: Int,         // 设计容量 mAh（-1 未知）
    val cycleCount: Int,        // 循环次数（-1 未知）
    val healthPct: Float,       // 健康度 %（-1 未知）
    val estimatedMah: Int,      // 估算满充容量（-1 未知）
    val learnedMinMah: Int,     // 最小学习容量（-1 未知）
    val learnedMaxMah: Int,     // 最大学习容量（-1 未知）
    val learnedLastMah: Int,    // 上次学习容量（-1 未知）
)
