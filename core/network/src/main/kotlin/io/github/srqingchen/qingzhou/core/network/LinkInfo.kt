package io.github.srqingchen.qingzhou.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import io.github.srqingchen.qingzhou.core.common.QzLog
import java.net.Inet4Address
import java.net.NetworkInterface

/** 单接口快照（发现引擎按接口定向广播用）。 */
data class IfaceInfo(
    val name: String,
    val ipv4: String,
    val broadcast: String?, // 定向广播（原生为空时由前缀推导 —— 桥接口 ap_br0 常不 reporting）
    val hostCandidate: String?, // 网关探测候选（网段+1，热点主机几乎总在 .1）
    val prefix: Short,
) {
    fun containsAddress(addr: String): Boolean {
        val ipL = ipToLong(ipv4) ?: return false
        val aL = ipToLong(addr) ?: return false
        if (prefix <= 0 || prefix > 32) return false
        val mask = if (prefix == 32.toShort()) -1L else (-1L shl (32 - prefix)) and 0xFFFFFFFFL
        return (ipL and mask) == (aL and mask)
    }
}

internal fun ipToLong(ip: String): Long? {
    val parts = ip.split(".")
    if (parts.size != 4) return null
    var v = 0L
    for (p in parts) {
        val n = p.toIntOrNull() ?: return null
        if (n !in 0..255) return null
        v = (v shl 8) or n.toLong()
    }
    return v
}

internal fun longToIp(v: Long): String =
    "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"

/** 本机链路快照。 */
data class LinkInfo(
    val wifiConnected: Boolean,
    val ownAddress: String?, // 本 WiFi/热点 IPv4
    val gateway: String?, // 网关 IPv4（热点场景 = 对端主机）
    val hotspotActive: Boolean, // 本机热点/P2P 组主机
    /** 全部本设备 IPv4 接口（含 STA、AP、P2P、自建热点）。 */
    val interfaces: List<IfaceInfo> = emptyList(),
) {
    val ready: Boolean get() = wifiConnected || hotspotActive
}

/**
 * 链路探测。
 *
 * 热点主机接口名因 ROM 而异（ap0 / swlan0 / wlan1 / softap0 / ap_br0…），
 * 统一策略：枚举全部 WiFi 系接口，凡带站点本地 IPv4 且非 STA 地址者视作 AP；
 * 无任何 WiFi 网络却存在此类接口时兜底判为主机（真机日志 22:44 热点窗口
 * “wifi=false ip=null ap=false” 即旧探测漏判 wlan1/ap_br0 所致）。
 */
object LinkProbe {

    private val AP_NAMES = setOf("ap0", "swlan0", "wlan1", "ap1", "softap0", "ap_br0")

    private fun isWifiFamily(name: String): Boolean =
        name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("sw") ||
            name.startsWith("p2p") || name == "br0"

    fun snapshot(context: Context): LinkInfo {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        var staIp: String? = null
        var gateway: String? = null
        var wifiConnected = false

        if (cm != null) {
            runCatching {
                for (network in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                    val lp: LinkProperties = cm.getLinkProperties(network) ?: continue
                    val ip = lp.linkAddresses.asSequence()
                        .mapNotNull { it.address as? Inet4Address }
                        .firstOrNull { it.isSiteLocalAddress } ?: continue
                    staIp = ip.hostAddress
                    wifiConnected = true
                    gateway = lp.routes
                        .firstOrNull { it.isDefaultRoute }
                        ?.gateway?.hostAddress
                    break
                }
            }.onFailure { QzLog.w("link", "WiFi 链路探测失败：${it.message}") }
        }

        // 枚举 WiFi 系接口（广播地址缺失时按前缀推导，桥接口常见为空）
        val interfaces = mutableListOf<IfaceInfo>()
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                if (!isWifiFamily(iface.name)) continue
                for (ia in iface.interfaceAddresses) {
                    val addr = ia.address as? Inet4Address ?: continue
                    if (!addr.isSiteLocalAddress) continue
                    val prefix = ia.networkPrefixLength
                    val broadcast = ia.broadcast?.hostAddress
                        ?: if (prefix in 1..31) {
                            val mask = (-1L shl (32 - prefix)) and 0xFFFFFFFFL
                            longToIp((ipToLong(addr.hostAddress!!) ?: 0L) or (mask.inv() and 0xFFFFFFFFL))
                        } else {
                            null
                        }
                    // 网段+1 作主机候选（DHCP/热点主机惯例）
                    val hostCandidate = if (prefix in 1..31) {
                        val mask = (-1L shl (32 - prefix)) and 0xFFFFFFFFL
                        val net = (ipToLong(addr.hostAddress!!) ?: 0L) and mask
                        longToIp(net + 1)
                    } else {
                        null
                    }
                    interfaces.add(IfaceInfo(iface.name, addr.hostAddress, broadcast, hostCandidate, prefix))
                }
            }
        }.onFailure { QzLog.w("link", "接口枚举失败：${it.message}") }

        // 主机判定：仅 AP 命名接口带非 STA 地址；无 WiFi 网络时也仅限 AP 命名接口兜底。
        // 真机教训（09-23 08:35 日志）：STA 连他人热点的 wlan2 不被 ConnectivityManager 上报，
        // 旧兜底把“连热点的客户端”误判成“热点主机”。
        var hotspotActive = false
        var apAddress: String? = null
        for (f in interfaces) {
            val apNamed = f.name in AP_NAMES || f.name.startsWith("ap") || f.name.startsWith("sw") || f.name.startsWith("softap")
            if (apNamed && f.ipv4 != staIp) {
                hotspotActive = true
                apAddress = f.ipv4
                break
            }
        }

        // 有任何带 IPv4 的 wlan 系接口即视为具备 WiFi 连通（ConnectivityManager 不上报的
        // 无 internet 热点网络也能被正确标记 ready）
        val wifiUp = wifiConnected || interfaces.any { it.name.startsWith("wlan") && it.ipv4 != apAddress }

        val info = LinkInfo(
            wifiConnected = wifiUp,
            ownAddress = apAddress ?: staIp ?: interfaces.firstOrNull()?.ipv4,
            gateway = gateway,
            hotspotActive = hotspotActive,
            interfaces = interfaces,
        )
        QzLog.d(
            "link",
            "wifi=$wifiUp sta=$staIp gw=$gateway ap=$hotspotActive(${apAddress ?: "-"}) ifaces=" +
                interfaces.joinToString(",") { "${it.name}=${it.ipv4}/${it.prefix}" },
        )
        return info
    }
}
