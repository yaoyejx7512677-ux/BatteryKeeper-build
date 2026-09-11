package com.batterykeeper.app.battery

object HistoryMath {
    data class Point(val time: Long, val currentA: Double, val powerW: Double, val tempC: Double)
    data class Summary(val chargedMah: Double, val drainedMah: Double, val avgTempC: Double?,
        val avgDrainW: Double?, val drainShare: Double?, val coverageMs: Long)
    fun summarize(points: List<Point>, from: Long, to: Long): Summary {
        var charged = 0.0; var drained = 0.0; var temp = 0.0
        var drainEnergy = 0.0; var drainTime = 0L; var coverage = 0L
        points.zipWithNext().forEach { (a,b) ->
            val full = MeasurementMath.durationMs(a.time,b.time)
            val dt = MeasurementMath.overlapMs(a.time,b.time,from,to)
            if(full==0L || dt==0L || !a.powerW.isFinite() || !b.powerW.isFinite()) return@forEach
            // State transitions are ambiguous between observations: exclude them.
            if ((a.powerW < 0) != (b.powerW < 0)) return@forEach
            val l=(maxOf(a.time,from)-a.time).toDouble()/full
            val r=(minOf(b.time,to)-a.time).toDouble()/full
            fun avg(x: Double,y: Double) = x+(y-x)*(l+r)/2
            val mah=avg(a.currentA,b.currentA)*dt/3600.0
            if(a.powerW<0 && b.powerW<0) {
                drained += mah; drainTime += dt; drainEnergy += -avg(a.powerW,b.powerW)*dt
            } else charged += mah
            temp += avg(a.tempC,b.tempC)*dt; coverage += dt
        }
        return Summary(charged,drained,if(coverage>0)temp/coverage else null,
            if(drainTime>0)drainEnergy/drainTime else null,
            if(coverage>0)drainTime*100.0/coverage else null,coverage)
    }
}
