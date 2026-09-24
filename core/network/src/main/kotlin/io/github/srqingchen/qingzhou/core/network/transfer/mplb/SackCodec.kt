package io.github.srqingchen.qingzhou.core.network.transfer.mplb

/**
 * 舟协议 v2 · SACK 位图编解码：块级到位压缩（8 块/字节，字节内低位在前）。
 *
 * 接收端内存位图为「每块一字节」（0/1），磁盘与控制帧传输统一走本编解码，
 * 10GB/4MB 块的整单位图 ≈ 313 字节（D3：替换 v1 每块全量重写 + 大帧裸传）。
 */
object SackCodec {

    /** 每块一字节（仅 0/1 有效）→ 位压缩。 */
    fun pack(bits: ByteArray): ByteArray {
        val packed = ByteArray((bits.size + 7) shr 3)
        for (i in bits.indices) {
            if (bits[i] != 0.toByte()) packed[i shr 3] = (packed[i shr 3].toInt() or (1 shl (i and 7))).toByte()
        }
        return packed
    }

    /** 位压缩 → 每块一字节。[chunks] 越界的压缩位按 0 截断。 */
    fun unpack(packed: ByteArray, chunks: Int): ByteArray {
        val out = ByteArray(chunks)
        for (i in 0 until chunks) {
            val byte = packed.getOrElse(i shr 3) { 0.toByte() }.toInt()
            if (byte and (1 shl (i and 7)) != 0) out[i] = 1
        }
        return out
    }

    /** 收集为 1 的块号（文件内局部序）。 */
    fun ones(bits: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        for (i in bits.indices) if (bits[i] != 0.toByte()) out.add(i)
        return out
    }

    /** 收集为 0 的块号 —— 补发清单（fileRepair.miss）数据源。 */
    fun zeros(bits: ByteArray, limit: Int = 65536): List<Int> {
        val out = ArrayList<Int>()
        for (i in bits.indices) {
            if (bits[i] == 0.toByte()) {
                out.add(i)
                if (out.size >= limit) break
            }
        }
        return out
    }
}
