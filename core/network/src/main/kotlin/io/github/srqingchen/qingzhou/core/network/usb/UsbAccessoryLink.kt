package io.github.srqingchen.qingzhou.core.network.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import io.github.srqingchen.qingzhou.core.common.QzLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * USB AOA 双角色链路（有线+WiFi 并行的有线半边）：
 * - Host 侧：枚举对端 → 若未处于 accessory 模式则发切换请求（control transfer 52/53）
 *   → 对端重枚举为 accessory → requestPermission → openAccessory 拿 fd；
 * - Accessory 侧：系统 USB_ACCESSORY_ATTACHED 广播 + manifest filter 唤起 → openAccessory 拿 fd。
 *
 * fd 生命周期：attach 后启动常驻接收循环（读端独占）；发送 sink 只写不读（纯写设计，
 * 双端同时互发安全）。发送侧经 [obtainSendStream] 取写端。
 * 兼容性：任一步失败仅记日志静默降级为纯 WiFi（能力位 Cap.USB 双端协商后才启用）。
 */
object UsbAccessoryLink {

    private const val TAG = "usb"
    private const val ACTION_USB_PERMISSION = "io.github.srqingchen.qingzhou.USB_PERMISSION"

    /** AOA accessory 模式厂商/产品 ID（Google 约定）。 */
    private const val GOOG_VID = 0x18D1
    private const val ACC_PID_1 = 0x2D00
    private const val ACC_PID_2 = 0x2D01

    private const val ACC_MANUFACTURER = "SrQingChen"
    private const val ACC_MODEL = "QingZhou"
    private const val ACC_VERSION = "1.0"

    sealed class UsbState {
        data object Detached : UsbState() // 未连接（初始态）
        data object HostSwitching : UsbState() // 已发切换请求，等待对端重枚举
        data class Connected(val asHost: Boolean) : UsbState()
    }

    private val _state = MutableStateFlow<UsbState>(UsbState.Detached)
    val state: StateFlow<UsbState> = _state.asStateFlow()

    val attached: Boolean get() = _state.value is UsbState.Connected

    private var appContext: Context? = null
    private var scope: CoroutineScope? = null
    private var receiver: BroadcastReceiver? = null

    @Volatile
    private var pfd: ParcelFileDescriptor? = null

    @Volatile
    private var linkInput: InputStream? = null

    @Volatile
    private var linkOutput: OutputStream? = null

    @Volatile
    private var linkCloser: (() -> Unit)? = null

    /** 接收循环宿主（由 FileTransferEngine 注册入流处理器）。 */
    @Volatile
    var sinkListener: ((InputStream) -> Unit)? = null

    @Volatile
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val br = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        QzLog.i(TAG, "USB 设备插入 → 尝试建立 accessory 链路")
                        tryConnectAsHost()
                    }

                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        QzLog.i(TAG, "USB 设备拔出 → 关闭 accessory/bulk 链路")
                        closeLink("device detached")
                    }

                    UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> tryConnectAsAccessory()

                    ACTION_USB_PERMISSION -> {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        QzLog.i(TAG, "USB 权限结果：$granted")
                        if (granted) tryConnectAsHost()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        runCatching { context.applicationContext.registerReceiver(br, filter) }
        receiver = br
        // 启动即探测（可能线已插好）
        tryConnectAsAccessory()
        tryConnectAsHost()
        QzLog.i(TAG, "USB 链路助手已启动")
    }

    fun stop() {
        runCatching { receiver?.let { appContext?.unregisterReceiver(it) } }
        receiver = null
        closeLink("stop")
        scope?.cancel()
        scope = null
        started = false
    }

    /** 发送侧取写流（纯写设计：不触碰读端）。无链路返回 null，调用方优雅降级。 */
    fun obtainSendStream(): OutputStream? = linkOutput

    // ---------- Host 侧 ----------

    /**
     * Host 侧主流程（幂等）：
     * ① 对端已处于 accessory 模式且有权限 → 打开（优先 UsbRequest 异步 bulk 队列，失败回退 fd）；
     * ② 有 accessory 无权限 → 申请 accessory 权限；
     * ③ 对端是普通手机且有设备权限 → 发 accessory 切换请求（等重枚举）；
     * ④ 普通手机无权限 → 申请设备权限（回调后回到本函数）。
     * NCM/RNDIS 网络设备一律跳过切换（对端已被协商切换为 usb0 网卡，切换请求会杀掉它）。
     */
    private fun tryConnectAsHost() {
        val ctx = appContext ?: return
        if (attached) return
        val usb = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        runCatching {
            val acc = usb.accessoryList?.firstOrNull()
            if (acc != null) {
                if (usb.hasPermission(acc)) {
                    // D6：Host 侧优先 claimInterface + UsbRequest 异步队列（吞吐/CPU 双优），失败回退 fd
                    if (!tryBindBulkAsHost(ctx, usb)) {
                        usb.openAccessory(acc)?.let { bindFd(it, asHost = true) }
                    }
                } else {
                    requestPermission(ctx, usb, null)
                }
                return
            }
            val device = usb.deviceList.values.firstOrNull { it.vendorId != GOOG_VID } ?: return
            if (isNetworkGadget(device)) {
                QzLog.i(TAG, "对端为 USB 网络设备（NCM/RNDIS），AOA 侧让位 usb0 链路")
                return
            }
            if (usb.hasPermission(device)) {
                val conn = usb.openDevice(device) ?: return
                val ok = sendSwitchRequest(conn)
                conn.close()
                if (ok) {
                    _state.value = UsbState.HostSwitching
                    QzLog.i(TAG, "已请求对端进入 accessory 模式，等待重枚举（对端需允许青舟）")
                }
            } else {
                requestPermission(ctx, usb, device)
            }
        }.onFailure { QzLog.d(TAG, "Host 侧探测失败（静默降级）：${it.message}") }
    }

    /** 网络设备判定：CDC-Control(0x02) / CDC-Data(0x0A) / RNDIS IAD(MISC 0xEF) / vendor-NCM(0xFF/0x0D)。 */
    private fun isNetworkGadget(d: UsbDevice): Boolean {
        for (i in 0 until d.interfaceCount) {
            val iface = d.getInterface(i)
            if (iface.interfaceClass == 0x02 || iface.interfaceClass == 0x0A) return true
            if (iface.interfaceClass == 0xEF && iface.interfaceSubclass == 0x02) return true
            if (iface.interfaceClass == 0xFF && iface.interfaceSubclass == 0x0D) return true
        }
        return false
    }

    /** Host 侧 bulk 异步通道（UsbBulkChannel）：找到双 bulk 端点的接口并接管；失败回 false。 */
    private fun tryBindBulkAsHost(ctx: Context, usb: UsbManager): Boolean = runCatching {
        val dev = usb.deviceList.values.firstOrNull {
            it.vendorId == GOOG_VID && (it.productId == ACC_PID_1 || it.productId == ACC_PID_2)
        } ?: return false
        if (!usb.hasPermission(dev)) {
            requestPermission(ctx, usb, dev)
            return false
        }
        val conn = usb.openDevice(dev) ?: return false
        for (i in 0 until dev.interfaceCount) {
            val iface = dev.getInterface(i)
            var outEp: UsbEndpoint? = null
            var inEp: UsbEndpoint? = null
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_OUT) outEp = ep else inEp = ep
                }
            }
            if (outEp != null && inEp != null && conn.claimInterface(iface, true)) {
                val channel = UsbBulkChannel(conn, outEp, inEp)
                bind(channel.source, channel.sink, asHost = true) { channel.close("rebind/detach") }
                channel.start()
                return true
            }
        }
        conn.close()
        false
    }.getOrDefault(false)

    private fun requestPermission(ctx: Context, usb: UsbManager, switchTarget: UsbDevice?) {
        val pi = PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName),
            PendingIntent.FLAG_MUTABLE,
        )
        when {
            switchTarget != null -> runCatching {
                QzLog.i(TAG, "请求 USB 设备权限（拟切换 accessory）：${switchTarget.deviceName}")
                usb.requestPermission(switchTarget, pi)
            }

            else -> runCatching {
                val acc = usb.accessoryList?.firstOrNull() ?: return
                QzLog.i(TAG, "请求 accessory 权限：${acc.description ?: acc.model}")
                usb.requestPermission(acc, pi)
            }
        }
    }

    /** AOA 切换：发送厂商/型号字符串 + START。成功后对端重枚举为 accessory 设备。 */
    private fun sendSwitchRequest(conn: UsbDeviceConnection): Boolean = runCatching {
        var sent = 0
        fun send(index: Int, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val n = conn.controlTransfer(
                0x40, // DIR_OUT | TYPE_VENDOR
                52,   // ACCESSORY_SEND_STRING
                0,
                index,
                bytes,
                bytes.size,
                3000,
            )
            if (n >= 0) sent++
        }
        send(0, ACC_MANUFACTURER)
        send(1, ACC_MODEL)
        send(2, "QingZhou wired link") // description
        send(3, ACC_VERSION)
        send(4, "https://github.com/SrQingChen") // uri
        send(5, "0") // serial
        if (sent < 6) return false
        conn.controlTransfer(0x40, 53, 0, 0, null, 0, 3000) // ACCESSORY_START
        true
    }.getOrDefault(false)

    // ---------- Accessory 侧 ----------

    private fun tryConnectAsAccessory() {
        val ctx = appContext ?: return
        if (attached) return
        val usb = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        runCatching {
            val acc = usb.accessoryList?.firstOrNull() ?: return
            if (usb.hasPermission(acc)) {
                usb.openAccessory(acc)?.let { bindFd(it, asHost = false) }
            } else {
                // accessory 权限一般由 attach 弹窗授予；这里补请求
                requestPermission(ctx, usb, null)
            }
        }.onFailure { QzLog.d(TAG, "Accessory 侧探测失败（静默降级）：${it.message}") }
    }

    // ---------- fd 生命周期 ----------

    /** fd 版链路（accessory 侧 / host 侧 bulk 失败回退）。 */
    private fun bindFd(p: ParcelFileDescriptor, asHost: Boolean) {
        bind(
            FileInputStream(p.fileDescriptor),
            FileOutputStream(p.fileDescriptor),
            asHost,
        ) { runCatching { p.close() } }
        pfd = p
    }

    /**
     * 通用链路绑定（fd 流或 bulk 异步通道）。
     * 常驻接收循环：读端独占（发送 sink 纯写，不冲突）；close 由 [closer] 负责。
     */
    private fun bind(input: InputStream, output: OutputStream, asHost: Boolean, closer: () -> Unit) {
        if (attached) {
            closer()
            return
        }
        linkInput = input
        linkOutput = output
        linkCloser = closer
        _state.value = UsbState.Connected(asHost)
        QzLog.i(TAG, "USB accessory 链路已建立（${if (asHost) "Host" else "Accessory"} 侧，${output.javaClass.simpleName}）")
        val listener = sinkListener
        if (listener != null) {
            scope?.launch {
                runCatching { listener(input) }
                QzLog.i(TAG, "USB 接收循环结束")
            }
        }
    }

    private fun closeLink(reason: String) {
        runCatching { (linkOutput as? FileOutputStream)?.flush() }
        runCatching { linkCloser?.invoke() }
        runCatching { pfd?.close() }
        pfd = null
        linkInput = null
        linkOutput = null
        linkCloser = null
        _state.value = UsbState.Detached
        QzLog.i(TAG, "USB 链路已关闭（$reason）")
    }

    /** 按接口端点探测（诊断备用：确认 bulk 双端点存在）。 */
    @Suppress("unused")
    private fun dumpBulkEndpoints(device: UsbDevice): String {
        val sb = StringBuilder(device.deviceName)
        for (i in 0 until device.interfaceCount) {
            val iface: UsbInterface = device.getInterface(i)
            for (e in 0 until iface.endpointCount) {
                val ep: UsbEndpoint = iface.getEndpoint(e)
                sb.append(" ep:").append(ep.endpointNumber)
                    .append(if (ep.direction == UsbConstants.USB_DIR_OUT) "OUT" else "IN")
            }
        }
        return sb.toString()
    }

    /** FileDescriptor 直开（兼容 FileInputStream 构造，诊断用）。 */
    @Suppress("unused")
    private fun fdInputStream(fd: FileDescriptor): InputStream = FileInputStream(fd)
}
