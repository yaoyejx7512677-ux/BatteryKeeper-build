package com.batterykeeper.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batterykeeper.app.export.CsvExporter
import com.batterykeeper.app.report.ReportImport
import com.batterykeeper.app.ui.BatteryViewModel
import com.batterykeeper.app.ui.components.BarChart
import com.batterykeeper.app.ui.components.CardTitle
import com.batterykeeper.app.ui.components.DualLineChart
import com.batterykeeper.app.ui.components.GlassCard
import com.batterykeeper.app.ui.components.MetricCompact
import com.batterykeeper.app.ui.theme.Green
import com.batterykeeper.app.ui.theme.Orange
import com.batterykeeper.app.ui.theme.TxtSecondary
import com.batterykeeper.app.ui.theme.TxtTertiary
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun ReportsScreen(vm: BatteryViewModel) {
    val records by vm.cycleRecords.collectAsState()
    val dailies by vm.dailyStats.collectAsState()
    val reports by vm.reports.collectAsState()
    val snapshot by vm.latest.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var importMsg by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<com.batterykeeper.app.data.HealthReport?>(null) }
    var deleteId by remember { mutableStateOf<Long?>(null) }
    if (preview != null) {
        val report = preview!!
        var full by remember(report) { mutableStateOf(report.fullChargeMah.takeIf { it>0 }?.toString() ?: "") }
        var cycles by remember(report) { mutableStateOf(report.cycleCount.takeIf { it>=0 }?.toString() ?: "") }
        var health by remember(report) { mutableStateOf(report.healthPct.takeIf { it>0 }?.toString() ?: "") }
        var error by remember(report) { mutableStateOf<String?>(null) }
        var saving by remember(report) { mutableStateOf(false) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!saving) preview=null },
            title = { Text("核对识别结果") },
            text = { Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("确认数值与原报告一致。缺失项可留空。",fontSize=13.sp)
                androidx.compose.material3.OutlinedTextField(full,{ full=it },label={ Text("满充容量 mAh") },singleLine=true)
                androidx.compose.material3.OutlinedTextField(cycles,{ cycles=it },label={ Text("循环次数") },singleLine=true)
                androidx.compose.material3.OutlinedTextField(health,{ health=it },label={ Text("健康度 %") },singleLine=true)
                error?.let { Text(it,color=androidx.compose.material3.MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { androidx.compose.material3.TextButton(enabled=!saving,onClick={
                scope.launch {
                    saving=true
                    runCatching {
                        val f=if(full.isBlank()) -1 else full.toIntOrNull() ?: kotlin.error("容量格式不正确")
                        val c=if(cycles.isBlank()) -1 else cycles.toIntOrNull() ?: kotlin.error("循环格式不正确")
                        val h=if(health.isBlank()) -1f else health.toFloatOrNull() ?: kotlin.error("健康度格式不正确")
                        require(f>0 || c>=0 || h>0) { "至少填写一个有效指标" }
                        vm.saveReport(report.copy(fullChargeMah=f,cycleCount=c,healthPct=h))
                    }.onSuccess { preview=null; importMsg="✓ 报告已保存" }
                     .onFailure { error=it.message }
                    saving=false
                }
            }) { Text(if(saving) "保存中…" else "保存") } },
            dismissButton={ androidx.compose.material3.TextButton(enabled=!saving,onClick={preview=null}) { Text("取消") } }
        )
    }
    if(deleteId!=null) androidx.compose.material3.AlertDialog(onDismissRequest={deleteId=null},
        title={ Text("删除此报告？") },text={ Text("删除后不再用于健康趋势分析。") },
        confirmButton={ androidx.compose.material3.TextButton(onClick={vm.deleteReport(deleteId!!);deleteId=null}) { Text("删除") } },
        dismissButton={ androidx.compose.material3.TextButton(onClick={deleteId=null}) { Text("取消") } })

    val fmtTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    // 记住上次选择位置：以最近一次选取的文件 URI 作为初始定位提示
    fun String.toUriOrNull(): android.net.Uri? =
        runCatching { android.net.Uri.parse(this) }.getOrNull()

    // 澎湃OS 的 MIUI/debug_log 日志项有时不会被文件提供方标记为 OPENABLE。
    // CATEGORY_OPENABLE 会把这类原始 bugreport 直接置灰；复制到 Downloads 后才可选。
    // 因此这里保持 * / * 的宽松 MIME，并不再强制 OPENABLE，读取能力交给解析阶段校验。
    fun pickIntent(): Intent =
        Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            vm.settings.lastImportUri.toUriOrNull()?.let {
                putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, it)
            }
        }

    val zipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (uri != null) {
            vm.settings.lastImportUri = uri.toString()
            scope.launch {
                importing=true
                vm.importBugReport(context, uri)
                    .onSuccess {
                        preview = it
                        importMsg = null
                    }
                    .onFailure { importMsg = "导入失败：${it.message}" }
                importing=false
            }
        }
    }
    val imgPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (uri != null) {
            vm.settings.lastImportUri = uri.toString()
            scope.launch {
                importing=true
                vm.importScreenshot(context, uri)
                    .onSuccess {
                        preview = it
                        importMsg = null
                    }
                    .onFailure { importMsg = "识别失败：${it.message}" }
                importing=false
            }
        }
    }

    val totalCycles = vm.displayCycleCount(
        snapshot?.cycleCount ?: -1,
        reports.lastOrNull { it.cycleCount > 0 }?.cycleCount,
    ).takeIf { it >= 0 } ?: -1

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("电池报告", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(
                    "检测报告 · 循环计数 · 历史趋势",
                    fontSize = 11.5.sp, color = TxtSecondary, modifier = Modifier.padding(top = 3.dp),
                )
            }
        }

        // ===== 澎湃OS 检测报告 =====
        GlassCard {
            CardTitle("澎湃OS 检测报告", badge = "${reports.size} 条记录")
            Spacer(Modifier.height(8.dp))
            Text(
                "获取方式一：拨号盘输入 *#*#284#*#* 生成 Bug 报告，等待生成完成（通知栏提示），" +
                    "将 ZIP 文件保存后在此导入；\n获取方式二：对检测报告/电池信息页面截图，识别导入。",
                fontSize = 11.sp, color = TxtSecondary, lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { zipPicker.launch(pickIntent()) },
                    enabled = !importing,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0x33FF8A3D), contentColor = Orange,
                    ),
                ) { Text("导入 Bug 报告", fontSize = 12.sp) }
                Button(
                    onClick = { imgPicker.launch(pickIntent()) },
                    enabled = !importing,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0x264ADE80), contentColor = Green,
                    ),
                ) { Text("截图识别导入", fontSize = 12.sp) }
            }
            if(importing) Text("正在读取和识别，请稍候…",color=TxtSecondary,modifier=Modifier.padding(top=10.dp))
            importMsg?.let {
                Text(
                    it, fontSize = 11.sp,
                    color = if (it.startsWith("✓")) Green else Color(0xFFF87171),
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        // ===== 报告历史列表 =====
        if (reports.isNotEmpty()) {
            GlassCard(Modifier.padding(top = 14.dp)) {
                CardTitle("检测历史")
                Spacer(Modifier.height(6.dp))
                // 健康度趋势
                val healthSeries = reports.filter { it.healthPct > 0 }
                if (healthSeries.size >= 2) {
                    DualLineChart(
                        data = healthSeries.map { Triple(it.timestamp, it.healthPct, 0f) },
                        mainColor = Green,
                        secondaryColor = Green,
                        modifier = Modifier.height(120.dp),
                        mainMax = 100f, mainMin = 80f,
                        gridLines = listOf(85f, 90f, 95f),
                        gridLabels = listOf("95%", "90%", "85%"),
                    )
                    Text(
                        "健康度趋势（${healthSeries.size} 条）",
                        fontSize = 10.sp, color = TxtTertiary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                reports.reversed().forEach { r ->
                    Column(Modifier.padding(vertical = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                fmtTime.format(Date(r.timestamp)),
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (r.source == "bugreport") "Bug 报告" else "截图识别",
                                fontSize = 10.sp, color = TxtTertiary,
                            )
                        }
                        androidx.compose.material3.TextButton(onClick = { deleteId = r.id }) { Text("删除此记录", color = TxtSecondary) }
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            MetricCompact(
                                if (r.healthPct > 0) "%.1f".format(r.healthPct) else "--",
                                "%", "健康度", valueColor = Green,
                            )
                            MetricCompact(
                                if (r.fullChargeMah > 0) "${r.fullChargeMah}" else "--",
                                "mAh", "满充容量",
                            )
                            MetricCompact(
                                if (r.cycleCount > 0) "${r.cycleCount}" else "--",
                                "次", "循环次数", valueColor = Orange,
                            )
                            MetricCompact(
                                if (r.estimatedMah > 0) "${r.estimatedMah}" else "--",
                                "mAh", "估算容量",
                            )
                        }
                    }
                }
                // 循环分析摘要
                val cycles = reports.filter { it.cycleCount > 0 }
                if (cycles.size >= 2) {
                    val spanDays = ((cycles.last().timestamp - cycles.first().timestamp) / 86_400_000f).coerceAtLeast(1f)
                    val delta = cycles.last().cycleCount - cycles.first().cycleCount
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "分析：${spanDays.toInt()} 天内循环 +$delta 次（日均 %.1f 次）".format(delta / spanDays),
                        fontSize = 11.sp, color = TxtSecondary,
                    )
                }
            }
        }

        // ===== 循环计数总览 =====
        GlassCard(Modifier.padding(top = 14.dp)) {
            CardTitle(if ((snapshot?.cycleCount ?: -1) >= 0 || reports.any { it.cycleCount >= 0 }) "累计循环次数" else "安装后估算循环", badge = if (totalCycles >= 0) "数据正常" else "待积累")
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (totalCycles >= 0) "$totalCycles" else "--",
                    fontSize = 50.sp, fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    " 次" + if (records.isNotEmpty())
                        " · 自 ${records.first().date} 记录" else "",
                    fontSize = 13.sp, color = TxtSecondary,
                    modifier = Modifier.padding(bottom = 8.dp, start = 4.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            val monthFmt = SimpleDateFormat("yyyy-MM", Locale.CHINA)
            val thisMonth = monthFmt.format(Calendar.getInstance().time)
            val monthStartCycles = records.lastOrNull { it.date < "$thisMonth-01" }?.cycleCount
            val hasData = records.any { it.cycleCount >= 0 } && (snapshot?.cycleCount ?: -1) >= 0
            val monthDelta = if (hasData && monthStartCycles != null && monthStartCycles >= 0)
                (totalCycles - monthStartCycles).coerceAtLeast(0) else -1
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MetricCompact(
                    if (monthDelta >= 0) "+$monthDelta" else "--", "", "本月新增",
                    valueColor = Orange,
                )
                MetricCompact("${records.size}", "天", "已记录天数")
                MetricCompact(
                    if (hasData && records.count { it.cycleCount >= 0 } > 1)
                        "%.1f".format(
                            (totalCycles - records.first { it.cycleCount >= 0 }.cycleCount).toFloat() /
                                java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(records.first { it.cycleCount >= 0 }.date),java.time.LocalDate.now()).coerceAtLeast(1)
                        )
                    else "--",
                    "", "日均循环",
                )
            }
        }

        // ===== 每月循环柱状图 =====
        GlassCard(Modifier.padding(top = 14.dp)) {
            CardTitle("月内已记录循环增量 · 近 12 个月")
            Spacer(Modifier.height(10.dp))
            val monthly = records.filter { it.cycleCount >= 0 }.groupBy { it.date.substring(0, 7) }
                .toSortedMap()
                .let { m ->
                    m.entries.map { (month, recs) ->
                        month to (recs.maxOf { it.cycleCount } - recs.minOf { it.cycleCount }).coerceAtLeast(0)
                    }
                }.takeLast(12)
            if (monthly.isNotEmpty()) {
                BarChart(
                    values = monthly.map { it.second },
                    labels = monthly.map { it.first.substring(5) },
                    modifier = Modifier.height(150.dp),
                )
            } else {
                Text(
                    "每日快照积累后此处显示月度柱状图（也可通过上方检测报告直接获取真实循环数据）",
                    fontSize = 11.5.sp, color = TxtSecondary,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }

        // ===== 月度报表 =====
        GlassCard(Modifier.padding(top = 14.dp)) {
            CardTitle("月度报表")
            Spacer(Modifier.height(6.dp))
            if (dailies.isEmpty()) {
                Text(
                    "日结统计由每日维护任务生成，次日可见",
                    fontSize = 11.5.sp, color = TxtSecondary,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                dailies.groupBy { it.date.substring(0, 7) }
                    .toSortedMap(compareByDescending { it })
                    .forEach { (month, stats) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(month, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "循环 +${(stats.filter { it.cycleCountEnd >= 0 }.let { if (it.size >= 2) it.maxOf { r -> r.cycleCountEnd } - it.minOf { r -> r.cycleCountEnd } else "—" })} · " +
                                    "充电 ${stats.sumOf { it.chargeSessions }} 次 · " +
                                    "充入 ${stats.sumOf { it.chargedMah } / 1000}Ah",
                                fontSize = 11.sp, color = TxtSecondary,
                            )
                        }
                    }
            }
        }

        // ===== 导出 =====
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = {
                if (!exporting) {
                    exporting = true
                    scope.launch {
                        runCatching {
                            val uri = CsvExporter.export(context)
                            context.startActivity(
                                Intent.createChooser(
                                    CsvExporter.shareIntent(context, uri), "导出电池数据",
                                )
                            )
                        }.onFailure { importMsg = "导出失败：${it.message}" }
                        exporting = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0x26FF8A3D), contentColor = Orange,
            ),
        ) {
            Text(if (exporting) "正在生成…" else "导出数据报表（CSV）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "包含原始采样与日统计 · 通过系统分享导出",
            fontSize = 10.5.sp, color = TxtTertiary,
            modifier = Modifier.padding(top = 10.dp, bottom = 20.dp).align(Alignment.CenterHorizontally),
        )
    }
}
