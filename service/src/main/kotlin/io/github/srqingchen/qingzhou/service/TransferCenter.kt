package io.github.srqingchen.qingzhou.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.srqingchen.qingzhou.core.common.QzLog
import io.github.srqingchen.qingzhou.core.crypto.IdentityStore
import io.github.srqingchen.qingzhou.core.data.InboxStore
import io.github.srqingchen.qingzhou.core.data.PairedDeviceStore
import io.github.srqingchen.qingzhou.core.data.SettingsStore
import io.github.srqingchen.qingzhou.core.network.MessageBus
import io.github.srqingchen.qingzhou.core.network.QzEvent
import io.github.srqingchen.qingzhou.core.network.discovery.DiscoveryEngine
import io.github.srqingchen.qingzhou.core.network.messaging.MicroMessenger
import io.github.srqingchen.qingzhou.core.network.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 总控（同尘露 AutomationController 模式）：接收开关的唯一事实源。
 * 启动 = 前台服务 + 发现引擎 + 控制通道 + 微消息通道 + 事件→通知管线。
 */
object TransferCenter {

    private const val TAG = "center"

    private val _receiving = kotlinx.coroutines.flow.MutableStateFlow(false)
    val receiving: kotlinx.coroutines.flow.StateFlow<Boolean> = _receiving

    private var scope: CoroutineScope? = null

    /** App 启动调用：拉起前台服务并启动全部引擎（幂等）。 */
    fun start(context: Context) {
        if (_receiving.value) return
        val appContext = context.applicationContext
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        s.launch {
            runCatching { IdentityStore.loadOrCreate(appContext) }
                .onSuccess { QzLog.i(TAG, "身份就绪：指纹 ${it.fingerprintHex.take(8)}… 短码 ${it.shortCode}") }
                .onFailure { QzLog.e(TAG, "身份初始化失败：${it.message}") }
            io.github.srqingchen.qingzhou.core.network.transfer.TransferRepository.init(appContext, s)
            DiscoveryEngine.start(appContext)
            SessionManager.start(appContext)
            MicroMessenger.start(appContext)
            io.github.srqingchen.qingzhou.core.network.transfer.FileTransferEngine.start(appContext)
            io.github.srqingchen.qingzhou.core.network.p2p.WifiDirectHelper.start(appContext)
        }
        // BLE 辅助发现随设置开关启停
        s.launch {
            var lastBle = false
            SettingsStore.settings.collect { cfg ->
                if (cfg.bleEnabled != lastBle) {
                    lastBle = cfg.bleEnabled
                    val identity = IdentityStore.peek()
                    if (cfg.bleEnabled && identity != null) {
                        io.github.srqingchen.qingzhou.core.network.ble.BleBeacon.start(
                            appContext,
                            identity.fingerprintHex,
                            cfg.displayName.ifEmpty { "青舟" },
                        )
                    } else {
                        io.github.srqingchen.qingzhou.core.network.ble.BleBeacon.stop(appContext)
                    }
                }
            }
        }
        s.launch {
            MessageBus.events.collect { event ->
                Notifier.onEvent(appContext, event)
                // 聊天记录入库（收发文本与文件）
                when (event) {
                    is io.github.srqingchen.qingzhou.core.network.QzEvent.TextReceived ->
                        io.github.srqingchen.qingzhou.core.data.ChatStore.addText(
                            event.fromFp, mine = false, content = event.content,
                        )

                    // TextSent 不入库：聊天界面发送时本地已记录（避免重复）
                    is io.github.srqingchen.qingzhou.core.network.QzEvent.FilesReceived -> {
                        event.fileNames.forEach { name ->
                            io.github.srqingchen.qingzhou.core.data.ChatStore.addFile(
                                event.fromFp, mine = false, name = name,
                            )
                        }
                    }

                    else -> Unit
                }
            }
        }
        s.launch { progressLoop(appContext) }
        ContextCompat.startForegroundService(appContext, Intent(appContext, QzTransferService::class.java))
        _receiving.value = true
        QzLog.i(TAG, "总控已启动")
    }

    /** 2Hz 进度通知/上岛循环；无活动任务时收岛。 */
    private suspend fun progressLoop(appContext: android.content.Context) {
        val island = io.github.srqingchen.qingzhou.service.island.FocusIslandPublisher
        island.restoreGateIfNeeded(appContext)
        var hadActive = false
        var firstShown = false
        var lastIdlePublish = 0L
        var idleShown = false
        while (true) {
            kotlinx.coroutines.delay(500)
            val settings = SettingsStore.settings.value
            island.enabled = settings.islandEnabled
            island.compatMode = settings.islandCompat && io.github.srqingchen.qingzhou.core.shizuku.QzShizukuManager.isReady

            val active = io.github.srqingchen.qingzhou.core.network.transfer.TransferRepository.tasks.value.firstOrNull {
                it.state == io.github.srqingchen.qingzhou.core.model.TaskState.RUNNING ||
                    it.state == io.github.srqingchen.qingzhou.core.model.TaskState.VERIFYING ||
                    it.state == io.github.srqingchen.qingzhou.core.model.TaskState.WAITING_ACCEPT
            }
            if (active == null) {
                if (hadActive) {
                    island.dismiss(appContext)
                    hadActive = false
                    firstShown = false
                    idleShown = false
                }
                // 待命岛：空闲 + 应用后台 + 接收开启时上岛；回前台收起（尘露同款生命周期）
                if (!island.appForeground && _receiving.value && settings.islandEnabled) {
                    val now = System.currentTimeMillis()
                    if (!idleShown || now - lastIdlePublish > 60_000) {
                        val myName = settings.displayName.ifEmpty { "青舟" }
                        island.publishIdle(appContext, myName)
                        lastIdlePublish = now
                        idleShown = true
                    }
                } else if (idleShown) {
                    island.dismiss(appContext)
                    idleShown = false
                }
                continue
            }
            lastIdlePublish = System.currentTimeMillis()
            hadActive = true
            val dir = if (active.direction == io.github.srqingchen.qingzhou.core.model.TransferDirection.SEND) "发送" else "接收"
            val title = "青舟 · ${dir}给 ${active.peerName}"
            val content = "${active.files.size} 个文件${if (active.state == io.github.srqingchen.qingzhou.core.model.TaskState.VERIFYING) "（校验中）" else ""}"
            val speed = io.github.srqingchen.qingzhou.core.model.formatSpeed(active.speedBps)
            val done = io.github.srqingchen.qingzhou.core.model.formatSize(active.bytesDone)
            val total = io.github.srqingchen.qingzhou.core.model.formatSize(active.totalBytes)
            island.publishProgress(
                appContext, title, content, active.progress,
                "$speed · $done/$total", firstShown,
            )
            firstShown = false
        }
    }

    /** 完全停止（设置页「停止接收」）。 */
    fun stop(context: Context) {
        if (!_receiving.value) return
        _receiving.value = false
        DiscoveryEngine.stop()
        SessionManager.stop()
        MicroMessenger.stop()
        io.github.srqingchen.qingzhou.core.network.transfer.FileTransferEngine.stop()
        io.github.srqingchen.qingzhou.core.network.p2p.WifiDirectHelper.stop()
        io.github.srqingchen.qingzhou.core.network.ble.BleBeacon.stop(context)
        io.github.srqingchen.qingzhou.service.island.FocusIslandPublisher.dismiss(context)
        scope?.cancel()
        scope = null
        appContextStopService(context)
        QzLog.i(TAG, "总控已停止")
    }

    private fun appContextStopService(context: Context) {
        runCatching {
            context.applicationContext.stopService(
                Intent(context.applicationContext, QzTransferService::class.java),
            )
        }
    }
}
