package io.github.srqingchen.qingzhou.core.crypto

import org.bouncycastle.crypto.digests.Blake2bDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 传输层加密辅助：
 * - 握手/微消息：ChaCha20-Poly1305（见 [aeadEncrypt]/[aeadDecrypt]）
 * - 大文件分块：AES-256-GCM（javax.crypto → Conscrypt 硬件加速，ARMv8 crypto 指令）
 * - 校验：BLAKE2b-256 / SHA-256
 */
object QzCrypto {

    private const val GCM_TAG_BITS = 128
    private const val GCM_NONCE_LEN = 12

    /**
     * AES-256-GCM 单块加密。nonce 12 字节（会话基 + 块序号派生），密文 = ct || tag。
     */
    fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == GCM_NONCE_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(plaintext)
    }

    fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == GCM_NONCE_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(ciphertext)
    }

    /** 数据密钥的块 nonce：4 字节方向盐（调用方约定）+ 块号 u64 大端。 */
    fun chunkNonce(salt4: ByteArray, blockIndex: Long): ByteArray {
        require(salt4.size == 4)
        return salt4 + byteArrayOf(
            (blockIndex ushr 56).toByte(),
            (blockIndex ushr 48).toByte(),
            (blockIndex ushr 40).toByte(),
            (blockIndex ushr 32).toByte(),
            (blockIndex ushr 24).toByte(),
            (blockIndex ushr 16).toByte(),
            (blockIndex ushr 8).toByte(),
            blockIndex.toByte(),
        )
    }

    fun blake2b256(data: ByteArray): ByteArray {
        val digest = Blake2bDigest(256)
        digest.update(data, 0, data.size)
        val out = ByteArray(32)
        digest.doFinal(out, 0)
        return out
    }
}

/**
 * 设备指纹展示：SHA-256(静态公钥) 的可人读形式。
 * - 6 位数字短码（配对时人工核对）
 * - 4 色色块种子（设备头像）
 */
object Fingerprint {

    fun of(staticPublicKey: ByteArray): ByteArray = sha256(staticPublicKey)

    fun hex(staticPublicKey: ByteArray): String =
        of(staticPublicKey).joinToString("") { "%02x".format(it) }

    /** 6 位短码：指纹前 8 个 hex（32bit）取模 1_000_000 —— 双方对同一指纹显示同一值。 */
    fun shortCodeOf(fingerprintHex: String): String {
        val v = fingerprintHex.take(8).toLongOrNull(16) ?: return "000000"
        return "%06d".format(v % 1_000_000L)
    }

    /** 头像色块：指纹前 3 字节映射为 HSB 三通道，稳定且区分度高。返回 0xRRGGBB。 */
    fun avatarColor(staticPublicKey: ByteArray): Long {
        val fp = of(staticPublicKey)
        val h = (fp[4].toInt() and 0xFF) / 255f * 360f
        val s = 0.45f + (fp[5].toInt() and 0xFF) / 255f * 0.35f
        val b = 0.60f + (fp[6].toInt() and 0xFF) / 255f * 0.35f
        return hsvToRgb(h, s, b)
    }

    private fun hsvToRgb(hDeg: Float, s: Float, v: Float): Long {
        val c = v * s
        val x = c * (1 - kotlin.math.abs((hDeg / 60f) % 2f - 1f))
        val m = v - c
        val (r1, g1, b1) = when {
            hDeg < 60f -> Triple(c, x, 0f)
            hDeg < 120f -> Triple(x, c, 0f)
            hDeg < 180f -> Triple(0f, c, x)
            hDeg < 240f -> Triple(0f, x, c)
            hDeg < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((r1 + m) * 255).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255).toInt().coerceIn(0, 255)
        return ((r shl 16) or (g shl 8) or b).toLong() and 0xFFFFFF
    }
}
