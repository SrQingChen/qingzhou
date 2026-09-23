package io.github.srqingchen.qingzhou.core.network.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import io.github.srqingchen.qingzhou.core.common.QzLog
import io.github.srqingchen.qingzhou.core.crypto.QzCrypto
import io.github.srqingchen.qingzhou.core.crypto.IdentityStore
import io.github.srqingchen.qingzhou.core.data.InboxItem
import io.github.srqingchen.qingzhou.core.data.InboxKind
import io.github.srqingchen.qingzhou.core.data.InboxStore
import io.github.srqingchen.qingzhou.core.data.PairedDeviceStore
import io.github.srqingchen.qingzhou.core.data.SettingsStore
import io.github.srqingchen.qingzhou.core.data.b64
import io.github.srqingchen.qingzhou.core.data.toB64
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
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
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 文件传输引擎：分块（默认 4MB）+ 多 TCP 流（默认 4 条）并行 + 每块 AES-256-GCM
 * （ARMv8 硬件指令）+ 全文件 SHA-256 校验 + 基于确定性 token 的断点续传。
 *
 * 通道分工：
 * - 控制帧（offer/accept/ready/go/done/result）走 IK 加密会话（[SessionManager]）；
 * - 数据块走独立 TCP（:39530），逐块 AEAD —— 明文分块读盘利于管道化，
 *   校验按块独立，单块损坏只需重传该块。
 *
 * 安全：fileKey 只存在于加密控制帧中，数据通道伪造包无法通过 AEAD 校验。
 */
object FileTransferEngine {

    private const val TAG = "xfer"
    const val PORT_DATA = 39530

    private const val FRAME_HELLO = 1
    private const val FRAME_CHUNK = 2
    private const val FRAME_BYE = 3

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
        // USB 常驻接收循环：入流与 TCP 同一处理逻辑（纯读，与对端纯写互不冲突）
        io.github.srqingchen.qingzhou.core.network.usb.UsbAccessoryLink.sinkListener = { input ->
            handleSinkStream(java.io.DataInputStream(java.io.BufferedInputStream(input, 512 shl 10)), null)
        }
        io.github.srqingchen.qingzhou.core.network.usb.UsbAccessoryLink.start(context)
        QzLog.i(TAG, "数据通道已监听 :$PORT_DATA")
    }

    fun stop() {
        synchronized(this) {
            if (!started) return
            started = false
        }
        runCatching { dataServer?.close() }
        dataServer = null
        io.github.srqingchen.qingzhou.core.network.usb.UsbAccessoryLink.sinkListener = null
        io.github.srqingchen.qingzhou.core.network.usb.UsbAccessoryLink.stop()
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

    /** 发送文件（UI 入口）。全程进度见 [TransferRepository]。 */
    suspend fun sendFiles(peer: Peer, uris: List<Uri>): Result<Unit> = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext Result.failure(IllegalStateException("引擎未启动"))
        if (uris.isEmpty()) return@withContext Result.failure(IllegalArgumentException("未选择文件"))
        val identity = IdentityStore.peek() ?: return@withContext Result.failure(IllegalStateException("身份未就绪"))
        // 对端地址刷新：发现列表里的最新地址优先（旧地址可能是对方换网络前的陈旧 IP）
        val peer = DiscoveryEngine.peers.value.firstOrNull { it.fingerprint == peer.fingerprint }
            ?: peer

        // 1. 所选内容先落缓存（SAF URI 变为可按序随机读的文件），同时算 SHA-256
        QzLog.i(TAG, "发送请求：${uris.size} 个文件 → ${peer.name}@${peer.host}:${peer.controlPort}")
        val stagingDir = File(context.cacheDir, "send_src").apply { mkdirs() }
        val staged = mutableListOf<Pair<FileMeta, File>>()
        val srcIndex = AtomicInteger(0)
        for (uri in uris) {
            val pair = stage(context, uri, File(stagingDir, "${System.currentTimeMillis()}_${srcIndex.getAndIncrement()}.bin"))
                ?: run {
                    QzLog.e(TAG, "元数据准备失败（无法读取所选内容）：$uri")
                    return@withContext Result.failure(IllegalArgumentException("无法读取所选内容"))
                }
            staged.add(pair)
        }
        QzLog.i(TAG, "元数据就绪：${staged.joinToString(", ") { it.first.name }}")
        val metas = staged.map { it.first }
        val sources = staged.map { it.second }

        val token = deterministicToken(identity.fingerprintHex, peer.fingerprint, metas)
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
                    .toString().toByteArray(Charsets.UTF_8),
            )

            // 3. 等回应
            var resumeMaps: List<ByteArray>? = null
            while (true) {
                val payload = session.recv(fio)
                    ?: return@withSession Result.failure<Unit>(IllegalStateException("连接中断"))
                val json = JSONObject(String(payload, Charsets.UTF_8))
                when (json.optString("t")) {
                    "fileAccept" -> {
                        val resume = json.optJSONObject("resume")
                        if (resume != null) {
                            resumeMaps = metas.mapIndexed { i, _ ->
                                val bm = resume.optString("$i")
                                if (bm.isEmpty()) ByteArray(0) else bm.b64()
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

            // 4. 密钥与参数；对端就绪（fileGo）后开流。
            //    有线并行：USB AOA 链路可用 + 对端能力位含 USB + 设置开启 → 追加 1 条 USB sink
            val usbSink = settings.usbEnabled &&
                (peer.capabilities and io.github.srqingchen.qingzhou.core.model.Cap.USB) != 0 &&
                io.github.srqingchen.qingzhou.core.network.usb.UsbAccessoryLink.attached
            if (usbSink) QzLog.i(TAG, "有线+WiFi 并行：本条传输附加 USB sink")
            session.send(
                fio,
                JSONObject()
                    .put("t", "fileReady")
                    .put("token", token)
                    .put("key", fileKey.toB64())
                    .put("streams", settings.parallelStreams)
                    .put("usb", if (usbSink) 1 else 0)
                    .put("chunkMiB", settings.chunkSizeMiB)
                    .toString().toByteArray(Charsets.UTF_8),
            )
            var go = false
            while (!go) {
                val payload = session.recv(fio) ?: return@withSession Result.failure<Unit>(
                    IllegalStateException("连接中断（等待对端就绪）"),
                )
                val json = JSONObject(String(payload, Charsets.UTF_8))
                go = json.optString("t") == "fileGo" && json.optString("token") == token
            }

            // 5. 多流并行发送
            val failures = ConcurrentHashMap<Int, Throwable>()
            val scheduler = ChunkScheduler(metas, resumeMaps, chunkSize)
            coroutineScope {
                val jobs = (0 until settings.parallelStreams).map { streamIdx ->
                    async(Dispatchers.IO) {
                        sendStream(
                            peer, streamIdx, token, fileKey, scheduler,
                            sources, chunkSize, sentBytes, failures,
                        )
                    }
                }
                if (usbSink) {
                    jobs + async(Dispatchers.IO) {
                        // USB 纯写 sink：hello 不读应答（读端由常驻接收循环独占），异常 best-effort 补 bye
                        val out = io.github.srqingchen.qingzhou.core.network.usb.UsbAccessoryLink.obtainSendStream()
                        if (out == null) {
                            QzLog.w(TAG, "USB sink 获取失败，本条传输退回纯 WiFi")
                        } else {
                            sendUsbSink(out, settings.parallelStreams, token, fileKey, scheduler, sources, chunkSize, sentBytes)
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
            val payload = session.recv(fio)
            if (payload != null) {
                val json = JSONObject(String(payload, Charsets.UTF_8))
                if (json.optString("t") == "fileResult") result = json.optString("ok", "")
            }
            if (result != "ok") {
                Result.failure<Unit>(IllegalStateException("对方校验未通过"))
            } else {
                Result.success(Unit)
            }
            }
        }.onFailure { e ->
            if (e is kotlinx.coroutines.CancellationException) {
                QzLog.w(TAG, "发送已取消：$token")
            } else {
                QzLog.e(TAG, "发送流程异常：${e.message}")
            }
        }.getOrNull()

        sources.forEach { runCatching { it.delete() } }
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

    /** 单条数据流：hello → 依次取块 → 加密发送。 */
    private fun sendStream(
        peer: Peer,
        streamIdx: Int,
        token: String,
        fileKey: ByteArray,
        scheduler: ChunkScheduler,
        sources: List<File>,
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

            val buf = ByteArray(chunkSize)
            val openFiles = HashMap<Int, RandomAccessFile>()
            while (true) {
                val chunk = scheduler.next() ?: break
                val raf = openFiles.getOrPut(chunk.fileIdx) { RandomAccessFile(sources[chunk.fileIdx], "r") }
                raf.seek(chunk.offset)
                raf.readFully(buf, 0, chunk.length)
                val nonce = chunkNonce(token, chunk.fileIdx, chunk.chunkIdx.toInt())
                val ct = QzCrypto.aesGcmEncrypt(fileKey, nonce, buf.copyOf(chunk.length))
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
            openFiles.values.forEach { runCatching { it.close() } }
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

    /** USB 纯写 sink：块帧与 TCP 完全一致（接收端按 fileIdx+chunkIdx 天然聚合）。 */
    private fun sendUsbSink(
        rawOut: OutputStream,
        streamIdx: Int,
        token: String,
        fileKey: ByteArray,
        scheduler: ChunkScheduler,
        sources: List<File>,
        chunkSize: Int,
        sentBytes: AtomicLong,
    ) {
        val out = java.io.DataOutputStream(java.io.BufferedOutputStream(rawOut, 512 shl 10))
        try {
            val tokenBytes = token.toByteArray(Charsets.UTF_8)
            out.writeInt(1 + tokenBytes.size + 4)
            out.writeByte(FRAME_HELLO)
            out.write(tokenBytes)
            out.writeInt(streamIdx)
            out.flush()
            val buf = ByteArray(chunkSize)
            val openFiles = HashMap<Int, RandomAccessFile>()
            while (true) {
                val chunk = scheduler.next() ?: break
                val raf = openFiles.getOrPut(chunk.fileIdx) { RandomAccessFile(sources[chunk.fileIdx], "r") }
                raf.seek(chunk.offset)
                raf.readFully(buf, 0, chunk.length)
                val nonce = chunkNonce(token, chunk.fileIdx, chunk.chunkIdx.toInt())
                val ct = QzCrypto.aesGcmEncrypt(fileKey, nonce, buf.copyOf(chunk.length))
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
            openFiles.values.forEach { runCatching { it.close() } }
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
     *
     * 关键：同步（挂起）执行 —— 控制通道同一时刻只允许一个读取者。
     * 旧版用 scope.launch 异步接管，服务端主循环与引擎并发读同一条 socket，
     * fileReady 帧被主循环抢走 → 发送方等不到 fileGo → “连接中断”。
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

        val paired = PairedDeviceStore.byFingerprint(senderFp)
        TransferRepository.upsert(
            TransferTask(
                id = token,
                direction = TransferDirection.RECV,
                peerFp = senderFp,
                peerName = senderName,
                files = files,
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
            if (bm.isNotEmpty()) resume.put("$i", bm.toB64())
        }

        session.send(
            fio,
            JSONObject().put("t", "fileAccept").put("token", token).put("resume", resume).toString().toByteArray(Charsets.UTF_8),
        )

        // 等 fileReady（密钥 + 参数）
        var fileKey: ByteArray? = null
        var streams = 4
        var usbCount = 0
        var chunkMiB = 4
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
                chunkMiB = readyJson.optInt("chunkMiB", 4).coerceIn(1, 16)
            }
        }

        // 预注册接收会话（数据连接即将到来）再放行发送方
        val state = ReceiverState(token, files, fileKey!!, chunkMiB shl 20, partDir, bitmaps)
        receivers[token] = state
        state.prepareFiles()
        TransferRepository.updateState(token, TaskState.RUNNING)
        WifiLocks.holdTransfer(context)
        session.send(fio, JSONObject().put("t", "fileGo").put("token", token).toString().toByteArray(Charsets.UTF_8))

        try {
            state.awaitCompletion(streams + usbCount)
            TransferRepository.updateState(token, TaskState.VERIFYING)
            val failed = state.verifyAndFinalize(context)
            if (failed.isEmpty()) {
                session.send(
                    fio,
                    JSONObject().put("t", "fileResult").put("token", token).put("ok", "ok").toString().toByteArray(Charsets.UTF_8),
                )
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
                session.send(
                    fio,
                    JSONObject().put("t", "fileResult").put("token", token).put("ok", "fail")
                        .put("errors", JSONArray(failed)).toString().toByteArray(Charsets.UTF_8),
                )
                TransferRepository.updateState(token, TaskState.FAILED, "校验失败：${failed.joinToString()}")
            }
        } catch (e: Exception) {
            QzLog.e(TAG, "接收失败：${e.message}")
            TransferRepository.updateState(token, TaskState.FAILED, e.message)
        } finally {
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
            handleSinkStream(din, socket.getOutputStream())
        } catch (e: Exception) {
            QzLog.d(TAG, "数据连接结束：${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * TCP/USB 共用的接收循环。BYE 计数在循环出口闭环（正常 BYE / 流断开都会走到）。
     * @param out TCP 版写 hello 应答；USB 纯写设计传 null（AEAD 校验即认证，无需应答）
     */
    private fun handleSinkStream(din: DataInputStream, out: OutputStream?) {
        var state: ReceiverState? = null
        try {
            val helloLen = din.readInt()
            if (helloLen !in 6..4096) return
            if (din.readByte().toInt() != FRAME_HELLO) return
            val tokenBytes = ByteArray(helloLen - 5)
            din.readFully(tokenBytes)
            din.readInt() // streamIdx
            val state0 = receivers[String(tokenBytes, Charsets.UTF_8)]
            if (state0 == null) {
                out?.let { it.write(0); it.flush() } // 未知会话（正常拒绝路径，不计 BYE）
                return
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
                if (len !in 15..(20 shl 20)) break
                if (din.readByte().toInt() != FRAME_CHUNK) break
                val fileIdx = din.readInt()
                val chunkIdx = din.readLong()
                val plainLen = din.readInt()
                val ctLen = len - 17
                if (ctLen != plainLen + 16) break
                val ct = ByteArray(ctLen)
                din.readFully(ct)
                state.writeChunk(fileIdx, chunkIdx, plainLen, ct)
            }
        } catch (e: Exception) {
            QzLog.d(TAG, "数据入流结束：${e.message}")
        } finally {
            state?.streamBye()
        }
    }

    // ---------- 工具 ----------

    private fun chunkNonce(token: String, fileIdx: Int, chunkIdx: Int): ByteArray {
        val salt = QzCrypto.blake2b256(token.toByteArray(Charsets.UTF_8)).copyOfRange(0, 4)
        return salt + byteArrayOf(
            (fileIdx ushr 24).toByte(), (fileIdx ushr 16).toByte(), (fileIdx ushr 8).toByte(), fileIdx.toByte(),
            (chunkIdx ushr 24).toByte(), (chunkIdx ushr 16).toByte(), (chunkIdx ushr 8).toByte(), chunkIdx.toByte(),
        )
    }

    private fun deterministicToken(myFp: String, peerFp: String, metas: List<FileMeta>): String {
        val md = MessageDigest.getInstance("SHA-256")
        listOf(myFp, peerFp).sorted().forEach { md.update(it.toByteArray(Charsets.UTF_8)) }
        metas.forEach {
            md.update(it.name.toByteArray(Charsets.UTF_8))
            md.update(it.sha256.toByteArray(Charsets.UTF_8))
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    /** SAF/任意 URI → 缓存文件 + 元数据（含 SHA-256）。 */
    private fun stage(context: Context, uri: Uri, dst: File): Pair<FileMeta, File>? = runCatching {
        val cr = context.contentResolver
        val name = queryName(cr, uri) ?: dst.name
        val mime = cr.getType(uri)
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
        FileMeta(name, size, md.digest().joinToString("") { "%02x".format(it) }, mime) to dst
    }.getOrNull()

    private fun queryName(cr: android.content.ContentResolver, uri: Uri): String? =
        runCatching {
            cr.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
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

    private class ReceiverState(
        val token: String,
        val files: List<FileMeta>,
        val fileKey: ByteArray,
        private val chunkSize: Int,
        val partDir: File,
        resumeBitmaps: List<ByteArray>,
    ) {
        private val rafs = ConcurrentHashMap<Int, RandomAccessFile>()
        private val doneBytes =
            AtomicLong(resumeBitmaps.sumOf { bm -> bm.count { it == 1.toByte() }.toLong() * chunkSize })
        private val byeCount = AtomicLong(0)
        @Volatile
        private var expectedStreams = -1
        private val completion = CompletableDeferred<Unit>()

        val finalPaths = arrayOfNulls<String?>(files.size)

        /**
         * 落地：API 29+ 经 MediaStore 透写进公共 Download/QingZhou（真正写入内容，
         * 旧版只 insert 一条空记录）；低版本拷入应用外部目录。
         * @return 最终路径（content URI 或文件路径）
         */
        fun publishFinal(context: Context, part: File, meta: FileMeta): String {
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

        fun prepareFiles() {
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
            files.forEachIndexed { i, meta ->
                val part = File(partDir, "$i.part")
                val raf = RandomAccessFile(part, "rw")
                raf.setLength(meta.size)
                rafs[i] = raf
            }
            TransferRepository.updateProgress(token, doneBytes.get())
        }

        fun registerStream() = Unit // 保留签名：完成判定改为 BYE 计数制

        /**
         * 每条数据连接（正常 BYE 或异常断开）恰好调用一次。
         * 旧版用“剩余连接数 ≤ 0”判定 —— 但数据通道要 1+ RTT 才连上，
         * 期望值刚设置时计数为 0，会瞬间误判完成（真机日志 22:36:01
         * “对端拒绝数据连接”即此雪崩）。现在必须收满 N 个 BYE 才完成。
         */
        fun streamBye() {
            if (expectedStreams > 0 && byeCount.incrementAndGet() >= expectedStreams) {
                completion.complete(Unit)
            }
        }

        fun writeChunk(fileIdx: Int, chunkIdx: Long, plainLen: Int, ct: ByteArray) {
            if (fileIdx < 0 || fileIdx >= files.size) return
            val plain = runCatching {
                QzCrypto.aesGcmDecrypt(fileKey, chunkNonce(token, fileIdx, chunkIdx.toInt()), ct)
            }.getOrNull() ?: run {
                QzLog.e(TAG, "块校验失败 file=$fileIdx chunk=$chunkIdx —— 丢弃")
                return
            }
            val isNew = updateBitmap(fileIdx, chunkIdx)
            if (isNew) {
                val raf = rafs[fileIdx] ?: return
                synchronized(raf) {
                    raf.seek(chunkIdx * chunkSize)
                    raf.write(plain, 0, plainLen)
                }
                doneBytes.addAndGet(plainLen.toLong())
                TransferRepository.updateProgress(token, doneBytes.get())
            }
        }

        @Synchronized
        private fun updateBitmap(fileIdx: Int, chunkIdx: Long): Boolean {
            val bmFile = File(partDir, "$fileIdx.bm")
            val bm = if (bmFile.exists()) {
                bmFile.readBytes().toMutableList()
            } else {
                MutableList(((files[fileIdx].size + chunkSize - 1) / chunkSize).toInt()) { 0.toByte() }
            }
            if (chunkIdx >= bm.size || bm[chunkIdx.toInt()] == 1.toByte()) return false
            bm[chunkIdx.toInt()] = 1
            bmFile.writeBytes(bm.toByteArray())
            return true
        }

        suspend fun awaitCompletion(streams: Int) {
            expectedStreams = streams
            if (byeCount.get() >= streams) completion.complete(Unit)
            // 全部 BYE / 10 分钟兜底 / 30s 无进展（链路中途死亡如拔线）→ 提前结束进入校验
            val done = kotlinx.coroutines.withTimeoutOrNull(600_000) {
                var lastBytes = doneBytes.get()
                while (!completion.isCompleted) {
                    kotlinx.coroutines.delay(5_000)
                    val now = doneBytes.get()
                    if (now != lastBytes) {
                        lastBytes = now
                        continue
                    }
                    val progressed = kotlinx.coroutines.withTimeoutOrNull(25_000) {
                        while (doneBytes.get() == now && !completion.isCompleted) kotlinx.coroutines.delay(2_000)
                    }
                    if (progressed == null && doneBytes.get() == now) {
                        QzLog.w(TAG, "数据通道 ${streams} 条流 ${byeCount.get()} 已收尾，30s 无进展 → 提前校验")
                        completion.complete(Unit)
                    }
                }
                true
            }
            if (done == null && !completion.isCompleted) throw IllegalStateException("数据通道超时未完成")
        }

        fun cancel(reason: String) {
            runCatching { rafs.values.forEach { it.close() } }
            completion.completeExceptionally(IllegalStateException(reason))
        }

        /** 校验 SHA-256 并把 .part 提升为正式文件。返回失败文件名列表。 */
        fun verifyAndFinalize(context: Context): List<String> {
            val failed = mutableListOf<String>()
            files.forEachIndexed { i, meta ->
                val part = File(partDir, "$i.part")
                val md = MessageDigest.getInstance("SHA-256")
                RandomAccessFile(part, "r").use { raf ->
                    val buf = ByteArray(1 shl 20)
                    var remain = meta.size
                    while (remain > 0) {
                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), remain).toInt())
                        if (n < 0) break
                        md.update(buf, 0, n)
                        remain -= n
                    }
                }
                val hash = md.digest().joinToString("") { "%02x".format(it) }
                if (hash == meta.sha256) {
                    finalPaths[i] = publishFinal(context, part, meta)
                } else {
                    failed.add(meta.name)
                }
            }
            runCatching { rafs.values.forEach { it.close() } }
            runCatching { partDir.deleteRecursively() }
            return failed
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

    /** 块调度器：跨文件顺序遍历，跳过续传已完成块；多流并发取块线程安全。 */
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
