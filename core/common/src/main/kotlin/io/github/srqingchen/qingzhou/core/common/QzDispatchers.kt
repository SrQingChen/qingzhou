package io.github.srqingchen.qingzhou.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 全局协程调度器出口。
 * 网络引擎的收发循环全部走 [io]，避免阻塞 Default 池影响 UI 与调度。
 */
object QzDispatchers {
    val default: CoroutineDispatcher = Dispatchers.Default
    val io: CoroutineDispatcher = Dispatchers.IO
}
