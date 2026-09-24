package io.github.srqingchen.qingzhou.core.network.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.github.srqingchen.qingzhou.core.common.QzLog
import io.github.srqingchen.qingzhou.core.data.SettingsStore
import io.github.srqingchen.qingzhou.core.shizuku.QzShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB 网络链路（docs/04 §2.2 · 路线 NCM）：
 *
 * - **Gadget 侧（手机作为 USB 设备）**：经 Shizuku 以 shell 身份执行 `svc usb setFunctions ncm`
 *   （com.android.shell 持 MANAGE_USB —— 无 root 可行）。对端枚举成功后本机 Tethering 模块
 *   自动对 usb0 启 DHCP 服务端 + NAT；拔线时恢复原始 functions（不劫持用户端口）。
 * - **Host 侧（手机作为 USB 主机）**：Android 15+ 框架把 usb0 当 ETHERNET transport 跟踪
 *   （EthernetTracker V 起接口正则放宽到 (usb|eth)\d+），NetworkRequest 即可拿到 Network，
 *   数据流经 `Network.bindSocket`/`socketFactory` 绑定走 usb0；Android 13/14 默认不跟踪
 *   （无 CAP_NET_ADMIN 配不了 IP）→ 由协商层回退 AOA/纯 WiFi。
 * - **协商安全**：gadget 切换仅由对端经控制通道确认后触发（fileReady.ncmReq / fileGo.ncmGo），
 *   避免 USB 口插着 PC 时被 WiFi 上无关设备的能力位误触发。
 *
 * 链路成立后与 WiFi 完全并存：数据块帧格式不变，接收端零改动（LaneProvider 见引擎）。
 */
object UsbNetLink {

    private const val TAG = "usbnet"

    // UsbManager.ACTION_USB_STATE / USB_CONNECTED 为系统保护广播与非公开常量，字符串硬编码
    private const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
    private const val EXTRA_USB_CONNECTED = "connected"

    enum class Role { NONE, HOST, GADGET }

    /** 双端协商快照（fileReady/fileGo.wired 字段的数据源）。 */
    data class WiredInfo(
        val role: Role,
        val shizuku: Boolean,
        val usb0Ip: String?, // gadget 侧自己 usb0 的地址（host 看不到对端，gadget 直接上报）
        val ethIp: String?, // host 侧自己 usb0 的地址
        val speed: String?, // svc usb getUsbSpeed（best-effort）
    )

    private val _role = MutableStateFlow(Role.NONE)
    val role: StateFlow<Role> = _role.asStateFlow()

    @Volatile
    var ethNet: Network? = null
        private set

    @Volatile
    var ethIp: String? = null
        private set

    /** host 侧 usb0 网关 = gadget 侧 usb0 地址（发起 usb0 数据流的天然目标）。 */
    @Volatile
    var ethGateway: String? = null
        private set

    @Volatile
    var gadgetIp: String? = null
        private set

    @Volatile
    var linkSpeed: String? = null
        private set

    @Volatile
    private var gadgetAttached = false

    @Volatile
    private var origFunctions: String? = null

    private val switchedByUs = AtomicBoolean(false)

    private var appContext: Context? = null
    private var scope: CoroutineScope? = null
    private var receiver: BroadcastReceiver? = null
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Host 侧：跟踪 usb0 Ethernet 网络（Android 15+ 或厂商放宽的 13/14）
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return@runCatching
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = eval(network, cm)

                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = eval(network, cm)

                override fun onLost(network: Network) {
                    if (ethNet == network) {
                        ethNet = null
                        ethIp = null
                        if (_role.value == Role.HOST) _role.value = Role.NONE
                        QzLog.i(TAG, "usb0 以太网网络丢失")
                    }
                }

                private fun eval(network: Network, cm: ConnectivityManager) {
                    val lp = runCatching { cm.getLinkProperties(network) }.getOrNull() ?: return
                    val iface = lp.interfaceName ?: return
                    if (!iface.matches(Regex("usb\\d+"))) return
                    ethNet = network
                    ethIp = lp.linkAddresses.asSequence()
                        .mapNotNull { it.address as? Inet4Address }
                        .firstOrNull { it.isSiteLocalAddress }?.hostAddress
                    ethGateway = lp.routes.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress
                    if (_role.value != Role.GADGET) _role.value = Role.HOST
                    QzLog.i(TAG, "usb0 网络就绪：iface=$iface ip=$ethIp gw=$ethGateway（host 侧）")
                }
            })
        }.onFailure { QzLog.w(TAG, "ETHERNET 网络跟踪注册失败：${it.message}") }

        // Gadget 侧：USB_STATE（本机 gadget 连/断 host；host 侧不收此广播）
        val br = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != ACTION_USB_STATE) return
                val connected = intent.getBooleanExtra(EXTRA_USB_CONNECTED, false)
                gadgetAttached = connected
                if (connected) {
                    if (_role.value != Role.HOST) _role.value = Role.GADGET
                    refreshGadgetIp()
                    QzLog.i(TAG, "本机作为 USB 设备已连接（gadget 侧）usb0=$gadgetIp")
                } else {
                    if (_role.value == Role.GADGET) _role.value = Role.NONE
                    gadgetIp = null
                    restoreFunctions("拔线")
                }
            }
        }
        runCatching { context.registerReceiver(br, IntentFilter(ACTION_USB_STATE)) }
        receiver = br
        QzLog.i(TAG, "USB 网络链路助手已启动")
    }

    fun stop() {
        runCatching { receiver?.let { appContext?.unregisterReceiver(it) } }
        receiver = null
        restoreFunctions("stop")
        ethNet = null
        ethIp = null
        gadgetIp = null
        _role.value = Role.NONE
        scope?.cancel()
        scope = null
        started = false
    }

    fun info(): WiredInfo =
        WiredInfo(_role.value, QzShizukuManager.isReady, gadgetIp, ethIp, linkSpeed)

    // ================= Gadget 侧（Shizuku） =================

    /**
     * 切换 gadget functions 到 NCM（仅在对端经控制通道确认后调用）。
     * 幂等：已切换直接返回 true。失败记日志返回 false（上层回退 AOA/纯 WiFi）。
     */
    fun switchToNcm(): Boolean {
        if (!switchedByUs.get()) {
            if (!QzShizukuManager.isReady) {
                QzLog.w(TAG, "NCM 切换需要 Shizuku（当前不可用）")
                return false
            }
            if (!gadgetAttached && _role.value != Role.GADGET) {
                QzLog.w(TAG, "非 gadget 态，跳过 NCM 切换")
                return false
            }
            // 记住原始 functions 以便恢复（去 adb：adb 由 persist 独立管理）
            val orig = shizuku("svc", "usb", "getFunctions")?.second?.trim()
            if (orig != null) origFunctions = orig.replace(Regex("[,\\s]*adb"), "").trim()
            val r = shizuku("svc", "usb", "setFunctions", "ncm")
            if (r == null || r.first != 0) {
                QzLog.w(TAG, "svc usb setFunctions ncm 失败：${r?.second ?: "shizuku 不可用"}")
                return false
            }
            switchedByUs.set(true)
            QzLog.i(TAG, "gadget functions → ncm（原=$orig）")
        }
        readUsbSpeed()
        return true
    }

    /** 恢复切换前的 functions（拔线 / 停止 / 协商失败）。 */
    fun restoreFunctions(reason: String) {
        if (!switchedByUs.compareAndSet(true, false)) return
        val target = origFunctions?.takeIf { it.isNotEmpty() } ?: "mtp"
        scope?.launch {
            val r = shizuku("svc", "usb", "setFunctions", target)
            QzLog.i(TAG, "恢复 usb functions=$target（$reason）：${r?.second ?: "-"}")
        }
        origFunctions = null
    }

    /** 读取实际协商速率（super-speed / high-speed…，SS 则引擎加密 usb0 流数）。 */
    fun readUsbSpeed() {
        if (!QzShizukuManager.isReady) return
        scope?.launch {
            shizuku("svc", "usb", "getUsbSpeed")?.let { (_, out) ->
                linkSpeed = out.trim().takeIf { it.isNotEmpty() }?.also {
                    QzLog.i(TAG, "USB 协商速率：$it")
                }
            }
        }
    }

    /** usb0 车道数建议：SuperSpeed 3 条，否则 2 条。 */
    fun suggestedLanes(): Int = if (linkSpeed?.contains("super") == true) 3 else 2

    /** 枚举本机 usb\d+ 接口 IPv4（gadget 侧无框架网络对象，直接读接口）。 */
    fun refreshGadgetIp() {
        gadgetIp = findUsb0Ip()
    }

    private fun findUsb0Ip(): String? = runCatching {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (ni in ifaces) {
            if (!ni.isUp || !ni.name.matches(Regex("usb\\d+"))) continue
            for (ia in ni.interfaceAddresses) {
                val a = ia.address as? Inet4Address ?: continue
                if (a.isSiteLocalAddress) return a.hostAddress
            }
        }
        null
    }.getOrNull()

    /** 等待 gadget 侧 usb0 拿到地址（Tethering 起 DHCP 后）。 */
    suspend fun awaitGadgetIp(timeoutMs: Long = 8_000): String? = withTimeoutOrNull(timeoutMs) {
        while (true) {
            refreshGadgetIp()
            gadgetIp?.let { return@withTimeoutOrNull it }
            kotlinx.coroutines.delay(200)
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    /** 等待 host 侧 usb0 网络出现（协商对面刚切 NCM 后）。 */
    suspend fun awaitEthNet(timeoutMs: Long = 8_000): Network? = withTimeoutOrNull(timeoutMs) {
        while (true) {
            ethNet?.let { return@withTimeoutOrNull it }
            kotlinx.coroutines.delay(200)
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    private fun shizuku(vararg cmd: String): Pair<Int, String>? {
        val r = QzShizukuManager.execShell(8, *cmd) ?: return null
        return r.exitCode to r.output
    }

    /** 设置总开关（engine 协商前查询）。 */
    fun enabled(): Boolean = SettingsStore.settings.value.usbNetEnabled
}
