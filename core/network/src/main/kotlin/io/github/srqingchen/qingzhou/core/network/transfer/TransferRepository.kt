package io.github.srqingchen.qingzhou.core.network.transfer

import android.content.Context
import io.github.srqingchen.qingzhou.core.common.QzDispatchers
import io.github.srqingchen.qingzhou.core.common.QzLog
import io.github.srqingchen.qingzhou.core.model.TransferTask
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
import java.util.concurrent.ConcurrentHashMap

/**
 * 传输任务仓库：内存任务表 + 持久化历史（最近 100 条终态）。
 * 进度高频更新走 [updateProgress]（无落盘），状态变化走 [updateState]（落盘节流）。
 */
object TransferRepository {

    private const val TAG = "transfer"
    private const val FILE = "transfer_history.json"
    private const val MAX_HISTORY = 100

    private val _tasks = MutableStateFlow<List<TransferTask>>(emptyList())
    val tasks: StateFlow<List<TransferTask>> = _tasks.asStateFlow()

    private val taskMap = ConcurrentHashMap<String, TransferTask>()

    /** 进行中任务数（决定 WifiLock 持有）。 */
    val activeCount: Int get() = _tasks.value.count {
        it.state in listOf(
            io.github.srqingchen.qingzhou.core.model.TaskState.NEGOTIATING,
            io.github.srqingchen.qingzhou.core.model.TaskState.WAITING_ACCEPT,
            io.github.srqingchen.qingzhou.core.model.TaskState.WAITING_MY_ACCEPT,
            io.github.srqingchen.qingzhou.core.model.TaskState.RUNNING,
            io.github.srqingchen.qingzhou.core.model.TaskState.VERIFYING,
        )
    }

    private var file: File? = null
    private var saveJob: Job? = null

    // 自持作用域：不可传入总控的作用域 —— 总控停止会取消它，
    // 此后所有记录静默不落盘（真机“传输记录消失”的根源）。init 忽略外部 scope。
    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + QzDispatchers.default)

    // 每任务速率采样（bytesDone 快照）
    private val lastSample = ConcurrentHashMap<String, Pair<Long, Long>>() // id -> (timestamp, bytes)

    fun init(context: Context, @Suppress("UNUSED_PARAMETER") scope: CoroutineScope? = null) {
        if (file != null) return
        synchronized(this) {
            if (file != null) return
            file = File(context.applicationContext.filesDir, FILE)
            load()
        }
    }

    fun upsert(task: TransferTask) {
        taskMap[task.id] = task
        publish()
        scheduleSave()
    }

    fun updateState(id: String, state: io.github.srqingchen.qingzhou.core.model.TaskState, error: String? = null) {
        val cur = taskMap[id] ?: return
        val ended = state in listOf(
            io.github.srqingchen.qingzhou.core.model.TaskState.DONE,
            io.github.srqingchen.qingzhou.core.model.TaskState.DECLINED,
            io.github.srqingchen.qingzhou.core.model.TaskState.CANCELLED,
            io.github.srqingchen.qingzhou.core.model.TaskState.FAILED,
        )
        if (!ended) {
            taskMap[id] = cur.copy(state = state, error = error ?: cur.error)
            lastSample.remove(id)
            publish()
            scheduleSave()
            return
        }
        // 终态速率归一：以“总字节 / 任务真实历时”计算平均速率 —— 不论文件多小多快都准确；
        // 采样不足（<3 点，传输快于采样间隔）时以 [起点0, 终点平均] 两点重建曲线，
        // 大文件保留真实波动采样。零热路径开销。
        val end = System.currentTimeMillis()
        val durationMs = (end - cur.startTime).coerceAtLeast(1)
        val avg = (cur.bytesDone * 1000 / durationMs).coerceAtLeast(0)
        val series = when {
            state == io.github.srqingchen.qingzhou.core.model.TaskState.DONE && cur.speedSeries.size >= 3 ->
                (cur.speedSeries + (end to avg)).takeLast(600)

            else -> listOf(cur.startTime to 0L, end to avg)
        }
        taskMap[id] = cur.copy(
            state = state,
            error = error ?: cur.error,
            endTime = end,
            speedBps = avg,
            speedSeries = series,
        )
        lastSample.remove(id)
        publish()
        scheduleSave(immediate = true)
    }

    /** 高频进度更新（不落盘，节流发布）。 */
    fun updateProgress(id: String, bytesDone: Long) {
        val cur = taskMap[id] ?: return
        if (cur.state != io.github.srqingchen.qingzhou.core.model.TaskState.RUNNING) return
        val now = System.currentTimeMillis()
        val sample = lastSample[id]
        var speed = cur.speedBps
        var series = cur.speedSeries
        if (sample == null) {
            lastSample[id] = now to bytesDone
            series = listOf(now to 0L)
        } else if (now - sample.first >= 500) {
            val dt = now - sample.first
            speed = ((bytesDone - sample.second) * 1000 / dt).coerceAtLeast(0)
            lastSample[id] = now to bytesDone
            series = (series + (now to speed)).takeLast(600)
        }
        if (bytesDone == cur.bytesDone && speed == cur.speedBps) return
        taskMap[id] = cur.copy(bytesDone = bytesDone, speedBps = speed, speedSeries = series)
        publishThrottled()
    }

    fun get(id: String): TransferTask? = taskMap[id]

    fun byToken(token: String): TransferTask? = taskMap[token]

    fun remove(id: String) {
        taskMap.remove(id)
        lastSample.remove(id)
        publish()
        scheduleSave()
    }

    /** 清空全部终态历史（进行中任务保留）。 */
    fun clearHistory() {
        val terminal = listOf(
            io.github.srqingchen.qingzhou.core.model.TaskState.DONE,
            io.github.srqingchen.qingzhou.core.model.TaskState.DECLINED,
            io.github.srqingchen.qingzhou.core.model.TaskState.CANCELLED,
            io.github.srqingchen.qingzhou.core.model.TaskState.FAILED,
        )
        taskMap.entries.removeIf { it.value.state in terminal }
        publish()
        scheduleSave(immediate = true)
    }

    private var lastPublish = 0L

    private val publishLock = Any()

    private fun publishThrottled() {
        synchronized(publishLock) {
            val now = System.currentTimeMillis()
            if (now - lastPublish < 200) return
            lastPublish = now
        }
        publish()
    }

    private fun publish() {
        val active = taskMap.values.filter {
            it.state != io.github.srqingchen.qingzhou.core.model.TaskState.DONE &&
                it.state != io.github.srqingchen.qingzhou.core.model.TaskState.DECLINED &&
                it.state != io.github.srqingchen.qingzhou.core.model.TaskState.CANCELLED &&
                it.state != io.github.srqingchen.qingzhou.core.model.TaskState.FAILED
        }.sortedByDescending { it.startTime }
        val history = taskMap.values.filter { it !in active }.sortedByDescending { it.endTime ?: 0 }
        _tasks.value = active + history
    }

    private fun load() {
        val f = file ?: return
        runCatching {
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                val list = mutableListOf<TransferTask>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(taskFromJson(o))
                }
                // 仅恢复终态历史；进行中任务进程已死，标记为失败
                list.forEach { t ->
                    val state = when (t.state) {
                        io.github.srqingchen.qingzhou.core.model.TaskState.DONE,
                        io.github.srqingchen.qingzhou.core.model.TaskState.DECLINED,
                        io.github.srqingchen.qingzhou.core.model.TaskState.CANCELLED,
                        io.github.srqingchen.qingzhou.core.model.TaskState.FAILED,
                        -> t.state

                        else -> io.github.srqingchen.qingzhou.core.model.TaskState.FAILED
                    }
                    taskMap[t.id] = t.copy(state = state, error = t.error ?: "进程重启中断")
                }
                publish()
            }
        }.onFailure { QzLog.w(TAG, "历史读取失败：${it.message}") }
    }

    private fun scheduleSave(immediate: Boolean = false) {
        saveJob?.cancel()
        saveJob = scope.launch {
            if (!immediate) delay(800)
            withContext(QzDispatchers.io) {
                val f = file ?: return@withContext
                val arr = JSONArray()
                taskMap.values
                    .sortedByDescending { it.endTime ?: it.startTime }
                    .take(MAX_HISTORY)
                    .forEach { arr.put(taskToJson(it)) }
                runCatching { f.writeText(arr.toString()) }
            }
        }
    }

    private fun taskToJson(t: TransferTask): JSONObject = JSONObject()
        .put("id", t.id)
        .put("dir", t.direction.name)
        .put("fp", t.peerFp)
        .put("name", t.peerName)
        .put(
            "files",
            JSONArray().apply {
                t.files.forEach {
                    put(JSONObject().put("n", it.name).put("s", it.size).put("h", it.sha256))
                }
            },
        )
        .put("state", t.state.name)
        .put("done", t.bytesDone)
        .put("total", t.totalBytes)
        .put("err", t.error ?: "")
        .put("start", t.startTime)
        .put("end", t.endTime ?: 0L)

    private fun taskFromJson(o: JSONObject): TransferTask = TransferTask(
        id = o.getString("id"),
        direction = if (o.optString("dir") == "SEND") {
            io.github.srqingchen.qingzhou.core.model.TransferDirection.SEND
        } else {
            io.github.srqingchen.qingzhou.core.model.TransferDirection.RECV
        },
        peerFp = o.getString("fp"),
        peerName = o.optString("name", "未知设备"),
        files = buildList {
            val arr = o.optJSONArray("files") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                add(io.github.srqingchen.qingzhou.core.model.FileMeta(f.getString("n"), f.getLong("s"), f.getString("h")))
            }
        },
        state = runCatching {
            io.github.srqingchen.qingzhou.core.model.TaskState.valueOf(o.getString("state"))
        }.getOrDefault(io.github.srqingchen.qingzhou.core.model.TaskState.FAILED),
        bytesDone = o.optLong("done", 0),
        totalBytes = o.optLong("total", 0),
        error = o.optString("err").takeIf { it.isNotEmpty() },
        startTime = o.optLong("start", 0),
        endTime = o.optLong("end", 0).takeIf { it > 0 },
    )
}
