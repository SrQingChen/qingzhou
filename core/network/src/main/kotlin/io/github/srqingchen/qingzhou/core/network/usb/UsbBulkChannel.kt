package io.github.srqingchen.qingzhou.core.network.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import io.github.srqingchen.qingzhou.core.common.QzLog
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host 侧 AOA bulk 异步通道（D6 修复）：
 * 以 claimInterface + UsbRequest 队列（16KB × depth 深度）替代 fd 同步流写 ——
 * 每次 write 一个 URB 的 16KB syscall 上限被 N 个在飞请求摊薄，吞吐与 CPU 双优。
 *
 * 设计：
 * - 单完成泵线程循环 [UsbDeviceConnection.requestWait] 分发 IN/OUT 完成
 *   （请求 → 附属数据的映射经 [registry] 维护 —— UsbRequest.client 非公开 API）；
 * - OUT：[sink] 带背压（信号量=缓冲池深度），写入方阻塞即天然流控；
 * - IN：常驻预排深度份请求，完成后按序送入 [source]（阻塞队列流），
 *   供引擎常驻接收循环读取（与 fd 版完全同构）；
 * - [close] 后各端读 EOF、写异常。
 */
class UsbBulkChannel(
    private val connection: UsbDeviceConnection,
    private val outEp: UsbEndpoint,
    private val inEp: UsbEndpoint,
    private val depth: Int = 12,
) {
    private companion object {
        const val TAG = "usb"
        const val BUF = 16_384 // UsbRequest 缓冲上限
        const val QUEUE_POLL_MS = 2_000L
    }

    private class Pending(val request: UsbRequest, val buffer: ByteBuffer, val read: Boolean)

    private val closed = AtomicBoolean(false)
    private val outPermits = Semaphore(depth)
    private val outPool = ArrayBlockingQueue<Pending>(depth)
    private val inQueue = ArrayBlockingQueue<ByteArray>(depth * 4)
    private val registry = ConcurrentHashMap<UsbRequest, Pending>()
    private val eof = ByteArray(0)

    private fun newOutPending(): Pending =
        Pending(UsbRequest().also { it.initialize(connection, outEp) }, ByteBuffer.allocateDirect(BUF), read = false)

    private fun newInPending(): Pending =
        Pending(UsbRequest().also { it.initialize(connection, inEp) }, ByteBuffer.allocateDirect(BUF), read = true)

    /** 完成泵：IN → 喂读流；OUT → 回池放行。 */
    private val pump = Thread {
        while (!closed.get()) {
            val r = try {
                connection.requestWait()
            } catch (e: Exception) {
                break
            } ?: break
            val pending = registry.remove(r) ?: continue
            val data = pending.buffer
            try {
                if (pending.read) {
                    data.flip()
                    if (data.hasRemaining()) {
                        val bytes = ByteArray(data.remaining())
                        data.get(bytes)
                        inQueue.put(bytes)
                    }
                    if (!closed.get()) {
                        data.clear()
                        registry[r] = pending
                        runCatching { r.queue(data, 0) }
                            .onFailure { registry.remove(r) }
                    }
                } else {
                    outPool.put(pending)
                    outPermits.release()
                }
            } catch (e: InterruptedException) {
                break
            }
        }
        // 泵退出 = 链路死：读端 EOF、写端放行让上层尽快感知异常
        runCatching { inQueue.put(eof) }
        repeat(depth) { outPermits.release() }
    }

    val source: InputStream = object : InputStream() {
        private var leftover: ByteArray? = null
        private var leftoverPos = 0

        override fun read(): Int {
            val buf = ByteArray(1)
            return if (read(buf, 0, 1) == -1) -1 else buf[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0
            // 先消耗上一次的剩余
            leftover?.let { l ->
                val n = minOf(len, l.size - leftoverPos)
                System.arraycopy(l, leftoverPos, b, off, n)
                leftoverPos += n
                if (leftoverPos >= l.size) {
                    leftover = null
                    leftoverPos = 0
                }
                return n
            }
            val chunk = pollChunk() ?: return -1
            val n = minOf(len, chunk.size)
            System.arraycopy(chunk, 0, b, off, n)
            if (n < chunk.size) {
                leftover = chunk
                leftoverPos = n
            }
            return n
        }

        private fun pollChunk(): ByteArray? = try {
            when (val c = inQueue.poll(QUEUE_POLL_MS, TimeUnit.MILLISECONDS)) {
                null -> if (closed.get()) null else pollChunk()
                // 空数组 = EOF 哨兵
                else -> if (c.isEmpty()) null else c
            }
        } catch (e: InterruptedException) {
            null
        }
    }

    val sink: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var pos = off
            val end = off + len
            while (pos < end) {
                if (closed.get()) throw java.io.IOException("USB bulk 通道已关闭")
                val n = minOf(BUF, end - pos)
                outPermits.acquire()
                if (closed.get()) {
                    outPermits.release()
                    throw java.io.IOException("USB bulk 通道已关闭")
                }
                val pending = outPool.poll() ?: newOutPending()
                pending.buffer.clear()
                pending.buffer.put(b, pos, n)
                pending.buffer.flip()
                registry[pending.request] = pending
                if (!pending.request.queue(pending.buffer, n)) {
                    registry.remove(pending.request)
                    outPool.put(pending)
                    outPermits.release()
                    throw java.io.IOException("USB bulk OUT 排队失败")
                }
                pos += n
            }
        }

        override fun flush() = Unit // 异步队列无缓冲概念；背压即流控

        override fun close() = this@UsbBulkChannel.close("sink close")
    }

    fun start() {
        // 预排 IN 深度份
        for (i in 0 until depth) {
            val pending = newInPending()
            registry[pending.request] = pending
            if (!pending.request.queue(pending.buffer, 0)) {
                registry.remove(pending.request)
                QzLog.w(TAG, "USB bulk IN 预排失败 #$i")
                break
            }
        }
        pump.isDaemon = true
        pump.name = "qz-usb-bulk-pump"
        pump.start()
        QzLog.i(TAG, "USB bulk 异步通道启动（depth=$depth × 16KB）")
    }

    fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        QzLog.i(TAG, "USB bulk 通道关闭（$reason）")
        runCatching { inQueue.put(eof) }
        runCatching { connection.close() } // 触发 requestWait 退出
        pump.interrupt()
    }
}
