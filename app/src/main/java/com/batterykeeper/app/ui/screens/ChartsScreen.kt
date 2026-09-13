package com.batterykeeper.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batterykeeper.app.battery.HistoryMath
import com.batterykeeper.app.data.Sample
import com.batterykeeper.app.ui.BatteryViewModel
import com.batterykeeper.app.ui.components.CardTitle
import com.batterykeeper.app.ui.components.DualLineChart
import com.batterykeeper.app.ui.components.GlassCard
import com.batterykeeper.app.ui.components.MetricCompact
import com.batterykeeper.app.ui.components.SegmentedControl
import com.batterykeeper.app.ui.theme.Blue
import com.batterykeeper.app.ui.theme.Green
import com.batterykeeper.app.ui.theme.Orange
import com.batterykeeper.app.ui.theme.TxtSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

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
                    listOf(
                        chunk.first(),
                        chunk.minBy { it.powerW },
                        chunk.maxBy { it.powerW },
                        chunk.minBy { it.tempC },
                        chunk.maxBy { it.tempC },
                        chunk.last(),
                    ).distinctBy { it.timestamp }.sortedBy { it.timestamp }
                }
                withContext(Dispatchers.Main) {
                    samples = reduced
                    count = visible.size
                    summary = result
                    loading = false
                }
            }
            delay(30000)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("用电趋势", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("$count 条", fontSize = 11.sp, color = TxtSecondary)
        }
        SegmentedControl(listOf("今日", "近7天", "近30天"), range) { range = it }

        GlassCard(modifier = Modifier.weight(1.55f), contentPadding = 10.dp) {
            CardTitle("电量与功率")
            Spacer(Modifier.weight(0.08f))
            if (samples.size < 2) {
                Text(if (loading) "正在读取…" else "暂无足够记录", color = TxtSecondary)
                Spacer(Modifier.weight(1f))
            } else {
                DualLineChart(
                    samples.map { Triple(it.timestamp, it.level.toFloat(), it.powerW) },
                    Blue,
                    Orange,
                    Modifier.fillMaxWidth().weight(1f),
                    gridLines = listOf(25f, 50f, 75f),
                    gridLabels = listOf("25", "50", "75"),
                    highlightCharging = true,
                )
                TimeLabels(samples.first().timestamp, samples.last().timestamp)
            }
        }

        GlassCard(modifier = Modifier.weight(1.0f), contentPadding = 10.dp) {
            CardTitle("电池温度 · ℃")
            Spacer(Modifier.weight(0.06f))
            if (samples.size >= 2) {
                val low = (samples.minOf { it.tempC } - 3).coerceAtMost(20f)
                val high = (samples.maxOf { it.tempC } + 3).coerceAtLeast(45f)
                DualLineChart(
                    samples.map { Triple(it.timestamp, it.tempC, 0f) },
                    Green,
                    Color.Transparent,
                    Modifier.fillMaxWidth().weight(1f),
                    mainMin = low,
                    mainMax = high,
                    gridLines = listOf(low, (low + high) / 2, high),
                    gridLabels = listOf("", "", ""),
                )
                TimeLabels(samples.first().timestamp, samples.last().timestamp)
            } else {
                Text("暂无温度记录", color = TxtSecondary)
                Spacer(Modifier.weight(1f))
            }
        }

        GlassCard(modifier = Modifier.weight(0.43f), contentPadding = 9.dp) {
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MetricCompact(summary?.avgDrainW?.let { "%.1f".format(it) } ?: "—", "W", "平均放电")
                MetricCompact(summary?.drainShare?.let { "%.0f".format(it) } ?: "—", "%", "放电占比")
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun TimeLabels(start: Long, end: Long) {
    val format = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
    Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(format.format(java.util.Date(start)), fontSize = 9.5.sp, color = TxtSecondary)
        Text(format.format(java.util.Date(end)), fontSize = 9.5.sp, color = TxtSecondary)
    }
}
