package io.github.srqingchen.qingzhou.core.network.transfer.mplb

import io.github.srqingchen.qingzhou.core.model.FileMeta
import java.util.ArrayDeque

/**
 * 舟协议 v2 · MPLB 块租约调度器（Multi-Path Link Bonding 核心）。
 *
 * 相对 v1 ChunkScheduler（单游标多消费者、隐性 work-stealing）的增量：
 * 1. **块租约**：块出队即挂 (lane, deadline)；超时未确认由 [sweep] 自动回收入队，
 *    可被其他链路取走 —— stall 链路不再囤死块（修复 D1/D2 的调度侧）。
 * 2. **每链路信用**：某链路 inflight 字节超过信用时 [lease] 拒绝派块（BLEST 思想，
 *    慢链路少囤块防队头阻塞）；信用由接收端按消费速度经 linkCredit 帧动态更新。
 * 3. **SACK 回收**：接收端周期上报位图，[onSack] 释放租约与 inflight，
 *    并按实测送达速率更新链路 EWMA —— 截止期自适应（好链路块到期快、坏链路早回收）。
 * 4. **尾块冗余复制**：进入尾部（剩余 < 5%）时 [dupLease] 允许第二链路对同一未确认块
 *    再发一份（mqvpn reinjection 思路，替代 FEC；nonce 覆盖 lane 保证跨链路重复发送安全）。
 *
 * 线程安全：全部操作 synchronized；调用方为各链路 worker 线程 + 发送端 sweeper。
 */
class LeaseScheduler(
    metas: List<FileMeta>,
    resumeBitmaps: List<ByteArray>?,
    private val chunkSize: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class Cursor(
        val fileIdx: Int,
        val chunkIdx: Long,
        val offset: Long,
        val length: Int,
        val globalIdx: Long,
        /** true = 尾部冗余复制（同块已由其他链路持有租约）。 */
        val duplicate: Boolean,
    )

    private class LaneLease(val lane: Int, val since: Long, val deadline: Long, val bytes: Int)

    private class LaneStat(@Volatile var inflight: Long, @Volatile var credit: Long, @Volatile var ewmaBps: Long)

    private val fileSizes: LongArray = metas.map { it.size }.toLongArray()
    private val perFileChunks: IntArray = fileSizes.map { ((it + chunkSize - 1) / chunkSize).toInt() }.toIntArray()
    private val fileBase: LongArray = LongArray(perFileChunks.size).also { base ->
        var acc = 0L
        for (i in perFileChunks.indices) {
            base[i] = acc
            acc += perFileChunks[i]
        }
    }
    private val totalChunkCount: Long = perFileChunks.sum().toLong()
    val totalBytes: Long = fileSizes.sum()

    /** 每文件已送达位图（每块一字节，与接收端 SACK 同构）。 */
    private val delivered = Array(perFileChunks.size) { f -> ByteArray(perFileChunks[f]) }

    /** 待派发队列（globalIdx；头部优先）。 */
    private val pending = ArrayDeque<Long>()

    /** globalIdx → 各链路租约（同块可被多链路持有 = 尾部冗余）。 */
    private val leases = HashMap<Long, MutableList<LaneLease>>()

    private val lanes = HashMap<Int, LaneStat>()

    @Volatile
    private var deliveredBytesAcc = 0L

    init {
        resumeBitmaps?.forEachIndexed { f, bm ->
            bm.forEachIndexed { c, v ->
                if (v != 0.toByte() && c < perFileChunks[f]) {
                    delivered[f][c] = 1
                    deliveredBytesAcc += chunkLen(f, c.toLong())
                }
            }
        }
        for (g in 0 until totalChunkCount) if (!isDelivered(g)) pending.add(g)
    }

    // ---------- 链路 ----------

    fun addLane(lane: Int, initialCreditBytes: Long) {
        lanes.getOrPut(lane) { LaneStat(0, initialCreditBytes, DEFAULT_EWMA_BPS) }
    }

    fun updateCredit(lane: Int, bytes: Long) {
        lanes[lane]?.credit = bytes.coerceIn(MIN_CREDIT, MAX_CREDIT)
    }

    // ---------- 状态查询 ----------

    val undeliveredBytes: Long get() = totalBytes - deliveredBytesAcc

    fun deliveredBytes(): Long = deliveredBytesAcc

    fun hasQueued(): Boolean = pending.isNotEmpty()

    fun allDelivered(): Boolean = deliveredBytesAcc >= totalBytes

    /** 尾部模式：剩余 < 5% 且仍有未确认块（触发冗余复制）。 */
    fun tailMode(): Boolean =
        totalBytes > 0 && undeliveredBytes * 20 < totalBytes && undeliveredBytes > 0

    // ---------- 派发 ----------

    /** 按信用派发一块给 [lane]；无可派块或信用耗尽返回 null（调用方退避等待）。 */
    @Synchronized
    fun lease(lane: Int): Cursor? {
        val stat = lanes[lane] ?: return null
        while (pending.isNotEmpty()) {
            val g = pending.pollFirst() ?: break
            if (isDelivered(g)) continue
            val len = chunkLenOf(g)
            if (stat.inflight + len > stat.credit) {
                pending.addFirst(g) // 信用不足：放回队头
                return null
            }
            grantLease(g, lane, stat, len)
            return cursorOf(g, duplicate = false)
        }
        return null
    }

    /**
     * 尾部冗余复制：把一块「已被其他链路租走但未确认」的块再租给 [lane]。
     * 仅 tailMode 下派发；本链路冗余块在飞数有上限，且信用放宽 [TAIL_CREDIT_SLACK]。
     */
    @Synchronized
    fun dupLease(lane: Int, maxDupInflight: Int = 2): Cursor? {
        if (!tailMode()) return null
        val stat = lanes[lane] ?: return null
        var dupInflight = 0
        var best: Long = -1
        var bestDeadline = Long.MAX_VALUE
        for ((g, list) in leases) {
            if (isDelivered(g)) continue
            val mine = list.firstOrNull { it.lane == lane }
            if (mine != null) {
                dupInflight++
                continue
            }
            val dl = list.minOf { it.deadline }
            if (dl < bestDeadline) {
                bestDeadline = dl
                best = g
            }
        }
        if (best < 0 || dupInflight >= maxDupInflight) return null
        val len = chunkLenOf(best)
        if (stat.inflight + len > stat.credit + TAIL_CREDIT_SLACK) return null
        grantLease(best, lane, stat, len)
        return cursorOf(best, duplicate = true)
    }

    private fun grantLease(g: Long, lane: Int, stat: LaneStat, len: Int) {
        val now = clock()
        leases.getOrPut(g) { ArrayList(2) }.add(LaneLease(lane, now, now + deadlineMs(lane), len))
        stat.inflight += len
    }

    // ---------- 回收 ----------

    /** SACK：按接收端位压缩位图批量确认（对应单文件）。幂等。 */
    @Synchronized
    fun onSack(fileIdx: Int, packedBits: ByteArray) {
        if (fileIdx < 0 || fileIdx >= delivered.size) return
        val bits = SackCodec.unpack(packedBits, perFileChunks[fileIdx])
        val bm = delivered[fileIdx]
        for (c in bits.indices) {
            if (bits[c] != 0.toByte() && bm[c] == 0.toByte()) {
                bm[c] = 1
                deliveredBytesAcc += chunkLen(fileIdx, c.toLong())
                releaseLeasesOf(fileBase[fileIdx] + c, acked = true)
            }
        }
    }

    /** 补发清单：接收端缺失块强制回队（以接收端位图为准，可纠正本地超前确认）。 */
    @Synchronized
    fun onRepairMiss(miss: List<Pair<Int, Long>>) {
        for ((f, c) in miss) {
            if (f < 0 || f >= delivered.size || c < 0 || c >= perFileChunks[f]) continue
            val g = fileBase[f] + c
            if (delivered[f][c.toInt()] != 0.toByte()) {
                delivered[f][c.toInt()] = 0
                deliveredBytesAcc -= chunkLen(f, c)
            }
            releaseLeasesOf(g, acked = false)
            if (!pending.contains(g)) pending.addFirst(g)
        }
    }

    /** 周期回收：过期租约解除并回队（头部优先），链路 EWMA 减半惩罚。返回回收数。 */
    @Synchronized
    fun sweep(): Int {
        val now = clock()
        val dead = ArrayList<Pair<Long, LaneLease>>()
        for ((g, list) in leases) {
            val it = list.iterator()
            while (it.hasNext()) {
                val l = it.next()
                if (now >= l.deadline) {
                    it.remove()
                    dead.add(g to l)
                }
            }
        }
        for ((g, l) in dead) {
            lanes[l.lane]?.let {
                it.inflight = (it.inflight - l.bytes).coerceAtLeast(0)
                it.ewmaBps = (it.ewmaBps / 2).coerceAtLeast(MIN_EWMA_BPS)
            }
            if (!isDelivered(g) && leases[g].isNullOrEmpty() && !pending.contains(g)) pending.addFirst(g)
        }
        leases.entries.removeIf { it.value.isEmpty() }
        return dead.size
    }

    /** 链路死亡（流断开/拔线）：立即释放该链路全部租约并回队。 */
    @Synchronized
    fun laneDead(lane: Int) {
        val stat = lanes[lane] ?: return
        stat.inflight = 0
        val affected = ArrayList<Long>()
        for ((g, list) in leases) {
            list.removeAll { it.lane == lane }
            if (list.isEmpty()) affected.add(g)
        }
        leases.entries.removeIf { it.value.isEmpty() }
        for (g in affected) {
            if (!isDelivered(g) && !pending.contains(g)) pending.addFirst(g)
        }
        stat.ewmaBps = (stat.ewmaBps / 2).coerceAtLeast(MIN_EWMA_BPS)
    }

    // ---------- 内部 ----------

    private fun releaseLeasesOf(g: Long, acked: Boolean) {
        val list = leases.remove(g) ?: return
        for (l in list) {
            val stat = lanes[l.lane]
            stat?.let { it.inflight = (it.inflight - l.bytes).coerceAtLeast(0) }
            if (acked && stat != null) {
                // 送达：按实测时延更新 EWMA（好链路块到期更快，坏链路被 sweep 惩罚）
                val dt = (clock() - l.since).coerceAtLeast(1)
                val bps = l.bytes * 1000L / dt
                stat.ewmaBps = ((stat.ewmaBps * 3) / 4 + bps / 4).coerceIn(MIN_EWMA_BPS, MAX_EWMA_BPS)
            }
        }
    }

    private fun isDelivered(g: Long): Boolean {
        val (f, c) = locate(g)
        return delivered[f][c.toInt()] != 0.toByte()
    }

    private fun locate(g: Long): Pair<Int, Long> {
        var f = perFileChunks.size - 1
        for (i in perFileChunks.indices) {
            if (g < fileBase[i] + perFileChunks[i]) {
                f = i
                break
            }
        }
        return f to (g - fileBase[f])
    }

    private fun cursorOf(g: Long, duplicate: Boolean): Cursor {
        val (f, c) = locate(g)
        val off = c * chunkSize
        val len = chunkLen(f, c)
        return Cursor(f, c, off, len, g, duplicate)
    }

    private fun chunkLen(f: Int, c: Long): Int {
        val remain = fileSizes[f] - c * chunkSize
        return minOf(chunkSize.toLong(), remain).toInt().coerceAtLeast(0)
    }

    private fun chunkLenOf(g: Long): Int {
        val (f, c) = locate(g)
        return chunkLen(f, c)
    }

    /** 截止期 = 预期送达时间 × 4，按链路 EWMA 自适应，钳制 [MIN_DEADLINE_MS, MAX_DEADLINE_MS]。 */
    private fun deadlineMs(lane: Int): Long {
        val ewma = lanes[lane]?.ewmaBps ?: DEFAULT_EWMA_BPS
        val expect = chunkSize * 4000L / ewma.coerceAtLeast(1)
        return expect.coerceIn(MIN_DEADLINE_MS, MAX_DEADLINE_MS)
    }

    companion object {
        const val DEFAULT_EWMA_BPS = 24L shl 20 // 24MB/s 起步（WiFi 好链路量级）
        const val MIN_EWMA_BPS = 512L shl 10
        const val MAX_EWMA_BPS = 1L shl 30
        const val MIN_CREDIT = 1L shl 20
        const val MAX_CREDIT = 48L shl 20
        const val MIN_DEADLINE_MS = 1_500L
        const val MAX_DEADLINE_MS = 25_000L
        private const val TAIL_CREDIT_SLACK = 8L shl 20
    }
}
