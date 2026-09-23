package io.github.srqingchen.qingzhou.core.network.messaging

import android.content.Context
import io.github.srqingchen.qingzhou.core.common.QzLog
import io.github.srqingchen.qingzhou.core.crypto.IdentityStore
import io.github.srqingchen.qingzhou.core.crypto.aeadDecrypt
import io.github.srqingchen.qingzhou.core.crypto.aeadEncrypt
import io.github.srqingchen.qingzhou.core.crypto.dhShared
import io.github.srqingchen.qingzhou.core.crypto.hkdf
import io.github.srqingchen.qingzhou.core.crypto.sha256
import io.github.srqingchen.qingzhou.core.data.InboxStore
import io.github.srqingchen.qingzhou.core.data.PairedDeviceStore
import io.github.srqingchen.qingzhou.core.data.SettingsStore
import io.github.srqingchen.qingzhou.core.data.b64
import io.github.srqingchen.qingzhou.core.model.Peer
import io.github.srqingchen.qingzhou.core.model.Protocol
import io.github.srqingchen.qingzhou.core.network.MessageBus
import io.github.srqingchen.qingzhou.core.network.QzEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 微消息通道：已配对设备间 UDP 单包 AEAD 直达（免握手、毫秒级）。
 *
 * 密钥派生（两侧对称）：HKDF(SHA256("qz-micro-v1" || 排序后的双方指纹), DH(静态,静态))；
 * 指纹字典序小的一方使用 k1 发送 / k2 接收，另一方相反。
 * 载荷：[8B 计数器大端][AEAD(JSON)]；nonce = "QZM1" || 计数器；接收端滑动窗口抗重放。
 */
object MicroMessenger {

    private const val TAG = "micro"
    private const val SALT_PREFIX = "qz-micro-v1"
    private val NONCE_PREFIX = byteArrayOf(0x51, 0x5A, 0x4D, 0x31) // "QZM1"

    private var scope: CoroutineScope? = null
    private var socket: DatagramSocket? = null

    /** 每对端接收计数器（抗重放窗口）。 */
    private val lastCounter = ConcurrentHashMap<String, Long>()
    private val recentCounters = ConcurrentHashMap<String, MutableSet<Long>>()

    @Volatile
    private var started = false

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runCatching {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.bind(InetSocketAddress(Protocol.PORT_MESSAGE))
            socket = s
        }.onFailure {
            QzLog.e(TAG, "微消息端口绑定失败：${it.message}")
            return
        }
        scope?.launch { receiveLoop() }
        QzLog.i(TAG, "微消息通道已启动 (:${Protocol.PORT_MESSAGE})")
    }

    fun stop() {
        synchronized(this) {
            if (!started) return
            started = false
        }
        runCatching { socket?.close() }
        socket = null
        scope?.cancel()
        scope = null
    }

    /** 发送（尽力而为，无送达确认 —— 及时性优先）。失败返回 false 由调用方回退 TCP 会话。 */
    suspend fun send(peer: Peer, content: String): Boolean = withContext(Dispatchers.IO) {
        val identity = IdentityStore.peek() ?: return@withContext false
        val paired = PairedDeviceStore.byFingerprint(peer.fingerprint) ?: return@withContext false
        val keys = microKeys(identity.staticPrivate, paired) ?: return@withContext false
        val counter = (System.currentTimeMillis() * 1000) xor (identity.fingerprintHex.hashCode().toLong() and 0xFFFF)
        val json = JSONObject()
            .put("t", "text")
            .put("c", content)
            .put("id", identity.fingerprintHex)
            .put("n", SettingsStore.settings.value.displayName.ifEmpty { "青舟" })
            .toString()
        val plaintext = json.toByteArray(Charsets.UTF_8)
        if (plaintext.size + 8 > Protocol.MAX_MICRO) return@withContext false
        val nonce = NONCE_PREFIX + counterTo8(counter)
        val ct = aeadEncrypt(keys.first, nonce, ByteArray(0), plaintext)
        val packet = counterTo8(counter) + ct
        return@withContext runCatching {
            val s = socket ?: DatagramSocket()
            s.send(DatagramPacket(packet, packet.size, InetAddress.getByName(peer.host), peer.messagePort))
            QzLog.d(TAG, "微消息已发 → ${peer.name} (${packet.size}B)")
            true
        }.getOrElse {
            QzLog.w(TAG, "微消息发送失败：${it.message}")
            false
        }
    }

    private suspend fun receiveLoop() = withContext(Dispatchers.IO) {
        val buf = ByteArray(Protocol.MAX_MICRO + 64)
        while (kotlin.coroutines.coroutineContext.isActive) {
            val s = socket ?: break
            val packet = DatagramPacket(buf, buf.size)
            try {
                s.receive(packet)
            } catch (e: Exception) {
                if (socket == null || !socket!!.isBound) break
                continue
            }
            if (packet.length <= 8) continue
            handlePacket(buf.copyOf(packet.length))
        }
    }

    private fun handlePacket(data: ByteArray) {
        runCatching {
            val counter = counterFrom8(data.copyOfRange(0, 8))
            val ct = data.copyOfRange(8, data.size)
            val identity = IdentityStore.peek() ?: return

            // 需要先确定发送者才能选接收密钥：穷举已配对设备（数量极少，1~几台）
            val candidates = PairedDeviceStore.devices.value.values
            for (device in candidates) {
                val keys = microKeys(identity.staticPrivate, device) ?: continue
                val plaintext = runCatching {
                    aeadDecrypt(keys.second, NONCE_PREFIX + counterTo8(counter), ByteArray(0), ct)
                }.getOrNull() ?: continue

                val json = JSONObject(String(plaintext, Charsets.UTF_8))
                if (json.optString("t") != "text") continue
                val senderFp = json.optString("id")
                if (senderFp != device.fingerprint) continue // 声称身份与密钥不匹配 → 丢弃

                // 抗重放：窗口 [highest-64, highest]，拒绝旧重复
                if (!acceptCounter(senderFp, counter)) {
                    QzLog.w(TAG, "疑似重放包已丢弃（$senderFp #$counter）")
                    return
                }

                val content = json.optString("c")
                if (content.isEmpty()) return
                QzLog.i(TAG, "收到微消息 ← ${device.name} (${data.size}B)")
                if (SettingsStore.settings.value.autoReceiveText) {
                    InboxStore.addText(senderFp, device.name, content)
                }
                MessageBus.post(QzEvent.TextReceived(senderFp, device.name, content, viaMicro = true))
                return
            }
            // 无密钥可解：忽略（可能是未清理的旧设备广播杂音）
        }.onFailure { QzLog.d(TAG, "微消息处理异常：${it.message}") }
    }

    private fun acceptCounter(fp: String, counter: Long): Boolean {
        val highest = lastCounter[fp] ?: -1L
        if (counter > highest) {
            lastCounter[fp] = counter
            recentCounters[fp]?.clear()
            return true
        }
        if (counter <= highest - 64) return false
        val seen = recentCounters.getOrPut(fp) { ConcurrentHashMap.newKeySet() }
        // 清理窗口外
        if (seen.size > 256) seen.removeIf { it <= highest - 64 }
        return seen.add(counter)
    }

    private fun counterTo8(v: Long): ByteArray = byteArrayOf(
        (v ushr 56).toByte(), (v ushr 48).toByte(), (v ushr 40).toByte(), (v ushr 32).toByte(),
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    private fun counterFrom8(b: ByteArray): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    /** 微消息方向密钥：(send, recv)。 */
    internal fun microKeys(
        myStaticPriv: ByteArray,
        peer: io.github.srqingchen.qingzhou.core.data.PairedDevice,
    ): Pair<ByteArray, ByteArray>? {
        val identity = IdentityStore.peek() ?: return null
        val myFp = identity.fingerprintHex
        val sorted = listOf(myFp, peer.fingerprint).sorted()
        val salt = sha256((SALT_PREFIX + sorted[0] + sorted[1]).toByteArray(Charsets.UTF_8))
        val shared = dhShared(myStaticPriv, peer.staticPublicB64.b64())
        val okm = hkdf(salt, shared, 64)
        val k1 = okm.copyOfRange(0, 32)
        val k2 = okm.copyOfRange(32, 64)
        return if (myFp == sorted[0]) k1 to k2 else k2 to k1
    }
}
