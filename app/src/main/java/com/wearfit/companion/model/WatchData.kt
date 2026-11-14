package com.wearfit.companion.model

data class WatchData(
    val batteryPercent: Int = -1,
    val steps: Int = 0,
    val heartRate: Int = 0,
    val calories: Int = 0,
    val isConnected: Boolean = false,
    val deviceName: String = "",
    val deviceAddress: String = ""
)
