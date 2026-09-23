package io.github.srqingchen.qingzhou.feature.home.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import io.github.srqingchen.qingzhou.core.crypto.IdentityStore
import io.github.srqingchen.qingzhou.core.data.PairedDeviceStore
import io.github.srqingchen.qingzhou.core.data.SettingsStore
import io.github.srqingchen.qingzhou.core.designsystem.theme.QzAccent
import io.github.srqingchen.qingzhou.core.designsystem.theme.QzGlassCard
import io.github.srqingchen.qingzhou.core.model.Peer
import io.github.srqingchen.qingzhou.core.network.discovery.DiscoveryEngine
import io.github.srqingchen.qingzhou.core.network.session.SessionManager
import io.github.srqingchen.qingzhou.core.network.transfer.FileTransferEngine
import io.github.srqingchen.qingzhou.core.network.p2p.HotspotHelper
import io.github.srqingchen.qingzhou.core.network.p2p.WifiDirectHelper
import android.Manifest
import android.os.Build
import kotlinx.coroutines.launch

/** 发现分区：本机状态 + 设备列表 + 发送入口。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(modifier: Modifier = Modifier, onOpenChat: (io.github.srqingchen.qingzhou.core.model.Peer) -> Unit = {}) {
    val peers by DiscoveryEngine.peers.collectAsState()
    val link by DiscoveryEngine.link.collectAsState()
    val settings by SettingsStore.settings.collectAsState()
    val p2p by WifiDirectHelper.state.collectAsState()
    val blePeers by io.github.srqingchen.qingzhou.core.network.ble.BleBeacon.peers.collectAsState()
    val share by io.github.srqingchen.qingzhou.core.data.ShareInbox.pending.collectAsState()
    val identity = remember { IdentityStore.peek() }
    var pairing by remember { mutableStateOf<Peer?>(null) }
    var selected by remember { mutableStateOf<Peer?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // 记录“选完文件发给谁”。文件选择器必须注册在屏幕顶层：
    // 若注册在弹窗内且点按钮时先关弹窗，launcher 会随组合销毁而反注册，
    // 选择结果被静默丢弃 —— 真机“发送文件后完全无反应”的根因。
    var pickerPeer by remember { mutableStateOf<Peer?>(null) }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        val p = pickerPeer
        pickerPeer = null
        if (uris.isNotEmpty() && p != null) {
            selected = null
            toast = "正在准备 ${uris.size} 个文件…"
            FileTransferEngine.submitSend(p, uris) { result ->
                toast = result.fold(
                    { "已发送完成 ${p.name}" },
                    { it.message ?: "发送失败" },
                )
            }
        }
    }
    val p2pPerms = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) WifiDirectHelper.discover()
    }

    LaunchedEffect(Unit) { DiscoveryEngine.probeNow() }

    LazyColumn(
        modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            QzGlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            settings.displayName.ifEmpty { "青舟·${android.os.Build.MODEL}" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            when {
                                link.hotspotActive -> "热点已开启 · ${link.ownAddress ?: "--"}（对端可直连你）"
                                link.wifiConnected -> "WiFi 已连接 · ${link.ownAddress ?: "--"}"
                                else -> "未连接 WiFi/热点 —— 连接后自动发现附近设备"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (link.ready) QzAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        identity?.let {
                            Text(
                                "配对码 ${it.shortCode.take(3)} ${it.shortCode.takeLast(3)}",
                                style = MaterialTheme.typography.headlineSmall,
                                color = QzAccent,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                    IconButton(onClick = { scope.launch { DiscoveryEngine.probeNow() } }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "立即发现")
                    }
                }
            }
        }

        // 系统分享进来的待发送内容横幅
        if (share != null) {
            item(key = "share_banner") {
                val sp = share!!
                QzGlassCard {
                    Text("待分享到青舟设备", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        sp.summary + (sp.fromApp?.let { " · 来自 $it" } ?: "") +
                            "，点击下方已配对设备即可发送",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { io.github.srqingchen.qingzhou.core.data.ShareInbox.clear() }) {
                        Text("取消")
                    }
                }
            }
        }

        item {
            Text(
                "附近的设备（${peers.size}）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 无网直连分区（未连 WiFi/热点时显示）
        if (!link.ready) {
            item {
                val context = androidx.compose.ui.platform.LocalContext.current
                var hotspotInfo by remember { mutableStateOf<HotspotHelper.HotspotInfo?>(null) }
                QzGlassCard {
                    Text("没有网络？自建直连", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            HotspotHelper.start(context) { result ->
                                hotspotInfo = result.getOrNull()
                                toast = result.fold(
                                    { "热点已开启：${it.ssid}，请对方连接后自动发现" },
                                    { it.message ?: "开启失败" },
                                )
                            }
                        }) { Text("创建热点") }
                        Button(onClick = {
                            val needed = if (Build.VERSION.SDK_INT >= 33) {
                                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
                            } else {
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                            p2pPerms.launch(needed)
                        }) { Text("WiFi 直连") }
                    }
                    hotspotInfo?.let {
                        Text(
                            "已开启「${it.ssid}」—— 对方在系统 WiFi 列表连接此网络即可",
                            style = MaterialTheme.typography.bodySmall,
                            color = QzAccent,
                        )
                    }
                    if (p2p.connected) {
                        Text(
                            "WiFi 直连已组网（${if (p2p.isGroupOwner) "本机为主机" else "已连接对方"}）",
                            style = MaterialTheme.typography.bodySmall,
                            color = QzAccent,
                        )
                    } else if (p2p.peers.isNotEmpty()) {
                        Text(
                            "P2P 设备（点击连接）：",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        p2p.peers.take(5).forEach { p2pPeer ->
                            TextButton(onClick = { WifiDirectHelper.connect(p2pPeer.address) }) {
                                Text("· ${p2pPeer.name}${if (p2pPeer.isQingZhou) "（青舟）" else ""}")
                            }
                        }
                    }
                }
            }
        }

        if (peers.isEmpty()) {
            item {
                QzGlassCard {
                    Text(
                        "正在搜索…\n两台设备接入同一 WiFi 或热点后，1 秒内互相可见",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (blePeers.isNotEmpty()) {
                        Text(
                            "蓝牙感知到 ${blePeers.size} 台青舟设备（${blePeers.values.joinToString { it.name }}）—— 连入同一网络即可互传",
                            style = MaterialTheme.typography.bodySmall,
                            color = QzAccent,
                        )
                    }
                }
            }
        } else {
            items(peers, key = { it.fingerprint }) { peer ->
                PeerCard(peer) {
                    val sp = io.github.srqingchen.qingzhou.core.data.ShareInbox.pending.value
                    when {
                        sp != null && peer.paired && sp.isFiles -> {
                            io.github.srqingchen.qingzhou.core.data.ShareInbox.clear()
                            toast = "正在发送分享内容…"
                            FileTransferEngine.submitSend(peer, sp.uris) { r ->
                                toast = r.fold({ "已发送完成" }, { it.message ?: "发送失败" })
                            }
                        }

                        sp != null && peer.paired && sp.isText -> {
                            io.github.srqingchen.qingzhou.core.data.ShareInbox.clear()
                            val content = sp.text.orEmpty()
                            io.github.srqingchen.qingzhou.core.common.QzWorkScope.scope.launch {
                                val r = SessionManager.sendText(peer, content)
                                toast = r.fold({ "已送达" }, { it.message ?: "发送失败" })
                            }
                        }

                        peer.paired -> selected = peer
                        else -> pairing = peer
                    }
                }
            }
        }
    }

    // 设备小窗：发消息（进聊天）/ 发送文件 / 取消配对
    selected?.let { peer ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(peer.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${peer.model.ifEmpty { "未知机型" }} · 配对码 ${peer.shortCode.take(3)} ${peer.shortCode.takeLast(3)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        selected = null
                        onOpenChat(peer)
                    },
                ) { Text("发消息（聊天）") }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        pickerPeer = peer // 弹窗保持打开直到选择结果回来（回调里再关）
                        filePicker.launch("*/*")
                    },
                ) { Text("发送文件") }
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        PairedDeviceStore.remove(peer.fingerprint)
                        selected = null
                        toast = "已取消与 ${peer.name} 的配对"
                    },
                ) { Text("取消配对") }
            }
        }
    }

    // 配对确认（未配对设备首次发送）
    pairing?.let { peer ->
        AlertDialog(
            onDismissRequest = { pairing = null },
            title = { Text("配对确认") },
            text = {
                Text(
                    "即将与「${peer.name}」（${peer.model.ifEmpty { "未知机型" }}）建立加密配对。\n\n" +
                        "对方配对码 ${peer.shortCode.take(3)} ${peer.shortCode.takeLast(3)}，" +
                        "请与对方屏幕上显示的本机配对码一致后再继续。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = peer
                    pairing = null
                    io.github.srqingchen.qingzhou.core.common.QzWorkScope.scope.launch {
                        val result = SessionManager.initiatePair(p)
                        toast = result.fold(
                            { "配对成功：${it.name}，现在可以互发消息" },
                            { it.message ?: "配对失败" },
                        )
                    }
                }) { Text("开始配对") }
            },
            dismissButton = {
                TextButton(onClick = { pairing = null }) { Text("取消") }
            },
        )
    }

    toast?.let { message ->
        LaunchedEffect(message) {
            kotlinx.coroutines.delay(2500)
            toast = null
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                shape = CircleShape,
                color = Color(0xEE10201F),
                contentColor = Color.White,
            ) {
                Text(message, Modifier.padding(horizontal = 20.dp, vertical = 10.dp), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun PeerCard(peer: Peer, onClick: () -> Unit) {
    QzGlassCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val colorSeed = remember(peer.fingerprint) {
                peer.fingerprint.take(6).toLongOrNull(16) ?: 0x00838F
            }
            Box(
                Modifier
                    .size(44.dp)
                    .background(Color(0xFF000000 or colorSeed), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(peer.name.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(peer.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        append(peer.model.ifEmpty { "未知机型" })
                        if (peer.paired) append(" · 已配对")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (peer.paired) QzAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                peer.host,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
