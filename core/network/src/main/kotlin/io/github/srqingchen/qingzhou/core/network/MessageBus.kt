package io.github.srqingchen.qingzhou.core.network

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** 跨层事件（UI 通知 + 系统通知都从这里取）。 */
sealed interface QzEvent {
    data class DeviceFound(val fingerprint: String, val name: String) : QzEvent
    data class DeviceLost(val fingerprint: String, val name: String) : QzEvent

    /** 响应方收到配对请求（需人工确认）。 */
    data class PairRequested(
        val requestId: String,
        val fingerprint: String,
        val name: String,
        val model: String,
        val shortCode: String,
    ) : QzEvent

    data class PairCompleted(val fingerprint: String, val name: String) : QzEvent
    data class PairFailed(val fingerprint: String?, val reason: String) : QzEvent
    data class PairDeclined(val fingerprint: String) : QzEvent

    data class TextReceived(
        val fromFp: String,
        val fromName: String,
        val content: String,
        val viaMicro: Boolean,
    ) : QzEvent

    data class TextSent(val toFp: String, val viaMicro: Boolean) : QzEvent

    /** 接收方收到文件传输请求（需人工确认，除非该设备开启自动接收）。 */
    data class OfferRequested(
        val token: String,
        val fingerprint: String,
        val name: String,
        val fileNames: List<String>,
        val totalBytes: Long,
    ) : QzEvent

    data class FilesReceived(
        val token: String,
        val fromFp: String,
        val fromName: String,
        val fileNames: List<String>,
    ) : QzEvent
}

object MessageBus {
    private val _events = MutableSharedFlow<QzEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<QzEvent> = _events

    fun post(event: QzEvent) {
        if (!_events.tryEmit(event)) {
            // 缓冲满时丢弃低优事件，保高优
            when (event) {
                is QzEvent.TextReceived, is QzEvent.PairRequested -> _events.tryEmit(event)
                else -> Unit
            }
        }
    }
}
