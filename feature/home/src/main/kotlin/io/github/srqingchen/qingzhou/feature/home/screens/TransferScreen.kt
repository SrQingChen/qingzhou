package io.github.srqingchen.qingzhou.feature.home.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.srqingchen.qingzhou.core.designsystem.theme.QzAccent
import io.github.srqingchen.qingzhou.core.designsystem.theme.QzGlassCard
import io.github.srqingchen.qingzhou.core.model.TaskState
import io.github.srqingchen.qingzhou.core.model.TransferTask
import io.github.srqingchen.qingzhou.core.model.formatSize
import io.github.srqingchen.qingzhou.core.model.formatSpeed
import io.github.srqingchen.qingzhou.core.network.transfer.TransferRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 传输分区：进行中任务（进度环/速率/剩余时间）+ 历史。 */
@Composable
fun TransferScreen(modifier: Modifier = Modifier) {
    val tasks by TransferRepository.tasks.collectAsState()
    val active = tasks.filter { it.state != TaskState.DONE && it.state != TaskState.DECLINED && it.state != TaskState.CANCELLED && it.state != TaskState.FAILED }
    val history = tasks.filter { it !in active }
    var chartTask by remember { mutableStateOf<TransferTask?>(null) }

    LazyColumn(
        modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (active.isEmpty() && history.isEmpty()) {
            item {
                QzGlassCard {
                    Text("传输任务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "暂无任务\n在「发现」页选择设备，可发送文本或文件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(active, key = { it.id }) { task ->
            TaskCard(task, activeCard = true, onClick = { chartTask = task })
        }
        if (history.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "历史（${history.size}）· 点击查看速率曲线",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { TransferRepository.clearHistory() }) { Text("清空历史") }
                }
            }
            items(history.take(30), key = { it.id }) { task ->
                TaskCard(
                    task,
                    activeCard = false,
                    onClick = { chartTask = task },
                    onDelete = { TransferRepository.remove(task.id) },
                )
            }
        }
    }

    // 速率折线图（点任意任务卡查看该次传输的速率波动）
    chartTask?.let { task ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { chartTask = null },
            title = {
                Text(
                    "${if (task.direction == io.github.srqingchen.qingzhou.core.model.TransferDirection.SEND) "发给" else "来自"} ${task.peerName}",
                )
            },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(
                        "${task.files.size} 个文件 · ${formatSize(task.totalBytes)} · ${task.state.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    task.files.take(6).forEach { Text("· ${it.name}", style = MaterialTheme.typography.bodySmall) }
                    SpeedChart(
                        series = task.speedSeries,
                        durationMs = (task.endTime ?: System.currentTimeMillis()) - task.startTime,
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { chartTask = null }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun TaskCard(
    task: TransferTask,
    activeCard: Boolean,
    onClick: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    QzGlassCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${if (task.direction == io.github.srqingchen.qingzhou.core.model.TransferDirection.SEND) "发给" else "来自"} ${task.peerName}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${task.files.size} 个文件 · ${formatSize(task.totalBytes)}" +
                        (task.error?.let { " · ⚠ $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                task.state.label,
                style = MaterialTheme.typography.labelLarge,
                color = when (task.state) {
                    TaskState.DONE -> QzAccent
                    TaskState.FAILED, TaskState.DECLINED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                },
            )
        }
        if (activeCard || task.state == TaskState.DONE) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { task.progress / 100f },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "  ${task.progress}%",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (task.state == TaskState.RUNNING || task.state == TaskState.VERIFYING) {
                Text(
                    buildString {
                        append(formatSpeed(task.speedBps))
                        append(" · ${formatSize(task.bytesDone)} / ${formatSize(task.totalBytes)}")
                        if (task.etaMs > 0) append(" · 剩余 ${formatEta(task.etaMs)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!activeCard && task.endTime != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(task.endTime!!)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

private fun formatEta(ms: Long): String {
    val sec = ms / 1000
    return when {
        sec >= 60 -> "${sec / 60} 分 ${sec % 60} 秒"
        else -> "$sec 秒"
    }
}
