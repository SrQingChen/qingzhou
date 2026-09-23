package io.github.srqingchen.qingzhou.core.shizuku

import android.os.IBinder
import android.os.Parcel

/**
 * [ShellService] 的客户端代理：与手写 Binder 协议配对。
 * transact 均为同步调用（flags=0），必须在 IO 线程使用。
 */
class ShellProxy(private val binder: IBinder) {

    data class ExecResult(val exitCode: Int, val output: String)

    fun ping(): Boolean = binder.pingBinder()

    fun version(): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShellService.DESCRIPTOR)
            binder.transact(ShellService.TRANSACTION_VERSION, data, reply, 0)
            reply.readException()
            reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** 执行命令；返回 (exitCode, output)。 */
    fun exec(timeoutSec: Int = 5, cwd: String? = null, vararg cmd: String): ExecResult {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShellService.DESCRIPTOR)
            data.writeInt(timeoutSec)
            data.writeString(cwd)
            data.writeInt(cmd.size)
            cmd.forEach { data.writeString(it) }
            binder.transact(ShellService.TRANSACTION_EXEC, data, reply, 0)
            reply.readException()
            ExecResult(reply.readInt(), reply.readString().orEmpty())
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** 超级岛兼容模式：block=true 切断 xmsf 联网，false 恢复。返回 null 表示成功。 */
    fun xmsfGate(block: Boolean): String? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShellService.DESCRIPTOR)
            data.writeInt(if (block) 1 else 0)
            binder.transact(ShellService.TRANSACTION_XMSF_GATE, data, reply, 0)
            reply.readException()
            val code = reply.readInt()
            val detail = reply.readString().orEmpty()
            if (code == 0) null else "code=$code: $detail"
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun lastError(): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShellService.DESCRIPTOR)
            binder.transact(ShellService.TRANSACTION_LAST_ERROR, data, reply, 0)
            reply.readException()
            reply.readString().orEmpty()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
