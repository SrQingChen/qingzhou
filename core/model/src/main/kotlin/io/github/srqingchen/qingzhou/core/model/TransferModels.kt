package io.github.srqingchen.qingzhou.core.model

/** 传输任务模型（发现/传输/收件箱三屏与通知共用）。 */

data class FileMeta(
    val name: String,
    val size: Long,
    val sha256: String, // 全文件 SHA-256（发送前计算/接收后校验）
    val mimeType: String? = null,
)

enum class TransferDirection { SEND, RECV }

enum class TaskState(val label: String) {
    NEGOTIATING("协商中"),
    WAITING_ACCEPT("等待对方确认"),
    WAITING_MY_ACCEPT("等待你确认"),
    RUNNING("传输中"),
    VERIFYING("校验中"),
    DONE("已完成"),
    DECLINED("已拒绝"),
    CANCELLED("已取消"),
    FAILED("失败"),
}

data class TransferTask(
    val id: String, // 确定性 token = SHA-256(双方指纹+清单) —— 断点续传匹配键
    val direction: TransferDirection,
    val peerFp: String,
    val peerName: String,
    val files: List<FileMeta>,
    val state: TaskState,
    val bytesDone: Long = 0,
    val totalBytes: Long,
    val speedBps: Long = 0,
    val error: String? = null,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    /** 速率采样序列（时间毫秒, B/s）—— 速率折线图数据源；内存态，最多 600 点。 */
    val speedSeries: List<Pair<Long, Long>> = emptyList(),
) {
    val progress: Int
        get() = if (totalBytes <= 0) 0 else ((bytesDone * 100) / totalBytes).toInt().coerceIn(0, 100)

    val etaMs: Long
        get() = if (speedBps <= 0) -1 else ((totalBytes - bytesDone) * 1000) / speedBps
}

/** 速度格式化。 */
fun formatSpeed(bps: Long): String = when {
    bps >= 1_000_000 -> "%.1f MB/s".format(bps / 1_000_000.0)
    bps >= 1_000 -> "%.0f KB/s".format(bps / 1_000.0)
    else -> "$bps B/s"
}

/** 大小格式化。 */
fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / 1073741824.0)
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
