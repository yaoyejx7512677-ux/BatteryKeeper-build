package com.batterykeeper.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batterykeeper.app.battery.display
import com.batterykeeper.app.ui.BatteryViewModel
import com.batterykeeper.app.ui.components.*
import com.batterykeeper.app.ui.theme.*

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

    Column(
        Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("电池管家", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("热爱生活·为您充电！", fontSize = 11.5.sp, color = TxtSecondary)
            }
            StatusDot(if (s == null) "监测未运行" else "监测中", s != null)
        }

        GlassCard(contentPadding = 13.dp) {
            CardTitle("当前电量", badge = s?.stateName ?: "等待数据")
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                Text(s?.level?.takeIf { it >= 0 }?.toString() ?: "—", fontSize = 58.sp, fontWeight = FontWeight.Bold)
                Text(" %", fontSize = 21.sp, color = TxtSecondary, modifier = Modifier.padding(bottom = 8.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 7.dp)) {
                    Text(s?.powerW?.display()?.let { "$it W" } ?: "— W", fontSize = 25.sp, fontWeight = FontWeight.SemiBold, color = if (s?.isCharging == true) Orange else Green)
                    Text("电池侧功率", fontSize = 10.5.sp, color = TxtSecondary)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricCompact(s?.tempC?.display() ?: "—", "℃", "温度")
                MetricCompact(s?.voltageV?.display(2) ?: "—", "V", "电压")
                MetricCompact(s?.currentA?.display(2) ?: "—", "A", "电流")
            }
        }

        GlassCard(contentPadding = 13.dp) {
            CardTitle(if (session != null) "本次充电" else "今日充电", badge = if (session != null) "进行中" else "已完成")
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MetricCompact((session?.energyMah ?: today.chargedMah).toString(), "mAh", "已充入")
                if (session != null) MetricCompact(session!!.peakPowerW.display(), "W", "峰值")
                else MetricCompact(today.sessions.toString(), "次", "记录")
            }
        }

        GlassCard(contentPadding = 13.dp) {
            CardTitle("电池健康", badge = if (report != null) "报告值" else if (health != null) "满充估算" else "暂无数据")
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                    HealthRing((health ?: 0f) / 100, Modifier.fillMaxSize())
                    Text(health?.let { "%.1f%%".format(it) } ?: "—", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    val capacity = report?.fullChargeMah?.takeIf { it > 0 } ?: vm.settings.fullChargeMah.takeIf { it > 0 }
                    Text(capacity?.let { "$it mAh" } ?: "等待容量数据", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text("满充容量", fontSize = 10.5.sp, color = TxtSecondary)
                    val timestamp = report?.timestamp ?: vm.settings.fullChargeTime
                    if (timestamp > 0) Text(BatteryViewModel.fmtDate(timestamp), fontSize = 10.sp, color = TxtTertiary, modifier = Modifier.padding(top = 3.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MetricCompact((report?.designMah?.takeIf { it > 0 } ?: vm.settings.designCapacityMah).toString(), "mAh", "设计容量")
                MetricCompact(cycles.takeIf { it >= 0 }?.toString() ?: "—", "次", "循环次数")
            }
        }
    }
}
