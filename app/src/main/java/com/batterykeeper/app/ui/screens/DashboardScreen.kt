package com.batterykeeper.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batterykeeper.app.battery.BatterySnapshot
import com.batterykeeper.app.battery.display
import com.batterykeeper.app.battery.displayMah
import com.batterykeeper.app.ui.BatteryViewModel
import com.batterykeeper.app.ui.components.CardTitle
import com.batterykeeper.app.ui.components.GlassCard
import com.batterykeeper.app.ui.components.HealthRing
import com.batterykeeper.app.ui.components.MetricCompact
import com.batterykeeper.app.ui.components.StatusDot
import com.batterykeeper.app.ui.theme.Green
import com.batterykeeper.app.ui.theme.Orange
import com.batterykeeper.app.ui.theme.TxtSecondary
import com.batterykeeper.app.ui.theme.TxtTertiary
import kotlin.math.abs

@Composable
fun DashboardScreen(vm: BatteryViewModel) {
    val s by vm.latest.collectAsState()
    val health by vm.healthPct.collectAsState()
    val reports by vm.reports.collectAsState()
    val today by vm.todayStats.collectAsState()
    val session by vm.session.collectAsState()
    val report = reports.lastOrNull { it.healthPct > 0 }
    val cycleReport = reports.lastOrNull { it.cycleCount >= 0 }
    val cycles = vm.displayCycleCount(s?.cycleCount ?: -1, cycleReport?.cycleCount)
    val todayCharged = today.chargedMah.toDouble() + (session?.energyMah ?: 0.0)
    val todaySessions = today.sessions + if (session != null) 1 else 0
    val fullCapacityMah = report?.fullChargeMah?.takeIf { it > 0 }
        ?: vm.settings.fullChargeMah.takeIf { it > 0 }
        ?: vm.settings.designCapacityMah.takeIf { it > 0 }
        ?: 0
    val etaMs = s?.let { estimateChargeRemainingMs(it, fullCapacityMah) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 690.dp
        val gap = if (compact) 6.dp else 8.dp
        val outerV = if (compact) 7.dp else 9.dp
        val cardPad = if (compact) 10.dp else 12.dp

        Column(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = outerV),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("电池管家", fontSize = if (compact) 23.sp else 25.sp, fontWeight = FontWeight.Bold)
                    Text("热爱生活·为您充电！", fontSize = 11.sp, color = TxtSecondary)
                }
                StatusDot(if (s == null) "监测未运行" else "监测中", s != null)
            }

            GlassCard(modifier = Modifier.weight(1.32f), contentPadding = cardPad) {
                CardTitle("当前电量", badge = s?.stateName ?: "等待数据")
                if (s?.isCharging == true) {
                    Text(
                        etaMs?.let { "预计充满 · ${formatRemaining(it)}" } ?: "预计充满 · 正在计算",
                        fontSize = 10.5.sp,
                        color = TxtTertiary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.weight(0.12f))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text(
                        s?.let(::levelText) ?: "—",
                        fontSize = if (compact) 51.sp else 56.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(" %", fontSize = 20.sp, color = TxtSecondary, modifier = Modifier.padding(bottom = 7.dp))
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 5.dp)) {
                        Text(
                            s?.powerW?.display(2)?.let { "$it W" } ?: "— W",
                            fontSize = if (compact) 22.sp else 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (s?.isCharging == true) Orange else Green,
                        )
                        Text("电池侧功率", fontSize = 10.5.sp, color = TxtSecondary)
                    }
                }
                Spacer(Modifier.weight(0.10f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricCompact(s?.tempC?.display() ?: "—", "℃", "温度")
                    MetricCompact(s?.voltageV?.display(2) ?: "—", "V", "电压")
                    MetricCompact(s?.currentA?.display(2) ?: "—", "A", "电流")
                }
            }

            GlassCard(modifier = Modifier.weight(0.70f), contentPadding = cardPad) {
                CardTitle("今日充电", badge = if (session != null) "进行中" else "已完成")
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MetricCompact(todayCharged.displayMah(), "mAh", "今日充入")
                    MetricCompact(todaySessions.toString(), "次", "充电次数")
                    MetricCompact(session?.peakPowerW?.display(2) ?: "—", "W", "本次峰值")
                }
                Spacer(Modifier.weight(0.7f))
            }

            GlassCard(modifier = Modifier.weight(1.08f), contentPadding = cardPad) {
                CardTitle("电池健康", badge = if (report != null) "报告值" else if (health != null) "满充估算" else "暂无数据")
                Spacer(Modifier.weight(0.20f))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(if (compact) 68.dp else 74.dp), contentAlignment = Alignment.Center) {
                        HealthRing((health ?: 0f) / 100, Modifier.fillMaxSize())
                        Text(health?.let { "%.1f%%".format(it) } ?: "—", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        val capacity = report?.fullChargeMah?.takeIf { it > 0 }
                            ?: vm.settings.fullChargeMah.takeIf { it > 0 }
                        Text(capacity?.let { "${it.displayMah()} mAh" } ?: "等待容量数据", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Text("满充容量", fontSize = 10.5.sp, color = TxtSecondary)
                        val timestamp = report?.timestamp ?: vm.settings.fullChargeTime
                        if (timestamp > 0) {
                            Text(
                                BatteryViewModel.fmtDate(timestamp),
                                fontSize = 10.sp,
                                color = TxtTertiary,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(0.18f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MetricCompact(
                        (report?.designMah?.takeIf { it > 0 } ?: vm.settings.designCapacityMah).displayMah(),
                        "mAh",
                        "设计容量",
                    )
                    MetricCompact(cycles.takeIf { it >= 0 }?.toString() ?: "—", "次", "循环次数")
                }
            }
        }
    }
}

/** 只有系统真正提供了小数精度时才显示两位；scale=100 时继续显示整数，避免伪精度。 */
private fun levelText(snapshot: BatterySnapshot): String {
    if (snapshot.level < 0) return "—"
    val exact = snapshot.levelExact
    return if (exact.isFinite() && abs(exact - snapshot.level.toFloat()) >= 0.005f) {
        "%.2f".format(exact)
    } else {
        snapshot.level.toString()
    }
}

/** 优先使用 Android 系统 ETA；系统不提供时，用当前电流和容量做保守估算。 */
private fun estimateChargeRemainingMs(snapshot: BatterySnapshot, fullCapacityMah: Int): Long? {
    if (!snapshot.isCharging || snapshot.level >= 100) return null
    snapshot.chargeTimeRemainingMs.takeIf { it in 60_000L..24L * 60 * 60_000L }?.let { return it }

    val currentMa = snapshot.currentA.takeIf { it.isFinite() && it >= 0.05f }?.times(1000f) ?: return null
    val levelPct = snapshot.levelExact.takeIf { it.isFinite() && it in 0f..100f }
        ?: snapshot.level.toFloat().takeIf { it in 0f..100f }
        ?: return null
    val remainingMah = fullCapacityMah * (100f - levelPct) / 100f
    if (remainingMah <= 0f) return null
    // 充电末段会降流，加入 12% 缓冲，避免瞬时大电流把 ETA 估得过于乐观。
    val estimate = (remainingMah / currentMa * 3_600_000L * 1.12).toLong()
    return estimate.takeIf { it in 60_000L..24L * 60 * 60_000L }
}

private fun formatRemaining(ms: Long): String {
    val totalMinutes = (ms / 60_000L).coerceAtLeast(1L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours <= 0 -> "约 ${minutes} 分钟"
        minutes == 0L -> "约 ${hours} 小时"
        else -> "约 ${hours} 小时 ${minutes} 分"
    }
}
