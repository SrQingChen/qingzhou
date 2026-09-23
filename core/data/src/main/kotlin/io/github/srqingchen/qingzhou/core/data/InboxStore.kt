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

enum class InboxKind(val label: String) {
    TEXT("文本"),
    LINK("链接"),
    CLIPBOARD("剪贴板"),
    FILE("文件"),
}

data class InboxItem(
    val id: Long,
    val fromFp: String,
    val fromName: String,
    val kind: InboxKind,
    val content: String, // 文本/链接/剪贴板内容；文件为文件名（M2 另存路径）
    val filePath: String? = null,
    val fileHash: String? = null,
    val timestamp: Long,
)

/**
 * 收件箱：最近 300 条，JSON 文件 + 防抖落盘。
 */
object InboxStore {

    private const val FILE = "inbox.json"
    private const val MAX = 300

    private val _items = MutableStateFlow<List<InboxItem>>(emptyList())
    val items: StateFlow<List<InboxItem>> = _items.asStateFlow()

    private var file: File? = null
    private var saveJob: Job? = null
    private var scope: CoroutineScope? = null
    private var nextId = System.currentTimeMillis()

    fun init(context: Context, scope: CoroutineScope) {
        if (file != null) return
        this.scope = scope
        file = File(context.applicationContext.filesDir, FILE)
        load()
    }

    fun add(item: InboxItem): InboxItem {
        _items.value = (listOf(item) + _items.value).take(MAX)
        scheduleSave()
        return item
    }

    fun addText(fromFp: String, fromName: String, content: String): InboxItem {
        val kind = if (content.startsWith("http://") || content.startsWith("https://")) InboxKind.LINK else InboxKind.TEXT
        return add(
            InboxItem(
                id = ++nextId,
                fromFp = fromFp,
                fromName = fromName,
                kind = kind,
                content = content,
                timestamp = System.currentTimeMillis(),
            ),
        )
    }

    fun remove(id: Long) {
        _items.value = _items.value.filterNot { it.id == id }
        scheduleSave()
    }

    fun clear() {
        _items.value = emptyList()
        scheduleSave()
    }

    private fun load() {
        val f = file ?: return
        runCatching {
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                val list = mutableListOf<InboxItem>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        InboxItem(
                            id = o.getLong("id"),
                            fromFp = o.getString("fp"),
                            fromName = o.optString("from", "未知设备"),
                            kind = runCatching { InboxKind.valueOf(o.optString("kind")) }
                                .getOrDefault(InboxKind.TEXT),
                            content = o.optString("c", ""),
                            filePath = o.optString("path").takeIf { it.isNotEmpty() },
                            fileHash = o.optString("hash").takeIf { it.isNotEmpty() },
                            timestamp = o.getLong("ts"),
                        ),
                    )
                }
                _items.value = list
                nextId = (list.maxOfOrNull { it.id } ?: System.currentTimeMillis()) + 1
            }
        }.onFailure { QzLog.w("inbox", "读取失败：${it.message}") }
    }

    private fun scheduleSave() {
        val s = scope ?: return
        saveJob?.cancel()
        saveJob = s.launch {
            delay(500)
            withContext(QzDispatchers.io) {
                val f = file ?: return@withContext
                val arr = JSONArray()
                _items.value.forEach {
                    arr.put(
                        JSONObject()
                            .put("id", it.id)
                            .put("fp", it.fromFp)
                            .put("from", it.fromName)
                            .put("kind", it.kind.name)
                            .put("c", it.content)
                            .put("path", it.filePath ?: "")
                            .put("hash", it.fileHash ?: "")
                            .put("ts", it.timestamp),
                    )
                }
                runCatching { f.writeText(arr.toString()) }
                    .onFailure { QzLog.w("inbox", "落盘失败：${it.message}") }
            }
        }
    }
}
