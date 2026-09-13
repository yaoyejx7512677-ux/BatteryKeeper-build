package com.batterykeeper.app.ui.screens

import androidx.compose.foundation.layout.*
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
        loading = true
        while (isActive) {
            val to = System.currentTimeMillis()
            val from = LocalDate.now().minusDays(listOf(0L, 6L, 29L)[range])
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            withContext(Dispatchers.Default) {
                val raw = vm.samplesBetween(from - 120000, to)
                val result = HistoryMath.summarize(raw.map {
                    HistoryMath.Point(it.timestamp, it.currentA.toDouble(), it.powerW.toDouble(), it.tempC.toDouble())
                }, from, to)
                val visible = raw.filter { it.timestamp >= from }
                val reduced = visible.chunked((visible.size / 100).coerceAtLeast(1)).flatMap { chunk ->
                    listOf(chunk.first(), chunk.minBy { it.powerW }, chunk.maxBy { it.powerW },
                        chunk.minBy { it.tempC }, chunk.maxBy { it.tempC }, chunk.last())
                        .distinctBy { it.timestamp }.sortedBy { it.timestamp }
                }
                withContext(Dispatchers.Main) { samples = reduced; count = visible.size; summary = result; loading = false }
            }
            delay(30000)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("用电趋势", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("$count 条", fontSize = 11.sp, color = TxtSecondary)
        }
        SegmentedControl(listOf("今日", "近7天", "近30天"), range) { range = it }

        GlassCard(contentPadding = 11.dp) {
            CardTitle("电量与功率")
            Spacer(Modifier.height(6.dp))
            if (samples.size < 2) Text(if (loading) "正在读取…" else "暂无足够记录", color = TxtSecondary)
            else {
                DualLineChart(
                    samples.map { Triple(it.timestamp, it.level.toFloat(), it.powerW) }, Blue, Orange,
                    Modifier.height(180.dp), gridLines = listOf(25f, 50f, 75f), gridLabels = listOf("25", "50", "75"), highlightCharging = true,
                )
                TimeLabels(samples.first().timestamp, samples.last().timestamp)
            }
        }

        GlassCard(contentPadding = 11.dp) {
            CardTitle("电池温度 · ℃")
            Spacer(Modifier.height(4.dp))
            if (samples.size >= 2) {
                val low = (samples.minOf { it.tempC } - 3).coerceAtMost(20f)
                val high = (samples.maxOf { it.tempC } + 3).coerceAtLeast(45f)
                DualLineChart(samples.map { Triple(it.timestamp, it.tempC, 0f) }, Green, androidx.compose.ui.graphics.Color.Transparent,
                    Modifier.height(110.dp), mainMin = low, mainMax = high, gridLines = listOf(low, (low + high) / 2, high), gridLabels = listOf("", "", ""))
                TimeLabels(samples.first().timestamp, samples.last().timestamp)
            } else Text("暂无温度记录", color = TxtSecondary)
        }

        GlassCard(contentPadding = 11.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MetricCompact(summary?.avgDrainW?.let { "%.1f".format(it) } ?: "—", "W", "平均放电")
                MetricCompact(summary?.drainShare?.let { "%.0f".format(it) } ?: "—", "%", "放电占比")
            }
        }
    }
}

@Composable
private fun TimeLabels(start: Long, end: Long) {
    val format = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(format.format(java.util.Date(start)), fontSize = 9.5.sp, color = TxtSecondary)
        Text(format.format(java.util.Date(end)), fontSize = 9.5.sp, color = TxtSecondary)
    }
}
