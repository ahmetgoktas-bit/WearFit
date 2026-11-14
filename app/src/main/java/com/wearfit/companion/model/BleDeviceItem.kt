package com.wearfit.companion.model

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice

data class BleDeviceItem(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int
) {
    @SuppressLint("MissingPermission")
    companion object {
        fun from(device: BluetoothDevice, rssi: Int): BleDeviceItem {
            val deviceName = try {
                device.name ?: "Bilinmeyen Cihaz"
            } catch (e: SecurityException) {
                "Bilinmeyen Cihaz"
            }
            return BleDeviceItem(
                device = device,
                name = deviceName,
                address = device.address,
                rssi = rssi
            )
        }
    }
}
