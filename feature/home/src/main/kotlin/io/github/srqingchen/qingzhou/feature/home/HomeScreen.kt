package io.github.srqingchen.qingzhou.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.srqingchen.qingzhou.core.designsystem.theme.QzBackground
import io.github.srqingchen.qingzhou.core.designsystem.theme.QzIcons
import io.github.srqingchen.qingzhou.core.model.Peer
import io.github.srqingchen.qingzhou.core.model.TaskState
import io.github.srqingchen.qingzhou.core.model.TransferDirection
import io.github.srqingchen.qingzhou.core.model.formatSize
import io.github.srqingchen.qingzhou.core.network.session.SessionManager
import io.github.srqingchen.qingzhou.core.network.transfer.FileTransferEngine
import io.github.srqingchen.qingzhou.core.network.transfer.TransferRepository
import io.github.srqingchen.qingzhou.feature.home.screens.ChatScreen
import io.github.srqingchen.qingzhou.feature.home.screens.DiscoverScreen
import io.github.srqingchen.qingzhou.feature.home.screens.InboxScreen
import io.github.srqingchen.qingzhou.feature.home.screens.SettingsScreen
import io.github.srqingchen.qingzhou.feature.home.screens.TransferScreen

/** 主功能分区索引（底部导航）。 */
enum class HomeSection(val label: String) {
    DISCOVER("发现"),
    TRANSFER("传输"),
    INBOX("收件箱"),
    SETTINGS("设置"),
}

/**
 * 单屏四分区：底部 NavigationBar 切换，分区内容各自持有状态。
 * 无导航库依赖（同尘露模式），分区索引 rememberSaveable 保持转屏/进程恢复。
 */
@Composable
fun HomeScreen() {
    var section by rememberSaveable { mutableIntStateOf(HomeSection.DISCOVER.ordinal) }
    val context = LocalContext.current
    var chatPeer by remember { mutableStateOf<Peer?>(null) }
    var chatToast by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        bottomBar = {
            NavigationBar {
                HomeSection.entries.forEachIndexed { index, s ->
                    NavigationBarItem(
                        selected = section == index,
                        onClick = { section = index },
                        icon = {
                            when (s) {
                                HomeSection.DISCOVER -> Icon(QzIcons.Radar, contentDescription = s.label)
                                HomeSection.TRANSFER -> Icon(QzIcons.Transfer, contentDescription = s.label)
                                HomeSection.INBOX -> Icon(QzIcons.Inbox, contentDescription = s.label)
                                HomeSection.SETTINGS -> Icon(QzIcons.Sliders, contentDescription = s.label)
                            }
                        },
                        label = { Text(s.label) },
                    )
                }
            }
        },
    ) { padding ->
        QzBackground(
            Modifier.padding(padding),
        ) {
            when (HomeSection.entries.getOrNull(section) ?: HomeSection.DISCOVER) {
                HomeSection.DISCOVER -> DiscoverScreen(onOpenChat = { chatPeer = it })
                HomeSection.TRANSFER -> TransferScreen()
                HomeSection.INBOX -> InboxScreen()
                HomeSection.SETTINGS -> SettingsScreen(context = context)
            }
        }
    }

    // 聊天全屏覆盖（类微信）：QzBackground 垫底保证不透出主页
    chatPeer?.let { peer ->
        QzBackground(Modifier.fillMaxSize()) {
            ChatScreen(
                peer = peer,
                onBack = { chatPeer = null },
                toast = { chatToast = it },
            )
        }
    }

    chatToast?.let { message ->
        LaunchedEffect(message) {
            kotlinx.coroutines.delay(2200)
            chatToast = null
        }
    }

    // 响应方配对请求弹窗（60s 内有效；通知栏亦可操作）
    val pending by SessionManager.pendingPairs.collectAsState()
    val responderPair = remember(pending) {
        pending.values.firstOrNull { !it.amInitiator && System.currentTimeMillis() - it.createdAt < 55_000 }
    }
    responderPair?.let { pair ->
        AlertDialog(
            onDismissRequest = { SessionManager.declinePair(pair.id) },
            title = { Text("配对请求") },
            text = {
                Text(
                    "「${pair.remoteName}」（${pair.remoteModel.ifEmpty { "未知机型" }}）请求配对。\n\n" +
                        "对方配对码 ${pair.remoteShortCode.take(3)} ${pair.remoteShortCode.takeLast(3)}\n" +
                        "请与对方屏幕上的配对码核对一致后接受。",
                )
            },
            confirmButton = {
                TextButton(onClick = { SessionManager.acceptPair(pair.id) }) { Text("接受") }
            },
            dismissButton = {
                TextButton(onClick = { SessionManager.declinePair(pair.id) }) { Text("拒绝") }
            },
        )
    }

    // 文件接收确认（已配对且开自动接收的设备自动跳过此弹窗）
    val tasks by TransferRepository.tasks.collectAsState()
    val pendingOffer = remember(tasks) {
        tasks.firstOrNull {
            it.state == TaskState.WAITING_MY_ACCEPT && it.direction == TransferDirection.RECV
        }
    }
    pendingOffer?.let { offer ->
        AlertDialog(
            onDismissRequest = { FileTransferEngine.declineOffer(offer.id) },
            title = { Text("接收文件") },
            text = {
                Text(
                    "${offer.peerName} 想发送 ${offer.files.size} 个文件（${formatSize(offer.totalBytes)}）：\n" +
                        offer.files.take(5).joinToString("\n") { "· ${it.name}" } +
                        if (offer.files.size > 5) "\n… 等共 ${offer.files.size} 个" else "",
                )
            },
            confirmButton = {
                TextButton(onClick = { FileTransferEngine.acceptOffer(offer.id) }) { Text("接收") }
            },
            dismissButton = {
                TextButton(onClick = { FileTransferEngine.declineOffer(offer.id) }) { Text("拒绝") }
            },
        )
    }
}
