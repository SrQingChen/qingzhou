package io.github.srqingchen.qingzhou.core.data

import android.content.Context
import android.os.Build
import io.github.srqingchen.qingzhou.core.common.QzDispatchers
import io.github.srqingchen.qingzhou.core.common.QzLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** 性能偏好：映射发现频率 / WifiLock 档位 / 并行流数。 */
enum class PerfMode(val label: String, val heartbeatMs: Long) {
    SAVER("省电优先", 5_000),
    BALANCED("均衡", 2_000),
    TURBO("极速", 800),
}

data class QzSettings(
    val displayName: String = "",
    val logMode: String = "FULL", // FULL / WARN / OFF（对应 QzLog.Mode）
    val perfMode: PerfMode = PerfMode.BALANCED,
    val discoverable: Boolean = true,
    val autoReceiveText: Boolean = true, // 已配对设备文本自动入库（免确认）
    val islandEnabled: Boolean = true,
    val islandCompat: Boolean = false, // 超级岛兼容模式（需 Shizuku + 用户知情）
    val bleEnabled: Boolean = false,
    val usbEnabled: Boolean = true, // 有线+WiFi 并行（AOA），探测即用、失败静默降级
    val usbNetEnabled: Boolean = true, // USB 网络链路（NCM/usb0，经 Shizuku；协商确认后才切换）
    val parallelStreams: Int = 4,
    val chunkSizeMiB: Int = 4,
) {
    companion object {
        fun defaultName(): String = "青舟·${Build.MODEL ?: "设备"}"
    }
}

/**
 * 设置仓库：JSON 文件 + 防抖落盘 + StateFlow（尘露同款零依赖模式）。
 */
object SettingsStore {

    private const val FILE = "settings.json"

    private val _settings = MutableStateFlow(QzSettings())
    val settings: StateFlow<QzSettings> = _settings.asStateFlow()

    private var file: File? = null
    private var saveJob: Job? = null
    private var scope: CoroutineScope? = null

    fun init(context: Context, scope: CoroutineScope) {
        if (file != null) return
        this.scope = scope
        file = File(context.applicationContext.filesDir, FILE)
        load()
    }

    fun update(transform: (QzSettings) -> QzSettings) {
        _settings.value = transform(_settings.value)
        scheduleSave()
    }

    private fun load() {
        val f = file ?: return
        runCatching {
            if (f.exists()) {
                val json = JSONObject(f.readText())
                _settings.value = QzSettings(
                    displayName = json.optString("name", ""),
                    logMode = json.optString("logMode", "FULL"),
                    perfMode = runCatching {
                        PerfMode.valueOf(json.optString("perf", PerfMode.BALANCED.name))
                    }.getOrDefault(PerfMode.BALANCED),
                    discoverable = json.optBoolean("visible", true),
                    autoReceiveText = json.optBoolean("autoText", true),
                    islandEnabled = json.optBoolean("island", true),
                    islandCompat = json.optBoolean("islandCompat", false),
                    bleEnabled = json.optBoolean("ble", false),
                    usbEnabled = json.optBoolean("usb", true),
                    usbNetEnabled = json.optBoolean("usbNet", true),
                    parallelStreams = json.optInt("streams", 4).coerceIn(1, 8),
                    chunkSizeMiB = json.optInt("chunk", 4).coerceIn(1, 16),
                )
            }
        }.onFailure { QzLog.w("settings", "读取失败：${it.message}") }
    }

    private fun scheduleSave() {
        val s = scope ?: return
        saveJob?.cancel()
        saveJob = s.launch {
            delay(300)
            withContext(QzDispatchers.io) {
                val f = file ?: return@withContext
                val cur = _settings.value
                val json = JSONObject()
                    .put("name", cur.displayName)
                    .put("logMode", cur.logMode)
                    .put("perf", cur.perfMode.name)
                    .put("visible", cur.discoverable)
                    .put("autoText", cur.autoReceiveText)
                    .put("island", cur.islandEnabled)
                    .put("islandCompat", cur.islandCompat)
                    .put("ble", cur.bleEnabled)
                    .put("usb", cur.usbEnabled)
                    .put("usbNet", cur.usbNetEnabled)
                    .put("streams", cur.parallelStreams)
                    .put("chunk", cur.chunkSizeMiB)
                runCatching { f.writeText(json.toString()) }
                    .onFailure { QzLog.w("settings", "落盘失败：${it.message}") }
            }
        }
    }
}
