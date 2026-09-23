package io.github.srqingchen.qingzhou.core.network.p2p

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import io.github.srqingchen.qingzhou.core.common.QzLog
import io.github.srqingchen.qingzhou.core.network.discovery.DiscoveryEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * WiFi Direct 兜底链路：无路由器/热点的最后一公里。
 *
 * 关键设计：P2P 组自带 DHCP 子网（GO 即网关 192.168.49.1）——组网成功后
 * 直接触发 [DiscoveryEngine.probeNow]，现有的广播发现/网关直连探测/控制/数据
 * 通道原样运行在 P2P 接口上，无需任何协议分支。
 */
@SuppressLint("MissingPermission")
object WifiDirectHelper {

    data class P2pPeer(val name: String, val address: String, val isQingZhou: Boolean)

    data class P2pState(
        val enabled: Boolean = false,
        val connected: Boolean = false,
        val isGroupOwner: Boolean = false,
        val groupOwnerAddress: String? = null,
        val peers: List<P2pPeer> = emptyList(),
    )

    private val _state = MutableStateFlow(P2pState())
    val state: StateFlow<P2pState> = _state.asStateFlow()

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var appContext: Context? = null

    @Volatile
    private var started = false

    fun start(context: Context) {
        if (started) return
        val ctx = context.applicationContext
        manager = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager ?: return
        channel = manager?.initialize(ctx, Looper.getMainLooper(), null)
        appContext = ctx
        started = true

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        val br = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val enabled =
                            intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        _state.value = _state.value.copy(enabled = enabled)
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        if (networkInfo?.isConnected == true) {
                            requestConnectionInfo()
                        } else {
                            _state.value = _state.value.copy(connected = false, isGroupOwner = false, groupOwnerAddress = null)
                        }
                    }
                }
            }
        }
        ctx.registerReceiver(br, filter)
        receiver = br
        QzLog.i("p2p", "WiFi Direct 助手已启动")
    }

    fun stop() {
        runCatching { receiver?.let { appContext?.unregisterReceiver(it) } }
        receiver = null
        started = false
        _state.value = P2pState()
    }

    /** 开始发现（需要定位/附近设备权限，由 UI 层保证）。 */
    fun discover() {
        val m = manager ?: return
        val ch = channel ?: return
        m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                QzLog.d("p2p", "P2P 发现已启动")
            }

            override fun onFailure(reason: Int) {
                QzLog.w("p2p", "P2P 发现失败：$reason")
            }
        })
    }

    /** 连接对端设备（自动协商组角色）。 */
    fun connect(address: String) {
        val m = manager ?: return
        val ch = channel ?: return
        val config = WifiP2pConfig().apply {
            this.deviceAddress = address
            wps.setup = WpsInfo.PBC
        }
        m.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                QzLog.i("p2p", "P2P 连接请求已发送")
            }

            override fun onFailure(reason: Int) {
                QzLog.w("p2p", "P2P 连接失败：$reason（回退：请开系统热点）")
            }
        })
    }

    private fun requestPeers() {
        val m = manager ?: return
        val ch = channel ?: return
        m.requestPeers(ch) { peers: WifiP2pDeviceList? ->
            val list = peers?.deviceList?.map { d: WifiP2pDevice ->
                P2pPeer(
                    name = d.deviceName?.takeIf { it.isNotEmpty() } ?: "未知设备",
                    address = d.deviceAddress,
                    isQingZhou = d.deviceName?.startsWith("QZ_") == true,
                )
            }.orEmpty()
            _state.value = _state.value.copy(peers = list)
        }
    }

    private fun requestConnectionInfo() {
        val m = manager ?: return
        val ch = channel ?: return
        m.requestConnectionInfo(ch) { info: WifiP2pInfo? ->
            if (info == null || !info.groupFormed) return@requestConnectionInfo
            _state.value = _state.value.copy(
                connected = true,
                isGroupOwner = info.isGroupOwner,
                groupOwnerAddress = info.groupOwnerAddress?.hostAddress,
            )
            QzLog.i("p2p", "P2P 组网成功：GO=${info.groupOwnerAddress?.hostAddress} 我=${if (info.isGroupOwner) "GO" else "客户端"}")
            // 组网即触发一轮发现：客户端会网关直连探测 GO；GO 靠客户端广播
            DiscoveryEngine.probeNow()
        }
    }

    // 注：setDeviceName 已在新版 API 移除，青舟设备识别依靠应用层 announce。
}
