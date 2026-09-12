package com.batterykeeper.app.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batterykeeper.app.battery.BatteryMonitorService
import com.batterykeeper.app.battery.XiaomiIslandController
import com.batterykeeper.app.ui.BatteryViewModel
import com.batterykeeper.app.ui.components.CardTitle
import com.batterykeeper.app.ui.components.GlassCard
import com.batterykeeper.app.ui.theme.Orange
import com.batterykeeper.app.ui.theme.TxtSecondary
import com.batterykeeper.app.ui.theme.TxtTertiary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(vm: BatteryViewModel) {
    val context = LocalContext.current
    val appVersion = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0),
            ).versionName ?: "?"
        }.getOrDefault("?")
    }
    val snapshot by vm.latest.collectAsState()
    val s = snapshot

    var chargeInterval by remember { mutableIntStateOf(vm.settings.chargingIntervalSec) }
    var idleInterval by remember { mutableIntStateOf(vm.settings.idleIntervalSec) }
    var capacityText by remember { mutableStateOf(vm.settings.designCapacityMah.toString()) }
    var monitorOn by remember { mutableStateOf(vm.settings.monitorEnabled) }
    var islandOn by remember { mutableStateOf(vm.settings.nativeIslandEnabled) }
    var islandStatus by remember { mutableStateOf<XiaomiIslandController.IslandStatus?>(null) }

    LaunchedEffect(islandOn, monitorOn) {
        while (true) {
            islandStatus = withContext(Dispatchers.IO) {
                XiaomiIslandController(context.applicationContext).status()
            }
            delay(2_000)
        }
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            Column {
                Text("设置", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(
                    "电池管家 v$appVersion · Android ${android.os.Build.VERSION.RELEASE}",
                    fontSize = 11.5.sp,
                    color = TxtSecondary,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }

        GlassCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("监测服务", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (s != null) "监测正在运行"
                        else if (monitorOn) "尚未获得数据，请检查后台运行权限"
                        else "监测已关闭",
                        fontSize = 11.sp,
                        color = TxtSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Switch(
                    checked = monitorOn,
                    onCheckedChange = { on ->
                        monitorOn = on
                        vm.settings.monitorEnabled = on
                        runCatching {
                            if (on) BatteryMonitorService.start(context)
                            else BatteryMonitorService.stop(context)
                        }.onFailure {
                            monitorOn = false
                            vm.settings.monitorEnabled = false
                        }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Orange),
                )
            }
        }

        GlassCard(Modifier.padding(top = 14.dp)) {
            CardTitle("监测")
            Spacer(Modifier.height(8.dp))
            SettingStepper("充电时采样间隔", listOf(1, 2, 5, 10), chargeInterval) {
                chargeInterval = it
                vm.settings.chargingIntervalSec = it
            }
            SettingStepper("平时采样间隔", listOf(5, 10, 30, 60), idleInterval) {
                idleInterval = it
                vm.settings.idleIntervalSec = it
            }
        }

        GlassCard(Modifier.padding(top = 14.dp)) {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("小米原生超级岛", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "连接电源时常驻 · 点击摘要态展开主要信息 · 再点击进入主界面",
                            fontSize = 11.sp,
                            color = TxtSecondary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Switch(
                        checked = islandOn,
                        onCheckedChange = { on ->
                            islandOn = on
                            vm.settings.nativeIslandEnabled = on
                            if (!on) XiaomiIslandController(context.applicationContext).dismiss("用户关闭超级岛")
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = Orange),
                    )
                }

                val st = islandStatus
                Spacer(Modifier.height(8.dp))
                when {
                    st == null -> Text(
                        "正在检测 HyperOS 超级岛能力…",
                        fontSize = 10.5.sp,
                        color = TxtTertiary,
                    )
                    st.systemSupported -> {
                        Text(
                            "✓ 系统支持原生超级岛 · 焦点通知协议 v${st.protocolVersion}",
                            fontSize = 10.5.sp,
                            color = TxtSecondary,
                        )
                        Text(
                            if (st.focusPermission)
                                "✓ 当前应用焦点通知权限已开启"
                            else
                                "⚠ 当前应用尚未获得/开启焦点通知权限；正式上岛还需在小米澎湃OS开发者平台完成场景审核与授权",
                            fontSize = 10.5.sp,
                            color = if (st.focusPermission) TxtSecondary else Orange,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    else -> Text(
                        "当前系统未检测到 HyperOS 3 原生超级岛能力；本版本不会再使用悬浮窗模拟岛。",
                        fontSize = 10.5.sp,
                        color = TxtTertiary,
                    )
                }

                if (st != null) {
                    Spacer(Modifier.height(12.dp))
                    Text("超级岛诊断", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "BatteryKeeper 应用侧",
                        fontSize = 10.5.sp, fontWeight = FontWeight.Medium,
                        color = TxtSecondary, modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        "通知总开关：${if (st.notificationsEnabled) "开启" else "关闭"} · " +
                            "统一通知 #1：${if (st.unifiedNotificationActive) "存在" else "不存在"}",
                        fontSize = 10.5.sp, color = TxtSecondary, modifier = Modifier.padding(top = 3.dp),
                    )
                    Text(
                        "监测心跳：${if (st.monitorHeartbeatFresh) "正常" else "超时/未运行"}（${ageText(st.monitorHeartbeatTime)}） · " +
                            "plugged=${st.monitorLastPlugged} · status=${st.monitorLastStatus}",
                        fontSize = 10.5.sp, color = TxtSecondary, modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "最近投递：${ageText(st.lastPostTime)} · ${st.lastPostReason} · " +
                            "累计 ${st.postCount} 次 / 通知缺失恢复 ${st.recoveryCount} 次",
                        fontSize = 10.5.sp, color = TxtSecondary, modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "最近主动取消：${ageText(st.lastDismissTime)} · ${st.lastDismissReason}",
                        fontSize = 10.5.sp, color = TxtSecondary, modifier = Modifier.padding(top = 4.dp),
                    )

                    Text(
                        "HyperOS / SystemUI",
                        fontSize = 10.5.sp, fontWeight = FontWeight.Medium,
                        color = TxtSecondary, modifier = Modifier.padding(top = 9.dp),
                    )
                    Text(
                        "焦点参数查询：${if (st.focusPayloadActive) "存在" else "暂未读取到"} · " +
                            "焦点权限：${if (st.focusPermission) "已开启" else "未获得/未开启"}",
                        fontSize = 10.5.sp, color = TxtSecondary, modifier = Modifier.padding(top = 3.dp),
                    )
                    Text(
                        "小米平台 App ID：${if (st.officialAppIdConfigured) "已配置" else "未配置（个人自用模式）"} · " +
                            "构建：${if (st.debuggable) "Debug" else "Release"}",
                        fontSize = 10.5.sp, color = if (st.officialAppIdConfigured) TxtTertiary else Orange,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "固定签名 SHA-256：${st.signingCertSha256}",
                        fontSize = 9.5.sp, color = TxtTertiary, modifier = Modifier.padding(top = 5.dp),
                    )

                    val diagnosis = when {
                        !st.notificationsEnabled ->
                            "⚠ 系统通知总开关已关闭，前台监测和超级岛都无法稳定工作。"
                        !st.monitorHeartbeatFresh ->
                            "⚠ 监测心跳已超时；优先检查后台运行、自启动和省电限制。"
                        st.monitorLastPlugged != 0 && !st.unifiedNotificationActive ->
                            "⚠ 已插电但统一通知 #1 不存在；v1.5.4 仅在这种情况下自动恢复通知，避免无意义高频重发。"
                        st.monitorLastPlugged != 0 && st.unifiedNotificationActive && !st.focusPayloadActive ->
                            "统一通知 #1 仍存在，但应用暂未读取到焦点参数。v1.5.4 不会因此立即补发；下一次正常 10 秒刷新会重新写入焦点参数。"
                        st.monitorLastPlugged != 0 && st.unifiedNotificationActive && !st.officialAppIdConfigured ->
                            "应用侧状态正常。当前为个人自用、未配置小米平台 App ID；若摄像头区域无岛，通常是 HyperOS SystemUI 收起/权限策略，应用无法强制长期常驻。"
                        st.monitorHeartbeatFresh && st.monitorLastPlugged != 0 && st.unifiedNotificationActive ->
                            "应用侧状态正常。若摄像头区域已无岛，说明通知仍存活但视觉层被 HyperOS SystemUI 收起，不是 BatteryKeeper 主动取消。"
                        else ->
                            "当前应用侧未发现异常；插电后重点观察统一通知 #1、心跳和“通知缺失恢复”次数。"
                    }
                    Text(
                        diagnosis,
                        fontSize = 10.5.sp,
                        color = if (diagnosis.startsWith("⚠") || !st.officialAppIdConfigured) Orange else TxtSecondary,
                        modifier = Modifier.padding(top = 7.dp),
                    )
                }
            }
        }

        GlassCard(Modifier.padding(top = 14.dp)) {
            CardTitle("电池参数")
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = capacityText,
                onValueChange = { capacityText = it.filter { c -> c.isDigit() }.take(5) },
                label = { Text("设计容量 mAh（小米17 标准版约 7000）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            androidx.compose.material3.TextButton(onClick = {
                capacityText.toIntOrNull()?.let {
                    if (it in 1000..15000) vm.settings.designCapacityMah = it
                }
            }) { Text("保存设计容量", color = Orange) }
            Text(
                "健康度 = 实际满充容量 ÷ 设计容量。当前循环次数：${s?.cycleCount?.takeIf { it > 0 } ?: "系统未上报"}",
                fontSize = 10.5.sp,
                color = TxtTertiary,
            )
        }

        Text(
            "电池管家 v$appVersion · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            fontSize = 10.5.sp,
            color = TxtTertiary,
            modifier = Modifier.padding(vertical = 16.dp).align(Alignment.CenterHorizontally),
        )
    }
}

private fun ageText(timestamp: Long): String {
    if (timestamp <= 0L) return "无记录"
    val deltaSec = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0L) / 1000L)
    return when {
        deltaSec < 5 -> "刚刚"
        deltaSec < 60 -> "${deltaSec}秒前"
        deltaSec < 3600 -> "${deltaSec / 60}分钟前"
        else -> "${deltaSec / 3600}小时前"
    }
}

@Composable
private fun SettingStepper(
    label: String,
    options: List<Int>,
    current: Int,
    onPick: (Int) -> Unit,
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text("$label：${current}s", fontSize = 13.sp)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            options.forEach { opt ->
                val selected = opt == current
                androidx.compose.material3.Surface(
                    onClick = { onPick(opt) },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    color = if (selected) Color(0x33FF8A3D) else Color(0x0FFFFFFF),
                    modifier = Modifier.padding(vertical = 2.dp),
                ) {
                    Text(
                        "${opt}s",
                        fontSize = 12.sp,
                        color = if (selected) Orange else TxtSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
