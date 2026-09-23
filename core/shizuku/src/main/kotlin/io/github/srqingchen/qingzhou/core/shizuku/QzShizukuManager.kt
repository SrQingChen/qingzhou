package io.github.srqingchen.qingzhou.core.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import io.github.srqingchen.qingzhou.core.common.QzLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * Shizuku 通道管理：Binder 生命周期、权限三段式、UserService 绑定。
 * 状态对外以 [state] 暴露；[shell] 为 null 表示增强功能不可用。
 */
object QzShizukuManager {

    private const val TAG = "shizuku"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 10115

    private val _state = MutableStateFlow<QzShizukuState>(QzShizukuState.NotRunning)
    val state: StateFlow<QzShizukuState> = _state.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var started = false

    @Volatile
    var shell: ShellProxy? = null
        private set

    val isReady: Boolean get() = shell != null && _state.value is QzShizukuState.Ready

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                val proxy = ShellProxy(binder)
                shell = proxy
                _state.value = QzShizukuState.Ready(Shizuku.getUid())
                QzLog.i(TAG, "shell 服务就绪 (uid=${Shizuku.getUid()})")
            } else {
                _state.value = QzShizukuState.Failed("shell 服务返回无效 Binder")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shell = null
            _state.value = QzShizukuState.NotRunning
        }
    }

    private fun userServiceArgs(context: Context): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShellService::class.java.name),
        )
            .tag("qingzhou-shell")
            .version(ShellService.VERSION)
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(false)

    /** App 启动时调用：无条件注册 Binder/权限监听（幂等），随后评估一次状态。 */
    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            appContext = context.applicationContext
        }
        Shizuku.addBinderReceivedListenerSticky { onBinderAlive() }
        Shizuku.addBinderDeadListener {
            shell = null
            _state.value = QzShizukuState.NotRunning
        }
        Shizuku.addRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED) onBinderAlive()
        }
        refresh()
    }

    /** 重新评估状态（回到前台/用户点重试时调用）。 */
    fun refresh() {
        val ctx = appContext ?: return
        val info = runCatching {
            ctx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        }.getOrNull()
        if (info == null) {
            QzLog.d(TAG, "未见 $SHIZUKU_PACKAGE（增强功能将保持隐藏）")
            _state.value = QzShizukuState.NotInstalled
            return
        }
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (binderAlive) {
            onBinderAlive()
        } else if (_state.value !is QzShizukuState.Ready) {
            _state.value = QzShizukuState.NotRunning
        }
    }

    /** 由 UI 调用：请求用户授权（需前台）。 */
    fun requestPermission() {
        val ctx = appContext ?: return
        runCatching {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        }.onFailure {
            openShizukuApp(ctx)
        }
    }

    fun openShizukuApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }

    /** 在 IO 线程执行 shell 命令（增强功能统一入口）。 */
    fun execShell(timeoutSec: Int = 5, vararg cmd: String): ShellProxy.ExecResult? {
        val proxy = shell ?: return null
        return runCatching { proxy.exec(timeoutSec, null, *cmd) }.getOrNull()
    }

    private fun onBinderAlive() {
        if (!Shizuku.pingBinder()) {
            _state.value = QzShizukuState.NotRunning
            return
        }
        if (Shizuku.isPreV11()) {
            _state.value = QzShizukuState.Failed("Shizuku 版本过旧（需 v11+）")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            _state.value = QzShizukuState.AwaitingPermission
            return
        }
        if (shell != null) {
            _state.value = QzShizukuState.Ready(Shizuku.getUid())
            return
        }
        val ctx = appContext ?: return
        _state.value = QzShizukuState.Connecting
        val result = runCatching {
            Shizuku.bindUserService(userServiceArgs(ctx), userServiceConnection)
        }
        if (result.isFailure) {
            _state.value = QzShizukuState.Failed("绑定 shell 服务失败：${result.exceptionOrNull()?.message}")
        }
    }
}
