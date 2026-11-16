package com.wearfit.companion.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wearfit.companion.model.WatchData
import com.wearfit.companion.service.WearfitForegroundService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    private var lastConnectedAddress: String? = "C9:01:00:08:5C:A5"
    private var isExplicitDisconnect = false
    private var reconnectJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    fun isDeviceConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED

    private val _watchData = MutableStateFlow(WatchData())
    val watchData: StateFlow<WatchData> = _watchData.asStateFlow()

    private val _watchAlarms = MutableSharedFlow<WearfitProtocol.WatchAlarm>(extraBufferCapacity = 16)
    val watchAlarms: SharedFlow<WearfitProtocol.WatchAlarm> = _watchAlarms.asSharedFlow()

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
                    Log.i(TAG, "Disconnected from watch callback (status=$status, gatt=$gatt, active=$bluetoothGatt)")
                    try {
                        gatt?.close()
                    } catch (_: Exception) {}
                    if (bluetoothGatt == null || bluetoothGatt == gatt) {
                        _connectionState.value = ConnectionState.DISCONNECTED
                        _watchData.value = _watchData.value.copy(isConnected = false)
                        cleanUp()
                        if (!isExplicitDisconnect) {
                            Log.i(TAG, "Unexpected disconnect (status=$status). Scheduling auto-reconnect...")
                            scheduleAutoReconnect()
                        }
                    }
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
            reconnectJob?.cancel()

            // Request LOW POWER priority to maximize watch battery life (allows slave latency)
            try {
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
            } catch (_: Exception) {}

            WearfitForegroundService.start(context)

            // Send handshake and initial sync commands without waking optical sensor
            scope.launch {
                delay(600)
                syncTime()
                delay(300)
                queryBattery()
                delay(300)
                queryHealthData(triggerOptical = false)
                startPeriodicHealthSync()
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
            onHealthReceived = { steps, hr, cal, bpSys, bpDia, spO2 ->
                Log.i(TAG, "Parsed Health: Steps=$steps, HR=$hr, Cal=$cal, BP=$bpSys/$bpDia, SpO2=$spO2")
                val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                _watchData.value = _watchData.value.copy(
                    steps = if (steps >= 0) steps else _watchData.value.steps,
                    heartRate = if (hr > 0) hr else _watchData.value.heartRate,
                    calories = if (cal >= 0) cal else _watchData.value.calories,
                    bloodPressureSys = if (bpSys > 0) bpSys else _watchData.value.bloodPressureSys,
                    bloodPressureDia = if (bpDia > 0) bpDia else _watchData.value.bloodPressureDia,
                    spO2 = if (spO2 > 0) spO2 else _watchData.value.spO2,
                    lastSyncTime = currentTime
                )
            },
            onAlarmReceived = { alarm ->
                Log.i(TAG, "Parsed Alarm from watch: id=${alarm.id} ${alarm.hour}:${alarm.minute} enabled=${alarm.isEnabled} repeat=${alarm.repeat.label}")
                _watchAlarms.tryEmit(alarm)
            }
        )
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        isExplicitDisconnect = false
        lastConnectedAddress = device.address

        val currentGatt = bluetoothGatt
        val currentState = _connectionState.value
        if (currentGatt?.device?.address.equals(device.address, ignoreCase = true)) {
            if (currentState == ConnectionState.CONNECTED) {
                Log.i(TAG, "Already connected to ${device.address}")
                return
            }
            if (currentState == ConnectionState.CONNECTING) {
                Log.i(TAG, "Already connecting to ${device.address}")
                return
            }
        }

        // Clean up previous gatt cleanly without sending CMD_DISCONNECT packet to watch
        try {
            currentGatt?.disconnect()
            currentGatt?.close()
        } catch (_: Exception) {}
        bluetoothGatt = null
        writeCharacteristic = null
        notifyCharacteristic = null

        _connectionState.value = ConnectionState.CONNECTING
        Log.i(TAG, "Connecting to ${device.name ?: "Unknown"} (${device.address})...")
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun connectAddress(address: String): Boolean {
        isExplicitDisconnect = false
        lastConnectedAddress = address

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
        val adapter = bluetoothManager.adapter ?: return false
        if (!adapter.isEnabled) return false
        return try {
            val device = adapter.getRemoteDevice(address)
            connect(device)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to address $address: ${e.message}")
            false
        }
    }

    private var isDisconnecting = false

    @SuppressLint("MissingPermission")
    fun disconnect() {
        if (isDisconnecting) return
        isDisconnecting = true
        isExplicitDisconnect = true
        reconnectJob?.cancel()
        try {
            Log.i(TAG, "Explicit user disconnect requested")
            WearfitForegroundService.stop(context)
            stopPeriodicHealthSync()
            stopHeartRateMeasurement()
            WearfitProtocol.resetTimestamps()
            val gatt = bluetoothGatt ?: run {
                _connectionState.value = ConnectionState.DISCONNECTED
                _watchData.value = WatchData(isConnected = false)
                return
            }

            // 1. Send disconnect notification packet to watch (0xA1)
            try {
                val disconnectPacket = byteArrayOf(
                    WearfitProtocol.HEADER,
                    0x00,
                    0x04,
                    WearfitProtocol.DELIMITER,
                    0xA1.toByte(), // CMD_DISCONNECT
                    0x80.toByte(),
                    0x00
                )
                sendCommand(disconnectPacket)
            } catch (_: Exception) {}

            // 2. Disable notifications so watch stops streaming
            notifyCharacteristic?.let { char ->
                try {
                    gatt.setCharacteristicNotification(char, false)
                    val descriptor = char.getDescriptor(CCCD_UUID)
                    descriptor?.let { desc ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(desc, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            desc.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(desc)
                        }
                    }
                } catch (_: Exception) {}
            }

            // 3. Immediately set state to DISCONNECTED so UI responds
            _connectionState.value = ConnectionState.DISCONNECTED
            _watchData.value = WatchData(isConnected = false)

            bluetoothGatt = null
            writeCharacteristic = null
            notifyCharacteristic = null

            // 4. Disconnect and close GATT synchronously
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting/closing gatt: ${e.message}")
            }
        } finally {
            isDisconnecting = false
        }
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

    fun queryBattery(): Boolean {
        return sendCommand(WearfitProtocol.buildBatteryQueryCommand())
    }

    fun scheduleAutoReconnect() {
        if (isExplicitDisconnect) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.i(TAG, "Auto-reconnect scheduler active")
            var retryDelayMs = 3000L
            while (isActive && _connectionState.value != ConnectionState.CONNECTED && !isExplicitDisconnect) {
                delay(retryDelayMs)
                if (_connectionState.value == ConnectionState.CONNECTED || isExplicitDisconnect) break

                val targetAddress = lastConnectedAddress ?: "C9:01:00:08:5C:A5"
                Log.i(TAG, "Auto-reconnecting to $targetAddress (delay: ${retryDelayMs}ms)...")
                val initiated = connectAddress(targetAddress)
                if (initiated) {
                    var waited = 0L
                    while (waited < 10000L && _connectionState.value != ConnectionState.CONNECTED && !isExplicitDisconnect) {
                        delay(500L)
                        waited += 500L
                    }
                }
                // Gentle backoff delay up to 30s to conserve battery when watch is out of range
                retryDelayMs = (retryDelayMs * 1.5).toLong().coerceAtMost(30000L)
            }
        }
    }

    fun connectSavedWatch(): Boolean {
        isExplicitDisconnect = false
        val address = lastConnectedAddress ?: "C9:01:00:08:5C:A5"
        return connectAddress(address)
    }

    /**
     * Queries health data from watch.
     * @param triggerOptical If false, only reads memory records without activating green PPG LEDs.
     *                       If true (manual user click), turns on optical sensor for 15s.
     */
    fun queryHealthData(triggerOptical: Boolean = false) {
        sendCommand(WearfitProtocol.buildStepsQueryCommand())
        scope.launch {
            delay(250)
            queryBattery()
            if (triggerOptical) {
                delay(250)
                startHeartRateMeasurement()
            }
        }
    }

    fun startHeartRateMeasurement() {
        Log.i(TAG, "Starting optical heart rate sensor measurement (15s)")
        sendCommand(WearfitProtocol.buildHeartRateStartCommand())
        // Auto-stop measurement after 15 seconds so optical LED does NOT stay on continuously
        scope.launch {
            delay(15000)
            stopHeartRateMeasurement()
        }
    }

    fun stopHeartRateMeasurement() {
        Log.i(TAG, "Stopping optical heart rate sensor")
        sendCommand(WearfitProtocol.buildHeartRateStopCommand())
    }

    private var periodicJob: Job? = null

    private fun startPeriodicHealthSync() {
        periodicJob?.cancel()
        periodicJob = scope.launch {
            while (isActive && _connectionState.value == ConnectionState.CONNECTED) {
                delay(5 * 60 * 1000L) // Every 5 minutes
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    Log.i(TAG, "Periodic 5-minute health sync (memory only, optical LED OFF)")
                    queryHealthData(triggerOptical = false)
                }
            }
        }
    }

    private fun stopPeriodicHealthSync() {
        periodicJob?.cancel()
        periodicJob = null
    }

    fun setWatchVolume(percent: Int): Boolean {
        Log.i(TAG, "Setting watch volume via BLE: $percent%")
        return sendCommand(WearfitProtocol.buildVolumeCommand(percent))
    }

    fun setQuietMode(enabled: Boolean): Boolean {
        Log.i(TAG, "Setting watch quiet mode via BLE: $enabled")
        return sendCommand(WearfitProtocol.buildQuietHoursCommand(enabled))
    }

    fun setAlertMode(mode: WearfitProtocol.AlertMode): Boolean {
        Log.i(TAG, "Setting watch alert mode to $mode")
        return sendCommand(WearfitProtocol.buildAlertModeCommand(mode))
    }

    fun setVibrationIntensity(level: Int): Boolean {
        Log.i(TAG, "Setting vibration intensity to $level")
        return sendCommand(WearfitProtocol.buildVibrationIntensityCommand(level))
    }

    fun setAlarm(alarm: WearfitProtocol.WatchAlarm): Boolean {
        Log.i(TAG, "Setting watch alarm [${alarm.id}]: ${alarm.hour}:${alarm.minute} enabled=${alarm.isEnabled} repeat=${alarm.repeat.label}")
        return sendCommand(
            WearfitProtocol.buildAlarmCommand(
                index = alarm.id,
                enabled = alarm.isEnabled,
                hour = alarm.hour,
                minute = alarm.minute,
                repeat = alarm.repeat.value
            )
        )
    }

    fun deleteAlarm(index: Int): Boolean {
        Log.i(TAG, "Deleting/disabling watch alarm [$index]")
        return sendCommand(
            WearfitProtocol.buildAlarmCommand(
                index = index,
                enabled = false,
                hour = 0,
                minute = 0,
                repeat = WearfitProtocol.AlarmRepeat.ONCE.value
            )
        )
    }

    fun queryAlarms(): Boolean {
        if (!isDeviceConnected()) {
            Log.w(TAG, "Cannot query alarms: Watch not connected")
            return false
        }
        Log.i(TAG, "Querying alarms from watch...")
        val queries = WearfitProtocol.buildQueryAlarmsCommands()
        scope.launch {
            for (q in queries) {
                sendCommand(q)
                delay(60)
            }
        }
        return true
    }

    suspend fun syncContacts(
        contacts: List<WearfitProtocol.ContactItem>,
        sosIndex: Int = 0,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): Boolean {
        if (!isDeviceConnected()) {
            Log.w(TAG, "Cannot sync contacts: Watch not connected")
            return false
        }
        val safeContacts = contacts.take(10)
        val count = safeContacts.size
        Log.i(TAG, "Syncing $count contacts to watch (SOS index=$sosIndex)...")

        // 1. Send Init / Count packet
        val initCmd = WearfitProtocol.buildContactInitCommand(count, sosIndex)
        sendCommand(initCmd)
        delay(100)

        // 2. Send each contact (Name + Number)
        for (i in safeContacts.indices) {
            val contact = safeContacts[i]
            Log.d(TAG, "Sending contact [$i]: ${contact.name} (${contact.number})")

            val namePackets = WearfitProtocol.buildContactNameCommands(contact.name, i)
            for (p in namePackets) {
                sendCommand(p)
                delay(80)
            }

            val numberPacket = WearfitProtocol.buildContactNumberCommand(contact.number, i)
            sendCommand(numberPacket)
            delay(80)

            onProgress?.invoke(i + 1, count)
        }

        Log.i(TAG, "Finished syncing $count contacts to watch successfully")
        return true
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
