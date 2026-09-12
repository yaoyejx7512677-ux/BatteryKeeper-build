package com.batterykeeper.app.report

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 在用户通过 SAF 授权的“错误报告 / MIUI/debug_log”目录中定位最新 Bugreport ZIP。
 *
 * Android 14+ 的分区存储不允许普通应用静默遍历 MIUI/debug_log；因此首次使用需要
 * 用户授权一次目录。授权会持久保存，后续可一键自动扫描最新 ZIP。
 */
object ReportFileLocator {

    private val nameDate = Regex("(\\d{4})-(\\d{2})-(\\d{2})[-_](\\d{2})[-_](\\d{2})(?:[-_](\\d{2}))?")

    /** 尽量让系统目录选择器从小米常见 Bugreport 路径开始。 */
    fun defaultBugReportTreeUri(): Uri =
        Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMIUI%2Fdebug_log")

    data class LocatedReport(
        val uri: Uri,
        val displayName: String,
        val modifiedTime: Long,
    )

    suspend fun findLatestBugReport(context: Context, treeUri: Uri): LocatedReport? =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: return@withContext null

            var visited = 0
            var best: LocatedReport? = null

            fun effectiveTime(name: String, modified: Long): Long {
                if (modified > 0L) return modified
                val m = nameDate.find(name) ?: return 0L
                val (y, mo, d, h, mi, sec) = m.destructured
                return runCatching {
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                        .parse("$y-$mo-$d $h:$mi:${sec.ifBlank { "00" }}")?.time ?: 0L
                }.getOrDefault(0L)
            }

            fun scan(documentId: String, depth: Int) {
                if (depth > MAX_DEPTH || visited >= MAX_DOCUMENTS) return
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
                val columns = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                )
                resolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val modifiedIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    while (cursor.moveToNext() && visited < MAX_DOCUMENTS) {
                        visited++
                        val id = cursor.getString(idIdx) ?: continue
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                        val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty() else ""
                        val modified = if (modifiedIdx >= 0 && !cursor.isNull(modifiedIdx)) cursor.getLong(modifiedIdx) else 0L

                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            scan(id, depth + 1)
                        } else if (name.endsWith(".zip", ignoreCase = true)) {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                            val t = effectiveTime(name, modified)
                            val priorityBoost = if (
                                name.contains("bugreport", true) ||
                                name.contains("debug", true) ||
                                name.contains("report", true)
                            ) 1L else 0L
                            val candidate = LocatedReport(docUri, name, t)
                            val current = best
                            val candidateScore = t * 2 + priorityBoost
                            val currentScore = current?.let {
                                effectiveTime(it.displayName, it.modifiedTime) * 2 +
                                    if (it.displayName.contains("bugreport", true) || it.displayName.contains("debug", true) || it.displayName.contains("report", true)) 1L else 0L
                            } ?: Long.MIN_VALUE
                            if (candidateScore > currentScore) best = candidate
                        }
                    }
                }
            }

            scan(rootId, 0)
            best
        }

    private const val MAX_DEPTH = 4
    private const val MAX_DOCUMENTS = 1200
}
