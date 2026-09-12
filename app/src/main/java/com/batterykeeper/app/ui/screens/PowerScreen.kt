package com.batterykeeper.app.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batterykeeper.app.ui.BatteryViewModel
import com.batterykeeper.app.ui.components.CardTitle
import com.batterykeeper.app.ui.components.GlassCard
import com.batterykeeper.app.ui.components.LiveLineChart
import com.batterykeeper.app.ui.components.MetricCompact
import com.batterykeeper.app.ui.components.StatusDot
import com.batterykeeper.app.ui.theme.Green
import com.batterykeeper.app.ui.theme.Orange
import com.batterykeeper.app.ui.theme.TxtSecondary
import com.batterykeeper.app.ui.theme.TxtTertiary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import com.batterykeeper.app.battery.display

@Composable
fun PowerScreen(vm: BatteryViewModel) {
    val snapshot by vm.latest.collectAsState()
    val protocol by vm.protocol.collectAsState()
    val live by vm.liveBuffer.collectAsState()
    val session by vm.session.collectAsState()
    val s = snapshot
    var history by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<com.batterykeeper.app.data.ChargeSession>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            history=vm.latestSessions()
            kotlinx.coroutines.delay(15000)
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("实时功率", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("采样频率 ${vm.settings.chargingIntervalSec} 秒 · 前台实时监测", fontSize = 11.5.sp, color = TxtSecondary, modifier = Modifier.padding(top = 3.dp))
            }
            StatusDot(if (s == null) "监测未运行" else "监测中", active = s?.isCharging == true)
        }

        GlassCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (s?.isCharging == true) "▲ 充电中 · ${protocol.displayName}" else s?.stateName ?: "等待监测数据", fontSize = 12.sp, color = TxtSecondary)
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
                    Text(s?.powerW?.display() ?: "—", fontSize = 60.sp, fontWeight = FontWeight.ExtraBold, color = if (s?.isCharging == true) Orange else Green)
                    Text(" W", fontSize = 20.sp, color = TxtSecondary, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 10.dp))
                }
            }
        }

        GlassCard(Modifier.padding(top = 14.dp)) {
            CardTitle("功率曲线 · 近 10 分钟")
            Spacer(Modifier.height(10.dp))
            LiveLineChart(
                points = live.map { it.t to abs(it.powerW) },
                color = if (s?.isCharging == true) Orange else Green,
                modifier = Modifier.height(160.dp),
                maxV = (live.maxOfOrNull { abs(it.powerW) } ?: 5f).coerceAtLeast(5f) * 1.15f,
                minV = 0f,
            )
        }

        GlassCard(Modifier.padding(top = 14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MetricCompact(s?.currentA?.display(2) ?: "—", "A", "电流")
                MetricCompact(s?.voltageV?.display(2) ?: "—", "V", "电压")
                MetricCompact(s?.tempC?.display() ?: "—", "℃", "电池温度", valueColor = if ((s?.tempC ?: 0f) > 45f) Color(0xFFF87171) else Color.White)
            }
        }

        val sess = session
        GlassCard(Modifier.padding(top = 14.dp)) {
            CardTitle("本次充电会话", badge = if (sess != null) "进行中" else "未充电")
            Spacer(Modifier.height(14.dp))
            if (sess != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(sess.startTime)), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("开始时间", fontSize = 10.5.sp, color = TxtTertiary, modifier = Modifier.padding(top = 2.dp))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${sess.energyMah}", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("已充入 mAh", fontSize = 10.5.sp, color = TxtTertiary, modifier = Modifier.padding(top = 2.dp))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("%.1f".format(sess.avgPowerW), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("平均功率 W", fontSize = 10.5.sp, color = TxtTertiary, modifier = Modifier.padding(top = 2.dp))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("%.1f".format(sess.peakPowerW), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Orange)
                        Text("峰值功率 W", fontSize = 10.5.sp, color = TxtTertiary, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }

        GlassCard(Modifier.padding(top = 14.dp)) {
            CardTitle("最近充电记录")
            val completed=history.filter { it.startTime != session?.startTime }.take(10)
            completed.forEach { item ->
                Column(Modifier.fillMaxWidth().padding(vertical=12.dp)) {
                    Text(BatteryViewModel.fmtDate(item.startTime),fontSize=14.sp,fontWeight=FontWeight.SemiBold)
                    Text("${item.startLevel}% → ${item.endLevel ?: "—"}% · ${item.energyMah} mAh · 峰值 ${item.peakPowerW.display()} W",fontSize=12.sp,color=TxtSecondary)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
