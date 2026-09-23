package io.github.srqingchen.qingzhou.core.network.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import io.github.srqingchen.qingzhou.core.common.QzLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * BLE 辅助发现（仅存在感知，不走数据）：
 * 未连同一网络时先「看到」附近青舟设备（互传联盟/Quick Share 同思路）。
 * 广播 16B 服务数据 = "QZ1" + 指纹前 8 字节 hex；省电偏好下默认关闭。
 */
object BleBeacon {

    private val SERVICE_UUID: UUID = UUID.fromString("7a6b5c4d-3e2f-1a0b-9c8d-7e6f5a4b3c2d")
    private const val TAG = "ble"

    data class BlePeer(val name: String, val fingerprint8: String, val rssi: Int, val seenAt: Long)

    private val _peers = MutableStateFlow<Map<String, BlePeer>>(emptyMap())
    val peers: StateFlow<Map<String, BlePeer>> = _peers.asStateFlow()

    @Volatile
    private var advertising = false

    @Volatile
    private var scanning = false

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            QzLog.i(TAG, "BLE 广播已开启")
        }

        override fun onStartFailure(errorCode: Int) {
            QzLog.w(TAG, "BLE 广播失败：$errorCode")
            advertising = false
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.serviceData?.get(ParcelUuid(SERVICE_UUID)) ?: return
            if (data.size < 11) return
            val prefix = String(data, 0, 3, Charsets.US_ASCII)
            if (prefix != "QZ1") return
            val fp8 = (4 until 12).joinToString("") { "%02x".format(data[it]) }
            val name = result.scanRecord?.deviceName?.takeIf { it.isNotEmpty() }
                ?: runCatching { String(data, 12, data.size - 12, Charsets.UTF_8) }.getOrDefault("青舟设备")
            _peers.value = _peers.value + (fp8 to BlePeer(name, fp8, result.rssi, System.currentTimeMillis()))
        }
    }

    @SuppressLint("MissingPermission")
    fun start(context: Context, fingerprintHex: String, deviceName: String): Boolean {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
        val adapter = bm.adapter ?: return false
        if (!adapter.isEnabled) return false

        // 广播
        if (!advertising) {
            val payload = ByteArray(12 + deviceName.toByteArray(Charsets.UTF_8).size.coerceAtMost(20))
            "QZ1".toByteArray(Charsets.US_ASCII).copyInto(payload, 0)
            fingerprintHex.take(16).chunked(2).forEachIndexed { i, hex ->
                payload[4 + i] = hex.toInt(16).toByte()
            }
            deviceName.toByteArray(Charsets.UTF_8).copyInto(
                payload, 12, 0, minOf(20, deviceName.toByteArray(Charsets.UTF_8).size),
            )
            val ok = runCatching {
                adapter.bluetoothLeAdvertiser?.startAdvertising(
                    AdvertiseSettings.Builder()
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                        .setConnectable(false)
                        .build(),
                    AdvertiseData.Builder()
                        .addServiceUuid(ParcelUuid(SERVICE_UUID))
                        .addServiceData(ParcelUuid(SERVICE_UUID), payload)
                        .build(),
                    advertiseCallback,
                )
            }.isSuccess
            advertising = ok
        }

        // 扫描
        if (!scanning) {
            runCatching {
                adapter.bluetoothLeScanner?.startScan(
                    listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()),
                    ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build(),
                    scanCallback,
                )
            }
            scanning = true
        }
        return true
    }

    @SuppressLint("MissingPermission")
    fun stop(context: Context) {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        advertising = false
        scanning = false
        _peers.value = emptyMap()
    }
}
