package com.wearfit.companion.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.util.Log
import com.wearfit.companion.model.WatchData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

class BleManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Standard Battery Service & Characteristic
        private val BATTERY_SERVICE_UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL_CHAR_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        // Known Wearfit & Nordic UART Vendor Service UUIDs
        private val NORDIC_UART_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val NORDIC_UART_RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val NORDIC_UART_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

        @Volatile
        private var instance: BleManager? = null

        fun getInstance(context: Context): BleManager {
            return instance ?: synchronized(this) {
                instance ?: BleManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _watchData = MutableStateFlow(WatchData())
    val watchData: StateFlow<WatchData> = _watchData.asStateFlow()

    private val _debugInfo = MutableStateFlow("")
    val debugInfo: StateFlow<String> = _debugInfo.asStateFlow()

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = ConnectionState.CONNECTING
                    val name = gatt?.device?.name ?: "H10 Watch"
                    val address = gatt?.device?.address ?: ""
                    _watchData.value = _watchData.value.copy(
                        isConnected = true,
                        deviceName = name,
                        deviceAddress = address
                    )
                    Log.i(TAG, "Connected to $name ($address). Requesting MTU 512 & Discovering services...")
                    // Request higher MTU for faster/larger packet transfer
                    gatt?.requestMtu(512)
                    scope.launch {
                        delay(500)
                        gatt?.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from watch")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _watchData.value = _watchData.value.copy(isConnected = false)
                    cleanUp()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            Log.i(TAG, "MTU changed to $mtu, status=$status")
            gatt?.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            Log.i(TAG, "onServicesDiscovered: status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) return

            val sb = StringBuilder("Keşfedilen Servisler:\n")

            // Log all services & characteristics
            for (service in gatt.services) {
                val serviceUuid = service.uuid.toString()
                sb.append("• S: ").append(serviceUuid.substring(0, 8)).append("\n")
                Log.i(TAG, "Service: $serviceUuid")
                for (char in service.characteristics) {
                    val charUuid = char.uuid.toString()
                    val props = char.properties
                    val propDesc = buildString {
                        if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) append("R ")
                        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) append("W ")
                        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) append("WNR ")
                        if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append("NOTIF ")
                        if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) append("IND ")
                    }
                    Log.i(TAG, "  Char: $charUuid [$propDesc]")
                    sb.append("   - C: ").append(charUuid.substring(0, 8)).append(" [").append(propDesc.trim()).append("]\n")
                }
            }
            _debugInfo.value = sb.toString()

            // 1. First priority: Check Nordic UART Service
            val nusService = gatt.getService(NORDIC_UART_SERVICE)
            if (nusService != null) {
                writeCharacteristic = nusService.getCharacteristic(NORDIC_UART_RX)
                notifyCharacteristic = nusService.getCharacteristic(NORDIC_UART_TX)
                Log.i(TAG, "Found Nordic UART Service!")
            }

            // 2. Second priority: Find Vendor Service (excluding standard 000018xx Bluetooth SIG services)
            if (writeCharacteristic == null || notifyCharacteristic == null) {
                for (service in gatt.services) {
                    val uuidStr = service.uuid.toString().lowercase()
                    // Skip standard Bluetooth SIG services: 1800 (GAP), 1801 (GATT), 180a (DeviceInfo), 180f (Battery)
                    if (uuidStr.startsWith("00001800") || uuidStr.startsWith("00001801") ||
                        uuidStr.startsWith("0000180a") || uuidStr.startsWith("0000180f") ||
                        uuidStr.startsWith("00001802") || uuidStr.startsWith("00001803") ||
                        uuidStr.startsWith("00001804") || uuidStr.startsWith("00001812")) {
                        continue
                    }

                    // This is a custom vendor service!
                    for (char in service.characteristics) {
                        val props = char.properties
                        val canWrite = (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                                (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                        val canNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) ||
                                (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)

                        if (writeCharacteristic == null && canWrite) {
                            writeCharacteristic = char
                            Log.i(TAG, "Selected Write Char: ${char.uuid} in service ${service.uuid}")
                        }
                        if (notifyCharacteristic == null && canNotify) {
                            notifyCharacteristic = char
                            Log.i(TAG, "Selected Notify Char: ${char.uuid} in service ${service.uuid}")
                        }
                    }
                }
            }

            // Enable notifications on the notify characteristic
            notifyCharacteristic?.let { char ->
                val success = gatt.setCharacteristicNotification(char, true)
                Log.i(TAG, "setCharacteristicNotification on ${char.uuid}: $success")

                val descriptor = char.getDescriptor(CCCD_UUID)
                descriptor?.let { desc ->
                    val enableVal = if ((char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(desc, enableVal)
                    } else {
                        @Suppress("DEPRECATION")
                        desc.value = enableVal
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(desc)
                    }
                    Log.i(TAG, "CCCD Descriptor written for notifications")
                }
            }

            // Read standard battery service if present
            val batteryService = gatt.getService(BATTERY_SERVICE_UUID)
            batteryService?.getCharacteristic(BATTERY_LEVEL_CHAR_UUID)?.let { batChar ->
                gatt.readCharacteristic(batChar)
            }

            _connectionState.value = ConnectionState.CONNECTED
            Log.i(TAG, "Services discovered. Connected & Ready.")

            // Send handshake and sync commands after a short stabilization delay
            scope.launch {
                delay(800)
                syncTime()
                delay(400)
                queryBattery()
                delay(400)
                queryHealth()
            }
        }

        // Standard characteristic read callback (e.g. Battery Service)
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (characteristic.uuid == BATTERY_LEVEL_CHAR_UUID && value.isNotEmpty()) {
                    val battery = value[0].toInt() and 0xFF
                    Log.i(TAG, "Read standard battery characteristic: $battery%")
                    _watchData.value = _watchData.value.copy(batteryPercent = battery)
                }
            }
        }

        // Legacy read callback for Android 12 and below
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            @Suppress("DEPRECATION")
            characteristic?.value?.let { bytes ->
                if (characteristic.uuid == BATTERY_LEVEL_CHAR_UUID && bytes.isNotEmpty()) {
                    val battery = bytes[0].toInt() and 0xFF
                    _watchData.value = _watchData.value.copy(batteryPercent = battery)
                }
            }
        }

        // Android 13+ callback
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val hex = value.joinToString(" ") { String.format("%02X", it) }
            Log.i(TAG, "Incoming bytes from watch [${characteristic.uuid}]: $hex")
            handleIncomingBytes(value)
        }

        // Legacy callback for Android 12 and below
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            @Suppress("DEPRECATION")
            characteristic?.value?.let { handleIncomingBytes(it) }
        }
    }

    private fun handleIncomingBytes(bytes: ByteArray) {
        WearfitProtocol.parseIncomingPacket(
            data = bytes,
            onBatteryReceived = { percent ->
                Log.i(TAG, "Parsed Battery from Wearfit packet: $percent%")
                _watchData.value = _watchData.value.copy(batteryPercent = percent)
            },
            onHealthReceived = { steps, hr, cal ->
                Log.i(TAG, "Parsed Health: Steps=$steps, HR=$hr, Cal=$cal")
                _watchData.value = _watchData.value.copy(
                    steps = if (steps >= 0) steps else _watchData.value.steps,
                    heartRate = if (hr > 0) hr else _watchData.value.heartRate,
                    calories = if (cal >= 0) cal else _watchData.value.calories
                )
            }
        )
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        disconnect()
        _connectionState.value = ConnectionState.CONNECTING
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.disconnect()
        cleanUp()
    }

    private fun cleanUp() {
        try {
            bluetoothGatt?.close()
        } catch (_: Exception) {}
        bluetoothGatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
    }

    @SuppressLint("MissingPermission")
    fun sendCommand(command: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val characteristic = writeCharacteristic ?: return false

        val hex = command.joinToString(" ") { String.format("%02X", it) }
        Log.i(TAG, "Sending command to watch [${characteristic.uuid}]: $hex")

        val writeType = if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeCharacteristic(characteristic, command, writeType)
            result == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = command
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    fun syncTime() {
        sendCommand(WearfitProtocol.buildTimeSyncCommand())
    }

    fun queryBattery() {
        sendCommand(WearfitProtocol.buildBatteryQueryCommand())
    }

    fun queryHealth() {
        sendCommand(WearfitProtocol.buildStepsQueryCommand())
        scope.launch {
            delay(200)
            sendCommand(WearfitProtocol.buildHealthQueryCommand())
        }
    }

    fun findDevice(enable: Boolean = true) {
        if (enable) {
            sendCommand(WearfitProtocol.buildFindDeviceCommand(true))
        } else {
            val stopCommands = WearfitProtocol.buildFindDeviceStopCommands()
            scope.launch {
                for (cmd in stopCommands) {
                    sendCommand(cmd)
                    delay(80)
                }
            }
        }
    }

    fun sendNotification(appId: Byte, text: String) {
        val packets = WearfitProtocol.buildNotificationCommands(appId, text)
        scope.launch {
            for (packet in packets) {
                sendCommand(packet)
                delay(120)
            }
        }
    }
}
