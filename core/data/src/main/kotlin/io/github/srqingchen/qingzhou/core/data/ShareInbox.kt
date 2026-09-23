package io.github.srqingchen.qingzhou.core.data

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 系统分享入口暂存：其他应用「分享 → 青舟」后暂存于此，
 * 由发现页横幅展示，点击已配对设备即发送。
 */
object ShareInbox {

    data class SharePayload(
        val text: String? = null,
        val uris: List<Uri> = emptyList(),
        val mimeType: String? = null,
        val fromApp: String? = null,
    ) {
        val isText: Boolean get() = !text.isNullOrBlank() && uris.isEmpty()
        val isFiles: Boolean get() = uris.isNotEmpty()
        val summary: String
            get() = when {
                isFiles -> "${uris.size} 个文件"
                else -> "文本 ${(text?.length ?: 0)} 字"
            }
    }

    private val _pending = MutableStateFlow<SharePayload?>(null)
    val pending: StateFlow<SharePayload?> = _pending.asStateFlow()

    fun offer(payload: SharePayload) {
        _pending.value = payload
    }

    fun consume(): SharePayload? {
        val p = _pending.value
        _pending.value = null
        return p
    }

    fun clear() {
        _pending.value = null
    }
}
