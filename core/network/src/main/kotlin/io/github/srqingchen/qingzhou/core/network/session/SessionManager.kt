package io.github.srqingchen.qingzhou.core.network.session

import android.content.Context
import io.github.srqingchen.qingzhou.core.common.QzLog
import io.github.srqingchen.qingzhou.core.crypto.Fingerprint
import io.github.srqingchen.qingzhou.core.crypto.IdentityStore
import io.github.srqingchen.qingzhou.core.crypto.NoiseIk
import io.github.srqingchen.qingzhou.core.crypto.NoiseXx
import io.github.srqingchen.qingzhou.core.data.InboxStore
import io.github.srqingchen.qingzhou.core.data.PairedDevice
import io.github.srqingchen.qingzhou.core.data.PairedDeviceStore
import io.github.srqingchen.qingzhou.core.data.SettingsStore
import io.github.srqingchen.qingzhou.core.data.b64
import io.github.srqingchen.qingzhou.core.data.toB64
import io.github.srqingchen.qingzhou.core.model.AnnouncePacket
import io.github.srqingchen.qingzhou.core.model.Peer
import io.github.srqingchen.qingzhou.core.model.Protocol
import io.github.srqingchen.qingzhou.core.network.MessageBus
import io.github.srqingchen.qingzhou.core.network.QzEvent
import io.github.srqingchen.qingzhou.core.network.discovery.DiscoveryEngine
import io.github.srqingchen.qingzhou.core.network.messaging.MicroMessenger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话管理：控制通道 TCP 服务器 + 配对（Noise XX，双方人工确认）+
 * IK 会话（已配对 1-RTT）+ 应用帧路由。
 *
 * 安全红线：陌生静态公钥的 IK 连接直接拒绝；配对双端展示指纹短码人工核对。
 */
object SessionManager {

    private const val TAG = "session"
    private const val PAIR_TIMEOUT_MS = 60_000L

    /** UI 可见的配对流程状态。 */
    data class PendingPair(
        val id: String,
        val amInitiator: Boolean,
        val remoteFp: String,
        val remoteName: String,
        val remoteModel: String,
        val remoteShortCode: String, // 对端指纹短码（双方设备上显示同一值，人工核对）
        val myShortCode: String,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val _pendingPairs = kotlinx.coroutines.flow.MutableStateFlow<Map<String, PendingPair>>(emptyMap())
    val pendingPairs: kotlinx.coroutines.flow.StateFlow<Map<String, PendingPair>> = _pendingPairs

    /** 响应方等待用户确认的闸门（pairId → 确认结果）。 */
    private val acceptGates = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private var scope: CoroutineScope? = null

    /** 引擎启动时的上下文（应用帧回调移交文件引擎用）。 */
    @Volatile
    private var engineContext: Context? = null
    private var server: ServerSocket? = null

    @Volatile
    private var started = false

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            engineContext = context.applicationContext
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runCatching {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(Protocol.PORT_CONTROL))
            server = s
        }.onFailure {
            QzLog.e(TAG, "控制通道监听失败：${it.message}")
            return
        }
        scope?.launch { acceptLoop() }
        QzLog.i(TAG, "控制通道已监听 :${Protocol.PORT_CONTROL}")
    }

    fun stop() {
        synchronized(this) {
            if (!started) return
            started = false
        }
        runCatching { server?.close() }
        server = null
        acceptGates.values.forEach { it.cancel() }
        acceptGates.clear()
        _pendingPairs.value = emptyMap()
        scope?.cancel()
        scope = null
    }

    // ================= 发起方：配对 =================

    /**
     * 与未配对设备发起 Noise XX 配对。发起方的确认 = UI 调用本方法本身；
     * 响应方确认经通知/对话框 → [acceptPair]/[declinePair]。
     */
    suspend fun initiatePair(peer: Peer): Result<PairedDevice> = withContext(Dispatchers.IO) {
        val identity = IdentityStore.peek()!!
        val pairId = UUID.randomUUID().toString()
        val noise = NoiseXx(identity.staticPrivate, initiator = true)

        publishPair(
            PendingPair(
                id = pairId,
                amInitiator = true,
                remoteFp = peer.fingerprint,
                remoteName = peer.name,
                remoteModel = peer.model,
                remoteShortCode = peer.shortCode,
                myShortCode = identity.shortCode,
            ),
        )

        var socket: Socket? = null
        try {
            socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(peer.host, peer.controlPort), 3_000)
            socket.soTimeout = PAIR_TIMEOUT_MS.toInt()
            val fio = FrameIO(socket.getInputStream(), socket.getOutputStream())

            // M1: [2B 名长][announce JSON][noise e]
            val announceJson = announceForPairing(identity).encode(Protocol.UDP_ANNOUNCE)
            val e1 = noise.writeMessage1()
            val lenHeader = byteArrayOf((announceJson.size shr 8).toByte(), announceJson.size.toByte())
            fio.writeFrame(Protocol.FRAME_PAIR_XX_M1, lenHeader + announceJson + e1)

            // M2
            val m2 = fio.readFrame() ?: throw SecurityException("对端未响应配对（超时或拒绝）")
            when (m2.type) {
                Protocol.FRAME_PAIR_DECLINE -> {
                    MessageBus.post(QzEvent.PairDeclined(peer.fingerprint))
                    return@withContext Result.failure(SecurityException("对方拒绝了配对"))
                }
                Protocol.FRAME_PAIR_XX_M2 -> Unit
                else -> throw SecurityException("配对协议错误：未知帧 ${m2.type}")
            }
            val remoteStatic = noise.readMessage2(m2.payload)
            val remoteFp = Fingerprint.hex(remoteStatic)
            if (remoteFp != peer.fingerprint) {
                throw SecurityException("身份不一致：声称为 ${peer.fingerprint.take(8)}… 实为 ${remoteFp.take(8)}…（疑似中间人）")
            }

            // M3
            fio.writeFrame(Protocol.FRAME_PAIR_XX_M3, noise.writeMessage3())

            val device = PairedDevice(
                fingerprint = remoteFp,
                staticPublicB64 = remoteStatic.toB64(),
                name = peer.name,
                model = peer.model,
                lastAddress = peer.host,
            )
            PairedDeviceStore.upsert(device)
            DiscoveryEngine.probeNow()
            _pendingPairs.value = _pendingPairs.value - pairId
            MessageBus.post(QzEvent.PairCompleted(remoteFp, peer.name))
            QzLog.i(TAG, "配对完成（发起方）：${peer.name}")
            Result.success(device)
        } catch (e: Exception) {
            _pendingPairs.value = _pendingPairs.value - pairId
            val reason = e.message ?: "配对失败"
            MessageBus.post(QzEvent.PairFailed(peer.fingerprint, reason))
            runCatching { socket?.close() }
            Result.failure(e)
        } finally {
            runCatching { socket?.close() }
        }
    }

    // ================= 响应方：接受/拒绝 =================

    fun acceptPair(pairId: String) {
        acceptGates[pairId]?.complete(true)
    }

    fun declinePair(pairId: String) {
        acceptGates[pairId]?.complete(false)
    }

    fun cancelPair(pairId: String) {
        _pendingPairs.value = _pendingPairs.value - pairId
        acceptGates.remove(pairId)?.cancel()
    }

    // ================= 发送文本 =================

    /**
     * 发文本给对端：已配对优先微消息（毫秒级），失败/未配对回退 TCP 会话；
     * 未配对先走配对流程。
     */
    suspend fun sendText(peer: Peer, text: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (text.isEmpty()) return@withContext Result.failure(IllegalArgumentException("内容为空"))
        if (PairedDeviceStore.byFingerprint(peer.fingerprint) == null) {
            val pair = initiatePair(peer)
            if (pair.isFailure) return@withContext Result.failure(pair.exceptionOrNull()!!)
        }
        val fresh = DiscoveryEngine.peers.value.firstOrNull { it.fingerprint == peer.fingerprint } ?: peer

        // 路径一：微消息（及时性优先）
        if (MicroMessenger.send(fresh, text)) {
            MessageBus.post(QzEvent.TextSent(fresh.fingerprint, viaMicro = true))
            return@withContext Result.success(Unit)
        }

        // 路径二：TCP 会话（IK 1-RTT）
        val sent = withSession(fresh) { session, fio ->
            val json = JSONObject().put("t", "text").put("c", text).toString()
            session.send(fio, json.toByteArray(Charsets.UTF_8))
            fio.writeFrame(Protocol.FRAME_GOODBYE, ByteArray(0))
            true
        }
        if (sent == true) {
            MessageBus.post(QzEvent.TextSent(fresh.fingerprint, viaMicro = false))
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("发送失败：设备不在线或网络不通"))
        }
    }

    // ================= IK 会话 =================

    /** 会话（发起方视角）：split 后的密码态 + 对端信息。 */
    class Session(private val initiator: Boolean, sendCipher: io.github.srqingchen.qingzhou.core.crypto.CipherState, recvCipher: io.github.srqingchen.qingzhou.core.crypto.CipherState) {
        private val send = sendCipher
        private val recv = recvCipher
        val initiatorSide get() = initiator

        fun send(fio: FrameIO, payloadJson: ByteArray): Boolean = runCatching {
            fio.writeFrame(Protocol.FRAME_APP, send.encryptWithAd(ByteArray(0), payloadJson))
            true
        }.getOrDefault(false)

        fun recv(fio: FrameIO): ByteArray? {
            val frame = fio.readFrame() ?: return null
            if (frame.type != Protocol.FRAME_APP) return null
            return runCatching { recv.decryptWithAd(ByteArray(0), frame.payload) }.getOrNull()
        }
    }

    /** 打开一次性 IK 会话并执行块（用完即关）。块运行于 IO 上下文，可挂起。 */
    suspend fun <T> withSession(peer: Peer, block: suspend (Session, FrameIO) -> T): T? =
        withContext(Dispatchers.IO) {
            val identity = IdentityStore.peek() ?: return@withContext null
            val paired = PairedDeviceStore.byFingerprint(peer.fingerprint) ?: return@withContext null
            val remotePub = paired.staticPublicB64.b64()
            val noise = NoiseIk(identity.staticPrivate, initiator = true, remoteStaticPub = remotePub)
            val socket = Socket()
            var handshakeDone = false
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(peer.host, peer.controlPort), 3_000)
                // 长超时：发送方要先等对方点“接收”（最长 120s），再等收尾校验；
                // 短超时会在等待确认期间断读 —— 旧版“连接中断”的成因之一
                socket.soTimeout = 150_000
                val fio = FrameIO(socket.getInputStream(), socket.getOutputStream())
                val intro = JSONObject().put("n", SettingsStore.settings.value.displayName.ifEmpty { "青舟" }).toString()
                fio.writeFrame(Protocol.FRAME_SESSION_IK_M1, noise.writeMessage1(intro.toByteArray(Charsets.UTF_8)))
                val m2 = fio.readFrame() ?: return@withContext null
                if (m2.type != Protocol.FRAME_SESSION_IK_M2) return@withContext null
                noise.readMessage2(m2.payload)
                val (c1, c2) = noise.split()
                val session = Session(initiator = true, sendCipher = c1, recvCipher = c2)
                handshakeDone = true
                block(session, fio)
            } catch (e: Exception) {
                if (handshakeDone) throw e // 业务阶段失败原样上抛（避免误报“会话建立失败”）
                QzLog.w(TAG, "IK 会话失败：${e.message}")
                null
            } finally {
                runCatching { socket.close() }
            }
        }

    // ================= 服务器侧 =================

    private suspend fun acceptLoop() = withContext(Dispatchers.IO) {
        val serverSocket = server ?: return@withContext
        while (kotlin.coroutines.coroutineContext.isActive) {
            val client = try {
                serverSocket.accept()
            } catch (e: Exception) {
                if (server == null) break
                continue
            }
            scope?.launch { handleConnection(client) }
        }
    }

    private suspend fun handleConnection(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = PAIR_TIMEOUT_MS.toInt()
            val fio = FrameIO(socket.getInputStream(), socket.getOutputStream())
            val first = fio.readFrame() ?: return@withContext
            when (first.type) {
                Protocol.FRAME_PROBE -> handleProbe(fio, socket, first.payload)
                Protocol.FRAME_PAIR_XX_M1 -> handlePairM1(fio, socket, first.payload)
                Protocol.FRAME_SESSION_IK_M1 -> handleSessionM1(fio, socket, first.payload)
                else -> Unit
            }
        } catch (e: Exception) {
            QzLog.d(TAG, "连接处理异常：${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    /** 网关探测/直连探测：回 register。 */
    private fun handleProbe(fio: FrameIO, socket: Socket, payload: ByteArray) {
        val identity = IdentityStore.peek() ?: return
        if (payload.isNotEmpty()) {
            AnnouncePacket.decode(payload)?.let { (_, packet) ->
                if (packet.fingerprint != identity.fingerprintHex) {
                    socket.inetAddress?.hostAddress?.let { host ->
                        DiscoveryEngine.upsert(packet, host)
                    }
                }
            }
        }
        fio.writeFrame(Protocol.FRAME_REGISTER, announceForPairing(identity).encode(Protocol.UDP_ANNOUNCE))
    }

    /** 响应方配对：M1 → 等用户确认 → M2 → M3 → 入库。 */
    private suspend fun handlePairM1(fio: FrameIO, socket: Socket, payload: ByteArray) {
        val identity = IdentityStore.peek() ?: return
        if (payload.size < 34) return

        // 解析发起方 announce（明文元数据；真实身份以 Noise 解密静态公钥为准）
        val jsonLen = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        if (payload.size < 2 + jsonLen + 32) return
        val announceJson = payload.copyOfRange(2, 2 + jsonLen)
        val e1 = payload.copyOfRange(2 + jsonLen, payload.size)
        val (prefix, request) = AnnouncePacket.decode(announceJson) ?: return
        if (request.fingerprint == identity.fingerprintHex) return

        val noise = NoiseXx(identity.staticPrivate, initiator = false)
        runCatching { noise.readMessage1(e1) }.getOrElse {
            fio.writeFrame(Protocol.FRAME_PAIR_DECLINE, "协议错误".toByteArray())
            return
        }

        val pairId = UUID.randomUUID().toString()
        val gate = CompletableDeferred<Boolean>()
        acceptGates[pairId] = gate

        publishPair(
            PendingPair(
                id = pairId,
                amInitiator = false,
                remoteFp = request.fingerprint,
                remoteName = request.name,
                remoteModel = request.model,
                remoteShortCode = request.shortCode,
                myShortCode = identity.shortCode,
            ),
        )
        MessageBus.post(
            QzEvent.PairRequested(
                requestId = pairId,
                fingerprint = request.fingerprint,
                name = request.name,
                model = request.model,
                shortCode = request.shortCode,
            ),
        )
        QzLog.i(TAG, "收到配对请求 ← ${request.name}，等待确认")

        // 等待用户确认（60s）
        val accepted = withTimeoutOrNull(PAIR_TIMEOUT_MS) { gate.await() } ?: false
        acceptGates.remove(pairId)
        _pendingPairs.value = _pendingPairs.value - pairId

        if (!accepted) {
            fio.writeFrame(Protocol.FRAME_PAIR_DECLINE, "declined".toByteArray())
            MessageBus.post(QzEvent.PairDeclined(request.fingerprint))
            QzLog.i(TAG, "已拒绝配对：${request.name}")
            return
        }

        try {
            val m2 = noise.writeMessage2()
            fio.writeFrame(Protocol.FRAME_PAIR_XX_M2, m2)
            val m3 = fio.readFrame() ?: throw SecurityException("发起方未完成配对")
            if (m3.type != Protocol.FRAME_PAIR_XX_M3) throw SecurityException("配对协议错误")
            val initiatorStatic = noise.readMessage3(m3.payload)
            val realFp = Fingerprint.hex(initiatorStatic)
            if (realFp != request.fingerprint) {
                // 声称身份与密码学身份不符 —— 拒绝入库并告警
                QzLog.e(TAG, "配对身份不一致！announce=${request.fingerprint.take(8)}… noise=${realFp.take(8)}…")
                MessageBus.post(QzEvent.PairFailed(realFp, "对方声明身份与加密身份不一致，已拒绝"))
                return
            }
            val device = PairedDevice(
                fingerprint = realFp,
                staticPublicB64 = initiatorStatic.toB64(),
                name = request.name,
                model = request.model,
                lastAddress = socket.inetAddress?.hostAddress,
            )
            PairedDeviceStore.upsert(device)
            MessageBus.post(QzEvent.PairCompleted(realFp, request.name))
            QzLog.i(TAG, "配对完成（响应方）：${request.name}")
        } catch (e: Exception) {
            MessageBus.post(QzEvent.PairFailed(request.fingerprint, e.message ?: "配对失败"))
        }
    }

    /** 响应方 IK 会话：仅接受已配对设备的静态公钥。 */
    private suspend fun handleSessionM1(fio: FrameIO, socket: Socket, payload: ByteArray) {
        val identity = IdentityStore.peek() ?: return
        val noise = NoiseIk(identity.staticPrivate, initiator = false)

        val (initiatorStatic, intro) = runCatching { noise.readMessage1(payload) }.getOrElse {
            QzLog.w(TAG, "IK M1 解密失败（未配对或密钥不符）")
            return
        }
        val initiatorFp = Fingerprint.hex(initiatorStatic)
        val paired = PairedDeviceStore.byFingerprint(initiatorFp)
        if (paired == null) {
            QzLog.w(TAG, "拒绝陌生设备的 IK 会话（fp=${initiatorFp.take(8)}…）")
            return
        }
        runCatching {
            fio.writeFrame(Protocol.FRAME_SESSION_IK_M2, noise.writeMessage2(ByteArray(0)))
        }.getOrElse { return }

        val (c1, c2) = noise.split()
        val session = Session(initiator = false, sendCipher = c2, recvCipher = c1)
        PairedDeviceStore.updateLastSeen(initiatorFp, socket.inetAddress?.hostAddress)

        // 应用帧循环
        while (true) {
            val plaintext = runCatching { session.recv(fio) }.getOrNull() ?: break
            val json = runCatching { JSONObject(String(plaintext, Charsets.UTF_8)) }.getOrNull() ?: break
            // 文件传输帧优先移交引擎（引擎异步接管整个传输生命周期）
            if (engineContext != null && io.github.srqingchen.qingzhou.core.network.transfer.FileTransferEngine.onAppFrame(
                    engineContext!!, json, session, fio, initiatorFp, paired.name,
                )
            ) {
                continue
            }
            when (json.optString("t")) {
                "text" -> {
                    val content = json.optString("c")
                    if (content.isNotEmpty()) {
                        if (SettingsStore.settings.value.autoReceiveText) {
                            InboxStore.addText(initiatorFp, paired.name, content)
                        }
                        MessageBus.post(QzEvent.TextReceived(initiatorFp, paired.name, content, viaMicro = false))
                    }
                }
                else -> Unit
            }
        }
        QzLog.d(TAG, "IK 会话结束（${paired.name}）")
    }

    // ---------- 辅助 ----------

    private fun announceForPairing(identity: IdentityStore.Identity): AnnouncePacket {
        val settings = SettingsStore.settings.value
        return AnnouncePacket(
            protocolVersion = Protocol.VERSION,
            fingerprint = identity.fingerprintHex,
            name = settings.displayName.ifEmpty { "青舟·${android.os.Build.MODEL}" },
            model = android.os.Build.MODEL ?: "",
            apiLevel = android.os.Build.VERSION.SDK_INT,
            controlPort = Protocol.PORT_CONTROL,
            messagePort = Protocol.PORT_MESSAGE,
            capabilities = io.github.srqingchen.qingzhou.core.model.Cap.TEXT
                or io.github.srqingchen.qingzhou.core.model.Cap.FILE
                or io.github.srqingchen.qingzhou.core.model.Cap.USB,
            timestamp = System.currentTimeMillis(),
        )
    }

    private fun publishPair(pair: PendingPair) {
        _pendingPairs.value = _pendingPairs.value + (pair.id to pair)
    }
}
