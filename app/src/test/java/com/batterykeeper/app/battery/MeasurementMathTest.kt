package com.batterykeeper.app.battery
import org.junit.Assert.*
import org.junit.Test
class MeasurementMathTest {
 @Test fun epochTimestampsPreserveSeconds() {
   val t=1789160000000L
   assertEquals(0.5f,MeasurementMath.xFraction(t+1000,t,t+2000),0.00001f)
 }
 @Test fun rejectsSleepGapsAndBackwardsClock() {
   assertEquals(0L,MeasurementMath.durationMs(1000,500))
   assertEquals(0L,MeasurementMath.durationMs(0,120001))
 }
 @Test fun fractionalDischargeIsNotTruncated() {
   val amount=(1..100).sumOf { MeasurementMath.chargeMah(0.18,0.18,10000) }
   assertEquals(50.0,amount,0.000001)
 }
 @Test fun dailyBoundarySplitsCharge() {
   val points=listOf(HistoryMath.Point(0,1.0,4.0,30.0),HistoryMath.Point(60000,1.0,4.0,30.0))
   assertEquals(8.333333,HistoryMath.summarize(points,30000,90000).chargedMah,0.00001)
 }
 @Test fun timeWeightingAndMissingIntervals() {
   val p=listOf(HistoryMath.Point(0,1.0,-2.0,30.0),HistoryMath.Point(10000,1.0,-2.0,30.0),
     HistoryMath.Point(70000,1.0,-8.0,30.0),HistoryMath.Point(300000,10.0,-40.0,40.0))
   val result=HistoryMath.summarize(p,0,400000)
   assertEquals(70000L,result.coverageMs)
   assertEquals(320000.0/70000,result.avgDrainW!!,0.00001)
 }
 @Test fun emptyDataIsUnknown() { assertNull(HistoryMath.summarize(emptyList(),0,1000).avgTempC) }
}
