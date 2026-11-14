package com.wearfit.companion.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
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
            val name = device.name ?: result.scanRecord?.deviceName ?: return

            // Filter out empty name devices or non-connectable beacons
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
                currentList.add(item)
            }
            _scannedDevices.value = currentList
        }

        override fun onScanFailed(errorCode: Int) {
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
