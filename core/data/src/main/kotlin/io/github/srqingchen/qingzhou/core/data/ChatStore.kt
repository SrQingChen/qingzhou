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

/**
 * 聊天记录：按对端指纹分组的会话消息（文本/文件），JSON 持久化（每会话保留最近 500 条）。
 */
object ChatStore {

    data class ChatMessage(
        val id: Long,
        val mine: Boolean,
        val kind: String, // text / file
        val content: String, // 文本内容或文件名
        val filePath: String? = null,
        val timestamp: Long,
        val pending: Boolean = false, // 发送中（文件）
    )

    private const val FILE = "chats.json"
    private const val PER_PEER_MAX = 500

    /** fp → 按时间升序消息。 */
    private val _chats = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val chats: StateFlow<Map<String, List<ChatMessage>>> = _chats.asStateFlow()

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

    fun messagesOf(fp: String): List<ChatMessage> = _chats.value[fp] ?: emptyList()

    /** 会话列表（最近有消息的对端，按最后一条时间倒序）。 */
    fun sessionPeers(): List<String> =
        _chats.value.keys.sortedByDescending { fp -> _chats.value[fp]?.maxOfOrNull { it.timestamp } ?: 0 }

    fun append(fp: String, message: ChatMessage) {
        val list = _chats.value[fp].orEmpty()
        _chats.value = _chats.value + (fp to (list + message).takeLast(PER_PEER_MAX))
        scheduleSave()
    }

    fun addText(fp: String, mine: Boolean, content: String, ts: Long = System.currentTimeMillis()): ChatMessage {
        val msg = ChatMessage(id = ++nextId, mine = mine, kind = "text", content = content, timestamp = ts)
        append(fp, msg)
        return msg
    }

    fun addFile(fp: String, mine: Boolean, name: String, filePath: String? = null, pending: Boolean = false, ts: Long = System.currentTimeMillis()): ChatMessage {
        val msg = ChatMessage(id = ++nextId, mine = mine, kind = "file", content = name, filePath = filePath, timestamp = ts, pending = pending)
        append(fp, msg)
        return msg
    }

    /** 文件发送完成后由 pending 转终态。 */
    fun settleFile(fp: String, messageId: Long, ok: Boolean, finalPath: String? = null) {
        val list = _chats.value[fp] ?: return
        val updated = list.map {
            if (it.id == messageId) {
                it.copy(pending = false, content = if (ok) it.content else "⚠ ${it.content}")
            } else {
                it
            }
        }
        _chats.value = _chats.value + (fp to updated)
        scheduleSave()
    }

    fun removeMessage(fp: String, id: Long) {
        val list = _chats.value[fp] ?: return
        _chats.value = _chats.value + (fp to list.filterNot { it.id == id })
        scheduleSave()
    }

    fun clear(fp: String) {
        _chats.value = _chats.value - fp
        scheduleSave()
    }

    private fun load() {
        val f = file ?: return
        runCatching {
            if (f.exists()) {
                val root = JSONObject(f.readText())
                val map = mutableMapOf<String, List<ChatMessage>>()
                val keys = root.keys()
                while (keys.hasNext()) {
                    val fp = keys.next()
                    val arr = root.getJSONArray(fp)
                    val list = mutableListOf<ChatMessage>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(
                            ChatMessage(
                                id = o.getLong("id"),
                                mine = o.optBoolean("m", false),
                                kind = o.optString("k", "text"),
                                content = o.optString("c", ""),
                                filePath = o.optString("p").takeIf { it.isNotEmpty() },
                                timestamp = o.getLong("ts"),
                                pending = false,
                            ),
                        )
                    }
                    map[fp] = list
                }
                _chats.value = map
                nextId = (map.values.flatten().maxOfOrNull { it.id } ?: System.currentTimeMillis()) + 1
            }
        }.onFailure { QzLog.w("chat", "聊天记录读取失败：${it.message}") }
    }

    private fun scheduleSave() {
        val s = scope ?: return
        saveJob?.cancel()
        saveJob = s.launch {
            delay(500)
            withContext(QzDispatchers.io) {
                val f = file ?: return@withContext
                runCatching {
                    val root = JSONObject()
                    _chats.value.forEach { (fp, list) ->
                        root.put(
                            fp,
                            JSONArray().apply {
                                list.forEach {
                                    put(
                                        JSONObject()
                                            .put("id", it.id)
                                            .put("m", it.mine)
                                            .put("k", it.kind)
                                            .put("c", it.content)
                                            .put("p", it.filePath ?: "")
                                            .put("ts", it.timestamp),
                                    )
                                }
                            },
                        )
                    }
                    f.writeText(root.toString())
                }.onFailure { QzLog.w("chat", "聊天记录落盘失败：${it.message}") }
            }
        }
    }
}
