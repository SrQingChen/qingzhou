package io.github.srqingchen.qingzhou.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Noise 实现的正确性验证：
 * 1. XX/IK 双向握手后，双方派生出的两个方向密钥一致，且能互加解密；
 * 2. 篡改密文必被检出（MAC）；
 * 3. IK 用错误的对端静态公钥必然握手失败。
 */
class NoiseTest {

    private val random = SecureRandom()

    private fun keypair(): Pair<ByteArray, ByteArray> {
        val priv = generateStaticKey(random)
        return priv to publicKeyOf(priv)
    }

    @Test
    fun xxHandshake_roundTrip() {
        val (iPriv, iPub) = keypair()
        val (rPriv, rPub) = keypair()
        val initiator = NoiseXx(iPriv, initiator = true, random = random)
        val responder = NoiseXx(rPriv, initiator = false, random = random)

        val m1 = initiator.writeMessage1()
        assertEquals(32, m1.size)
        responder.readMessage1(m1)

        val m2 = responder.writeMessage2()
        assertEquals(32 + 32 + 16, m2.size)
        val learnedStatic = initiator.readMessage2(m2)
        assertArrayEquals(rPub, learnedStatic)

        val m3 = initiator.writeMessage3()
        assertEquals(32 + 16, m3.size)
        val initiatorStatic = responder.readMessage3(m3)
        assertArrayEquals(iPub, initiatorStatic)

        // 双方握手哈希一致
        assertArrayEquals(initiator.handshakeHash, responder.handshakeHash)

        val (iSend, iRecv) = initiator.split()
        val (rSend, rRecv) = responder.split()
        // 发起方 c1 ↔ 响应方 c1（发起方→响应方方向）；c2 ↔ c2
        assertArrayEquals(iSend.key, rSend.key)
        assertArrayEquals(iRecv.key, rRecv.key)

        val ct = iSend.encryptWithAd(ByteArray(0), "你好青舟".toByteArray())
        assertArrayEquals("你好青舟".toByteArray(), rSend.decryptWithAd(ByteArray(0), ct))
        val ct2 = rRecv.encryptWithAd(ByteArray(0), "收到".toByteArray())
        assertArrayEquals("收到".toByteArray(), iRecv.decryptWithAd(ByteArray(0), ct2))
    }

    @Test
    fun xxHandshake_tamperedMessage2_fails() {
        val initiator = NoiseXx(generateStaticKey(random), initiator = true, random = random)
        val responder = NoiseXx(generateStaticKey(random), initiator = false, random = random)
        responder.readMessage1(initiator.writeMessage1())
        val m2 = responder.writeMessage2()
        m2[40] = (m2[40].toInt() xor 0x01).toByte()
        assertThrows(SecurityException::class.java) {
            initiator.readMessage2(m2)
        }
    }

    @Test
    fun ikHandshake_roundTrip() {
        val (iPriv, iPub) = keypair()
        val (rPriv, rPub) = keypair()
        val initiator = NoiseIk(iPriv, initiator = true, remoteStaticPub = rPub, random = random)
        val responder = NoiseIk(rPriv, initiator = false, random = random)

        val m1 = initiator.writeMessage1(payload = "ping".toByteArray())
        val (seenStatic, payload) = responder.readMessage1(m1)
        assertArrayEquals(iPub, seenStatic)
        assertEquals("ping", String(payload))

        val m2 = responder.writeMessage2(payload = "pong".toByteArray())
        assertEquals("pong", String(initiator.readMessage2(m2)))

        val (iSend, _) = initiator.split()
        val (rSend, _) = responder.split()
        assertArrayEquals(iSend.key, rSend.key)
    }

    @Test
    fun ikHandshake_wrongRemoteStatic_fails() {
        val (rPriv, rPub) = keypair()
        val (_, fakePub) = keypair() // 假冒的“响应方静态公钥”（中间人场景）
        // 发起方误信假静态公钥 → es/ss 密钥不一致 → 响应方 MAC 校验失败
        val initiator = NoiseIk(generateStaticKey(random), initiator = true, remoteStaticPub = fakePub, random = random)
        val responder = NoiseIk(rPriv, initiator = false, random = random)
        val m1 = initiator.writeMessage1()
        assertThrows(SecurityException::class.java) {
            responder.readMessage1(m1)
        }
        assertTrue(rPub.size == 32)
    }

    @Test
    fun aead_replayTamperDetected() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12)
        val ct = aeadEncrypt(key, nonce, "ad".toByteArray(), "secret".toByteArray())
        assertThrows(SecurityException::class.java) {
            aeadDecrypt(key, nonce, "ad".toByteArray(), ct.copyOf().also { it[0] = 1 })
        }
        // AD 变化同样校验失败
        assertThrows(SecurityException::class.java) {
            aeadDecrypt(key, nonce, "other".toByteArray(), ct)
        }
    }

    @Test
    fun hkdf_deterministic() {
        val a = hkdf(ByteArray(32), "ikm".toByteArray(), 64)
        val b = hkdf(ByteArray(32), "ikm".toByteArray(), 64)
        assertArrayEquals(a, b)
        val c = hkdf(ByteArray(32), "ikm2".toByteArray(), 64)
        assertTrue(!a.contentEquals(c))
    }

    @Test
    fun fingerprintStable() {
        val (_, pub) = keypair()
        val fpHex = Fingerprint.hex(pub)
        assertEquals(Fingerprint.shortCodeOf(fpHex), Fingerprint.shortCodeOf(fpHex))
        assertEquals(6, Fingerprint.shortCodeOf(fpHex).length)
        assertEquals(6, Fingerprint.shortCodeOf("00000000").length)
        assertTrue(Fingerprint.avatarColor(pub) in 0..0xFFFFFF)
    }

    @Test
    fun aesGcm_roundTrip() {
        val key = ByteArray(32) { (it * 7).toByte() }
        val nonce = QzCrypto.chunkNonce(byteArrayOf(1, 2, 3, 4), 42)
        assertEquals(12, nonce.size)
        val data = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() } // 4MB 块
        val ct = QzCrypto.aesGcmEncrypt(key, nonce, data)
        assertArrayEquals(data, QzCrypto.aesGcmDecrypt(key, nonce, ct))
        ct[ct.size / 2] = (ct[ct.size / 2].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) {
            QzCrypto.aesGcmDecrypt(key, nonce, ct)
        }
    }
}
