package io.github.srqingchen.qingzhou.core.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 设备身份：静态 X25519 密钥对。
 *
 * 私钥文件经 AndroidKeyStore AES-GCM 密钥加密后落盘 —— 无密钥的副本无法解密，
 * 且 Keystore 密钥不可导出。首次调用 [loadOrCreate] 生成并持久化。
 */
object IdentityStore {

    private const val KEYSTORE_ALIAS = "qz_identity_master"
    private const val FILE_NAME = "identity.bin"
    private const val VERSION = 1

    data class Identity(
        val staticPrivate: ByteArray,
        val staticPublic: ByteArray,
        val fingerprintHex: String,
    ) {
        val shortCode: String get() = Fingerprint.shortCodeOf(fingerprintHex)
        val avatarColor: Long get() = Fingerprint.avatarColor(staticPublic)
    }

    @Volatile
    private var cached: Identity? = null

    fun loadOrCreate(context: Context): Identity {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            val identity = if (file.exists()) {
                runCatching { decryptIdentity(file.readBytes()) }
                    .getOrElse {
                        // 主密钥被 invalidate（极少见：清除凭据/降级）——重新生成身份
                        generateIdentity()
                    }
            } else {
                generateIdentity()
            }
            persist(file, identity)
            cached = identity
            return identity
        }
    }

    fun peek(): Identity? = cached

    /** 指纹重置（设置页「清除身份并重新配对」用）。 */
    fun reset(context: Context): Identity {
        synchronized(this) {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            file.delete()
            cached = null
            return loadOrCreate(context)
        }
    }

    private fun generateIdentity(): Identity {
        val priv = generateStaticKey(SecureRandom())
        return identityFrom(priv)
    }

    private fun identityFrom(priv: ByteArray): Identity {
        val pub = publicKeyOf(priv)
        return Identity(priv, pub, Fingerprint.hex(pub))
    }

    private fun persist(file: File, identity: Identity) {
        val plain = ByteArray(1 + 32)
        plain[0] = VERSION.toByte()
        System.arraycopy(identity.staticPrivate, 0, plain, 1, 32)
        file.writeBytes(keystoreEncrypt(plain))
    }

    private fun decryptIdentity(blob: ByteArray): Identity {
        val plain = keystoreDecrypt(blob)
        require(plain.size == 33 && plain[0] == VERSION.toByte()) { "身份文件格式非法" }
        return identityFrom(plain.copyOfRange(1, 33))
    }

    // ---------- AndroidKeyStore 主密钥 ----------

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
        generator.init(spec.build())
        return generator.generateKey()
    }

    /** 输出布局：[1B 版本][12B nonce][ct+tag]。 */
    private fun keystoreEncrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val ct = cipher.doFinal(plain)
        val out = ByteArray(1 + 12 + ct.size)
        out[0] = VERSION.toByte()
        cipher.iv.copyInto(out, 1)
        ct.copyInto(out, 13)
        return out
    }

    private fun keystoreDecrypt(blob: ByteArray): ByteArray {
        require(blob.size > 13 && blob[0] == VERSION.toByte()) { "密文格式非法" }
        val iv = blob.copyOfRange(1, 13)
        val ct = blob.copyOfRange(13, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }
}
