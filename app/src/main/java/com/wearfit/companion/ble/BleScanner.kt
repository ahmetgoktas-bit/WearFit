package com.wearfit.companion.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.util.Log
import com.wearfit.companion.model.BleDeviceItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BleScanner(private val bluetoothAdapter: BluetoothAdapter?) {

    private var scanner: BluetoothLeScanner? = null
    private var isScanning = false

    private val _scannedDevices = MutableStateFlow<List<BleDeviceItem>>(emptyList())
    val scannedDevices: StateFlow<List<BleDeviceItem>> = _scannedDevices.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val device = result.device
            val rawName = device.name ?: result.scanRecord?.deviceName
            val isTargetWatch = device.address.equals("C9:01:00:08:5C:A5", ignoreCase = true) ||
                    device.address.startsWith("C9:01", ignoreCase = true) ||
                    rawName?.contains("HK10", ignoreCase = true) == true ||
                    rawName?.contains("Wear", ignoreCase = true) == true ||
                    rawName?.contains("Watch", ignoreCase = true) == true

            val name = when {
                isTargetWatch && (rawName.isNullOrBlank() || rawName.contains("Watch", ignoreCase = true)) -> "HK10 ULTRA 3 MAX"
                !rawName.isNullOrBlank() -> rawName
                isTargetWatch -> "HK10 ULTRA 3 MAX"
                else -> "BLE Cihaz (${device.address})"
            }

            Log.d("BleScanner", "Discovered device: $name (${device.address}) RSSI=${result.rssi}")

            val currentList = _scannedDevices.value.toMutableList()
            val existingIndex = currentList.indexOfFirst { it.address == device.address }

            val item = BleDeviceItem(
                device = device,
                name = name,
                address = device.address,
                rssi = result.rssi
            )

            if (existingIndex >= 0) {
                currentList[existingIndex] = item
            } else {
                if (isTargetWatch) {
                    currentList.add(0, item)
                } else {
                    currentList.add(item)
                }
            }
            _scannedDevices.value = currentList
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BleScanner", "BLE Scan failed with errorCode: $errorCode")
            isScanning = false
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan(): Boolean {
        if (isScanning) return true
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return false

        scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) return false

        _scannedDevices.value = emptyList()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(null, settings, scanCallback)
        isScanning = true
        return true
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        isScanning = false
    }

    fun isCurrentlyScanning(): Boolean = isScanning
}
