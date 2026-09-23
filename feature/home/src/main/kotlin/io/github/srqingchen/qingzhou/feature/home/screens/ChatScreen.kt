package io.github.srqingchen.qingzhou.feature.home.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.srqingchen.qingzhou.core.data.ChatStore
import io.github.srqingchen.qingzhou.core.designsystem.theme.QzAccent
import io.github.srqingchen.qingzhou.core.model.Peer
import io.github.srqingchen.qingzhou.core.network.session.SessionManager
import io.github.srqingchen.qingzhou.core.network.transfer.FileTransferEngine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天界面（类微信）：消息气泡流 + 文本输入 + 附件发送。
 * 文本走微消息/会话（毫秒级），文件走分块多流传输引擎。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(peer: Peer, onBack: () -> Unit, toast: (String) -> Unit = {}) {
    val chats by ChatStore.chats.collectAsState()
    val messages = chats[peer.fingerprint].orEmpty()
    // 长按气泡菜单：删除 / 复制 / 以 TXT 转发给其他已配对设备
    var actionMsg by remember { mutableStateOf<ChatStore.ChatMessage?>(null) }
    var forwardMsg by remember { mutableStateOf<ChatStore.ChatMessage?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }

    // 系统返回手势与顶栏返回一致
    androidx.activity.compose.BackHandler(onBack = onBack)

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // 系统栏避让：状态栏/导航键不再遮挡，键盘弹出时输入区随之上移
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding(),
    ) {
        // 顶栏
        Surface(color = Color(0xE610201F), contentColor = Color.White) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Column(Modifier.weight(1f)) {
                    Text(peer.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text(
                        if (peer.paired) "已配对 · ${peer.model.ifEmpty { "在线" }}" else "未配对",
                        fontSize = 11.sp,
                        color = Color(0xB3FFFFFF),
                    )
                }
            }
        }

        // 消息流
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                Bubble(msg, onLongPress = { actionMsg = msg })
            }
            item {
                if (messages.isEmpty()) {
                    Text(
                        "与 ${peer.name} 的加密会话\n发一段文字，毫秒级直达对方",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
        }

        // 输入栏
        Surface(color = Color(0xE60D1B20), contentColor = Color.White) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                var sending by remember { mutableStateOf(false) }
                val filePicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetMultipleContents(),
                ) { uris ->
                    if (uris.isNotEmpty()) {
                        val p = peer
                        val placeholder = ChatStore.addFile(p.fingerprint, mine = true, name = "…", pending = true)
                        toast("正在发送 ${uris.size} 个文件…")
                        FileTransferEngine.submitSend(p, uris) { result ->
                            ChatStore.settleFile(p.fingerprint, placeholder.id, result.isSuccess)
                            toast(result.fold({ "文件已发送" }, { it.message ?: "发送失败" }))
                        }
                    }
                }
                IconButton(
                    enabled = !sending,
                    onClick = { filePicker.launch("*/*") },
                ) {
                    Icon(Icons.Filled.AddCircle, contentDescription = "发送文件", tint = QzAccent)
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { if (it.length <= 8000) input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("消息…", color = Color(0x80FFFFFF)) },
                    maxLines = 4,
                    shape = RoundedCornerShape(22.dp),
                )
                IconButton(
                    enabled = input.isNotBlank(),
                    onClick = {
                        val text = input
                        input = ""
                        val p = peer
                        ChatStore.addText(p.fingerprint, mine = true, content = text)
                        io.github.srqingchen.qingzhou.core.common.QzWorkScope.scope.launch {
                            val result = SessionManager.sendText(p, text)
                            result.onFailure {
                                ChatStore.addText(p.fingerprint, mine = false, content = "⚠ 发送失败：${it.message}")
                            }
                        }
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送", tint = QzAccent)
                }
            }
        }
    }

    // 长按气泡操作单
    actionMsg?.let { msg ->
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        ModalBottomSheet(onDismissRequest = { actionMsg = null }) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    msg.content.take(40),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(msg.content))
                    actionMsg = null
                }) { Text("复制") }
                if (msg.kind == "text") {
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        forwardMsg = msg
                        actionMsg = null
                    }) { Text("以 TXT 转发给…") }
                }
                TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                    ChatStore.removeMessage(peer.fingerprint, msg.id)
                    actionMsg = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    // 转发目标选择（其他已配对且在线的设备）
    forwardMsg?.let { msg ->
        val peers by io.github.srqingchen.qingzhou.core.network.discovery.DiscoveryEngine.peers.collectAsState()
        val targets = peers.filter { it.paired && it.fingerprint != peer.fingerprint }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { forwardMsg = null },
            title = { Text("转发给") },
            text = {
                if (targets.isEmpty()) {
                    Text("暂无其他在线的已配对设备")
                } else {
                    Column {
                        targets.take(6).forEach { t ->
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    val target = t
                                    val content = "[转自发件人] " + msg.content
                                    forwardMsg = null
                                    io.github.srqingchen.qingzhou.core.common.QzWorkScope.scope.launch {
                                        SessionManager.sendText(target, content)
                                        ChatStore.addText(target.fingerprint, mine = true, content = content)
                                    }
                                    toast("已转发给 ${target.name}")
                                },
                            ) { Text("${t.name}（${t.model.ifEmpty { "在线" }}）") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { forwardMsg = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(msg: ChatStore.ChatMessage, onLongPress: () -> Unit = {}) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(msg.timestamp))
    Box(Modifier.fillMaxWidth()) {
        val shape = RoundedCornerShape(
            topStart = 16.dp, topEnd = 16.dp,
            bottomStart = if (msg.mine) 16.dp else 4.dp,
            bottomEnd = if (msg.mine) 4.dp else 16.dp,
        )
        val bubble: @Composable (Color, Color) -> Unit = { bg, fg ->
            Surface(
                color = bg,
                shape = shape,
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).sizeIn(maxWidth = 280.dp)) {
                    if (msg.kind == "file") {
                        Text(
                            "📎 " + msg.content + if (msg.pending) "（发送中…）" else "",
                            color = fg,
                            fontSize = 15.sp,
                        )
                    } else {
                        Text(msg.content, color = fg, fontSize = 15.sp)
                    }
                    Text(time, fontSize = 10.sp, color = fg.copy(alpha = 0.55f))
                }
            }
        }
        if (msg.mine) {
            Box(Modifier.align(Alignment.CenterEnd)) {
                bubble(Color(0xFF00606B), Color.White)
            }
        } else {
            Box(Modifier.align(Alignment.CenterStart)) {
                bubble(Color(0xE6FFFFFF), Color(0xFF10201F))
            }
        }
    }
}
