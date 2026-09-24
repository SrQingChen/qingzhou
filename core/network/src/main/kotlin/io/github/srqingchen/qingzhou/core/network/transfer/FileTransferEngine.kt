package io.github.srqingchen.qingzhou.core.network.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import io.github.srqingchen.qingzhou.core.common.QzLog
import io.github.srqingchen.qingzhou.core.crypto.QzCrypto
import io.github.srqingchen.qingzhou.core.crypto.IdentityStore
import io.github.srqingchen.qingzhou.core.data.InboxItem
import io.github.srqingchen.qingzhou.core.data.InboxKind
import io.github.srqingchen.qingzhou.core.data.InboxStore
import io.github.srqingchen.qingzhou.core.data.PairedDeviceStore
import io.github.srqingchen.qingzhou.core.data.QzSettings
import io.github.srqingchen.qingzhou.core.data.SettingsStore
import io.github.srqingchen.qingzhou.core.data.b64
import io.github.srqingchen.qingzhou.core.data.toB64
import io.github.srqingchen.qingzhou.core.model.Cap
import io.github.srqingchen.qingzhou.core.model.FileMeta
import io.github.srqingchen.qingzhou.core.model.Peer
import io.github.srqingchen.qingzhou.core.model.TaskState
import io.github.srqingchen.qingzhou.core.model.TransferDirection
import io.github.srqingchen.qingzhou.core.model.TransferTask
import io.github.srqingchen.qingzhou.core.network.MessageBus
import io.github.srqingchen.qingzhou.core.network.QzEvent
import io.github.srqingchen.qingzhou.core.network.WifiLocks
import io.github.srqingchen.qingzhou.core.network.discovery.DiscoveryEngine
import io.github.srqingchen.qingzhou.core.network.session.FrameIO
import io.github.srqingchen.qingzhou.core.network.session.SessionManager
import io.github.srqingchen.qingzhou.core.network.transfer.mplb.LeaseScheduler
import io.github.srqingchen.qingzhou.core.network.transfer.mplb.SackCodec
import io.github.srqingchen.qingzhou.core.network.usb.UsbAccessoryLink
import io.github.srqingchen.qingzhou.core.network.usb.UsbNetLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 文件传输引擎 v2（舟协议 v2 · MPLB）：
 *
 * - 分块（默认 4MB）+ 多链路并行：WiFi N 条 TCP + USB AOA（bulk 异步队列）+ usb0/NCM N 条 TCP；
 * - 逐块 AES-256-GCM，v2 nonce 覆盖 lane（补发/尾块冗余的跨链路重复发送安全）；
 * - **租约调度**（[LeaseScheduler]）：块租约 + 截止期回收 + 每链路信用（接收端 linkCredit 驱动）+ 尾块冗余复制；
 * - **SACK + 补发闭环**（修复 D1）：接收端周期位图上报；收尾扫缺失块经控制通道 fileRepair 定向重发，
 *   USB 拔线/WiFi 抖动/AEAD 偶发坏块从致命错误降级为一次补发轮；
 * - **零暂存发送**（修复 D4）：SAF URI 直读（seekable pfd），SHA-256 发送后单遍补算，经数据面 fileHashes 帧送达；
 * - **接收端直写 MediaStore**（修复 D5）：pending 行 pfd 直写，校验通过仅翻 IS_PENDING，删除 .part 拷贝环节；
 * - **位图内存化**（修复 D3）：常驻内存 + 周期落盘（2s/64 块）。
 *
 * v1 对端（无 Cap.V2）：完整回退 v1 行为（暂存 + ChunkScheduler + 旧 nonce + 无补发）。
 *
 * 安全：fileKey 只存在于加密控制帧中，数据通道伪造包无法通过 AEAD 校验。
 */
object FileTransferEngine {

    private const val TAG = "xfer"
    const val PORT_DATA = 39530

    private const val FRAME_HELLO = 1
    private const val FRAME_CHUNK = 2
    private const val FRAME_BYE = 3
    private const val FRAME_HASHES = 4 // v2：fileHashes 走数据面（接收端 sink 循环直读，免控制通道读写争用）

    /** v2 数据面车道标签（hello 追加字节，参与 nonce 派生与信用/健康统计）。 */
    const val LANE_WIFI = 0
    const val LANE_USB_AOA = 1
    const val LANE_USB_NET = 2

    private var scope: CoroutineScope? = null
    private var dataServer: ServerSocket? = null
    private var appContext: Context? = null

    /** 接收中会话（token → 接收状态）。 */
    private val receivers = ConcurrentHashMap<String, ReceiverState>()

    /** 接收确认闸门（token → 用户决定）。 */
    private val offerGates = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    @Volatile
    private var started = false

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            appContext = context.applicationContext
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        runCatching {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(PORT_DATA))
            dataServer = s
        }.onFailure {
            QzLog.e(TAG, "数据通道监听失败：${it.message}")
            return
        }
        scope?.launch { dataAcceptLoop() }
        // USB 常驻接收循环：入流与 TCP 同一处理逻辑；BYE 后继续监听（v2 补发轮复用同一 fd）
        UsbAccessoryLink.sinkListener = { input ->
            val din = DataInputStream(BufferedInputStream(input, 512 shl 10))
            var alive = true
            while (alive) alive = runCatching { handleSinkStreamOnce(din, null) }.getOrDefault(false)
        }
        UsbAccessoryLink.start(context)
        UsbNetLink.start(context)
        QzLog.i(TAG, "数据通道已监听 :$PORT_DATA")
    }

    fun stop() {
        synchronized(this) {
            if (!started) return
            started = false
        }
        runCatching { dataServer?.close() }
        dataServer = null
        UsbAccessoryLink.sinkListener = null
        UsbAccessoryLink.stop()
        UsbNetLink.stop()
        receivers.values.forEach { it.cancel("engine stop") }
        receivers.clear()
        offerGates.values.forEach { it.cancel() }
        offerGates.clear()
        scope?.cancel()
        scope = null
    }

    // ================= 发送侧 =================

    /**
     * 提交发送（UI 安全入口）：在进程级工作作用域执行，回调回 UI。
     * 界面销毁不影响传输（见 QzWorkScope 注释里的真机教训）。
     */
    fun submitSend(peer: Peer, uris: List<Uri>, onResult: (Result<Unit>) -> Unit = {}) {
        io.github.srqingchen.qingzhou.core.common.QzWorkScope.scope.launch {
            val r = sendFiles(peer, uris)
            onResult(r)
        }
    }

    /**
     * v2 发送源句柄：直读 pfd（零暂存）或暂存文件；统一 FileChannel 读盘（可 seek 探测），
     * 读盘全局锁（多 worker 共享 fd，FileChannel 的 position 是共享状态）。
     */
    private class SourceHandle(
        val meta: FileMeta,
        private val channel: java.nio.channels.FileChannel,
        private val stagedFile: File? = null,
        private val closers: List<AutoCloseable> = emptyList(), // FileInputStream / AssetFileDescriptor / RAF（关闭责任）
    ) {
        val sha256Known: Boolean get() = meta.sha256.isNotEmpty()

        fun readAt(offset: Long, len: Int): ByteArray = synchronized(channel) {
            val bb = java.nio.ByteBuffer.allocate(len)
            channel.position(offset)
            while (bb.hasRemaining()) {
                if (channel.read(bb) < 0) break
            }
            bb.flip()
            val out = ByteArray(bb.remaining())
            bb.get(out)
            out
        }

        /** 发送后单遍补算 SHA-256（零暂存路径）。 */
        fun hashSequential(): String {
            val md = MessageDigest.getInstance("SHA-256")
            synchronized(channel) {
                channel.position(0)
                val bb = java.nio.ByteBuffer.allocate(1 shl 20)
                var remain = meta.size
                while (remain > 0) {
                    bb.clear()
                    bb.limit(minOf(bb.capacity().toLong(), remain).toInt())
                    if (channel.read(bb) < 0) break
                    md.update(bb.array(), 0, bb.position())
                    remain -= bb.position()
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        fun close() {
            runCatching { synchronized(channel) { channel.close() } }
            closers.forEach { runCatching { it.close() } }
            stagedFile?.let { runCatching { it.delete() } }
        }
    }

    /** 发送文件（UI 入口）。全程进度见 [TransferRepository]。 */
    suspend fun sendFiles(peer: Peer, uris: List<Uri>): Result<Unit> = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext Result.failure(IllegalStateException("引擎未启动"))
        if (uris.isEmpty()) return@withContext Result.failure(IllegalArgumentException("未选择文件"))
        val identity = IdentityStore.peek() ?: return@withContext Result.failure(IllegalStateException("身份未就绪"))
        // 对端地址刷新：发现列表里的最新地址优先（旧地址可能是对方换网络前的陈旧 IP）
        val peer = DiscoveryEngine.peers.value.firstOrNull { it.fingerprint == peer.fingerprint }
            ?: peer
        val v2Peer = (peer.capabilities and Cap.V2) != 0

        // 1. 源准备：v2 对端优先零暂存直读（D4）；v1 对端/不可 seek 源走暂存
        QzLog.i(TAG, "发送请求：${uris.size} 个文件 → ${peer.name}@${peer.host}:${peer.controlPort}（v2=$v2Peer）")
        val sources = mutableListOf<SourceHandle>()
        for ((i, uri) in uris.withIndex()) {
            val src = if (v2Peer) prepareSource(context, uri) else null
            val handle = src ?: stageToSource(context, uri, File(context.cacheDir, "send_src/${System.currentTimeMillis()}_$i.bin"))
                ?: run {
                    QzLog.e(TAG, "元数据准备失败（无法读取所选内容）：$uri")
                    return@withContext Result.failure(IllegalArgumentException("无法读取所选内容"))
                }
            sources.add(handle)
        }
        val metas = sources.map { it.meta }
        val token = if (v2Peer) deterministicTokenV2(identity.fingerprintHex, peer.fingerprint, metas)
        else deterministicToken(identity.fingerprintHex, peer.fingerprint, metas)
        val totalBytes = metas.sumOf { it.size }
        QzLog.i(TAG, "任务 $token 建立：${metas.size} 个文件 / $totalBytes 字节，开始会话")
        TransferRepository.upsert(
            TransferTask(
                id = token,
                direction = TransferDirection.SEND,
                peerFp = peer.fingerprint,
                peerName = peer.name,
                files = metas,
                state = TaskState.NEGOTIATING,
                totalBytes = totalBytes,
            ),
        )

        val settings = SettingsStore.settings.value
        val chunkSize = settings.chunkSizeMiB shl 20
        val fileKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val sentBytes = AtomicLong(0)

        val outcome: Result<Unit>? = runCatching {
            QzLog.i(TAG, "控制会话连接中…")
            SessionManager.withSession(peer) { session, fio ->
                // 2. offer
                session.send(
                    fio,
                    JSONObject()
                        .put("t", "fileOffer")
                        .put("token", token)
                        .put(
                            "files",
                            JSONArray().apply {
                                metas.forEach {
                                    put(
                                        JSONObject()
                                            .put("n", it.name)
                                            .put("s", it.size)
                                            .put("h", it.sha256)
                                            .put("m", it.mimeType ?: ""),
                                    )
                                }
                            },
                        )
                        .apply { if (v2Peer) put("v", 2) }
                        .toString().toByteArray(Charsets.UTF_8),
                )

                // 3. 等回应（v2 resume 为位压缩 SACK 格式，v1 为每块一字节）
                var resumeMaps: List<ByteArray>? = null
                while (true) {
                    val payload = session.recv(fio)
                        ?: return@withSession Result.failure<Unit>(IllegalStateException("连接中断"))
                    val json = JSONObject(String(payload, Charsets.UTF_8))
                    when (json.optString("t")) {
                        "fileAccept" -> {
                            val resume = json.optJSONObject("resume")
                            if (resume != null) {
                                resumeMaps = metas.mapIndexed { i, meta ->
                                    val raw = resume.optString("$i")
                                    if (raw.isEmpty()) ByteArray(0) else {
                                        val bytes = raw.b64()
                                        if (v2Peer) SackCodec.unpack(
                                            bytes,
                                            ((meta.size + chunkSize - 1) / chunkSize).toInt(),
                                        ) else bytes
                                    }
                                }
                            }
                            break
                        }

                        "fileDecline" -> return@withSession Result.failure<Unit>(IllegalStateException("对方拒绝了本次发送"))
                        else -> continue
                    }
                }

                TransferRepository.updateState(token, TaskState.RUNNING)
                WifiLocks.holdTransfer(context)

                // 4. 有线协商 + 参数下发；对端就绪（fileGo）后开流
                val peerNet = (peer.capabilities and Cap.USB_NET) != 0 && settings.usbNetEnabled && v2Peer
                val myWired = UsbNetLink.info()
                // 发送方为 host 且尚无 usb0 网络 → 请求 gadget 侧对端切 NCM（仅 A15+ host 有意义）
                val ncmReq = peerNet && myWired.role == UsbNetLink.Role.HOST &&
                    UsbNetLink.ethNet == null && Build.VERSION.SDK_INT >= 35
                // AOA sink：NCM 计划会重置 gadget functions（杀 accessory），二者互斥
                val usbSink = settings.usbEnabled && !ncmReq &&
                    (peer.capabilities and Cap.USB) != 0 && UsbAccessoryLink.attached
                if (usbSink) QzLog.i(TAG, "有线+WiFi 并行：本条传输附加 USB AOA sink")
                if (ncmReq) QzLog.i(TAG, "请求对端切换 NCM（usb0 并行车道）")

                session.send(
                    fio,
                    JSONObject()
                        .put("t", "fileReady")
                        .put("token", token)
                        .put("key", fileKey.toB64())
                        .put("streams", settings.parallelStreams)
                        .put("usb", if (usbSink) 1 else 0)
                        .put("chunkMiB", settings.chunkSizeMiB)
                        .apply {
                            if (v2Peer) {
                                put("v", 2)
                                put("unet", if (peerNet) UsbNetLink.suggestedLanes() else 0)
                                put("wired", wiredJson(myWired))
                                put("ncmReq", if (ncmReq) 1 else 0)
                            }
                        }
                        .toString().toByteArray(Charsets.UTF_8),
                )
                var goJson: JSONObject? = null
                while (goJson == null) {
                    val payload = session.recv(fio) ?: return@withSession Result.failure<Unit>(
                        IllegalStateException("连接中断（等待对端就绪）"),
                    )
                    val json = JSONObject(String(payload, Charsets.UTF_8))
                    if (json.optString("t") == "fileGo" && json.optString("token") == token) goJson = json
                }

                if (!v2Peer) {
                    // ===== v1 路径（原样保留）=====
                    val failures = ConcurrentHashMap<Int, Throwable>()
                    val scheduler = ChunkScheduler(metas, resumeMaps, chunkSize)
                    coroutineScope {
                        val jobs = (0 until settings.parallelStreams).map { streamIdx ->
                            async(Dispatchers.IO) {
                                sendStreamV1(
                                    peer, streamIdx, token, fileKey, scheduler,
                                    sources, chunkSize, sentBytes, failures,
                                )
                            }
                        }
                        if (usbSink) {
                            jobs + async(Dispatchers.IO) {
                                val out = UsbAccessoryLink.obtainSendStream()
                                if (out == null) {
                                    QzLog.w(TAG, "USB sink 获取失败，本条传输退回纯 WiFi")
                                } else {
                                    sendUsbSinkV1(out, settings.parallelStreams, token, fileKey, scheduler, sources, chunkSize, sentBytes)
                                }
                            }
                        } else {
                            jobs
                        }.awaitAll()
                    }
                    if (failures.isNotEmpty()) throw failures.values.first()

                    // 6. 结果
                    session.send(fio, JSONObject().put("t", "fileDone").put("token", token).toString().toByteArray(Charsets.UTF_8))
                    var result = ""
                    while (result.isEmpty()) {
                        val payload = session.recv(fio) ?: break
                        val json = JSONObject(String(payload, Charsets.UTF_8))
                        if (json.optString("t") == "fileResult") result = json.optString("ok", "fail").ifEmpty { "fail" }
                    }
                    if (result != "ok") Result.failure<Unit>(IllegalStateException("对方校验未通过"))
                    else Result.success(Unit)
                } else {
                    // ===== v2 路径（MPLB）=====
                    sendV2(
                        context, peer, session, fio, token, fileKey,
                        sources, chunkSize, sentBytes, settings,
                        resumeMaps, usbSink, peerNet, myWired, goJson!!,
                    )
                }
            }
        }.onFailure { e ->
            if (e is kotlinx.coroutines.CancellationException) {
                QzLog.w(TAG, "发送已取消：$token")
            } else {
                QzLog.e(TAG, "发送流程异常：${e.message}")
            }
        }.getOrNull()

        sources.forEach { it.close() }
        // runCatching 会吞掉取消异常：用上下文活性判定本次失败是否为“已取消”
        val cancelled = !kotlinx.coroutines.currentCoroutineContext().isActive
        val final: Result<Unit> = outcome ?: Result.failure(IllegalStateException("会话建立失败（对方不在线或未配对）"))
        TransferRepository.updateState(
            token,
            when {
                final.isSuccess -> TaskState.DONE
                cancelled -> TaskState.CANCELLED
                else -> TaskState.FAILED
            },
            if (final.isFailure && !cancelled) final.exceptionOrNull()?.message else null,
        )
        if (TransferRepository.activeCount == 0) WifiLocks.releaseTransfer()
        final
    }

    /** v2 发送全流程：车道规划 → 控制读循环 → workers → 哈希 → fileDone → 等 fileResult。 */
    private suspend fun sendV2(
        context: Context,
        peer: Peer,
        session: SessionManager.Session,
        fio: FrameIO,
        token: String,
        fileKey: ByteArray,
        sources: List<SourceHandle>,
        chunkSize: Int,
        sentBytes: AtomicLong,
        settings: QzSettings,
        resumeMaps: List<ByteArray>?,
        usbSink: Boolean,
        peerNet: Boolean,
        myWired: UsbNetLink.WiredInfo,
        goJson: JSONObject,
    ): Result<Unit> = coroutineScope {
        val goWired = goJson.optJSONObject("wired")
        val ncmGo = goJson.optInt("ncmGo", 0) == 1

        // ---- usb0 车道规划 ----
        var unetNet: Network? = null
        var unetTarget: String? = null
        var unetLanes = 0
        val hostIp = CompletableDeferred<String?>() // gadget 等 host 上报 usb0 地址（wiredUp 帧）
        if (peerNet) {
            when (myWired.role) {
                UsbNetLink.Role.HOST -> {
                    unetNet = UsbNetLink.ethNet ?: UsbNetLink.awaitEthNet(8_000)
                    unetTarget = goWired?.optString("usb0")?.takeIf { it.isNotEmpty() }
                        ?: UsbNetLink.ethGateway
                    if (unetNet != null && unetTarget != null) {
                        unetLanes = UsbNetLink.suggestedLanes()
                        QzLog.i(TAG, "usb0 车道：host→$unetTarget ×$unetLanes")
                    } else {
                        QzLog.w(TAG, "usb0 网络未就绪，退回 WiFi${if (usbSink) "+AOA" else ""}")
                    }
                }

                UsbNetLink.Role.GADGET -> {
                    if (ncmGo) {
                        QzLog.i(TAG, "对端确认 NCM：切换本机 gadget functions")
                        if (UsbNetLink.switchToNcm()) {
                            UsbNetLink.awaitGadgetIp(8_000)
                            unetLanes = UsbNetLink.suggestedLanes() // 目标地址经 wiredUp 帧异步到达
                            QzLog.i(TAG, "usb0 车道：gadget→host（等 wiredUp）×$unetLanes")
                        } else {
                            QzLog.w(TAG, "NCM 切换失败，退回 WiFi${if (usbSink) "+AOA" else ""}")
                        }
                    } else if (!goWired?.optString("eth").isNullOrEmpty()) {
                        // host 侧已有 usb0（如对端开了系统 USB 网络共享）：直接打对端报告的地址
                        unetTarget = goWired!!.optString("eth")
                        unetLanes = UsbNetLink.suggestedLanes()
                        QzLog.i(TAG, "usb0 车道：gadget→$unetTarget ×$unetLanes（host 已就绪）")
                    }
                }

                else -> Unit
            }
        }
        val wifiLanes = settings.parallelStreams

        // ---- 调度器 ----
        val scheduler = LeaseScheduler(sources.map { it.meta }, resumeMaps, chunkSize)
        scheduler.addLane(LANE_WIFI, 12L shl 20)
        if (usbSink) scheduler.addLane(LANE_USB_AOA, 16L shl 20)
        if (unetLanes > 0) scheduler.addLane(LANE_USB_NET, 16L shl 20)

        val hashesRef = java.util.concurrent.atomic.AtomicReference<List<String>>(emptyList())
        val readerOutcome = CompletableDeferred<Result<Unit>>()
        val activeWorkers = AtomicInteger(0)
        val repairBusy = AtomicBoolean(false)
        val sendLock = Any()

        fun sendCtrl(json: JSONObject) = synchronized(sendLock) { session.send(fio, json.toString().toByteArray(Charsets.UTF_8)) }

        // ---- 控制读循环：SACK / credit / fileRepair / wiredUp / ping / fileResult ----
        val reader = launch(Dispatchers.IO) {
            try {
                while (true) {
                    val payload = session.recv(fio) ?: break
                    val json = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull() ?: continue
                    when (json.optString("t")) {
                        "sack" -> scheduler.onSack(json.optInt("f", -1), json.optString("bits").b64())

                        "credit" -> {
                            val c = json.optJSONObject("c") ?: continue
                            c.keys().forEach { k -> k.toIntOrNull()?.let { scheduler.updateCredit(it, c.optLong(k)) } }
                        }

                        "fileRepair" -> {
                            val miss = parseMiss(json.optJSONArray("miss"))
                            if (miss.isEmpty()) continue
                            QzLog.w(TAG, "收到补发请求：${miss.size} 块")
                            scheduler.onRepairMiss(miss)
                            if (activeWorkers.get() == 0 && repairBusy.compareAndSet(false, true)) {
                                launch(Dispatchers.IO) {
                                    try {
                                        repairRound(peer, token, fileKey, scheduler, sources, chunkSize, sentBytes) { hashesRef.get() }
                                    } finally {
                                        repairBusy.set(false)
                                    }
                                }
                            }
                        }

                        "wiredUp" -> hostIp.complete(json.optString("ip").takeIf { it.isNotEmpty() })

                        "ping" -> Unit // 校验期心跳（保发送方读超时）

                        "fileResult" -> {
                            val ok = json.optString("ok") == "ok"
                            val errors = json.optString("errors").takeIf { it.isNotEmpty() }
                            readerOutcome.complete(
                                if (ok) Result.success(Unit)
                                else Result.failure(IllegalStateException(errors?.let { "对方校验未通过：$it" } ?: "对方校验未通过")),
                            )
                            break
                        }
                    }
                }
            } finally {
                hostIp.complete(null)
                readerOutcome.complete(Result.failure(IllegalStateException("控制通道中断")))
            }
        }

        // ---- sweeper：过期租约回收 ----
        val sweeper = launch(Dispatchers.IO) {
            while (isActive) {
                delay(500)
                runCatching { scheduler.sweep() }
            }
        }

        // ---- workers ----
        val workers = mutableListOf<kotlinx.coroutines.Job>()
        repeat(wifiLanes) { idx ->
            workers.add(
                launch(Dispatchers.IO) {
                    activeWorkers.incrementAndGet()
                    try {
                        laneSocketWorker(LANE_WIFI, idx, peer.host, null, token, fileKey, scheduler, sources, chunkSize, sentBytes, idleExitMs = 30_000)
                    } finally {
                        activeWorkers.decrementAndGet()
                    }
                },
            )
        }
        if (usbSink) {
            workers.add(
                launch(Dispatchers.IO) {
                    activeWorkers.incrementAndGet()
                    try {
                        usbAoaWorker(token, fileKey, scheduler, sources, chunkSize, sentBytes)
                    } finally {
                        activeWorkers.decrementAndGet()
                    }
                },
            )
        }
        if (unetLanes > 0) {
            repeat(unetLanes) { idx ->
                workers.add(
                    launch(Dispatchers.IO) {
                        activeWorkers.incrementAndGet()
                        try {
                            // gadget 侧需等 wiredUp 拿到 host 地址（host 侧目标已知）
                            val target = if (myWired.role == UsbNetLink.Role.GADGET && unetTarget == null) {
                                withTimeoutOrNull(12_000) { hostIp.await() }
                            } else {
                                unetTarget
                            }
                            if (target != null) {
                                laneSocketWorker(LANE_USB_NET, idx, target, unetNet, token, fileKey, scheduler, sources, chunkSize, sentBytes, idleExitMs = 30_000)
                            } else {
                                QzLog.w(TAG, "usb0 车道 #$idx 未获得对端地址，跳过")
                                scheduler.laneDead(LANE_USB_NET)
                            }
                        } finally {
                            activeWorkers.decrementAndGet()
                        }
                    },
                )
            }
        }
        workers.joinAll()
        sweeper.cancel()

        if (scheduler.undeliveredBytes > 0) {
            // 主 worker 退出仍有缺块：等接收端补发请求（reader 触发 repairRound）
            QzLog.w(TAG, "主车道收尾仍有 ${scheduler.undeliveredBytes} 字节未确认，等待补发轮")
        }

        // ---- 哈希（零暂存源单遍补算）+ 数据面 fileHashes ----
        hashesRef.set(sources.map { src -> src.meta.sha256.ifEmpty { src.hashSequential() } })
        sendHashesFrame(peer, unetTarget, unetNet, token, hashesRef.get())

        sendCtrl(JSONObject().put("t", "fileDone").put("token", token))

        // ---- 等 fileResult（含补发轮），10 分钟兜底 ----
        val outcome = withTimeoutOrNull(600_000) { readerOutcome.await() }
        if (outcome == null) {
            reader.cancel()
            fio.close() // 解除 recv 阻塞（协程取消不中断阻塞 IO）
            Result.failure(IllegalStateException("等待对方校验超时"))
        } else {
            outcome
        }
    }

    /** v2 通用 TCP 车道 worker（WiFi / usb0）。 */
    private fun laneSocketWorker(
        lane: Int,
        streamIdx: Int,
        host: String,
        bindNet: Network?,
        token: String,
        fileKey: ByteArray,
        scheduler: LeaseScheduler,
        sources: List<SourceHandle>,
        chunkSize: Int,
        sentBytes: AtomicLong,
        idleExitMs: Long,
    ) {
        val socket = try {
            val s = bindNet?.socketFactory?.createSocket() ?: Socket()
            s.tcpNoDelay = true
            s.sendBufferSize = 8 shl 20
            s.connect(InetSocketAddress(host, PORT_DATA), 5_000)
            s.soTimeout = 60_000
            s
        } catch (e: Exception) {
            QzLog.w(TAG, "车道 lane=$lane #$streamIdx 连接失败：${e.message}")
            scheduler.laneDead(lane)
            return
        }
        try {
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 512 shl 10))
            writeHello(out, token, streamIdx, lane)
            pumpV2(out, lane, token, fileKey, scheduler, sources, chunkSize, sentBytes, idleExitMs)
        } catch (e: Exception) {
            QzLog.w(TAG, "车道 lane=$lane #$streamIdx 失败：${e.message}")
            scheduler.laneDead(lane)
        } finally {
            runCatching { socket.close() }
        }
    }

    /** v2 USB AOA worker：复用常驻 fd 流；BYE 后不关流（补发轮可继续写）。 */
    private fun usbAoaWorker(
        token: String,
        fileKey: ByteArray,
        scheduler: LeaseScheduler,
        sources: List<SourceHandle>,
        chunkSize: Int,
        sentBytes: AtomicLong,
    ) {
        val raw = UsbAccessoryLink.obtainSendStream()
        if (raw == null) {
            QzLog.w(TAG, "USB sink 获取失败，本条传输退回其他车道")
            scheduler.laneDead(LANE_USB_AOA)
            return
        }
        val out = DataOutputStream(BufferedOutputStream(raw, 256 shl 10)) // D7：USB 侧 256KB 刷写粒度
        try {
            writeHello(out, token, 0, LANE_USB_AOA)
            pumpV2(out, LANE_USB_AOA, token, fileKey, scheduler, sources, chunkSize, sentBytes, idleExitMs = 30_000)
        } catch (e: Exception) {
            QzLog.w(TAG, "USB AOA 车道失败（其余链路继续）：${e.message}")
            scheduler.laneDead(LANE_USB_AOA)
            runCatching { // best-effort 补 bye：保证接收端完成计数闭环
                out.writeInt(1)
                out.writeByte(FRAME_BYE)
                out.flush()
            }
        }
    }

    /** v2 泵：租约取块 → 加密 → 发帧；空闲超过 [idleExitMs] 或全部确认后退出（含 BYE）。 */
    private fun pumpV2(
        out: DataOutputStream,
        lane: Int,
        token: String,
        fileKey: ByteArray,
        scheduler: LeaseScheduler,
        sources: List<SourceHandle>,
        chunkSize: Int,
        sentBytes: AtomicLong,
        idleExitMs: Long,
    ) {
        val buf = ByteArray(chunkSize)
        var idle = 0L
        while (true) {
            val chunk = scheduler.lease(lane) ?: scheduler.dupLease(lane)
            if (chunk == null) {
                if (scheduler.allDelivered()) break
                if (idle >= idleExitMs) break
                Thread.sleep(50)
                idle += 50
                continue
            }
            idle = 0
            val data = sources[chunk.fileIdx].readAt(chunk.offset, chunk.length)
            val nonce = chunkNonceV2(token, chunk.fileIdx, chunk.chunkIdx.toInt(), lane)
            val ct = QzCrypto.aesGcmEncrypt(fileKey, nonce, data)
            out.writeInt(1 + 4 + 8 + 4 + ct.size)
            out.writeByte(FRAME_CHUNK)
            out.writeInt(chunk.fileIdx)
            out.writeLong(chunk.chunkIdx)
            out.writeInt(chunk.length)
            out.write(ct)
            out.flush()
            if (!chunk.duplicate) {
                sentBytes.addAndGet(chunk.length.toLong())
                TransferRepository.updateProgress(token, sentBytes.get())
            }
        }
        out.writeInt(1)
        out.writeByte(FRAME_BYE)
        out.flush()
    }

    /** 补发轮：开新 WiFi 车道重发缺失块；哈希已算出则顺路重送。 */
    private suspend fun repairRound(
        peer: Peer,
        token: String,
        fileKey: ByteArray,
        scheduler: LeaseScheduler,
        sources: List<SourceHandle>,
        chunkSize: Int,
        sentBytes: AtomicLong,
        hashes: () -> List<String>,
    ) {
        if (scheduler.undeliveredBytes <= 0) return
        QzLog.i(TAG, "补发轮启动：${scheduler.undeliveredBytes} 字节待补")
        val socket = try {
            val s = Socket()
            s.tcpNoDelay = true
            s.sendBufferSize = 8 shl 20
            s.connect(InetSocketAddress(peer.host, PORT_DATA), 5_000)
            s.soTimeout = 60_000
            s
        } catch (e: Exception) {
            QzLog.w(TAG, "补发轮连接失败：${e.message}")
            return
        }
        try {
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 512 shl 10))
            writeHello(out, token, 0, LANE_WIFI)
            pumpV2(out, LANE_WIFI, token, fileKey, scheduler, sources, chunkSize, sentBytes, idleExitMs = 12_000)
            val hs = hashes()
            if (hs.isNotEmpty() && hs.all { it.isNotEmpty() }) {
                writeHashesFrame(out, hs)
                out.flush()
            }
        } catch (e: Exception) {
            QzLog.w(TAG, "补发轮失败：${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun writeHello(out: DataOutputStream, token: String, streamIdx: Int, lane: Int) {
        val tokenBytes = token.toByteArray(Charsets.UTF_8)
        out.writeInt(1 + tokenBytes.size + 4 + 1) // v2：多一个 lane 字节
        out.writeByte(FRAME_HELLO)
        out.write(tokenBytes)
        out.writeInt(streamIdx)
        out.writeByte(lane)
        out.flush()
    }

    /** 数据面 fileHashes：hello + 哈希帧 + BYE（一次短连接；失败换 usb0 / AOA 写流再试）。 */
    private fun sendHashesFrame(
        peer: Peer,
        unetTarget: String?,
        unetNet: Network?,
        token: String,
        hashes: List<String>,
    ) {
        // 首选 WiFi 短连接
        val ok = runCatching {
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(peer.host, PORT_DATA), 5_000)
                val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 shl 10))
                writeHello(out, token, 0, LANE_WIFI)
                writeHashesFrame(out, hashes)
                out.writeInt(1)
                out.writeByte(FRAME_BYE)
                out.flush()
                true
            } finally {
                runCatching { s.close() }
            }
        }.getOrDefault(false)
        if (ok) return
        // 兜底：usb0
        if (unetTarget != null) {
            val ok2 = runCatching {
                val s = unetNet?.socketFactory?.createSocket() ?: Socket()
                try {
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(unetTarget, PORT_DATA), 5_000)
                    val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 shl 10))
                    writeHello(out, token, 0, LANE_USB_NET)
                    writeHashesFrame(out, hashes)
                    out.writeInt(1)
                    out.writeByte(FRAME_BYE)
                    out.flush()
                    true
                } finally {
                    runCatching { s.close() }
                }
            }.getOrDefault(false)
            if (ok2) return
        }
        // 兜底：AOA 写流
        UsbAccessoryLink.obtainSendStream()?.let { raw ->
            runCatching {
                val out = DataOutputStream(BufferedOutputStream(raw, 64 shl 10))
                writeHashesFrame(out, hashes)
                out.writeInt(1)
                out.writeByte(FRAME_BYE)
                out.flush()
            }.onFailure { QzLog.w(TAG, "fileHashes 全部通道投递失败：${it.message}") }
        } ?: QzLog.w(TAG, "fileHashes 投递失败：无可用通道")
    }

    private fun writeHashesFrame(out: DataOutputStream, hashes: List<String>) {
        val payload = JSONObject().put("hs", JSONArray(hashes)).toString().toByteArray(Charsets.UTF_8)
        out.writeInt(1 + payload.size)
        out.writeByte(FRAME_HASHES)
        out.write(payload)
    }

    // ================= v1 数据流（原样保留） =================

    /** 单条数据流：hello → 依次取块 → 加密发送。 */
    private fun sendStreamV1(
        peer: Peer,
        streamIdx: Int,
        token: String,
        fileKey: ByteArray,
        scheduler: ChunkScheduler,
        sources: List<SourceHandle>,
        chunkSize: Int,
        sentBytes: AtomicLong,
        failures: ConcurrentHashMap<Int, Throwable>,
    ) {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.sendBufferSize = 8 shl 20
            socket.connect(InetSocketAddress(peer.host, PORT_DATA), 5_000)
            socket.soTimeout = 30_000
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 512 shl 10))
            val input = socket.getInputStream()

            val tokenBytes = token.toByteArray(Charsets.UTF_8)
            out.writeInt(1 + tokenBytes.size + 4)
            out.writeByte(FRAME_HELLO)
            out.write(tokenBytes)
            out.writeInt(streamIdx)
            out.flush()
            if (input.read() != 1) error("对端拒绝数据连接")

            while (true) {
                val chunk = scheduler.next() ?: break
                val data = sources[chunk.fileIdx].readAt(chunk.offset, chunk.length)
                val nonce = chunkNonce(token, chunk.fileIdx, chunk.chunkIdx.toInt())
                val ct = QzCrypto.aesGcmEncrypt(fileKey, nonce, data)
                out.writeInt(1 + 4 + 8 + 4 + ct.size)
                out.writeByte(FRAME_CHUNK)
                out.writeInt(chunk.fileIdx)
                out.writeLong(chunk.chunkIdx)
                out.writeInt(chunk.length)
                out.write(ct)
                out.flush()
                sentBytes.addAndGet(chunk.length.toLong())
                TransferRepository.updateProgress(token, sentBytes.get())
            }
            out.writeInt(1)
            out.writeByte(FRAME_BYE)
            out.flush()
        } catch (e: Exception) {
            failures[streamIdx] = e
            QzLog.w(TAG, "数据流 #$streamIdx 失败：${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    /** USB 纯写 sink（v1）：块帧与 TCP 完全一致（接收端按 fileIdx+chunkIdx 天然聚合）。 */
    private fun sendUsbSinkV1(
        rawOut: OutputStream,
        streamIdx: Int,
        token: String,
        fileKey: ByteArray,
        scheduler: ChunkScheduler,
        sources: List<SourceHandle>,
        chunkSize: Int,
        sentBytes: AtomicLong,
    ) {
        val out = DataOutputStream(BufferedOutputStream(rawOut, 512 shl 10))
        try {
            val tokenBytes = token.toByteArray(Charsets.UTF_8)
            out.writeInt(1 + tokenBytes.size + 4)
            out.writeByte(FRAME_HELLO)
            out.write(tokenBytes)
            out.writeInt(streamIdx)
            out.flush()
            while (true) {
                val chunk = scheduler.next() ?: break
                val data = sources[chunk.fileIdx].readAt(chunk.offset, chunk.length)
                val nonce = chunkNonce(token, chunk.fileIdx, chunk.chunkIdx.toInt())
                val ct = QzCrypto.aesGcmEncrypt(fileKey, nonce, data)
                out.writeInt(1 + 4 + 8 + 4 + ct.size)
                out.writeByte(FRAME_CHUNK)
                out.writeInt(chunk.fileIdx)
                out.writeLong(chunk.chunkIdx)
                out.writeInt(chunk.length)
                out.write(ct)
                out.flush()
                sentBytes.addAndGet(chunk.length.toLong())
                TransferRepository.updateProgress(token, sentBytes.get())
            }
            out.writeInt(1)
            out.writeByte(FRAME_BYE)
            out.flush()
        } catch (e: Exception) {
            QzLog.w(TAG, "USB sink 失败（其余链路继续）：${e.message}")
            runCatching { // best-effort 补 bye：保证接收端完成计数闭环
                out.writeInt(1)
                out.writeByte(FRAME_BYE)
                out.flush()
            }
        }
    }

    // ================= 接收侧 =================

    /**
     * SessionManager 服务端应用帧回调；返回 true 表示已接管处理。
     * 同步（挂起）执行 —— 控制通道同一时刻只允许一个读取者。
     */
    suspend fun onAppFrame(
        context: Context,
        json: JSONObject,
        session: SessionManager.Session,
        fio: FrameIO,
        senderFp: String,
        senderName: String,
    ): Boolean = when (json.optString("t")) {
        "fileOffer" -> {
            handleOffer(context, json, session, fio, senderFp, senderName)
            true
        }

        else -> false
    }

    private suspend fun handleOffer(
        context: Context,
        json: JSONObject,
        session: SessionManager.Session,
        fio: FrameIO,
        senderFp: String,
        senderName: String,
    ) = withContext(Dispatchers.IO) {
        val token = json.optString("token")
        val files = mutableListOf<FileMeta>()
        val arr = json.optJSONArray("files") ?: return@withContext
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            files.add(
                FileMeta(
                    o.getString("n"), o.getLong("s"), o.getString("h"),
                    o.optString("m").takeIf { it.isNotEmpty() },
                ),
            )
        }
        if (token.isEmpty() || files.isEmpty()) return@withContext
        val total = files.sumOf { it.size }
        val v2 = json.optInt("v", 1) == 2

        val paired = PairedDeviceStore.byFingerprint(senderFp)
        TransferRepository.upsert(
            TransferTask(
                id = token,
                direction = TransferDirection.RECV,
                peerFp = senderFp,
                peerName = senderName,
                files = files.toList(),
                state = TaskState.WAITING_MY_ACCEPT,
                totalBytes = total,
            ),
        )

        val accepted: Boolean = if (paired?.autoAcceptFiles == true) {
            true
        } else {
            val gate = CompletableDeferred<Boolean>()
            offerGates[token] = gate
            MessageBus.post(QzEvent.OfferRequested(token, senderFp, senderName, files.map { it.name }, total))
            val decision = runCatching { withTimeoutOrNull(120_000) { gate.await() } }.getOrNull()
            offerGates.remove(token)
            decision == true
        }

        if (!accepted) {
            session.send(fio, JSONObject().put("t", "fileDecline").put("token", token).toString().toByteArray(Charsets.UTF_8))
            TransferRepository.updateState(token, TaskState.DECLINED)
            return@withContext
        }

        // 断点续传位图
        val partDir = File(File(context.filesDir, "recv_parts"), token)
        val resume = JSONObject()
        val chunkSizeHint = readChunkSizeHint(partDir)
        val bitmaps = mutableListOf<ByteArray>()
        files.forEachIndexed { i, meta ->
            val bm = loadBitmap(partDir, i, meta, chunkSizeHint)
            bitmaps.add(bm)
            // 续传位图格式随发送方版本分叉：v2 位压缩 SACK，v1 每块一字节
            if (bm.isNotEmpty()) resume.put("$i", if (v2) SackCodec.pack(bm).toB64() else bm.toB64())
        }

        val sendLock = Any()
        fun sendCtrl(o: JSONObject) = synchronized(sendLock) { session.send(fio, o.toString().toByteArray(Charsets.UTF_8)) }

        sendCtrl(JSONObject().put("t", "fileAccept").put("token", token).put("resume", resume))

        // 等 fileReady（密钥 + 参数 + v2 有线协商）
        var fileKey: ByteArray? = null
        var streams = 4
        var usbCount = 0
        var unetCount = 0
        var chunkMiB = 4
        var senderWired: JSONObject? = null
        var ncmReq = false
        while (fileKey == null) {
            val payload = session.recv(fio) ?: run {
                TransferRepository.updateState(token, TaskState.FAILED, "连接中断")
                return@withContext
            }
            val readyJson = JSONObject(String(payload, Charsets.UTF_8))
            if (readyJson.optString("t") == "fileReady" && readyJson.optString("token") == token) {
                fileKey = readyJson.optString("key").b64()
                streams = readyJson.optInt("streams", 4).coerceIn(1, 8)
                usbCount = readyJson.optInt("usb", 0).coerceIn(0, 1)
                unetCount = if (v2) readyJson.optInt("unet", 0).coerceIn(0, 4) else 0
                chunkMiB = readyJson.optInt("chunkMiB", 4).coerceIn(1, 16)
                senderWired = readyJson.optJSONObject("wired")
                ncmReq = readyJson.optInt("ncmReq", 0) == 1
            }
        }

        // v2 有线协商：对端（host）请求本机（gadget+Shizuku）切 NCM
        val myWired = UsbNetLink.info()
        var ncmGo = false
        if (v2 && ncmReq && myWired.role == UsbNetLink.Role.GADGET && QzShizukuReady()) {
            QzLog.i(TAG, "对端请求 NCM：切换本机 gadget functions")
            if (UsbNetLink.switchToNcm()) {
                UsbNetLink.awaitGadgetIp(8_000)
                QzLog.i(TAG, "NCM 就绪：usb0=${UsbNetLink.gadgetIp}")
            } else {
                QzLog.w(TAG, "NCM 切换失败，有线侧退回 AOA/无")
            }
        } else if (v2 && myWired.role == UsbNetLink.Role.HOST && senderWired != null) {
            // 本机是 host 且对端是 gadget + Shizuku：请对端切（fileGo.ncmGo）
            val peerRole = senderWired.optString("role")
            ncmGo = UsbNetLink.ethNet == null && peerRole == "gadget" &&
                senderWired.optInt("szk", 0) == 1 && Build.VERSION.SDK_INT >= 35 &&
                UsbNetLink.enabled() && usbCount == 0 // AOA 与 NCM 互斥
        }

        // 预注册接收会话（数据连接即将到来）再放行发送方
        val state = ReceiverState(
            token, files, fileKey!!, chunkMiB shl 20, partDir, bitmaps,
            v2 = v2, directWrite = Build.VERSION.SDK_INT >= 29,
        )
        receivers[token] = state
        state.prepareFiles(context)
        TransferRepository.updateState(token, TaskState.RUNNING)
        WifiLocks.holdTransfer(context)
        sendCtrl(
            JSONObject().put("t", "fileGo").put("token", token)
                .apply {
                    if (v2) {
                        put("v", 2)
                        put("wired", wiredJson(UsbNetLink.info()))
                        put("ncmGo", if (ncmGo) 1 else 0)
                    }
                },
        )

        // v2 SACK/credit 上报器（含 wiredUp：host 等 DHCP 拿到地址后补报）
        var reporter: kotlinx.coroutines.Job? = null
        if (v2) {
            reporter = scope?.launch(Dispatchers.IO) {
                var wiredUpSent = UsbNetLink.ethIp != null
                while (receivers.containsKey(token)) {
                    delay(2_000)
                    runCatching {
                        state.sackSnapshot().forEach { (f, packed) ->
                            sendCtrl(JSONObject().put("t", "sack").put("token", token).put("f", f).put("bits", packed.toB64()))
                        }
                        val credits = state.laneConsumption()
                        if (credits.isNotEmpty()) {
                            sendCtrl(
                                JSONObject().put("t", "credit").put("token", token)
                                    .put("c", JSONObject().apply { credits.forEach { (k, v) -> put(k.toString(), v) } }),
                            )
                        }
                        if (!wiredUpSent && UsbNetLink.ethIp != null) {
                            wiredUpSent = true
                            sendCtrl(JSONObject().put("t", "wiredUp").put("token", token).put("ip", UsbNetLink.ethIp))
                        }
                    }
                }
            }
        }

        try {
            state.awaitCompletion(streams + usbCount + unetCount)
            // v2 补发轮（D1 修复核心）：收尾扫缺失块 → fileRepair → 等补齐，两轮无进展放弃
            if (v2) {
                var rounds = 0
                var idleRounds = 0
                while (rounds < 6 && idleRounds < 2) {
                    val miss = state.missingChunks(4096)
                    if (miss.isEmpty()) break
                    QzLog.w(TAG, "补发轮 #${rounds + 1}：缺 ${miss.size} 块")
                    sendCtrl(
                        JSONObject().put("t", "fileRepair").put("token", token)
                            .put("miss", JSONArray().apply { miss.forEach { (f, c) -> put(JSONArray().put(f).put(c)) } }),
                    )
                    rounds++
                    val before = miss.size
                    val cleared = withTimeoutOrNull(30_000) {
                        while (state.missingCount() > 0) delay(1_000)
                        true
                    } == true
                    val after = state.missingCount()
                    if (!cleared && after >= before) idleRounds++ else idleRounds = 0
                }
            }
            TransferRepository.updateState(token, TaskState.VERIFYING)
            val failed = state.verifyAndFinalize(context) { sendCtrl(JSONObject().put("t", "ping").put("token", token)) }
            if (failed.isEmpty()) {
                sendCtrl(JSONObject().put("t", "fileResult").put("token", token).put("ok", "ok"))
                TransferRepository.updateState(token, TaskState.DONE)
                files.forEachIndexed { i, meta ->
                    InboxStore.add(
                        InboxItem(
                            id = System.currentTimeMillis() + i,
                            fromFp = senderFp,
                            fromName = senderName,
                            kind = InboxKind.FILE,
                            content = meta.name,
                            filePath = state.finalPaths[i],
                            fileHash = meta.sha256,
                            timestamp = System.currentTimeMillis(),
                        ),
                    )
                }
                MessageBus.post(QzEvent.FilesReceived(token, senderFp, senderName, files.map { it.name }))
                QzLog.i(TAG, "接收完成：${files.size} 个文件 / $total 字节")
            } else {
                sendCtrl(
                    JSONObject().put("t", "fileResult").put("token", token).put("ok", "fail")
                        .put("errors", JSONArray(failed)),
                )
                TransferRepository.updateState(token, TaskState.FAILED, "校验失败：${failed.joinToString()}")
            }
        } catch (e: Exception) {
            QzLog.e(TAG, "接收失败：${e.message}")
            TransferRepository.updateState(token, TaskState.FAILED, e.message)
        } finally {
            reporter?.cancel()
            receivers.remove(token)
            if (TransferRepository.activeCount == 0) WifiLocks.releaseTransfer()
        }
    }

    fun acceptOffer(token: String) {
        offerGates[token]?.complete(true)
    }

    fun declineOffer(token: String) {
        offerGates[token]?.complete(false)
    }

    // ---------- 数据服务器 ----------

    private suspend fun dataAcceptLoop() = withContext(Dispatchers.IO) {
        val server = dataServer ?: return@withContext
        while (kotlin.coroutines.coroutineContext.isActive) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                if (dataServer == null) break
                continue
            }
            scope?.launch { handleDataConnection(client) }
        }
    }

    private suspend fun handleDataConnection(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            socket.tcpNoDelay = true
            socket.receiveBufferSize = 8 shl 20
            socket.soTimeout = 60_000
            val din = DataInputStream(BufferedInputStream(socket.getInputStream(), 512 shl 10))
            handleSinkStreamOnce(din, socket.getOutputStream())
        } catch (e: Exception) {
            QzLog.d(TAG, "数据连接结束：${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * TCP/USB 共用的单轮接收循环（USB 侧 BYE 后由外层继续监听补发轮）。
     * @return true = 干净 BYE 结束（流仍可继续读）；false = 流死亡/格式错误
     */
    private fun handleSinkStreamOnce(din: DataInputStream, out: OutputStream?): Boolean {
        var state: ReceiverState? = null
        try {
            val helloLen = din.readInt()
            if (helloLen !in 6..4096) return false
            if (din.readByte().toInt() != FRAME_HELLO) return false
            val tokenBytes = ByteArray(helloLen - 5)
            din.readFully(tokenBytes)
            din.readInt() // streamIdx
            // v2 hello 追加 lane 字节（帧长恰好多 1）
            val lane: Int? = if (helloLen == 1 + tokenBytes.size + 5) din.readByte().toInt() else null
            val state0 = receivers[String(tokenBytes, Charsets.UTF_8)]
            if (state0 == null) {
                out?.let { it.write(0); it.flush() } // 未知会话（正常拒绝路径，不计 BYE）
                return false
            }
            out?.let { it.write(1); it.flush() }
            state = state0
            state.registerStream()

            while (true) {
                val len = din.readInt()
                if (len == 1) {
                    if (din.readByte().toInt() == FRAME_BYE) break
                    continue
                }
                if (len !in 15..(20 shl 20)) return false
                when (din.readByte().toInt()) {
                    FRAME_CHUNK -> {
                        val fileIdx = din.readInt()
                        val chunkIdx = din.readLong()
                        val plainLen = din.readInt()
                        val ctLen = len - 17
                        if (ctLen != plainLen + 16) return false
                        val ct = ByteArray(ctLen)
                        din.readFully(ct)
                        state.writeChunk(fileIdx, chunkIdx, plainLen, ct, lane)
                    }

                    FRAME_HASHES -> {
                        val payload = ByteArray(len - 1)
                        din.readFully(payload)
                        runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull()
                            ?.optJSONArray("hs")?.let { hs ->
                                val list = (0 until hs.length()).map { hs.optString(it) }
                                state.setHashes(list)
                                QzLog.i(TAG, "收到全文件哈希（${list.size} 个）")
                            }
                    }

                    else -> return false
                }
            }
            return true
        } catch (e: Exception) {
            QzLog.d(TAG, "数据入流结束：${e.message}")
            return false
        } finally {
            state?.streamBye()
        }
    }

    // ---------- 工具 ----------

    private fun QzShizukuReady(): Boolean =
        io.github.srqingchen.qingzhou.core.shizuku.QzShizukuManager.isReady

    private fun wiredJson(w: UsbNetLink.WiredInfo): JSONObject = JSONObject()
        .put("role", w.role.name.lowercase())
        .put("szk", if (w.shizuku) 1 else 0)
        .put("usb0", w.usb0Ip ?: "")
        .put("eth", w.ethIp ?: "")
        .put("spd", w.speed ?: "")

    private fun parseMiss(arr: JSONArray?): List<Pair<Int, Long>> {
        if (arr == null) return emptyList()
        val out = ArrayList<Pair<Int, Long>>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.optJSONArray(i) ?: continue
            out.add(p.optInt(0) to p.optLong(1))
        }
        return out
    }

    /** v1 nonce：salt(4) + fileIdx(4) + chunkIdx(4)。 */
    private fun chunkNonce(token: String, fileIdx: Int, chunkIdx: Int): ByteArray {
        val salt = QzCrypto.blake2b256(token.toByteArray(Charsets.UTF_8)).copyOfRange(0, 4)
        return salt + byteArrayOf(
            (fileIdx ushr 24).toByte(), (fileIdx ushr 16).toByte(), (fileIdx ushr 8).toByte(), fileIdx.toByte(),
            (chunkIdx ushr 24).toByte(), (chunkIdx ushr 16).toByte(), (chunkIdx ushr 8).toByte(), chunkIdx.toByte(),
        )
    }

    /**
     * v2 nonce：salt(4) + fileIdx(3) + chunkIdx(4) + lane(1)。
     * nonce 覆盖 lane 是 v2 的硬要求：补发/尾块冗余会跨链路重复发送同一块，
     * lane 进入 nonce 保证任何两次加密都不复用 (key, nonce)；同链路重发产生
     * 相同密文（明文相同的重加密），仅泄露「等价性」——已是公开信息（chunkIdx 相同）。
     */
    private fun chunkNonceV2(token: String, fileIdx: Int, chunkIdx: Int, lane: Int): ByteArray {
        val salt = QzCrypto.blake2b256(token.toByteArray(Charsets.UTF_8)).copyOfRange(0, 4)
        return salt + byteArrayOf(
            (fileIdx ushr 16).toByte(), (fileIdx ushr 8).toByte(), fileIdx.toByte(),
            (chunkIdx ushr 24).toByte(), (chunkIdx ushr 16).toByte(), (chunkIdx ushr 8).toByte(), chunkIdx.toByte(),
            lane.toByte(),
        )
    }

    /** v1 token：SHA-256(双方指纹 + 每文件 name+sha256)。 */
    private fun deterministicToken(myFp: String, peerFp: String, metas: List<FileMeta>): String {
        val md = MessageDigest.getInstance("SHA-256")
        listOf(myFp, peerFp).sorted().forEach { md.update(it.toByteArray(Charsets.UTF_8)) }
        metas.forEach {
            md.update(it.name.toByteArray(Charsets.UTF_8))
            md.update(it.sha256.toByteArray(Charsets.UTF_8))
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    /** v2 token：SHA-256(双方指纹 + 每文件 name+size+mtime)—— 零暂存下不依赖预算哈希。 */
    private fun deterministicTokenV2(myFp: String, peerFp: String, metas: List<FileMeta>): String {
        val md = MessageDigest.getInstance("SHA-256")
        listOf(myFp, peerFp).sorted().forEach { md.update(it.toByteArray(Charsets.UTF_8)) }
        metas.forEach {
            md.update(it.name.toByteArray(Charsets.UTF_8))
            md.update(it.size.toString().toByteArray(Charsets.UTF_8))
            md.update((it.mtimeEpoch ?: 0L).toString().toByteArray(Charsets.UTF_8))
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    /** v2 零暂存源：SAF URI → seekable pfd 直读；不可 seek 返回 null（调用方走暂存）。 */
    private fun prepareSource(context: Context, uri: Uri): SourceHandle? = runCatching {
        val cr = context.contentResolver
        val name = queryName(cr, uri) ?: uri.lastPathSegment ?: "file"
        val mime = cr.getType(uri)
        val mtime = queryMtime(cr, uri)
        val size = querySize(cr, uri)
        val afd = cr.openAssetFileDescriptor(uri, "r") ?: return null
        val fis = java.io.FileInputStream(afd.parcelFileDescriptor.fileDescriptor)
        val channel = try {
            fis.channel.also { it.position(0) } // position 探测：管道型 provider 抛 IOException
        } catch (e: Exception) {
            runCatching { fis.close() }
            runCatching { afd.close() }
            return null
        }
        val finalSize = if (size != null && size >= 0) size else afd.length
        SourceHandle(FileMeta(name, finalSize, "", mime, mtime), channel, null, listOf(fis, afd))
    }.getOrNull()

    /** v1 暂存源：整文件拷缓存 + 顺带 SHA-256（v2 不可 seek 源同样走此路径，哈希即得）。 */
    private fun stageToSource(context: Context, uri: Uri, dst: File): SourceHandle? = runCatching {
        dst.parentFile?.mkdirs()
        val cr = context.contentResolver
        val name = queryName(cr, uri) ?: dst.name
        val mime = cr.getType(uri)
        val mtime = queryMtime(cr, uri)
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        cr.openInputStream(uri)!!.use { input ->
            dst.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                    size += n
                }
            }
        }
        val raf = RandomAccessFile(dst, "r")
        SourceHandle(
            FileMeta(name, size, md.digest().joinToString("") { "%02x".format(it) }, mime, mtime),
            raf.channel,
            dst,
            listOf(raf),
        )
    }.getOrNull()

    private fun queryName(cr: android.content.ContentResolver, uri: Uri): String? =
        runCatching {
            cr.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()

    private fun querySize(cr: android.content.ContentResolver, uri: Uri): Long? =
        runCatching {
            cr.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getLong(idx) else null
            }
        }.getOrNull()

    private fun queryMtime(cr: android.content.ContentResolver, uri: Uri): Long? = runCatching {
        cr.query(uri, null, null, null, null)?.use { c ->
            for (col in listOf(MediaStore.MediaColumns.DATE_MODIFIED, "last_modified", "lastModified")) {
                val idx = c.getColumnIndex(col)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) return c.getLong(idx)
            }
            null
        }
    }.getOrNull()

    private fun readChunkSizeHint(partDir: File): Int = runCatching {
        val metaFile = File(partDir, "meta.json")
        if (metaFile.exists()) JSONObject(metaFile.readText()).optInt("chunk", 4).coerceAtLeast(1) else 4
    }.getOrDefault(4)

    private fun loadBitmap(partDir: File, fileIdx: Int, meta: FileMeta, chunkMiB: Int): ByteArray {
        val bmFile = File(partDir, "$fileIdx.bm")
        if (!bmFile.exists()) return ByteArray(0)
        val chunkSize = chunkMiB shl 20
        val chunks = ((meta.size + chunkSize - 1) / chunkSize).toInt()
        val bytes = bmFile.readBytes()
        return ByteArray(chunks).also { System.arraycopy(bytes, 0, it, 0, minOf(bytes.size, chunks)) }
    }

    internal fun sanitize(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)

    // ================= 接收状态机 =================

    /** 接收块落盘目标：.part 文件（RandomAccessFile）或 MediaStore pending 行（pfd 通道）。 */
    private interface ChunkSink {
        fun writeAt(offset: Long, plain: ByteArray)
        fun readHash(size: Long): String?
        fun close()
    }

    private class RafSink(private val raf: RandomAccessFile) : ChunkSink {
        override fun writeAt(offset: Long, plain: ByteArray) = synchronized(raf) {
            raf.seek(offset)
            raf.write(plain)
        }

        override fun readHash(size: Long): String? = runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1 shl 20)
            synchronized(raf) {
                raf.seek(0)
                var remain = size
                while (remain > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), remain).toInt())
                    if (n < 0) break
                    md.update(buf, 0, n)
                    remain -= n
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()

        override fun close() {
            runCatching { raf.close() }
        }
    }

    /** MediaStore pending 行直写（D5）：写走 FileOutputStream 通道（position+write），校验走独立只读通道。 */
    private class PendingUriSink(private val pfd: ParcelFileDescriptor) : ChunkSink {
        private val fos = java.io.FileOutputStream(pfd.fileDescriptor)
        private val wch = fos.channel

        override fun writeAt(offset: Long, plain: ByteArray) {
            synchronized(wch) {
                wch.position(offset)
                wch.write(java.nio.ByteBuffer.wrap(plain))
            }
        }

        override fun readHash(size: Long): String? = runCatching {
            // 校验期无并发写；同 fd 只读通道此刻单线程，共享 fd 位置无碍
            java.io.FileInputStream(pfd.fileDescriptor).use { fis ->
                val ch = fis.channel
                val md = MessageDigest.getInstance("SHA-256")
                val bb = java.nio.ByteBuffer.allocate(1 shl 20)
                ch.position(0)
                var remain = size
                while (remain > 0) {
                    bb.clear()
                    bb.limit(minOf(bb.capacity().toLong(), remain).toInt())
                    val n = ch.read(bb)
                    if (n < 0) break
                    md.update(bb.array(), 0, n)
                    remain -= n
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }
        }.getOrNull()

        override fun close() {
            runCatching { wch.close() }
            runCatching { fos.close() }
            runCatching { pfd.close() }
        }
    }


    /**
     * 接收状态（v2 重构）：
     * - 位图常驻内存（D3），周期落盘（每 64 块或 2s，BYE/完成强制）；
     * - 直写 MediaStore pending 行（D5，API 29+），校验通过仅翻 IS_PENDING；任一环节失败回退 .part；
     * - 按 lane 统计消费速率（接收端信用的数据源）+ SACK 快照 + 缺失清单（补发协议数据源）。
     */
    private class ReceiverState(
        val token: String,
        val files: MutableList<FileMeta>,
        val fileKey: ByteArray,
        private val chunkSize: Int,
        val partDir: File,
        resumeBitmaps: List<ByteArray>,
        val v2: Boolean,
        val directWrite: Boolean,
    ) {
        private val sinks = ConcurrentHashMap<Int, ChunkSink>()
        private val bitmaps = Array(files.size) { f ->
            ByteArray(((files[f].size + chunkSize - 1) / chunkSize).toInt()).also { bm ->
                resumeBitmaps.getOrNull(f)?.let { r -> System.arraycopy(r, 0, bm, 0, minOf(r.size, bm.size)) }
            }
        }
        private val doneBytes =
            AtomicLong(bitmaps.sumOf { bm -> bm.count { it == 1.toByte() }.toLong() * chunkSize })
        private val byeCount = AtomicLong(0)
        private val laneBytes = ConcurrentHashMap<Int, Long>()
        private val laneReported = ConcurrentHashMap<Int, Long>()
        private val newSinceFlush = AtomicInteger(0)
        @Volatile
        private var lastFlush = 0L
        @Volatile
        private var expectedStreams = -1
        @Volatile
        private var active = true
        private val completion = CompletableDeferred<Unit>()

        /** 直写模式：每文件 pending URI。 */
        private val directUris = arrayOfNulls<String>(files.size)
        private var directActive = false

        val finalPaths = arrayOfNulls<String?>(files.size)

        fun isActive(): Boolean = active

        /** v2 fileHashes 帧：零暂存源的哈希在发送后送达（v1 暂存源 offer 已带）。 */
        fun setHashes(hs: List<String>) {
            hs.forEachIndexed { i, h ->
                if (i < files.size && h.isNotEmpty() && files[i].sha256.isEmpty()) files[i] = files[i].copy(sha256 = h)
            }
        }

        fun prepareFiles(context: Context) {
            partDir.mkdirs()
            val metaFile = File(partDir, "meta.json")
            if (!metaFile.exists()) {
                metaFile.writeText(
                    JSONObject().put("chunk", chunkSize shr 20).put(
                        "files",
                        JSONArray().apply {
                            files.forEach {
                                put(JSONObject().put("n", it.name).put("s", it.size).put("h", it.sha256))
                            }
                        },
                    ).toString(),
                )
            }
            if (directWrite && tryPrepareDirect(context, metaFile)) {
                directActive = true
                QzLog.i(TAG, "接收直写 MediaStore 开启（跳过 .part 双写）")
            } else {
                files.forEachIndexed { i, meta ->
                    val part = File(partDir, "$i.part")
                    val raf = RandomAccessFile(part, "rw")
                    runCatching { raf.setLength(meta.size) }
                    sinks[i] = RafSink(raf)
                }
            }
            TransferRepository.updateProgress(token, doneBytes.get())
        }

        /** 直写准备：恢复已有 pending 行或新建；任一失败整体回退（返回 false）。 */
        private fun tryPrepareDirect(context: Context, metaFile: File): Boolean {
            val resolver = context.contentResolver
            val saved = runCatching {
                val arr = JSONObject(metaFile.readText()).optJSONArray("uris") ?: return false
                Array(arr.length()) { arr.optString(it) }
            }.getOrNull()
            if (saved != null && saved.size == files.size && saved.all { it.isNotEmpty() }) {
                // 恢复：重开 pending 行；失败则清位图重来（行可能已过期被系统清理）
                return try {
                    saved.forEachIndexed { i, uriStr ->
                        val uri = Uri.parse(uriStr)
                        val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("open $uriStr")
                        sinks[i] = PendingUriSink(pfd)
                        directUris[i] = uriStr
                    }
                    true
                } catch (e: Exception) {
                    QzLog.w(TAG, "直写恢复失败（重开 pending 行）：${e.message}")
                    sinks.values.forEach { runCatching { it.close() } }
                    sinks.clear()
                    resetFresh()
                    false
                }
            }
            // 新建：全部行创建成功才启用
            val created = mutableListOf<Uri>()
            return try {
                files.forEachIndexed { i, meta ->
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, sanitize(meta.name))
                        put(MediaStore.MediaColumns.MIME_TYPE, meta.mimeType ?: "application/octet-stream")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/QingZhou")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                        ?: throw IOException("insert #$i")
                    created.add(uri)
                    val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("open #$i")
                    sinks[i] = PendingUriSink(pfd)
                    directUris[i] = uri.toString()
                }
                metaFile.writeText(JSONObject(metaFile.readText()).put("uris", JSONArray(directUris.toList())).toString())
                true
            } catch (e: Exception) {
                QzLog.w(TAG, "直写准备失败（回退 .part）：${e.message}")
                created.forEach { runCatching { resolver.delete(it, null, null) } }
                sinks.values.forEach { runCatching { it.close() } }
                sinks.clear()
                false
            }
        }

        /** 恢复直写失败后从零开始（此前数据都在 pending 行里，随行一起丢失）。 */
        private fun resetFresh() {
            bitmaps.forEach { it.fill(0) }
            doneBytes.set(0)
            files.indices.forEach { File(partDir, "$it.bm").delete() }
            TransferRepository.updateProgress(token, 0)
        }

        fun registerStream() = Unit // 保留签名：完成判定改为 BYE 计数制

        /**
         * 每条数据连接（正常 BYE 或异常断开）恰好调用一次；超出期望值的 BYE 只累计不触发。
         */
        fun streamBye() {
            if (expectedStreams > 0 && byeCount.incrementAndGet() >= expectedStreams) {
                completion.complete(Unit)
            }
        }

        fun writeChunk(fileIdx: Int, chunkIdx: Long, plainLen: Int, ct: ByteArray, lane: Int?) {
            if (fileIdx < 0 || fileIdx >= files.size) return
            val bm = bitmaps[fileIdx]
            if (chunkIdx < 0 || chunkIdx >= bm.size || bm[chunkIdx.toInt()] == 1.toByte()) return
            val nonce = if (v2 && lane != null) chunkNonceV2(token, fileIdx, chunkIdx.toInt(), lane)
            else chunkNonce(token, fileIdx, chunkIdx.toInt())
            val plain = runCatching { QzCrypto.aesGcmDecrypt(fileKey, nonce, ct) }.getOrNull() ?: run {
                QzLog.e(TAG, "块校验失败 file=$fileIdx chunk=$chunkIdx —— 丢弃（补发轮将重取）")
                return
            }
            bm[chunkIdx.toInt()] = 1
            sinks[fileIdx]?.writeAt(chunkIdx * chunkSize, plain)
            doneBytes.addAndGet(plainLen.toLong())
            if (lane != null) laneBytes.merge(lane, plainLen.toLong(), Long::plus)
            TransferRepository.updateProgress(token, doneBytes.get())
            maybeFlushBitmaps(fileIdx, force = false)
        }

        /** D3：位图内存化，落盘节流（64 块或 2s 一次）。 */
        @Synchronized
        private fun maybeFlushBitmaps(fileIdx: Int, force: Boolean) {
            if (!force && newSinceFlush.incrementAndGet() < 64 && System.currentTimeMillis() - lastFlush < 2_000) return
            newSinceFlush.set(0)
            lastFlush = System.currentTimeMillis()
            runCatching { File(partDir, "$fileIdx.bm").writeBytes(bitmaps[fileIdx]) }
        }

        fun missingCount(): Int = bitmaps.sumOf { bm -> bm.count { it == 0.toByte() } }

        fun missingChunks(limit: Int): List<Pair<Int, Long>> {
            val out = ArrayList<Pair<Int, Long>>()
            for (f in bitmaps.indices) {
                val bm = bitmaps[f]
                for (c in bm.indices) {
                    if (bm[c] == 0.toByte()) {
                        out.add(f to c.toLong())
                        if (out.size >= limit) return out
                    }
                }
            }
            return out
        }

        fun allPresent(): Boolean = missingCount() == 0

        /** SACK 快照：自上次上报以来有变化的文件（位压缩）。 */
        @Synchronized
        fun sackSnapshot(): List<Pair<Int, ByteArray>> {
            val out = mutableListOf<Pair<Int, ByteArray>>()
            bitmaps.forEachIndexed { f, bm ->
                if (bm.any { it == 1.toByte() }) out.add(f to SackCodec.pack(bm))
            }
            return out
        }

        /** 接收端信用：各 lane 自上次上报的消费量 ×2 + 8MB 底数（BLEST：慢链路信用小）。 */
        @Synchronized
        fun laneConsumption(): Map<Int, Long> {
            val out = mutableMapOf<Int, Long>()
            laneBytes.forEach { (lane, total) ->
                val delta = total - (laneReported[lane] ?: 0L)
                laneReported[lane] = total
                out[lane] = (delta * 2 + (8L shl 20)).coerceIn(8L shl 20, 48L shl 20)
            }
            return out
        }

        suspend fun awaitCompletion(streams: Int) {
            if (allPresent()) return // 续传即完整 / 车道全没开也能走补发
            expectedStreams = streams
            if (byeCount.get() >= streams) completion.complete(Unit)
            // 全部 BYE / 10 分钟兜底 / 30s 无进展（链路中途死亡如拔线）→ 提前结束进入校验
            val done = kotlinx.coroutines.withTimeoutOrNull(600_000) {
                var lastBytes = doneBytes.get()
                while (!completion.isCompleted) {
                    kotlinx.coroutines.delay(5_000)
                    if (allPresent()) { // 位图收满即完成（v2：承诺车道未开也不必干等）
                        completion.complete(Unit)
                        break
                    }
                    val now = doneBytes.get()
                    if (now != lastBytes) {
                        lastBytes = now
                        continue
                    }
                    val progressed = kotlinx.coroutines.withTimeoutOrNull(25_000) {
                        while (doneBytes.get() == now && !completion.isCompleted) kotlinx.coroutines.delay(2_000)
                    }
                    if (progressed == null && doneBytes.get() == now) {
                        QzLog.w(TAG, "数据通道 $streams 条流 ${byeCount.get()} 已收尾，30s 无进展 → 提前进入补发/校验")
                        completion.complete(Unit)
                    }
                }
                true
            }
            if (done == null && !completion.isCompleted) throw IllegalStateException("数据通道超时未完成")
        }

        fun cancel(reason: String) {
            active = false
            runCatching { sinks.values.forEach { it.close() } }
            completion.completeExceptionally(IllegalStateException(reason))
        }

        /**
         * 校验 SHA-256 并落定。直写模式仅翻 IS_PENDING；.part 模式入库拷贝。
         * @param keepalive 长文件校验期心跳（防发送方控制通道读超时）
         * @return 失败文件名列表
         */
        fun verifyAndFinalize(context: Context, keepalive: (() -> Unit)? = null): List<String> {
            flushAllBitmaps()
            val failed = mutableListOf<String>()
            var lastPing = System.currentTimeMillis()
            files.forEachIndexed { i, meta ->
                if (keepalive != null && System.currentTimeMillis() - lastPing > 30_000) {
                    keepalive()
                    lastPing = System.currentTimeMillis()
                }
                val hash = if (meta.sha256.isEmpty()) "" else hashAt(i, meta)
                when {
                    meta.sha256.isEmpty() -> {
                        QzLog.w(TAG, "文件[${meta.name}]未收到全文件哈希（fileHashes 通道中断）")
                        failed.add(meta.name)
                    }

                    hash == meta.sha256 -> finalPaths[i] = finalize(context, i, meta)

                    else -> failed.add(meta.name)
                }
            }
            runCatching { sinks.values.forEach { it.close() } }
            if (directActive) {
                // 直写失败/未完成的行清理；成功行保留（已 IS_PENDING=0）
                files.forEachIndexed { i, meta ->
                    if (failed.contains(meta.name)) directUris[i]?.let {
                        runCatching { context.contentResolver.delete(Uri.parse(it), null, null) }
                    }
                }
            }
            runCatching { partDir.deleteRecursively() }
            return failed
        }

        private fun flushAllBitmaps() {
            bitmaps.forEachIndexed { f, _ -> maybeFlushBitmaps(f, force = true) }
        }

        private fun hashAt(i: Int, meta: FileMeta): String = sinks[i]?.readHash(meta.size) ?: ""

        private fun finalize(context: Context, i: Int, meta: FileMeta): String {
            if (directActive && directUris[i] != null) {
                val uri = Uri.parse(directUris[i])
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                if (runCatching { context.contentResolver.update(uri, done, null, null) }.getOrDefault(-1) == 1) {
                    return uri.toString()
                }
                QzLog.w(TAG, "直写落定失败（IS_PENDING 翻转）file=$i")
            }
            val part = File(partDir, "$i.part")
            return if (part.exists()) publishFinal(context, part, meta) else {
                // 直写模式下无 part 文件且翻转失败：报告失败由上层校验名单处理
                ""
            }
        }

        /** .part 模式入库（v1 路径）。 */
        private fun publishFinal(context: Context, part: File, meta: FileMeta): String {
            if (Build.VERSION.SDK_INT >= 29) {
                runCatching {
                    val resolver = context.contentResolver
                    val name = sanitize(meta.name)
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, meta.mimeType ?: "application/octet-stream")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/QingZhou")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                        ?: return fallbackCopy(part, name)
                    resolver.openOutputStream(uri)?.use { out ->
                        part.inputStream().use { it.copyTo(out, 1 shl 20) }
                    } ?: return fallbackCopy(part, name)
                    val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    resolver.update(uri, done, null, null)
                    return uri.toString()
                }.onFailure { QzLog.w(TAG, "MediaStore 入库失败，回退应用目录：${it.message}") }
            }
            return fallbackCopy(part, sanitize(meta.name))
        }

        private fun fallbackCopy(part: File, name: String): String {
            val dir = File(
                appContext!!.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "QingZhou",
            ).apply { mkdirs() }
            val dst = uniqueDest(File(dir, name))
            part.copyTo(dst, overwrite = true)
            return dst.absolutePath
        }

        private fun uniqueDest(dest: File): File {
            if (!dest.exists()) return dest
            val base = dest.nameWithoutExtension
            val ext = dest.extension
            var i = 1
            while (true) {
                val candidate = File(dest.parentFile, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
                if (!candidate.exists()) return candidate
                i++
            }
        }
    }

    /** 块调度器（v1）：跨文件顺序遍历，跳过续传已完成块；多流并发取块线程安全。 */
    private class ChunkScheduler(
        private val metas: List<FileMeta>,
        resumeBitmaps: List<ByteArray>?,
        private val chunkSize: Int,
    ) {
        data class Cursor(val fileIdx: Int, val chunkIdx: Long, val offset: Long, val length: Int)

        private val totalChunks = metas.map { ((it.size + chunkSize - 1) / chunkSize).toInt() }
        private val cursor = AtomicLong(0)
        private val skips: List<ByteArray>? = resumeBitmaps

        @kotlin.jvm.Synchronized
        fun next(): Cursor? {
            while (true) {
                val global = cursor.getAndIncrement()
                var acc = 0L
                var fileIdx = -1
                var chunkIdx = -1L
                for (i in totalChunks.indices) {
                    if (global < acc + totalChunks[i]) {
                        fileIdx = i
                        chunkIdx = global - acc
                        break
                    }
                    acc += totalChunks[i]
                }
                if (fileIdx < 0) return null
                val skip = skips?.getOrNull(fileIdx)
                if (skip != null && chunkIdx < skip.size && skip[chunkIdx.toInt()] == 1.toByte()) continue
                val offset = chunkIdx * chunkSize
                val remain = metas[fileIdx].size - offset
                if (remain <= 0) continue
                return Cursor(fileIdx, chunkIdx, offset, minOf(chunkSize.toLong(), remain).toInt())
            }
        }
    }
}
