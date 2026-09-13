package com.batterykeeper.app.battery

/** Pure calculations. Gaps longer than two minutes are unknown, never extrapolated. */
object MeasurementMath {
    fun durationMs(previous: Long, current: Long, maxGapMs: Long = 120_000L): Long =
        (current - previous).takeIf { it in 1..maxGapMs } ?: 0L
    fun chargeMah(previousA: Double, currentA: Double, durationMs: Long): Double =
        (previousA + currentA) / 2.0 * durationMs / 3600.0
    fun xFraction(time: Long, start: Long, end: Long): Float =
        if (end <= start) 0f else ((time-start).toDouble() / (end-start)).toFloat()
    fun overlapMs(start: Long, end: Long, from: Long, to: Long): Long =
        (minOf(end,to)-maxOf(start,from)).coerceAtLeast(0)
}

/** mAh 统一展示 1 位小数；数据库仍保持原有整数 schema，避免升级迁移风险。 */
fun Number.displayMah(): String = "%.1f".format(toDouble())
