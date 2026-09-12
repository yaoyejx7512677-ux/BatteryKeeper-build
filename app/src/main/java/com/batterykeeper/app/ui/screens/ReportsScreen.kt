package com.batterykeeper.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import com.batterykeeper.app.report.ReportFileLocator
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
    val currentSystemVersion = remember { ReportImport.currentSystemVersion() }
    var importing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var importMsg by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<com.batterykeeper.app.data.HealthReport?>(null) }
    var batchPreview by remember { mutableStateOf<List<com.batterykeeper.app.data.HealthReport>?>(null) }
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
                val sourceMeta = ReportImport.sourceMeta(report.source)
                if (sourceMeta.kind == "bugreport") {
                    Text(
                        "解析格式：${sourceMeta.format} · 置信度：${sourceMeta.confidence}",
                        fontSize = 11.sp, color = TxtSecondary,
                    )
                    if (sourceMeta.origins.isNotBlank()) {
                        Text("字段来源：${sourceMeta.origins}", fontSize = 10.5.sp, color = TxtTertiary)
                    }
                }
                if (sourceMeta.systemVersion.isNotBlank()) {
                    Text("系统版本：${sourceMeta.systemVersion}", fontSize = 10.5.sp, color = TxtTertiary)
                }
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
    if (batchPreview != null) {
        val batch = batchPreview!!
        var savingBatch by remember(batch) { mutableStateOf(false) }
        var batchError by remember(batch) { mutableStateOf<String?>(null) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!savingBatch) batchPreview = null },
            title = { Text("识别到 ${batch.size} 条历史报告") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("将按时间导入，并自动跳过已有记录。请先核对每条记录和系统版本。", fontSize = 13.sp)
                    batch.sortedByDescending { it.timestamp }.forEach { r ->
                        val meta = ReportImport.sourceMeta(r.source)
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(BatteryViewModel.fmtDate(r.timestamp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(meta.systemVersion.ifBlank { "系统版本未知" }, fontSize = 10.5.sp, color = TxtTertiary)
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                MetricCompact(if (r.healthPct > 0) "%.2f".format(r.healthPct) else "--", "%", "健康度")
                                MetricCompact(if (r.fullChargeMah > 0) r.fullChargeMah.toString() else "--", "mAh", "满充容量")
                                MetricCompact(if (r.cycleCount >= 0) r.cycleCount.toString() else "--", "次", "循环次数")
                            }
                        }
                    }
                    batchError?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(enabled = !savingBatch, onClick = {
                    scope.launch {
                        savingBatch = true
                        runCatching { vm.saveReports(batch) }
                            .onSuccess { (saved, skipped) ->
                                batchPreview = null
                                importMsg = "✓ 已导入 $saved 条" + if (skipped > 0) "，跳过 $skipped 条重复记录" else ""
                            }
                            .onFailure { batchError = it.message }
                        savingBatch = false
                    }
                }) { Text(if (savingBatch) "导入中…" else "导入全部") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(enabled = !savingBatch, onClick = { batchPreview = null }) { Text("取消") }
            },
        )
    }

    if(deleteId!=null) androidx.compose.material3.AlertDialog(onDismissRequest={deleteId=null},
        title={ Text("删除此报告？") },text={ Text("删除后不再用于健康趋势分析。") },
        confirmButton={ androidx.compose.material3.TextButton(onClick={vm.deleteReport(deleteId!!);deleteId=null}) { Text("删除") } },
        dismissButton={ androidx.compose.material3.TextButton(onClick={deleteId=null}) { Text("取消") } })

    val fmtTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    fun String.toUriOrNull(): android.net.Uri? =
        runCatching { android.net.Uri.parse(this) }.getOrNull()

    // 手动 ZIP 导入仍保留宽松 GET_CONTENT，兼容部分小米文件提供方没有 OPENABLE 标记的日志项。
    fun zipPickIntent(): Intent =
        Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            vm.settings.lastImportUri.toUriOrNull()?.let {
                putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, it)
            }
        }

    fun importBugReportUri(uri: android.net.Uri, locatedName: String? = null) {
        vm.settings.lastImportUri = uri.toString()
        scope.launch {
            importing = true
            vm.importBugReport(context, uri)
                .onSuccess {
                    preview = it
                    importMsg = locatedName?.let { name -> "✓ 已找到最新报告：$name" }
                }
                .onFailure { importMsg = "导入失败：${it.message}" }
            importing = false
        }
    }

    fun scanLatestReport(treeUri: android.net.Uri) {
        scope.launch {
            importing = true
            val located = runCatching { ReportFileLocator.findLatestBugReport(context, treeUri) }
                .onFailure {
                    vm.settings.reportFolderUri = ""
                    importMsg = "目录授权已失效，请再次点击“自动查找最新报告”重新授权。"
                }
                .getOrNull()
            if (located == null) {
                if (importMsg == null || importMsg!!.startsWith("✓")) {
                    importMsg = "未在已授权的错误报告目录中找到 ZIP。"
                }
                importing = false
            } else {
                vm.settings.lastImportUri = located.uri.toString()
                vm.importBugReport(context, located.uri)
                    .onSuccess {
                        preview = it
                        importMsg = "✓ 已找到最新报告：${located.displayName}"
                    }
                    .onFailure { importMsg = "最新报告解析失败：${it.message}" }
                importing = false
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            vm.settings.reportFolderUri = uri.toString()
            scanLatestReport(uri)
        }
    }

    val zipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        result.data?.data?.let { importBugReportUri(it) }
    }

    // 使用 Android Photo Picker / 系统相册，不再跳到通用文件管理器。
    val imgPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                importing = true
                vm.importScreenshotReports(context, uri)
                    .onSuccess { list ->
                        when {
                            list.isEmpty() -> importMsg = "识别失败：图片中未找到电池健康记录"
                            list.size == 1 -> {
                                preview = list.first()
                                importMsg = "✓ 图片识别完成"
                            }
                            else -> {
                                batchPreview = list
                                importMsg = "✓ 图片识别到 ${list.size} 条历史记录"
                            }
                        }
                    }
                    .onFailure { importMsg = "识别失败：${it.message}" }
                importing = false
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

        // ===== 最新报告 + 导入入口 =====
        val latestHealthReport = reports.maxByOrNull { it.timestamp }
        GlassCard {
            val latestMeta = latestHealthReport?.let { ReportImport.sourceMeta(it.source) }
            CardTitle(
                "电池健康报告",
                badge = latestHealthReport?.let { "${reports.size} 条" } ?: "暂无记录",
            )
            Text(
                "当前系统 · $currentSystemVersion",
                fontSize = 10.5.sp, color = TxtTertiary, modifier = Modifier.padding(top = 6.dp),
            )
            if (latestHealthReport != null) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        fmtTime.format(Date(latestHealthReport.timestamp)),
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        latestMeta?.systemVersion?.takeIf { it.isNotBlank() } ?: latestMeta?.format ?: "检测报告",
                        fontSize = 11.sp, color = TxtSecondary,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MetricCompact(
                        if (latestHealthReport.healthPct > 0) "%.2f".format(latestHealthReport.healthPct) else "--",
                        "%", "健康度", valueColor = Green,
                    )
                    MetricCompact(
                        if (latestHealthReport.fullChargeMah > 0) latestHealthReport.fullChargeMah.toString() else "--",
                        "mAh", "满充容量",
                    )
                    MetricCompact(
                        if (latestHealthReport.cycleCount >= 0) latestHealthReport.cycleCount.toString() else "--",
                        "次", "循环次数", valueColor = Orange,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        val saved = vm.settings.reportFolderUri.toUriOrNull()
                        if (saved != null) {
                            scanLatestReport(saved)
                        } else {
                            folderPicker.launch(ReportFileLocator.defaultBugReportTreeUri())
                        }
                    },
                    enabled = !importing,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0x33FF8A3D), contentColor = Orange,
                    ),
                ) { Text("自动查找最新报告", fontSize = 11.5.sp) }
                Button(
                    onClick = { zipPicker.launch(zipPickIntent()) },
                    enabled = !importing,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0x22FFFFFF), contentColor = TxtSecondary,
                    ),
                ) { Text("选择 ZIP", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    imgPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                enabled = !importing,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0x264ADE80), contentColor = Green,
                ),
            ) { Text("从相册导入电池健康截图", fontSize = 12.sp) }

            if (importing) {
                Text("正在查找 / 识别…", color = TxtSecondary, modifier = Modifier.padding(top = 10.dp))
            }
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
                    Spacer(Modifier.height(10.dp))
                }
                reports.reversed().forEach { r ->
                    Column(Modifier.padding(vertical = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                fmtTime.format(Date(r.timestamp)),
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            )
                            val sourceMeta = ReportImport.sourceMeta(r.source)
                            Text(
                                sourceMeta.systemVersion.ifBlank { sourceMeta.format },
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
                        }
                    }
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
        Spacer(Modifier.height(20.dp))
    }
}
