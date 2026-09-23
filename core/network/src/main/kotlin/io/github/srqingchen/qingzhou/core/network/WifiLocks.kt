package io.github.srqingchen.qingzhou.core.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import io.github.srqingchen.qingzhou.core.common.QzLog

/**
 * WiFi 锁集中管理：发现/传输期间持锁，抵消系统省电策略的吞吐波动。
 * 引用计数：发现常驻锁（低延迟）+ 传输锁（高性能），归零释放。
 */
object WifiLocks {

    private var discoveryLock: WifiManager.WifiLock? = null
    private var transferLock: WifiManager.WifiLock? = null

    /** 发现/待命期间：低延迟优先（API 29+；低版本退化为高性能）。 */
    @Synchronized
    fun holdDiscovery(context: Context) {
        if (discoveryLock != null) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        discoveryLock = wifi.createWifiLock(mode, "qz:discovery").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
                .onFailure { QzLog.w("wifilock", "发现锁获取失败：${it.message}") }
        }
    }

    /** 传输期间：高性能优先（吞吐稳定）。 */
    @Synchronized
    fun holdTransfer(context: Context) {
        if (transferLock != null) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return
        transferLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "qz:transfer").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
                .onFailure { QzLog.w("wifilock", "传输锁获取失败：${it.message}") }
        }
    }

    @Synchronized
    fun releaseTransfer() {
        runCatching { transferLock?.takeIf { it.isHeld }?.release() }
        transferLock = null
    }

    @Synchronized
    fun releaseDiscovery() {
        runCatching { discoveryLock?.takeIf { it.isHeld }?.release() }
        discoveryLock = null
    }
}
