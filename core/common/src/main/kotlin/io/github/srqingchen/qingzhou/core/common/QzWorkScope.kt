package io.github.srqingchen.qingzhou.core.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 进程级工作作用域：发送文件/文本/配对等长任务统一在此运行。
 *
 * 教训（真机日志 09-23 09:08:42 "rememberCoroutineScope left the composition"）：
 * 传输跑在界面作用域里，用户切走页面协程即被取消 —— 文件已被对端完整校验，
 * 发送端却显示失败。长任务必须与界面生命周期解耦。
 */
object QzWorkScope {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
