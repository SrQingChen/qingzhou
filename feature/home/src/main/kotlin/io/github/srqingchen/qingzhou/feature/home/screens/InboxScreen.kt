package io.github.srqingchen.qingzhou.feature.home.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.srqingchen.qingzhou.core.data.InboxItem
import io.github.srqingchen.qingzhou.core.data.InboxKind
import io.github.srqingchen.qingzhou.core.data.InboxStore
import io.github.srqingchen.qingzhou.core.designsystem.theme.QzAccent
import io.github.srqingchen.qingzhou.core.designsystem.theme.QzGlassCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 收件箱分区：接收的文本/链接（M2 增加文件）。 */
@Composable
fun InboxScreen(modifier: Modifier = Modifier) {
    val items by InboxStore.items.collectAsState()
    val clipboard = LocalClipboardManager.current

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "收件箱（${items.size}）",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (items.isNotEmpty()) {
                TextButton(onClick = { InboxStore.clear() }) { Text("清空") }
            }
        }
        if (items.isEmpty()) {
            QzGlassCard {
                Text(
                    "暂无内容\n接收的文本与链接会出现在这里，可一键复制",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items, key = { it.id }) { item ->
                    InboxCard(item, onCopy = { clipboard.setText(AnnotatedString(item.content)) }, onDelete = {
                        InboxStore.remove(item.id)
                    })
                }
            }
        }
    }
}

@Composable
private fun InboxCard(item: InboxItem, onCopy: () -> Unit, onDelete: () -> Unit) {
    QzGlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.fromName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${item.kind.label} · ${
                    SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(item.timestamp))
                }",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (item.kind == InboxKind.FILE) item.content else item.content,
            style = MaterialTheme.typography.bodyMedium,
            color = if (item.kind == InboxKind.LINK) QzAccent else MaterialTheme.colorScheme.onSurface,
            maxLines = 6,
        )
        if (item.kind == InboxKind.FILE) {
            item.filePath?.let {
                Text(
                    "保存于 $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (item.kind == InboxKind.FILE) {
                val context = androidx.compose.ui.platform.LocalContext.current
                TextButton(onClick = { openFile(context, item) }) { Text("打开") }
                TextButton(onClick = { revealInFileManager(context, item) }) { Text("所在文件夹") }
            }
            TextButton(onClick = onCopy) { Text(if (item.kind == InboxKind.FILE) "复制路径" else "复制") }
            TextButton(onClick = onDelete) { Text("删除") }
        }
    }
}

/**
 * 在文件管理器中定位接收目录：依次尝试 Download/QingZhou → Download 根。
 * 不同 ROM 文件管理器对 DocumentsContract 的支持不一，全部 try-catch 逐级降级。
 */
private fun revealInFileManager(context: android.content.Context, item: InboxItem) {
    if (item.filePath == null) return
    val docRoots = listOf("primary:Download/QingZhou", "primary:Download")
    for (root in docRoots) {
        val uri = android.provider.DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            root,
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val ok = runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
        if (ok) return
    }
    android.widget.Toast.makeText(
        context,
        "此系统文件管理器不支持直接定位，文件在 Download/QingZhou 目录",
        android.widget.Toast.LENGTH_LONG,
    ).show()
}

/** 打开接收的文件：优先系统看图/播放器（content URI），旧路径走 FileProvider。 */
private fun openFile(context: android.content.Context, item: InboxItem) {
    runCatching {
        val uri: android.net.Uri = item.filePath?.let { path ->
            if (path.startsWith("content://")) {
                android.net.Uri.parse(path)
            } else {
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    context.packageName + ".fileprovider",
                    java.io.File(path),
                )
            }
        } ?: return
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.fileHash?.let { "*/*" } ?: "*/*")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }.onFailure {
        android.widget.Toast.makeText(context, "无法打开：${it.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}
