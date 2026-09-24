package io.github.srqingchen.qingzhou.core.network.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import io.github.srqingchen.qingzhou.core.common.QzLog
import io.github.srqingchen.qingzhou.core.crypto.IdentityStore
import io.github.srqingchen.qingzhou.core.data.PairedDeviceStore
import io.github.srqingchen.qingzhou.core.data.SettingsStore
import io.github.srqingchen.qingzhou.core.model.AnnouncePacket
import io.github.srqingchen.qingzhou.core.model.Cap
import io.github.srqingchen.qingzhou.core.model.Peer
import io.github.srqingchen.qingzhou.core.model.Protocol
import io.github.srqingchen.qingzhou.core.network.LinkInfo
import io.github.srqingchen.qingzhou.core.network.LinkProbe
import io.github.srqingchen.qingzhou.core.network.MessageBus
import io.github.srqingchen.qingzhou.core.network.QzEvent
import io.github.srqingchen.qingzhou.core.network.WifiLocks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * 发现引擎：UDP 广播/多播 announce + 单播 register 回应 + 网关直连探测。
 *
 * 生命周期：App 启动即启动（轻量：一个 UDP 监听 + 心跳），进程存活期间常驻。
 * 所有阻塞 IO 跑在 IO 调度器；[peers] / [link] 为 UI 的唯一事实源。
 */
object DiscoveryEngine {

    private const val TAG = "discovery"

    private val _peers = MutableStateFlowList()
    val peers get() = _peers.flow

    private val peerMap = ConcurrentHashMap<String, Peer>()

    private val _link = kotlinx.coroutines.flow.MutableStateFlow(LinkInfo(false, null, null, false))
    val link: kotlinx.coroutines.flow.StateFlow<LinkInfo> = _link

    private var scope: CoroutineScope? = null
    private var appContext: Context? = null
    private var socket: MulticastSocket? = null
    private var mcastLock: WifiManager.MulticastLock? = null
    private var heartbeatJob: Job? = null
    private var watchdogJob: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var started = false

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            appContext = context.applicationContext
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        val ctx = appContext ?: return
        WifiLocks.holdDiscovery(ctx)
        openSocket(ctx)
        scope?.launch { receiveLoop() }
        scope?.launch { heartbeatLoop() }
        scope?.launch { lockWatchdog() }
        registerNetworkCallback(ctx)
        refreshLinkAndBurst(ctx)
        QzLog.i(TAG, "发现引擎已启动")
    }

    fun stop() {
        synchronized(this) {
            if (!started) return
            started = false
        }
        runCatching { netCallback?.let { (appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(it) } }
        netCallback = null
        heartbeatJob?.cancel(); heartbeatJob = null
        watchdogJob?.cancel(); watchdogJob = null
        runCatching { socket?.close() }
        socket = null
        runCatching { mcastLock?.takeIf { it.isHeld }?.release() }
        mcastLock = null
        scope?.cancel()
        scope = null
        WifiLocks.releaseDiscovery()
        peerMap.clear()
        _peers.replaceAll(emptyList())
        QzLog.i(TAG, "发现引擎已停止")
    }

    /** 立即触发一轮敏捷发现（下拉刷新/进入前台）。 */
    fun probeNow() {
        val ctx = appContext ?: return
        scope?.launch {
            refreshLink(ctx)
            burstAnnounce()
            gatewayProbe()
        }
    }

    // ---------- 内部 ----------

    private fun openSocket(context: Context) {
        runCatching {
            val s = MulticastSocket(null)
            s.reuseAddress = true
            s.broadcast = true
            s.bind(InetSocketAddress(Protocol.PORT_DISCOVERY))
            s.soTimeout = 0
            socket = s

            val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            mcastLock = wifi?.createMulticastLock("qz:discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            joinMulticast(s)
        }.onFailure {
            QzLog.e(TAG, "UDP 监听打开失败：${it.message}")
        }
    }

    private fun joinMulticast(s: MulticastSocket) {
        runCatching {
            val group = InetAddress.getByName(Protocol.MULTICAST_GROUP)
            val iface = pickMulticastInterface() ?: return@runCatching
            s.joinGroup(InetSocketAddress(group, Protocol.PORT_DISCOVERY), iface)
            joinedIfaces.add(iface.name)
            QzLog.d(TAG, "已加入多播组 ${Protocol.MULTICAST_GROUP}@${iface.name}")
        }.onFailure {
            QzLog.w(TAG, "多播加入失败（广播仍可用）：${it.message}")
        }
    }

    private fun pickMulticastInterface(): NetworkInterface? {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (iface in ifaces) {
            if (!iface.isUp || iface.isLoopback || !iface.supportsMulticast()) continue
            val hasIpv4 = iface.inetAddresses.asSequence().any { it is java.net.Inet4Address && it.isSiteLocalAddress }
            if (hasIpv4 && (iface.name.startsWith("wlan") || iface.name == "ap0" || iface.name == "swlan0")) {
                return iface
            }
        }
        // 兜底：任意支持多播且有 IPv4 的接口
        val all = NetworkInterface.getNetworkInterfaces() ?: return null
        for (iface in all) {
            if (!iface.isUp || iface.isLoopback || !iface.supportsMulticast()) continue
            if (iface.inetAddresses.asSequence().any { it is java.net.Inet4Address && it.isSiteLocalAddress }) return iface
        }
        return null
    }

    private fun buildAnnounce(): AnnouncePacket {
        val identity = IdentityStore.peek()!!
        val settings = SettingsStore.settings.value
        return AnnouncePacket(
            protocolVersion = Protocol.VERSION,
            fingerprint = identity.fingerprintHex,
            name = settings.displayName.ifEmpty { "青舟·${android.os.Build.MODEL}" },
            model = android.os.Build.MODEL ?: "",
            apiLevel = android.os.Build.VERSION.SDK_INT,
            controlPort = Protocol.PORT_CONTROL,
            messagePort = Protocol.PORT_MESSAGE,
            capabilities = Cap.TEXT or Cap.FILE or Cap.USB or Cap.USB_NET or Cap.V2,
            timestamp = System.currentTimeMillis(),
        )
    }

    private val joinedIfaces = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 广播发送：向每个 WiFi 系接口的定向广播地址 + 有限广播 + 多播组各发一份。
     * 旧版只发 255.255.255.255 —— 无默认路由时 ENETUNREACH（真机日志 22:48:10），
     * 且热点/P2P 主机场景完全发不出去。
     */
    private fun sendAnnounce(target: InetAddress?, port: Int = Protocol.PORT_DISCOVERY) {
        val s = socket ?: return
        val settings = SettingsStore.settings.value
        if (!settings.discoverable && target == null) return // 不可见模式只回应不广播
        val bytes = buildAnnounce().encode(Protocol.UDP_ANNOUNCE)
        if (target != null) {
            runCatching { s.send(DatagramPacket(bytes, bytes.size, target, port)) }
                .onFailure { QzLog.d(TAG, "announce 单播失败：${it.message}") }
            return
        }
        var sent = 0
        // 每个接口的定向广播
        _link.value.interfaces.forEach { iface ->
            iface.broadcast?.let { bc ->
                runCatching {
                    s.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(bc), port))
                    sent++
                }.onFailure { QzLog.d(TAG, "announce→${iface.name}($bc) 失败：${it.message}") }
            }
        }
        // 有限广播兜底 + 多播组
        runCatching {
            s.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), port))
            sent++
        }
        runCatching {
            s.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(Protocol.MULTICAST_GROUP), port))
            sent++
        }
        if (sent == 0) QzLog.d(TAG, "announce 全部发送失败（无可用出口）")
    }

    private suspend fun receiveLoop() = withContext(Dispatchers.IO) {
        val buf = ByteArray(2048)
        while (isActive) {
            val s = socket ?: break
            val packet = DatagramPacket(buf, buf.size)
            try {
                s.receive(packet)
            } catch (e: Exception) {
                if (socket == null || !socket!!.isBound) break
                continue
            }
            if (packet.length <= 1) continue
            val from = packet.address.hostAddress ?: continue
            val data = buf.copyOf(packet.length)
            handleIncoming(data, from)
        }
    }

    private fun handleIncoming(data: ByteArray, fromHost: String) {
        val (prefix, packet) = AnnouncePacket.decode(data) ?: return
        val myFp = IdentityStore.peek()?.fingerprintHex ?: return
        if (packet.fingerprint == myFp) return
        if (packet.protocolVersion != Protocol.VERSION) return
        upsert(packet, fromHost)
        when (prefix) {
            Protocol.UDP_ANNOUNCE -> {
                // 单播 register 回应，令对方立刻看见我
                scope?.launch {
                    runCatching {
                        val s = socket ?: return@launch
                        val bytes = buildAnnounce().encode(Protocol.UDP_REGISTER)
                        s.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(fromHost), Protocol.PORT_DISCOVERY))
                    }
                }
            }
        }
    }

    /** 对外共享的 upsert（SessionManager 收到 TCP register 时也调用）。 */
    fun upsert(packet: AnnouncePacket, host: String) {
        val existing = peerMap[packet.fingerprint]
        val isNew = existing == null
        peerMap[packet.fingerprint] = Peer(
            fingerprint = packet.fingerprint,
            name = packet.name,
            model = packet.model,
            apiLevel = packet.apiLevel,
            host = host,
            controlPort = packet.controlPort,
            messagePort = packet.messagePort,
            capabilities = packet.capabilities,
            lastSeenMs = System.currentTimeMillis(),
            paired = PairedDeviceStore.byFingerprint(packet.fingerprint) != null,
        )
        if (existing != null) {
            PairedDeviceStore.updateLastSeen(packet.fingerprint, host)
        }
        publishPeers()
        if (isNew) {
            QzLog.i(TAG, "发现设备 ${packet.name} ($host)")
            MessageBus.post(QzEvent.DeviceFound(packet.fingerprint, packet.name))
        }
    }

    private fun publishPeers() {
        _peers.replaceAll(peerMap.values.sortedByDescending { it.lastSeenMs })
    }

    private suspend fun heartbeatLoop() {
        var lastLinkSig = ""
        while (kotlin.coroutines.coroutineContext.isActive) {
            val interval = SettingsStore.settings.value.perfMode.heartbeatMs
            val ctx = appContext
            if (ctx != null) {
                refreshLink(ctx)
                // 链路签名变化（换网/新接口/网关变化）→ 立即直连探测；
                // 不被 ConnectivityManager 上报的热点网络（wlan2 类）靠这里周期感知
                val link = _link.value
                val sig = "${link.ownAddress}|${link.gateway}|${link.interfaces.joinToString { it.name }}"
                if (sig != lastLinkSig) {
                    lastLinkSig = sig
                    gatewayProbe()
                }
            }
            sendAnnounce(null)
            prunePeers(interval)
            delay(interval)
        }
    }

    private fun prunePeers(intervalMs: Long) {
        // 极速档 800ms × 3 = 2.4s 过于激进（丢一拍就掉线），下限 10s
        val window = maxOf(intervalMs * 3, 10_000)
        val cutoff = System.currentTimeMillis() - window
        val removed = peerMap.entries.filter { it.value.lastSeenMs < cutoff }
        if (removed.isNotEmpty()) {
            removed.forEach {
                peerMap.remove(it.key)
                MessageBus.post(QzEvent.DeviceLost(it.key, it.value.name))
            }
            publishPeers()
        }
    }

    private suspend fun lockWatchdog() {
        while (kotlin.coroutines.coroutineContext.isActive) {
            delay(30_000)
            // MulticastLock 可能被系统/其他应用影响而失效，周期重取
            val ctx = appContext ?: break
            runCatching {
                mcastLock?.takeIf { it.isHeld }?.release()
            }
            val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            mcastLock = wifi?.createMulticastLock("qz:discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            WifiLocks.holdDiscovery(ctx)
            // 多播组跟随当前接口（启动时无 WiFi → 之后连上，需补入组）
            val s = socket
            if (s != null) {
                val iface = pickMulticastInterface()
                if (iface != null && iface.name !in joinedIfaces) {
                    runCatching {
                        s.joinGroup(
                            InetSocketAddress(InetAddress.getByName(Protocol.MULTICAST_GROUP), Protocol.PORT_DISCOVERY),
                            iface,
                        )
                        joinedIfaces.add(iface.name)
                        QzLog.d(TAG, "多播组已补入 ${iface.name}")
                    }
                }
            }
        }
    }

    private fun registerNetworkCallback(context: Context) {
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope?.launch {
                        refreshLink(context)
                        burstAnnounce()
                        gatewayProbe()
                    }
                }

                override fun onLost(network: Network) {
                    scope?.launch { refreshLink(context) }
                }
            }
            cm.registerNetworkCallback(request, callback)
            netCallback = callback
        }.onFailure { QzLog.w(TAG, "网络回调注册失败：${it.message}") }
    }

    private suspend fun refreshLink(context: Context) {
        val info = LinkProbe.snapshot(context)
        _link.value = info
        QzLog.d(TAG, "链路: wifi=${info.wifiConnected} ip=${info.ownAddress} gw=${info.gateway} ap=${info.hotspotActive}")
    }

    private fun refreshLinkAndBurst(context: Context) {
        scope?.launch {
            refreshLink(context)
            burstAnnounce()
            gatewayProbe()
        }
    }

    /** 入网三连发（100ms 间隔），让对端最快看见。 */
    private suspend fun burstAnnounce() {
        repeat(3) {
            sendAnnounce(null)
            delay(100)
        }
    }

    /**
     * 直连探测通道（零广播等待）：
     * - 系统网关（同网路由器/热点主机）；
     * - 每个接口网段的 .1（热点主机惯例；ConnectivityManager 不上报无 internet
     *   热点网络时，这是客户端找到主机的关键路径 —— 09-23 真机日志 08:35）；
     * - 已配对设备的历史地址（落在任一本机网段内时）。
     */
    private suspend fun gatewayProbe() = withContext(Dispatchers.IO) {
        val link = _link.value
        val me = link.ownAddress
        val targets = linkedSetOf<String>()
        link.gateway?.let { targets.add(it) }
        link.interfaces.forEach { f -> f.hostCandidate?.let { targets.add(it) } }
        PairedDeviceStore.devices.value.values.mapNotNull { it.lastAddress }.forEach { addr ->
            if (link.interfaces.any { it.containsAddress(addr) }) targets.add(addr)
        }
        targets.removeAll(setOfNotNull(me))
        if (targets.isEmpty()) return@withContext
        targets.forEach { gw -> runCatching {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(gw, Protocol.PORT_CONTROL), 700)
                socket.soTimeout = 1200
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                val payload = buildAnnounce().encode(Protocol.UDP_ANNOUNCE)
                // [u32 len][u8 type][payload]
                val len = payload.size + 1
                val header = byteArrayOf(
                    (len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte(),
                    Protocol.FRAME_PROBE.toByte(),
                )
                out.write(header + payload)
                out.flush()
                // 读 register 回应
                val din = java.io.DataInputStream(java.io.BufferedInputStream(input))
                val respLen = din.readInt()
                if (respLen in 2..4096) {
                    val type = din.readByte().toInt() and 0xFF
                    if (type == Protocol.FRAME_REGISTER) {
                        val body = ByteArray(respLen - 1)
                        din.readFully(body)
                        if (body.isNotEmpty()) {
                            AnnouncePacket.decode(body)?.let { (_, packet) ->
                                val myFp = IdentityStore.peek()?.fingerprintHex
                                if (packet.fingerprint != myFp) {
                                    upsert(packet, gw)
                                    QzLog.i(TAG, "网关直连探测命中 ${packet.name} ($gw)")
                                }
                            }
                        }
                    }
                }
            } finally {
                runCatching { socket.close() }
            }
        }.onFailure {
            QzLog.d(TAG, "直连探测未命中($gw)：${it.message}")
        } }
    }
}

/** 简单的 List StateFlow 包装（避免直接暴露可变列表）。 */
private class MutableStateFlowList {
    private val _flow = kotlinx.coroutines.flow.MutableStateFlow<List<Peer>>(emptyList())
    val flow: kotlinx.coroutines.flow.StateFlow<List<Peer>> = _flow

    fun replaceAll(list: List<Peer>) {
        _flow.value = list
    }
}
