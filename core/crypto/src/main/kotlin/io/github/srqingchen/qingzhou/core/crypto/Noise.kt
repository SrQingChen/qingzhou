package io.github.srqingchen.qingzhou.core.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 手写 Noise Protocol Framework 实现（规格 rev34 子集）：
 * 支持 Noise_XX_25519_ChaChaPoly_SHA256（首次配对）与
 * Noise_IK_25519_ChaChaPoly_SHA256（已配对设备 1-RTT 复用会话）。
 *
 * DH 记号按角色配对（双方各持一边私钥）：
 *   ee = 发起方临时 ↔ 响应方临时；es = 发起方临时 ↔ 响应方静态；
 *   se = 响应方临时 ↔ 发起方静态；ss = 发起方静态 ↔ 响应方静态。
 *
 * 原语全部走 BouncyCastle 轻量 API（不注册 JCA Provider，
 * 避免与系统 Conscrypt 冲突；大文件分块加密另走硬件 AES-GCM）。
 */

private const val DH_LEN = 32
private const val TAG_LEN = 16
private const val MAX_NOISE_PAYLOAD = 1 shl 20

/** 密码态：密钥 + 64 位计数器。key 为 null 表示明文透传（握手首条消息）。 */
class CipherState(val key: ByteArray? = null) {
    private var n = 0L
    val hasKey: Boolean get() = key != null

    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val k = key ?: return plaintext
        if (n < 0) throw IllegalStateException("Noise 计数器溢出")
        if (plaintext.size > MAX_NOISE_PAYLOAD) throw IllegalArgumentException("Noise 载荷过大")
        val out = aeadEncrypt(k, nonce(n), ad, plaintext)
        n++
        return out
    }

    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val k = key ?: return ciphertext
        val out = aeadDecrypt(k, nonce(n), ad, ciphertext)
        n++
        return out
    }

    private fun nonce(counter: Long): ByteArray {
        // Noise 规格：4 字节零前缀 + u64 大端计数
        val nonce = ByteArray(12)
        nonce[4] = (counter ushr 56).toByte()
        nonce[5] = (counter ushr 48).toByte()
        nonce[6] = (counter ushr 40).toByte()
        nonce[7] = (counter ushr 32).toByte()
        nonce[8] = (counter ushr 24).toByte()
        nonce[9] = (counter ushr 16).toByte()
        nonce[10] = (counter ushr 8).toByte()
        nonce[11] = counter.toByte()
        return nonce
    }
}

/** 对称状态：链密钥 + 握手哈希 + 当前密码态。加解密一律经 *AndHash 原语，杜绝 AD 时序错位。 */
private class SymmetricState(name: String) {
    val ck = ByteArray(DH_LEN)
    val h = ByteArray(DH_LEN)
    var cipher = CipherState()

    init {
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        if (nameBytes.size <= 32) {
            System.arraycopy(nameBytes, 0, h, 0, nameBytes.size)
        } else {
            sha256(nameBytes).copyInto(h)
        }
        h.copyInto(ck)
    }

    fun mixHash(data: ByteArray) {
        sha256(h + data).copyInto(h)
    }

    fun mixKey(ikm: ByteArray) {
        val okm = hkdf(ck, ikm, 64)
        okm.copyInto(ck, 0, 0, 32)
        val tempKey = okm.copyOfRange(32, 64)
        cipher = if (tempKey.all { it == 0.toByte() }) CipherState() else CipherState(tempKey)
    }

    /** 加密并将密文混入哈希（AD 为加密前的 h，与规格 EncryptAndHash 一致）。 */
    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ct = cipher.encryptWithAd(h, plaintext)
        mixHash(ct)
        return ct
    }

    /** 解密（AD 为混入密文前的 h）并将密文混入哈希。 */
    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val pt = cipher.decryptWithAd(h, ciphertext)
        mixHash(ciphertext)
        return pt
    }

    fun split(): Pair<CipherState, CipherState> {
        val okm = hkdf(ck, ByteArray(0), 64)
        return CipherState(okm.copyOfRange(0, 32)) to CipherState(okm.copyOfRange(32, 64))
    }
}

/** X25519 私钥 → 与对端公钥的共享密钥。 */
private fun dh(priv: ByteArray, pub: ByteArray): ByteArray {
    val agr = X25519Agreement()
    agr.init(X25519PrivateKeyParameters(priv, 0))
    val out = ByteArray(agr.agreementSize)
    agr.calculateAgreement(X25519PublicKeyParameters(pub, 0), out, 0)
    return out
}

private fun pubOf(priv: ByteArray): ByteArray =
    X25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded

/** 生成 X25519 静态密钥私钥（32B）。 */
fun generateStaticKey(random: SecureRandom = SecureRandom()): ByteArray {
    val priv = X25519PrivateKeyParameters(random)
    val out = ByteArray(32)
    priv.encode(out, 0)
    return out
}

fun publicKeyOf(priv: ByteArray): ByteArray = pubOf(priv)

/** 静态 X25519 共享密钥（微消息密钥派生用）。 */
fun dhShared(priv: ByteArray, pub: ByteArray): ByteArray = dh(priv, pub)

/**
 * Noise XX 握手（消息序：-> e / <- e,ee,s,es / -> s,se）。
 */
class NoiseXx(
    private val staticPriv: ByteArray,
    private val initiator: Boolean,
    random: SecureRandom = SecureRandom(),
) {
    private val state = SymmetricState("Noise_XX_25519_ChaChaPoly_SHA256")
    private val ephPrivObj = X25519PrivateKeyParameters(random)
    private val ephPriv = ByteArray(32).also { ephPrivObj.encode(it, 0) }
    private val ephPub = ephPrivObj.generatePublicKey().encoded
    private var re: ByteArray? = null // 对端临时公钥
    private var rs: ByteArray? = null // 对端静态公钥

    /** 握手哈希（消息 3 完成后可用，作为会话绑定指纹）。 */
    var handshakeHash: ByteArray = ByteArray(0)
        private set

    // ---- 消息 1：-> e（32B 明文临时公钥） ----

    fun writeMessage1(): ByteArray {
        require(initiator) { "仅发起方可写消息1" }
        state.mixHash(ephPub)
        return ephPub.copyOf()
    }

    fun readMessage1(msg: ByteArray) {
        require(!initiator) { "仅响应方可读消息1" }
        require(msg.size == DH_LEN) { "消息1 长度非法: ${msg.size}" }
        state.mixHash(msg)
        re = msg.copyOf()
    }

    // ---- 消息 2：<- e, ee, s, es（e 32B + 加密 s 48B = 80B） ----

    fun writeMessage2(): ByteArray {
        require(!initiator) { "仅响应方可写消息2" }
        val remoteEph = re ?: error("尚未读取消息1")
        state.mixHash(ephPub)                            // e
        state.mixKey(dh(ephPriv, remoteEph))             // ee：双方临时
        val sCt = state.encryptAndHash(pubOf(staticPriv)) // s：本方静态加密
        state.mixKey(dh(staticPriv, remoteEph))          // es：响应方静态 ↔ 发起方临时
        return ephPub + sCt
    }

    /** 发起方读消息 2，返回响应方静态公钥。 */
    fun readMessage2(msg: ByteArray): ByteArray {
        require(initiator) { "仅发起方可读消息2" }
        require(msg.size == DH_LEN * 2 + TAG_LEN) { "消息2 长度非法: ${msg.size}" }
        val remoteEph = msg.copyOfRange(0, DH_LEN)
        state.mixHash(remoteEph)                         // e
        re = remoteEph
        state.mixKey(dh(ephPriv, remoteEph))             // ee
        val sCt = msg.copyOfRange(DH_LEN, msg.size)
        val remoteStatic = state.decryptAndHash(sCt)     // s
        rs = remoteStatic
        state.mixKey(dh(ephPriv, remoteStatic))          // es：发起方临时 ↔ 响应方静态
        return remoteStatic
    }

    // ---- 消息 3：-> s, se（加密 s 48B） ----

    fun writeMessage3(): ByteArray {
        require(initiator) { "仅发起方可写消息3" }
        val remoteEph = re ?: error("尚未读取消息2")
        val sCt = state.encryptAndHash(pubOf(staticPriv)) // s：本方静态加密
        state.mixKey(dh(staticPriv, remoteEph))          // se：发起方静态 ↔ 响应方临时
        handshakeHash = state.h.copyOf()
        return sCt
    }

    /** 响应方读消息 3，返回发起方静态公钥。 */
    fun readMessage3(msg: ByteArray): ByteArray {
        require(!initiator) { "仅响应方可读消息3" }
        require(msg.size == DH_LEN + TAG_LEN) { "消息3 长度非法: ${msg.size}" }
        val remoteEph = re ?: error("尚未写消息2")
        val remoteStatic = state.decryptAndHash(msg)     // s
        rs = remoteStatic
        state.mixKey(dh(ephPriv, remoteStatic))          // se：响应方临时 ↔ 发起方静态
        handshakeHash = state.h.copyOf()
        return remoteStatic
    }

    /** 握手完成：返回（发起方→响应方方向, 响应方→发起方方向）两个密码态。 */
    fun split(): Pair<CipherState, CipherState> = state.split()

    val remoteStaticPublic: ByteArray? get() = rs?.copyOf()
}

/**
 * Noise IK 握手（预消息 "<- s"；消息序：-> e,es,s,ss / <- e,ee,se）。
 */
class NoiseIk(
    private val staticPriv: ByteArray,
    private val initiator: Boolean,
    /** 发起方传响应方静态公钥；响应方参数被忽略（预消息混入的是响应方静态公钥）。 */
    remoteStaticPub: ByteArray? = null,
    random: SecureRandom = SecureRandom(),
) {
    private val state = SymmetricState("Noise_IK_25519_ChaChaPoly_SHA256")
    private val ephPrivObj = X25519PrivateKeyParameters(random)
    private val ephPriv = ByteArray(32).also { ephPrivObj.encode(it, 0) }
    private val ephPub = ephPrivObj.generatePublicKey().encoded
    private var re: ByteArray? = null
    private var rsPub: ByteArray = if (initiator) remoteStaticPub!! else pubOf(staticPriv)
    private var initiatorStaticCache: ByteArray? = null

    init {
        state.mixHash(rsPub) // 预消息 "<- s"
    }

    // ---- 消息 1：-> e, es, s, ss（e 32B + 加密 s 48B + 加密载荷） ----

    fun writeMessage1(payload: ByteArray = ByteArray(0)): ByteArray {
        require(initiator) { "仅发起方可写消息1" }
        state.mixHash(ephPub)                             // e
        state.mixKey(dh(ephPriv, rsPub))                  // es：发起方临时 ↔ 响应方静态
        val sCt = state.encryptAndHash(pubOf(staticPriv)) // s
        state.mixKey(dh(staticPriv, rsPub))               // ss：双方静态
        val payloadCt = state.encryptAndHash(payload)
        return ephPub + sCt + payloadCt
    }

    /** 响应方读消息 1，返回（发起方静态公钥, 明文载荷）。解密失败即抛异常。 */
    fun readMessage1(msg: ByteArray): Pair<ByteArray, ByteArray> {
        require(!initiator) { "仅响应方可读消息1" }
        val remoteEph = msg.copyOfRange(0, DH_LEN)
        state.mixHash(remoteEph)                          // e
        re = remoteEph
        state.mixKey(dh(staticPriv, remoteEph))           // es：响应方静态 ↔ 发起方临时
        val sCt = msg.copyOfRange(DH_LEN, DH_LEN * 2 + TAG_LEN)
        val initiatorStatic = state.decryptAndHash(sCt)   // s
        initiatorStaticCache = initiatorStatic
        state.mixKey(dh(staticPriv, initiatorStatic))     // ss
        val payloadCt = msg.copyOfRange(DH_LEN * 2 + TAG_LEN, msg.size)
        val payload = state.decryptAndHash(payloadCt)
        return initiatorStatic to payload
    }

    // ---- 消息 2：<- e, ee, se（e 32B + 加密载荷） ----

    fun writeMessage2(payload: ByteArray = ByteArray(0)): ByteArray {
        require(!initiator) { "仅响应方可写消息2" }
        val remoteEph = re ?: error("尚未读取消息1")
        val initiatorStatic = initiatorStaticCache ?: error("内部错误")
        state.mixHash(ephPub)                             // e
        state.mixKey(dh(ephPriv, remoteEph))              // ee
        state.mixKey(dh(ephPriv, initiatorStatic))        // se：响应方临时 ↔ 发起方静态
        val payloadCt = state.encryptAndHash(payload)
        return ephPub + payloadCt
    }

    /** 发起方读消息 2，返回明文载荷。 */
    fun readMessage2(msg: ByteArray): ByteArray {
        require(initiator) { "仅发起方可读消息2" }
        val remoteEph = msg.copyOfRange(0, DH_LEN)
        state.mixHash(remoteEph)                          // e
        re = remoteEph
        state.mixKey(dh(ephPriv, remoteEph))              // ee
        state.mixKey(dh(staticPriv, remoteEph))           // se：发起方静态 ↔ 响应方临时
        val payloadCt = msg.copyOfRange(DH_LEN, msg.size)
        return state.decryptAndHash(payloadCt)
    }

    /** 握手完成：返回（发起方→响应方方向, 响应方→发起方方向）两个密码态。 */
    fun split(): Pair<CipherState, CipherState> = state.split()
}

// ---------------- 基础原语 ----------------

fun sha256(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(data)

fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = HMac(SHA256Digest())
    mac.init(KeyParameter(key))
    mac.update(data, 0, data.size)
    val out = ByteArray(mac.macSize)
    mac.doFinal(out, 0)
    return out
}

fun hkdf(salt: ByteArray, ikm: ByteArray, len: Int): ByteArray {
    val gen = HKDFBytesGenerator(SHA256Digest())
    gen.init(HKDFParameters(ikm, salt, null))
    val out = ByteArray(len)
    gen.generateBytes(out, 0, len)
    return out
}

/** ChaCha20-Poly1305 一次性 AEAD（BC 轻量引擎）。 */
fun aeadEncrypt(key: ByteArray, nonce: ByteArray, ad: ByteArray, plaintext: ByteArray): ByteArray {
    val aead = ChaCha20Poly1305()
    aead.init(true, AEADParameters(KeyParameter(key), 128, nonce, ad))
    val out = ByteArray(plaintext.size + TAG_LEN)
    val written = aead.processBytes(plaintext, 0, plaintext.size, out, 0)
    aead.doFinal(out, written)
    return out
}

/** 解密失败（含 MAC 校验不过）统一抛 [SecurityException]。 */
fun aeadDecrypt(key: ByteArray, nonce: ByteArray, ad: ByteArray, ciphertext: ByteArray): ByteArray {
    val aead = ChaCha20Poly1305()
    aead.init(false, AEADParameters(KeyParameter(key), 128, nonce, ad))
    return try {
        val out = ByteArray(ciphertext.size - TAG_LEN)
        val written = aead.processBytes(ciphertext, 0, ciphertext.size, out, 0)
        aead.doFinal(out, written)
        out
    } catch (e: org.bouncycastle.crypto.InvalidCipherTextException) {
        throw SecurityException("AEAD 校验失败", e)
    }
}
