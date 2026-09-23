package io.github.srqingchen.qingzhou.core.common

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 分档日志：内存环形缓冲（诊断页实时看）+ 文件落盘（可分享定位问题）。
 *
 * 档位：FULL（debug 及以上全落盘）/ WARN（仅警告与错误）/ OFF（仅内存，不落盘）。
 * 文件轮转：1MB × 2 份（qingzhou.log / qingzhou.log.1）。
 */
object QzLog {

    enum class Mode { FULL, WARN, OFF }

    data class Entry(val millis: Long, val level: Char, val tag: String, val message: String)

    private const val MAX_ENTRIES = 600
    private const val ROTATE_BYTES = 1 shl 20

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _mode = MutableStateFlow(Mode.FULL)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    @Volatile
    private var logDir: File? = null

    private val fileLock = Any()

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** App 启动时调用（未调用前仅内存缓冲）。 */
    fun init(context: android.content.Context) {
        if (logDir != null) return
        synchronized(fileLock) {
            if (logDir != null) return
            logDir = File(context.applicationContext.filesDir, "logs").apply { mkdirs() }
        }
    }

    fun setMode(mode: Mode) {
        _mode.value = mode
    }

    fun d(tag: String, message: String) = append('D', tag, message)
    fun i(tag: String, message: String) = append('I', tag, message)
    fun w(tag: String, message: String) = append('W', tag, message)
    fun e(tag: String, message: String) = append('E', tag, message)

    fun clear() {
        _entries.value = emptyList()
    }

    fun dump(): String = buildString {
        for (e in _entries.value) {
            append(fmt.format(Date(e.millis))).append(' ')
                .append(e.level).append('/').append(e.tag)
                .append(": ").append(e.message).append('\n')
        }
    }

    fun format(entry: Entry): String =
        "${fmt.format(Date(entry.millis))} ${entry.level}/${entry.tag}: ${entry.message}"

    /** 导出完整日志文件（含历史轮转），供分享。 */
    fun exportFile(): File? {
        val dir = logDir ?: return null
        synchronized(fileLock) {
            val current = File(dir, "qingzhou.log")
            // 把内存环形里可能未落盘的 WARN 档内容补写一份快照
            runCatching {
                File(dir, "memory-snapshot.log").writeText(dump())
            }
            return current.takeIf { it.exists() } ?: File(dir, "memory-snapshot.log").takeIf { it.exists() }
        }
    }

    private fun append(level: Char, tag: String, message: String) {
        val priority = when (level) {
            'E' -> Log.ERROR
            'W' -> Log.WARN
            'I' -> Log.INFO
            else -> Log.DEBUG
        }
        runCatching { Log.println(priority, "QingZhou/$tag", message) }
        val now = System.currentTimeMillis()
        _entries.update { list ->
            (list + Entry(now, level, tag, message)).takeLast(MAX_ENTRIES)
        }
        if (shouldWriteFile(level)) writeToFile(now, level, tag, message)
    }

    private fun shouldWriteFile(level: Char): Boolean = when (_mode.value) {
        Mode.FULL -> true
        Mode.WARN -> level == 'W' || level == 'E'
        Mode.OFF -> false
    }

    private fun writeToFile(now: Long, level: Char, tag: String, message: String) {
        val dir = logDir ?: return
        runCatching {
            synchronized(fileLock) {
                val file = File(dir, "qingzhou.log")
                if (file.exists() && file.length() > ROTATE_BYTES) {
                    val old = File(dir, "qingzhou.log.1")
                    old.delete()
                    file.renameTo(old)
                }
                File(dir, "qingzhou.log").appendText(
                    "${fmt.format(Date(now))} $level/$tag: $message\n",
                )
            }
        }
    }
}
