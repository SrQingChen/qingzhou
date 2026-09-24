package io.github.srqingchen.qingzhou.core.network.transfer.mplb

import io.github.srqingchen.qingzhou.core.model.FileMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MPLB 租约调度器行为验证：
 * 信用门控 / SACK 释放 / 截止期回收 / 补发回队 / 尾部冗余 / 断点续传跳过。
 */
class LeaseSchedulerTest {

    private val chunkSize = 1 shl 20 // 1MB 便于推演
    private var now = 0L

    private fun metas(vararg sizes: Long): List<FileMeta> =
        sizes.map { FileMeta("f$it", it, "h$it") }

    private fun sched(vararg sizes: Long, resume: List<ByteArray>? = null) =
        LeaseScheduler(metas(*sizes), resume, chunkSize) { now }

    @Test
    fun lease_respectsCredit() {
        val s = sched(10L shl 20) // 10 块
        s.addLane(0, 2L shl 20)
        assertNotNull(s.lease(0))
        assertNotNull(s.lease(0))
        assertNull("信用 2MB 已耗尽", s.lease(0))
    }

    @Test
    fun sack_releasesCreditAndMarksDelivered() {
        val s = sched(10L shl 20)
        s.addLane(0, 2L shl 20)
        s.lease(0)
        s.lease(0)
        assertNull("信用 2MB 已被两块占满", s.lease(0))
        // SACK 确认第一个块（文件 0，块 0）→ 释放 1MB 信用
        val bits = ByteArray(10)
        bits[0] = 1
        s.onSack(0, SackCodec.pack(bits))
        assertEquals(1L shl 20, s.deliveredBytes())
        assertTrue(s.hasQueued())
        assertNotNull("SACK 后信用恢复可继续派发", s.lease(0))
    }

    @Test
    fun sweep_expiresOverdueLeaseAndRequeues() {
        val s = sched(6L shl 20)
        s.addLane(0, 6L shl 20)
        repeat(6) { s.lease(0) } // 租空队列
        assertFalse(s.hasQueued())
        now += 26_000 // 超过 MAX_DEADLINE
        val expired = s.sweep()
        assertEquals(6, expired)
        assertTrue("过期块应回队", s.hasQueued())
        assertNotNull("回收后可再次派发", s.lease(0))
    }

    @Test
    fun laneDead_releasesAndRequeues() {
        val s = sched(4L shl 20)
        s.addLane(0, 4L shl 20)
        s.addLane(1, 4L shl 20)
        repeat(4) { s.lease(0) }
        assertFalse(s.hasQueued())
        s.laneDead(0)
        assertTrue("死亡链路的在飞块应回队", s.hasQueued())
        assertNotNull(s.lease(1))
    }

    @Test
    fun repairMiss_forcesRedelivery() {
        val s = sched(4L shl 20)
        s.addLane(0, 4L shl 20)
        repeat(4) { s.lease(0) }
        assertFalse(s.hasQueued())
        // 全部 SACK 确认后，接收端报告块 2 缺失
        val bits = ByteArray(4) { 1 }
        s.onSack(0, SackCodec.pack(bits))
        assertTrue(s.allDelivered())
        s.onRepairMiss(listOf(0 to 2L))
        assertFalse(s.allDelivered())
        val c = s.lease(0)!!
        assertEquals(2L, c.chunkIdx)
    }

    @Test
    fun tailDuplication_onlyInTailMode() {
        val s = sched(100L shl 20) // 100 块
        s.addLane(0, 100L shl 20)
        s.addLane(1, 100L shl 20)
        s.lease(0)
        assertNull("非尾部不允许冗余复制", s.dupLease(1))
        // SACK 96 块 → 剩 4%（尾部模式）
        val bits = ByteArray(100)
        for (i in 0 until 96) bits[i] = 1
        s.onSack(0, SackCodec.pack(bits))
        assertTrue(s.tailMode())
        assertNull("无在飞租约时无可复制块", s.dupLease(1))
        val leased = s.lease(0)!! // 尾部 worker 取走一块（在飞未确认）
        assertEquals(96L, leased.chunkIdx)
        val dup = s.dupLease(1)
        assertNotNull("尾部模式允许第二链路复制未确认块", dup)
        assertEquals(96L, dup!!.chunkIdx)
        assertTrue(dup.duplicate)
    }

    @Test
    fun resumeBitmaps_skipDelivered() {
        val resume = listOf(ByteArray(4) { if (it < 3) 1 else 0 })
        val s = sched(4L shl 20, resume = resume)
        s.addLane(0, 4L shl 20)
        assertEquals(3L shl 20, s.deliveredBytes())
        val c = s.lease(0)!!
        assertEquals(3L, c.chunkIdx)
        assertNull(s.lease(0))
    }

    @Test
    fun multiFile_globalIndexing() {
        val s = sched(2L shl 20, 3L shl 20) // 2 + 3 块
        s.addLane(0, 16L shl 20)
        val a = s.lease(0)!! // f0 c0
        val b = s.lease(0)!! // f0 c1
        val c = s.lease(0)!! // f1 c0
        assertEquals(0, a.fileIdx); assertEquals(0L, a.chunkIdx)
        assertEquals(0, b.fileIdx); assertEquals(1L, b.chunkIdx)
        assertEquals(1, c.fileIdx); assertEquals(0L, c.chunkIdx)
        assertEquals(5L shl 20, s.totalBytes)
    }
}
