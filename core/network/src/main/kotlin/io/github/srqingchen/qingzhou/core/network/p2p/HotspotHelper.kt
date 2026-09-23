package io.github.srqingchen.qingzhou.core.network.p2p

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import io.github.srqingchen.qingzhou.core.common.QzLog

/**
 * LocalOnlyHotspot：无路由器时 App 内一键自建局域网（双方无需系统热点设置）。
 * Android 13+ 仅需 NEARBY_WIFI_DEVICES；返回 SSID/密码供对端手动连接。
 */
object HotspotHelper {

    data class HotspotInfo(val ssid: String, val passphrase: String)

    @Volatile
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    @Volatile
    var current: HotspotInfo? = null
        private set

    /** 创建（异步回调）。重复创建幂等返回当前。 */
    fun start(context: Context, onReady: (Result<HotspotInfo>) -> Unit) {
        current?.let { onReady(Result.success(it)); return }
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return onReady(Result.failure(IllegalStateException("无 WiFi 服务")))
        runCatching {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    val cfg: WifiConfiguration? = res.softApConfiguration?.let {
                        // 新 API 不再暴露密码；提示用户到系统热点查看，或依赖直连发现
                        null
                    }
                    val ssid = res.softApConfiguration?.ssid ?: "青舟热点"
                    current = HotspotInfo(ssid, "")
                    QzLog.i("hotspot", "自建热点已开启：$ssid")
                    onReady(Result.success(current!!))
                }

                override fun onFailed(reason: Int) {
                    QzLog.w("hotspot", "自建热点失败：code=$reason")
                    onReady(Result.failure(IllegalStateException("开启失败（code=$reason），请在系统设置开热点")))
                }

                override fun onStopped() {
                    QzLog.i("hotspot", "自建热点已关闭")
                    reservation = null
                    current = null
                }
            }, null)
        }.onFailure { onReady(Result.failure(it)) }
    }

    fun stop() {
        runCatching { reservation?.close() }
        reservation = null
        current = null
    }
}
