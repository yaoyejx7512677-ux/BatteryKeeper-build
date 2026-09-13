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
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batterykeeper.app.battery.display
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
    val todayCharged = today.chargedMah + (session?.energyMah ?: 0)
    val todaySessions = today.sessions + if (session != null) 1 else 0

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 690.dp
        val gap = if (compact) 6.dp else 8.dp
        val outerV = if (compact) 7.dp else 9.dp
        val cardPad = if (compact) 11.dp else 13.dp

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

            GlassCard(modifier = Modifier.weight(1.26f), contentPadding = cardPad) {
                CardTitle("当前电量", badge = s?.stateName ?: "等待数据")
                Spacer(Modifier.weight(0.18f))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text(
                        s?.level?.takeIf { it >= 0 }?.toString() ?: "—",
                        fontSize = if (compact) 53.sp else 58.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(" %", fontSize = 20.sp, color = TxtSecondary, modifier = Modifier.padding(bottom = 7.dp))
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 6.dp)) {
                        Text(
                            s?.powerW?.display()?.let { "$it W" } ?: "— W",
                            fontSize = if (compact) 23.sp else 25.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (s?.isCharging == true) Orange else Green,
                        )
                        Text("电池侧功率", fontSize = 10.5.sp, color = TxtSecondary)
                    }
                }
                Spacer(Modifier.weight(0.16f))
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
                    MetricCompact(todayCharged.toString(), "mAh", "今日充入")
                    MetricCompact(todaySessions.toString(), "次", "充电次数")
                    MetricCompact(session?.peakPowerW?.display() ?: "—", "W", "本次峰值")
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
                        Text(capacity?.let { "$it mAh" } ?: "等待容量数据", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
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
                        (report?.designMah?.takeIf { it > 0 } ?: vm.settings.designCapacityMah).toString(),
                        "mAh",
                        "设计容量",
                    )
                    MetricCompact(cycles.takeIf { it >= 0 }?.toString() ?: "—", "次", "循环次数")
                }
            }
        }
    }
}
