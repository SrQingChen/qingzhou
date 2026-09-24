package io.github.srqingchen.qingzhou.core.model

import org.json.JSONObject

/** 舟协议 v1 常量。 */
object Protocol {
    const val VERSION = 1

    /** 发现：UDP 广播/多播端口。 */
    const val PORT_DISCOVERY = 39527

    /** 控制通道：TCP（探测 / 配对 / 会话）。 */
    const val PORT_CONTROL = 39528

    /** 微消息：UDP（已配对设备免握手直达）。 */
    const val PORT_MESSAGE = 39529

    /** 多播组。 */
    const val MULTICAST_GROUP = "224.0.0.167"

    /** 控制帧类型。 */
    const val FRAME_PROBE = 0x01
    const val FRAME_REGISTER = 0x02
    const val FRAME_PAIR_XX_M1 = 0x10
    const val FRAME_PAIR_XX_M2 = 0x11
    const val FRAME_PAIR_XX_M3 = 0x12
    const val FRAME_PAIR_DECLINE = 0x13
    const val FRAME_SESSION_IK_M1 = 0x20
    const val FRAME_SESSION_IK_M2 = 0x21
    const val FRAME_APP = 0x30
    const val FRAME_GOODBYE = 0x3E

    /** 单帧上限（握手与控制载荷）。 */
    const val MAX_FRAME = 1 shl 20

    /** 微消息上限（UDP 数据报安全区间）。 */
    const val MAX_MICRO = 48 * 1024

    /** UDP 发现包类型前缀。 */
    const val UDP_ANNOUNCE: Byte = 0x41 // 'A'
    const val UDP_REGISTER: Byte = 0x52 // 'R'
}

/** 设备能力位。 */
object Cap {
    const val TEXT = 1
    const val FILE = 2
    const val P2P = 4
    const val USB = 8 // 有线并行（AOA）：双端新版且 USB 已连接才启用
    const val USB_NET = 16 // USB 网络链路（NCM/usb0，经 Shizuku 协商切换）
    const val V2 = 32 // 舟协议 v2（MPLB：租约调度 + SACK + 补发 + 每链路信用）
}

/**
 * 发现自描述包（UDP 广播/多播 announce 与单播 register、TCP probe 共用）。
 */
data class AnnouncePacket(
    val protocolVersion: Int,
    val fingerprint: String, // 指纹 hex（身份）
    val name: String, // 显示名
    val model: String, // 机型
    val apiLevel: Int,
    val controlPort: Int,
    val messagePort: Int,
    val capabilities: Int,
    val timestamp: Long,
) {
    /** 配对码（与 Peer.shortCode 同公式）。 */
    val shortCode: String get() = Peer.shortCodeOf(fingerprint)

    fun encode(prefix: Byte = Protocol.UDP_ANNOUNCE): ByteArray {
        val json = JSONObject()
            .put("v", protocolVersion)
            .put("id", fingerprint)
            .put("n", name)
            .put("m", model)
            .put("os", apiLevel)
            .put("cp", controlPort)
            .put("mp", messagePort)
            .put("cap", capabilities)
            .put("t", timestamp)
            .toString()
        return byteArrayOf(prefix) + json.toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun decode(bytes: ByteArray): Pair<Byte, AnnouncePacket>? {
            if (bytes.isEmpty()) return null
            val prefix = bytes[0]
            val json = runCatching {
                JSONObject(String(bytes, 1, bytes.size - 1, Charsets.UTF_8))
            }.getOrNull() ?: return null
            val fp = json.optString("id")
            if (fp.length != 64) return null
            val packet = AnnouncePacket(
                protocolVersion = json.optInt("v", 0),
                fingerprint = fp,
                name = json.optString("n", "未知设备"),
                model = json.optString("m", ""),
                apiLevel = json.optInt("os", 0),
                controlPort = json.optInt("cp", Protocol.PORT_CONTROL),
                messagePort = json.optInt("mp", Protocol.PORT_MESSAGE),
                capabilities = json.optInt("cap", Cap.TEXT),
                timestamp = json.optLong("t", 0L),
            )
            return prefix to packet
        }
    }
}

/** 已发现的对端设备。 */
data class Peer(
    val fingerprint: String,
    val name: String,
    val model: String,
    val apiLevel: Int,
    val host: String, // 最近一次来源地址
    val controlPort: Int,
    val messagePort: Int,
    val capabilities: Int,
    val lastSeenMs: Long,
    val paired: Boolean,
) {
    /** 配对码：双方设备上对同一指纹显示同一值（与 AnnouncePacket.shortCode 同公式）。 */
    val shortCode: String
        get() = shortCodeOf(fingerprint)

    val supportsFile: Boolean get() = (capabilities and Cap.FILE) != 0

    companion object {
        fun shortCodeOf(fingerprintHex: String): String {
            val v = fingerprintHex.take(8).toLongOrNull(16) ?: return "000000"
            return "%06d".format(v % 1_000_000L)
        }
    }
}
