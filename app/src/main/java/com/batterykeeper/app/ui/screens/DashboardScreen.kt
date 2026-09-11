package com.batterykeeper.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    val report=reports.lastOrNull { it.healthPct>0 }
    val cycleReport=reports.lastOrNull { it.cycleCount>=0 }
    val cycles=vm.displayCycleCount(s?.cycleCount ?: -1,cycleReport?.cycleCount)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Column {
                Text("电池管家",fontSize=28.sp,fontWeight=FontWeight.Bold)
                Text("看清每一次充放电",fontSize=13.sp,color=TxtSecondary,modifier=Modifier.padding(top=5.dp))
            }
            StatusDot(if(s==null) "监测未运行" else "监测中",s!=null)
        }
        GlassCard {
            CardTitle("当前电量",badge=s?.stateName ?: "等待数据")
            Row(Modifier.fillMaxWidth().padding(vertical=18.dp),verticalAlignment=Alignment.Bottom) {
                Text(s?.level?.takeIf { it>=0 }?.toString() ?: "—",fontSize=72.sp,fontWeight=FontWeight.Bold)
                Text(" %",fontSize=24.sp,color=TxtSecondary,modifier=Modifier.padding(bottom=12.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment=Alignment.End,modifier=Modifier.padding(bottom=12.dp)) {
                    Text(s?.powerW?.display()?.let { "$it W" } ?: "— W",fontSize=28.sp,fontWeight=FontWeight.SemiBold,color=if(s?.isCharging==true) Orange else Green)
                    Text("电池侧功率",fontSize=12.sp,color=TxtSecondary)
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                MetricCompact(s?.tempC?.display() ?: "—","℃","温度")
                MetricCompact(s?.voltageV?.display(2) ?: "—","V","电压")
                MetricCompact(s?.currentA?.display(2) ?: "—","A","电流幅值")
            }
            Spacer(Modifier.height(14.dp))
            Text("${s?.pluggedName ?: "开启监测后显示实时数据"} · 功率按电池电流与电压估算",fontSize=12.sp,color=TxtSecondary)
        }
        GlassCard {
            CardTitle(if(session!=null) "本次充电" else "今日充电记录",badge=if(session!=null) "进行中" else "已完成")
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                MetricCompact((session?.energyMah ?: today.chargedMah).toString(),"mAh","已观测充入")
                if(session!=null) MetricCompact(session!!.peakPowerW.display(),"W","峰值功率")
                else MetricCompact(today.sessions.toString(),"次","充电记录")
            }
            Spacer(Modifier.height(12.dp))
            Text("仅统计监测覆盖时段，退出或系统中断可能分成多条记录。",fontSize=12.sp,color=TxtSecondary)
        }
        GlassCard {
            CardTitle("电池健康",badge=if(report!=null) "报告值" else if(health!=null) "满充估算" else "暂无数据")
            Row(Modifier.fillMaxWidth().padding(vertical=18.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(90.dp),contentAlignment=Alignment.Center) {
                    HealthRing((health ?: 0f)/100,Modifier.fillMaxSize())
                    Text(health?.let { "%.1f%%".format(it) } ?: "—",fontSize=20.sp,fontWeight=FontWeight.Bold)
                }
                Spacer(Modifier.width(20.dp))
                Column {
                    val capacity=report?.fullChargeMah?.takeIf { it>0 } ?: vm.settings.fullChargeMah.takeIf { it>0 }
                    Text(capacity?.let { "$it mAh" } ?: "等待容量数据",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                    Text("满充容量 · 非当前剩余电量",fontSize=12.sp,color=TxtSecondary)
                }
            }
            val timestamp=report?.timestamp ?: vm.settings.fullChargeTime
            Text(if(timestamp>0) "${if(report!=null) "检测报告" else "满充估算"} · ${BatteryViewModel.fmtDate(timestamp)}" else "在报表页导入系统报告，或保持监测至满充以尝试估算。",fontSize=12.sp,color=TxtSecondary)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                MetricCompact((report?.designMah?.takeIf { it>0 } ?: vm.settings.designCapacityMah).toString(),"mAh","设计容量")
                MetricCompact(cycles.takeIf { it>=0 }?.toString() ?: "—","次",when {
                    (s?.cycleCount ?: -1)>=0 -> "系统累计循环"
                    cycleReport!=null -> "报告累计循环"
                    else -> "安装后估算循环"
                })
            }
        }
    }
}
