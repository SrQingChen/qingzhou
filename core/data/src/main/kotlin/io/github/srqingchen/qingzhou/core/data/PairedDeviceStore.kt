package io.github.srqingchen.qingzhou.core.data

import android.content.Context
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/** 已配对设备（存储对端静态公钥；微消息密钥按需从静态 DH 派生）。 */
data class PairedDevice(
    val fingerprint: String, // SHA-256(静态公钥) hex
    val staticPublicB64: String,
    val name: String,
    val model: String,
    val autoAcceptFiles: Boolean = false, // M2：已配对设备自动接收文件（默认仍需确认）
    val lastSeenMs: Long = 0,
    val lastAddress: String? = null,
    val pairedAtMs: Long = System.currentTimeMillis(),
)

/**
 * 配对设备库：JSON 文件 + 防抖落盘。
 */
object PairedDeviceStore {

    private const val FILE = "paired_devices.json"

    private val _devices = MutableStateFlow<Map<String, PairedDevice>>(emptyMap())
    val devices: StateFlow<Map<String, PairedDevice>> = _devices.asStateFlow()

    private var file: File? = null
    private var saveJob: Job? = null
    private var scope: CoroutineScope? = null

    fun init(context: Context, scope: CoroutineScope) {
        if (file != null) return
        this.scope = scope
        file = File(context.applicationContext.filesDir, FILE)
        load()
    }

    fun byFingerprint(fp: String): PairedDevice? = _devices.value[fp]

    fun upsert(device: PairedDevice) {
        _devices.value = _devices.value + (device.fingerprint to device)
        scheduleSave()
    }

    fun updateLastSeen(fp: String, address: String?, nowMs: Long = System.currentTimeMillis()) {
        val cur = _devices.value[fp] ?: return
        _devices.value = _devices.value + (fp to cur.copy(lastSeenMs = nowMs, lastAddress = address))
        // 高频调用不落盘：仅在地址变化或距上次落盘较久时保存（由防抖节流）
        scheduleSave()
    }

    fun setAutoAccept(fp: String, auto: Boolean) {
        val cur = _devices.value[fp] ?: return
        _devices.value = _devices.value + (fp to cur.copy(autoAcceptFiles = auto))
        scheduleSave()
    }

    fun remove(fp: String) {
        _devices.value = _devices.value - fp
        scheduleSave()
    }

    private fun load() {
        val f = file ?: return
        runCatching {
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                val map = mutableMapOf<String, PairedDevice>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val d = PairedDevice(
                        fingerprint = o.getString("fp"),
                        staticPublicB64 = o.getString("pub"),
                        name = o.optString("name", "未知设备"),
                        model = o.optString("model", ""),
                        autoAcceptFiles = o.optBoolean("auto", false),
                        lastSeenMs = o.optLong("seen", 0),
                        lastAddress = o.optString("addr").takeIf { it.isNotEmpty() },
                        pairedAtMs = o.optLong("at", System.currentTimeMillis()),
                    )
                    map[d.fingerprint] = d
                }
                _devices.value = map
            }
        }.onFailure { QzLog.w("paired", "读取失败：${it.message}") }
    }

    private fun scheduleSave() {
        val s = scope ?: return
        saveJob?.cancel()
        saveJob = s.launch {
            delay(500)
            withContext(QzDispatchers.io) {
                val f = file ?: return@withContext
                val arr = JSONArray()
                _devices.value.values.forEach { d ->
                    arr.put(
                        JSONObject()
                            .put("fp", d.fingerprint)
                            .put("pub", d.staticPublicB64)
                            .put("name", d.name)
                            .put("model", d.model)
                            .put("auto", d.autoAcceptFiles)
                            .put("seen", d.lastSeenMs)
                            .put("addr", d.lastAddress ?: "")
                            .put("at", d.pairedAtMs),
                    )
                }
                runCatching { f.writeText(arr.toString()) }
                    .onFailure { QzLog.w("paired", "落盘失败：${it.message}") }
            }
        }
    }
}

/** Base64 辅助（minSdk 26 起 java.util.Base64 可用，配 desugar）。 */
fun ByteArray.toB64(): String = Base64.getEncoder().encodeToString(this)

fun String.b64(): ByteArray = Base64.getDecoder().decode(this)
