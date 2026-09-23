package io.github.srqingchen.qingzhou.core.shizuku

/** Shizuku 通道状态（驱动 UI 引导与增强功能开关）。 */
sealed interface QzShizukuState {
    /** 未安装 Shizuku 应用。 */
    data object NotInstalled : QzShizukuState

    /** 已安装但 server 未运行（重启后需重新启动）。 */
    data object NotRunning : QzShizukuState

    /** server 运行中，等待用户授权本应用。 */
    data object AwaitingPermission : QzShizukuState

    /** 正在拉起 UserService。 */
    data object Connecting : QzShizukuState

    /** shell 服务就绪。 */
    data class Ready(val uid: Int) : QzShizukuState

    /** 明确失败（版本过旧、绑定失败等）。 */
    data class Failed(val reason: String) : QzShizukuState
}
