package com.batterykeeper.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batterykeeper.app.battery.HistoryMath
import com.batterykeeper.app.data.Sample
import com.batterykeeper.app.ui.BatteryViewModel
import com.batterykeeper.app.ui.components.*
import com.batterykeeper.app.ui.theme.*
import kotlinx.coroutines.*
import java.time.*

@Composable
fun ChartsScreen(vm: BatteryViewModel) {
    var range by remember { mutableIntStateOf(0) }
    var samples by remember { mutableStateOf<List<Sample>>(emptyList()) }
    var summary by remember { mutableStateOf<HistoryMath.Summary?>(null) }
    var count by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(range) {
        loading=true
        while(isActive) {
            val to=System.currentTimeMillis()
            val from=LocalDate.now().minusDays(listOf(0L,6L,29L)[range])
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            withContext(Dispatchers.Default) {
                val raw=vm.samplesBetween(from-120000,to)
                val result=HistoryMath.summarize(raw.map {
                    HistoryMath.Point(it.timestamp,it.currentA.toDouble(),it.powerW.toDouble(),it.tempC.toDouble()) },from,to)
                // Keep boundaries and power/temperature extrema in each bucket.
                val visible=raw.filter { it.timestamp >= from }
                val reduced=visible.chunked((visible.size/100).coerceAtLeast(1)).flatMap { chunk ->
                    listOf(chunk.first(),chunk.minBy { it.powerW },chunk.maxBy { it.powerW },
                        chunk.minBy { it.tempC },chunk.maxBy { it.tempC },chunk.last()).distinctBy { it.timestamp }.sortedBy { it.timestamp }
                }
                withContext(Dispatchers.Main) { samples=reduced; count=visible.size; summary=result; loading=false }
            }
            delay(30000)
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("用电趋势",fontSize=26.sp,fontWeight=FontWeight.Bold)
        Text("按实际记录时长统计 · 中断时段不推算",fontSize=13.sp,color=TxtSecondary)
        SegmentedControl(listOf("今日","近7天","近30天"),range) { range=it }
        GlassCard {
            CardTitle("电量与电池侧功率",badge="$count 条记录")
            Spacer(Modifier.height(16.dp))
            if(samples.size<2) Text(if(loading) "正在读取…" else "尚无足够记录，开启监测后自动积累。",color=TxtSecondary)
            else {
                Text("蓝色：电量 %   橙色：功率 W",fontSize=12.sp,color=TxtSecondary)
                DualLineChart(samples.map { Triple(it.timestamp,it.level.toFloat(),it.powerW) },Blue,Orange,
                    Modifier.height(200.dp),gridLines=listOf(25f,50f,75f),gridLabels=listOf("25","50","75"),highlightCharging=true)
                TimeLabels(samples.first().timestamp,samples.last().timestamp)
            }
        }
        GlassCard {
            CardTitle("电池温度 · ℃")
            Spacer(Modifier.height(12.dp))
            if(samples.size>=2) {
                val low=(samples.minOf { it.tempC }-3).coerceAtMost(20f)
                val high=(samples.maxOf { it.tempC }+3).coerceAtLeast(45f)
                DualLineChart(samples.map { Triple(it.timestamp,it.tempC,0f) },Green,androidx.compose.ui.graphics.Color.Transparent,
                    Modifier.height(140.dp),mainMin=low,mainMax=high,gridLines=listOf(low,(low+high)/2,high),gridLabels=listOf("","",""))
                TimeLabels(samples.first().timestamp,samples.last().timestamp)
            } else Text("暂无温度记录",color=TxtSecondary)
        }
        GlassCard {
            CardTitle("区间摘要 · 已观测时段")
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                MetricCompact(summary?.avgDrainW?.let { "%.1f".format(it) } ?: "—","W","平均放电")
                MetricCompact(summary?.drainShare?.let { "%.0f".format(it) } ?: "—","%","放电时长占比")
            }
            Spacer(Modifier.height(12.dp))
            Text("有效记录 %.1f 小时；均值按时长加权，采样空缺不计入。".format((summary?.coverageMs ?: 0)/3600000.0),fontSize=12.sp,color=TxtSecondary)
        }
    }
}
@Composable
private fun TimeLabels(start: Long,end: Long) {
    val format=java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.CHINA)
    Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
        Text(format.format(java.util.Date(start)),fontSize=11.sp,color=TxtSecondary)
        Text(format.format(java.util.Date(end)),fontSize=11.sp,color=TxtSecondary)
    }
}
