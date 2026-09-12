package com.batterykeeper.app.ui.screens

import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batterykeeper.app.data.HealthReport
import com.batterykeeper.app.report.ReportFileLocator
import com.batterykeeper.app.report.ReportImport
import com.batterykeeper.app.ui.BatteryViewModel
import com.batterykeeper.app.ui.components.*
import com.batterykeeper.app.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ReportsScreen(vm: BatteryViewModel) {
    val reports by vm.reports.collectAsState()
    val snapshot by vm.latest.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<List<HealthReport>>(emptyList()) }
    var deleteId by remember { mutableStateOf<Long?>(null) }

    fun importBug(uri: android.net.Uri) {
        scope.launch {
            busy = true
            vm.importBugReport(context, uri)
                .onSuccess { pending = listOf(it); message = null }
                .onFailure { message = "导入失败：${it.message}" }
            busy = false
        }
    }

    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let { importBug(it) }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            vm.importScreenshotReports(context, uri)
                .onSuccess { pending = it; message = null }
                .onFailure { message = "图片识别失败：${it.message}" }
            busy = false
        }
    }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            vm.settings.reportFolderUri = uri.toString()
            scope.launch {
                busy = true
                val latest = ReportFileLocator.findLatestBugReport(context, uri)
                if (latest == null) message = "该目录下没有找到 ZIP 错误报告"
                else importBug(latest.uri)
                busy = false
            }
        }
    }

    if (pending.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { if (!busy) pending = emptyList() },
            title = { Text(if (pending.size > 1) "识别到 ${pending.size} 条记录" else "核对识别结果") },
            text = {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    pending.sortedByDescending { it.timestamp }.forEach { r ->
                        val meta = ReportImport.sourceMeta(r.source)
                        GlassCard {
                            Text(BatteryViewModel.fmtDate(r.timestamp), fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                MetricCompact(if (r.healthPct > 0) "%.2f".format(r.healthPct) else "—", "%", "健康度", Green)
                                MetricCompact(r.fullChargeMah.takeIf { it > 0 }?.toString() ?: "—", "mAh", "满充容量")
                                MetricCompact(r.cycleCount.takeIf { it >= 0 }?.toString() ?: "—", "次", "循环次数", Orange)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(meta.format, fontSize = 10.5.sp, color = TxtSecondary)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        runCatching {
                            if (pending.size == 1) {
                                vm.saveReport(pending.first()); 1 to 0
                            } else vm.saveReports(pending)
                        }.onSuccess { (saved, skipped) ->
                            message = "✓ 已保存 $saved 条" + if (skipped > 0) "，跳过重复 $skipped 条" else ""
                            pending = emptyList()
                        }.onFailure { message = "保存失败：${it.message}" }
                        busy = false
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { pending = emptyList() }) { Text("取消") } },
        )
    }

    if (deleteId != null) {
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("删除此报告？") },
            text = { Text("删除后不再用于健康趋势。") },
            confirmButton = { TextButton(onClick = { vm.deleteReport(deleteId!!); deleteId = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } },
        )
    }

    val latest = reports.lastOrNull()
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA) }
    val totalCycles = vm.displayCycleCount(
        snapshot?.cycleCount ?: -1,
        reports.lastOrNull { it.cycleCount >= 0 }?.cycleCount,
    )

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("电池报告", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("HyperOS 3 / 4 · ZIP 与图片识别", fontSize = 11.5.sp, color = TxtSecondary)
            }
        }

        GlassCard {
            CardTitle("最新健康报告", badge = latest?.let { fmt.format(Date(it.timestamp)) } ?: "暂无数据")
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MetricCompact(latest?.healthPct?.takeIf { it > 0 }?.let { "%.2f".format(it) } ?: "—", "%", "健康度", Green)
                MetricCompact(latest?.fullChargeMah?.takeIf { it > 0 }?.toString() ?: "—", "mAh", "满充容量")
                MetricCompact(latest?.cycleCount?.takeIf { it >= 0 }?.toString() ?: "—", "次", "循环次数", Orange)
            }
            latest?.let {
                val meta = ReportImport.sourceMeta(it.source)
                Spacer(Modifier.height(12.dp))
                Text(meta.format + if (meta.confidence != "—" && meta.confidence.isNotBlank()) " · 置信度 ${meta.confidence}" else "", fontSize = 10.5.sp, color = TxtSecondary)
            }
        }

        GlassCard {
            CardTitle("导入报告", badge = "${reports.size} 条")
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val saved = vm.settings.reportFolderUri
                    if (saved.isBlank()) {
                        treePicker.launch(ReportFileLocator.defaultBugReportTreeUri())
                    } else {
                        val tree = android.net.Uri.parse(saved)
                        scope.launch {
                            busy = true
                            val latestFile = runCatching { ReportFileLocator.findLatestBugReport(context, tree) }.getOrNull()
                            if (latestFile == null) message = "未找到最新错误报告，可重新授权目录"
                            else importBug(latestFile.uri)
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FF8A3D), contentColor = Orange),
            ) { Text("自动查找最新错误报告") }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        zipPicker.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                        })
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("手动选 ZIP", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("从相册导入", fontSize = 12.sp) }
            }
            if (vm.settings.reportFolderUri.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { treePicker.launch(ReportFileLocator.defaultBugReportTreeUri()) }) {
                    Text("重新选择错误报告目录", fontSize = 11.sp)
                }
            }
            if (busy) Text("正在读取和识别…", fontSize = 11.sp, color = TxtSecondary)
            message?.let { Text(it, fontSize = 11.sp, color = if (it.startsWith("✓")) Green else Color(0xFFF87171)) }
        }

        GlassCard {
            CardTitle("累计循环", badge = if (totalCycles >= 0) "$totalCycles 次" else "待积累")
            if (reports.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("导入 ZIP 或电池健康报告截图后显示历史。", color = TxtSecondary, fontSize = 12.sp)
            } else {
                Spacer(Modifier.height(8.dp))
                reports.asReversed().take(12).forEach { r ->
                    val meta = ReportImport.sourceMeta(r.source)
                    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(fmt.format(Date(r.timestamp)), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                            Text(meta.format, fontSize = 10.sp, color = TxtSecondary)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            MetricCompact(if (r.healthPct > 0) "%.2f".format(r.healthPct) else "—", "%", "健康度", Green)
                            MetricCompact(r.fullChargeMah.takeIf { it > 0 }?.toString() ?: "—", "mAh", "满充容量")
                            MetricCompact(r.cycleCount.takeIf { it >= 0 }?.toString() ?: "—", "次", "循环次数", Orange)
                        }
                        TextButton(onClick = { deleteId = r.id }, contentPadding = PaddingValues(0.dp)) {
                            Text("删除", fontSize = 10.5.sp, color = TxtSecondary)
                        }
                    }
                    HorizontalDivider(color = Color(0x18FFFFFF))
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
