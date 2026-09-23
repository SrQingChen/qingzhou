package io.github.srqingchen.qingzhou.core.shizuku

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import androidx.annotation.Keep
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 运行在 Shizuku server 侧（shell uid 2000）的通用 shell 服务。
 *
 * 手写 Binder 协议（不经 AIDL 工具链，规避非 ASCII 路径下的编码缺陷）；
 * UserService 进程不受非 SDK 接口限制。
 */
@Keep
class ShellService : Binder() {

    @Volatile
    private var lastError: String = ""

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            TRANSACTION_VERSION -> {
                reply?.writeNoException()
                reply?.writeInt(VERSION)
                return true
            }

            TRANSACTION_EXEC -> {
                data.enforceInterface(DESCRIPTOR)
                val timeoutSec = data.readInt()
                val cwd = data.readString()
                val cmdLen = data.readInt()
                val cmd = Array(cmdLen) { data.readString() ?: "" }
                val (exitCode, output) = runCatching { exec(timeoutSec, cwd, cmd) }
                    .getOrElse { -1 to (it.message ?: "exec error") }
                reply?.writeNoException()
                reply?.writeInt(exitCode)
                reply?.writeString(output.take(20_000))
                return true
            }

            TRANSACTION_XMSF_GATE -> {
                data.enforceInterface(DESCRIPTOR)
                val block = data.readInt() != 0
                val (exitCode, output) = runCatching { xmsfGate(block) }
                    .getOrElse { -1 to (it.message ?: "gate error") }
                reply?.writeNoException()
                reply?.writeInt(exitCode)
                reply?.writeString(output)
                return true
            }

            TRANSACTION_LAST_ERROR -> {
                reply?.writeNoException()
                reply?.writeString(lastError)
                return true
            }

            TRANSACTION_DESTROY -> {
                // Shizuku 约定的卸载事务码：延迟退出让回包先送达
                Thread {
                    SystemClock.sleep(100)
                    System.exit(0)
                }.start()
                reply?.writeNoException()
                return true
            }

            else -> return super.onTransact(code, data, reply, flags)
        }
    }

    /** 通用命令执行（合并 stderr，带超时）。 */
    private fun exec(timeoutSec: Int, cwd: String?, cmd: Array<String>): Pair<Int, String> {
        if (cmd.isEmpty()) return -1 to "empty command"
        val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
        if (!cwd.isNullOrEmpty()) {
            runCatching { pb.directory(File(cwd)) }
        }
        val process = pb.start()
        val output = StringBuilder()
        val reader = process.inputStream.bufferedReader()
        val pump = Thread {
            runCatching {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (output.length < 60_000) output.appendLine(line)
                }
            }
        }.apply { isDaemon = true }.also { it.start() }
        val finished = runCatching {
            process.waitFor(timeoutSec.coerceAtLeast(1).toLong(), TimeUnit.SECONDS)
        }.getOrDefault(false)
        return if (finished) {
            runCatching { pump.join(500) }
            process.exitValue() to output.toString().trim()
        } else {
            process.destroyForcibly()
            -124 to "${output}\n(超时 ${timeoutSec}s 被终止)"
        }
    }

    /** 超级岛兼容模式：切断/恢复 xmsf 联网（云端鉴权 fail-open）。 */
    private fun xmsfGate(block: Boolean): Pair<Int, String> {
        val mode = if (block) "false" else "true"
        return exec(5, null, arrayOf("cmd", "connectivity", "set-package-networking-enabled", mode, XMSF_PKG))
    }

    companion object {
        const val VERSION = 1
        private const val XMSF_PKG = "com.xiaomi.xmsf"
        const val DESCRIPTOR = "io.github.srqingchen.qingzhou.core.shizuku.IShell"

        const val TRANSACTION_VERSION = IBinder.FIRST_CALL_TRANSACTION
        const val TRANSACTION_EXEC = IBinder.FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_XMSF_GATE = IBinder.FIRST_CALL_TRANSACTION + 2
        const val TRANSACTION_LAST_ERROR = IBinder.FIRST_CALL_TRANSACTION + 3
        const val TRANSACTION_DESTROY = 16777114
    }
}
