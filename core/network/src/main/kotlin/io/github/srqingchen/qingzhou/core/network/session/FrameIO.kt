package io.github.srqingchen.qingzhou.core.network.session

import io.github.srqingchen.qingzhou.core.model.Protocol
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 控制通道帧编解码：[u32 长度][u8 类型][载荷]。
 * 长度含类型字节；上限 [Protocol.MAX_FRAME]。
 */
class FrameIO(input: InputStream, output: OutputStream) {

    data class Frame(val type: Int, val payload: ByteArray)

    private val din = DataInputStream(BufferedInputStream(input, 64 * 1024))
    private val dout = DataOutputStream(BufferedOutputStream(output, 64 * 1024))

    /** 读一帧；流结束/格式非法返回 null（调用方应关闭连接）。 */
    fun readFrame(maxLen: Int = Protocol.MAX_FRAME): Frame? {
        val len = try {
            din.readInt()
        } catch (e: Exception) {
            return null
        }
        if (len < 2 || len > maxLen) return null
        val type = try {
            din.readByte().toInt() and 0xFF
        } catch (e: Exception) {
            return null
        }
        val payload = ByteArray(len - 1)
        return try {
            din.readFully(payload)
            Frame(type, payload)
        } catch (e: Exception) {
            null
        }
    }

    fun writeFrame(type: Int, payload: ByteArray) {
        dout.writeInt(payload.size + 1)
        dout.writeByte(type)
        dout.write(payload)
        dout.flush()
    }

    fun close() {
        runCatching { dout.close() }
        runCatching { din.close() }
    }
}
