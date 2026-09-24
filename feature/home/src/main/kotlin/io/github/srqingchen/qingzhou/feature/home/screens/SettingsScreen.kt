package io.github.srqingchen.qingzhou.feature.home.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import io.github.srqingchen.qingzhou.core.data.PerfMode
import io.github.srqingchen.qingzhou.core.data.SettingsStore
import io.github.srqingchen.qingzhou.core.designsystem.theme.QzAccent
import io.github.srqingchen.qingzhou.core.designsystem.theme.QzGlassCard
import io.github.srqingchen.qingzhou.core.shizuku.QzShizukuManager
import io.github.srqingchen.qingzhou.core.shizuku.QzShizukuState
import io.github.srqingchen.qingzhou.service.TransferCenter

/** 设置分区：传输/发现/安全/超级岛/Shizuku/存储/诊断/关于。 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, context: android.content.Context) {
    val settings by SettingsStore.settings.collectAsState()
    val receiving by TransferCenter.receiving.collectAsState()
    val shizukuState by QzShizukuManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    var diag by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            QzGlassCard {
                Text("本机", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = settings.displayName,
                    onValueChange = { name ->
                        SettingsStore.update { it.copy(displayName = name.take(24)) }
                    },
                    label = { Text("设备名（对方看到的名字）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        }

        item {
            QzGlassCard {
                Text("链路加速", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                SettingSwitch(
                    title = "USB 有线并行（实验）",
                    subtitle = "USB-C 连接两台设备时，WiFi 之外叠加 USB 通道同时传输；不可用自动回退纯 WiFi",
                    checked = settings.usbEnabled,
                    onChecked = { v -> SettingsStore.update { it.copy(usbEnabled = v) } },
                )
                SettingSwitch(
                    title = "USB 网络链路 NCM（需 Shizuku）",
                    subtitle = "经 Shizuku 把 USB 切为 NCM 网卡（USB3 机型可远超 AOA），多流并入并行；仅与对端协商确认后启用，拔线自动还原",
                    checked = settings.usbNetEnabled,
                    onChecked = { v -> SettingsStore.update { it.copy(usbNetEnabled = v) } },
                )
            }
        }

        item {
            QzGlassCard {
                Text("性能偏好", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "影响发现频率与耗电；传输时始终自动进入高性能",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PerfMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = settings.perfMode == mode,
                            onClick = { SettingsStore.update { it.copy(perfMode = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(index, PerfMode.entries.size),
                        ) {
                            Text(mode.label)
                        }
                    }
                }
            }
        }

        item {
            QzGlassCard {
                Text("发现与安全", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                SettingSwitch(
                    title = "允许被发现",
                    subtitle = "关闭后不主动广播，但仍可回应定向探测",
                    checked = settings.discoverable,
                    onChecked = { v -> SettingsStore.update { it.copy(discoverable = v) } },
                )
                SettingSwitch(
                    title = "自动接收文本",
                    subtitle = "已配对设备发来的文本直接入库（文件始终需要确认）",
                    checked = settings.autoReceiveText,
                    onChecked = { v -> SettingsStore.update { it.copy(autoReceiveText = v) } },
                )
            }
        }

        item {
            QzGlassCard {
                Text("接收服务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                SettingSwitch(
                    title = "后台接收守护",
                    subtitle = if (receiving) "正在运行：息屏也能被发现与接收" else "已停止：应用退出后无法被发现",
                    checked = receiving,
                    onChecked = { v ->
                        if (v) TransferCenter.start(context) else TransferCenter.stop(context)
                    },
                )
            }
        }

        item {
            QzGlassCard {
                Text("超级岛（澎湃 OS）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val protocol = remember { io.github.srqingchen.qingzhou.service.island.FocusIslandPublisher.focusProtocol(context) }
                Text(
                    when (protocol) {
                        3 -> "本机支持 OS3 超级岛 · 传输进度将上岛"
                        2 -> "本机支持 OS2 焦点通知"
                        1 -> "本机为 OS1 焦点通知（旧模板）"
                        else -> "本机不支持焦点通知 · 已降级为进度常驻通知"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (protocol >= 2) QzAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingSwitch(
                    title = "传输进度上岛",
                    subtitle = "传输时以岛/焦点通知/进度通知实时展示",
                    checked = settings.islandEnabled,
                    onChecked = { v -> SettingsStore.update { it.copy(islandEnabled = v) } },
                )
                SettingSwitch(
                    title = "兼容模式（需 Shizuku）",
                    subtitle = "上岛期间临时切断小米服务框架联网绕过白名单，期间小米推送会延迟；收岛即恢复",
                    checked = settings.islandCompat,
                    onChecked = { v -> SettingsStore.update { it.copy(islandCompat = v) } },
                )
            }
        }

        item {
            QzGlassCard {
                Text("Shizuku 增强", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    when (val st = shizukuState) {
                        is QzShizukuState.Ready -> "已连接 —— 兼容模式/链路体检可用"
                        is QzShizukuState.AwaitingPermission -> "已检测到 Shizuku，等待授权"
                        is QzShizukuState.NotInstalled -> "未安装：链路体检与超级岛兼容模式不可用（可选增强）"
                        is QzShizukuState.NotRunning -> "Shizuku 未运行：重启手机后需手动打开"
                        is QzShizukuState.Connecting -> "连接中…"
                        is QzShizukuState.Failed -> "失败：${st.reason}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (shizukuState is QzShizukuState.Ready) QzAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        QzShizukuManager.refresh()
                        QzShizukuManager.requestPermission()
                    }) { Text("连接/授权") }
                    TextButton(onClick = { QzShizukuManager.openShizukuApp(context) }) { Text("打开 Shizuku") }
                    TextButton(onClick = {
                        scope.launch {
                            val result = QzShizukuManager.execShell(8, "dumpsys", "wifi")
                            diag = result?.output?.take(4000)
                                ?: "Shizuku 未连接（dumpsys wifi 体检需要它）"
                        }
                    }) { Text("链路体检") }
                }
            }
        }

        item {
            QzGlassCard {
                Text("日志与诊断", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "完整：调试及以上全落盘（默认）；仅警告：只记警告与错误；关闭：仅内存不落盘",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("FULL" to "完整", "WARN" to "仅警告", "OFF" to "关闭").forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = settings.logMode == mode,
                            onClick = {
                                val m = runCatching { io.github.srqingchen.qingzhou.core.common.QzLog.Mode.valueOf(mode) }
                                    .getOrDefault(io.github.srqingchen.qingzhou.core.common.QzLog.Mode.FULL)
                                io.github.srqingchen.qingzhou.core.common.QzLog.setMode(m)
                                SettingsStore.update { it.copy(logMode = mode) }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, 3),
                        ) {
                            Text(label)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val file = io.github.srqingchen.qingzhou.core.common.QzLog.exportFile()
                        if (file != null) {
                            runCatching {
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context, context.packageName + ".fileprovider", file,
                                )
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    android.content.Intent.createChooser(intent, "分享青舟日志"),
                                )
                            }.onFailure { diag = "分享失败：${it.message}" }
                        } else {
                            diag = "暂无日志文件"
                        }
                    }) { Text("导出并分享日志") }
                    TextButton(onClick = {
                        diag = io.github.srqingchen.qingzhou.core.common.QzLog.dump().take(8000).ifEmpty { "暂无日志" }
                    }) { Text("查看最近日志") }
                }
            }
        }

        item {
            QzGlassCard {
                Text("关于青舟", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "作者 SrQingChen · 局域网/热点极速安全互传\nNoise 端到端加密 · 澎湃 OS 1/2/3 适配",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    diag?.let { text ->
        AlertDialog(
            onDismissRequest = { diag = null },
            title = { Text("链路体检（dumpsys wifi 摘要）") },
            text = {
                Text(
                    extractLinkInfo(text),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 18,
                )
            },
            confirmButton = { TextButton(onClick = { diag = null }) { Text("关闭") } },
        )
    }
}

/** 从 dumpsys wifi 输出中抽取关键链路信息。 */
private fun extractLinkInfo(raw: String): String {
    val interesting = listOf(
        "mWifiInfo", "SSID", "BSSID", "frequency", "linkSpeed", "RxLinkSpeed",
        "score", "supplicant", "Band", "channel",
    )
    return raw.lineSequence()
        .filter { line -> interesting.any { line.contains(it, ignoreCase = true) } }
        .take(24)
        .joinToString("\n")
        .ifEmpty { raw.take(1500) }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
