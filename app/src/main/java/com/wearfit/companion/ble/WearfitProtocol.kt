package com.wearfit.companion.ble

import java.nio.charset.StandardCharsets
import java.util.Calendar

/**
 * Wearfit / Wake Up / Howear BLE Protocol Handler for HK10 ULTRA 3 MAX and compatible smartwatches.
 * Command packet format: [0xAB] [0x00] [Length_From_FF] [0xFF] [Command_ID] [Data...]
 */
object WearfitProtocol {

    const val HEADER: Byte = 0xAB.toByte()
    const val DELIMITER: Byte = 0xFF.toByte()

    // Command IDs
    const val CMD_HEART_RATE: Byte = 0x31.toByte()
    const val CMD_HEALTH_DATA: Byte = 0x32.toByte()
    const val CMD_STEPS_TODAY: Byte = 0x51.toByte()
    const val CMD_STEPS_HISTORY: Byte = 0x52.toByte()
    const val CMD_FIND_DEVICE: Byte = 0x71.toByte()
    const val CMD_NOTIFICATION: Byte = 0x72.toByte()
    const val CMD_ALARM: Byte = 0x73.toByte()
    const val CMD_BATTERY: Byte = 0x91.toByte()
    const val CMD_SET_TIME: Byte = 0x93.toByte()
    const val CMD_QUIET: Byte = 0x76.toByte()
    const val CMD_RAISE_TO_WAKE: Byte = 0x77.toByte()
    const val CMD_ALERT_MODE: Byte = 0x7A.toByte()
    const val CMD_VIBRATION: Byte = 0x7D.toByte()
    const val CMD_MEDIA_CONTROL: Byte = 0x99.toByte()
    const val CMD_CONTACT_INIT: Byte = 0xA5.toByte()
    const val CMD_CONTACT_NAME: Byte = 0xA2.toByte()
    const val CMD_CONTACT_NUMBER: Byte = 0xA3.toByte()

    enum class AlarmRepeat(val value: Byte, val label: String) {
        ONCE(0x80.toByte(), "Bir Kez"),
        EVERY_DAY(0x7F.toByte(), "Her Gün"),
        WEEKDAYS(0x1F.toByte(), "Hafta İçi"),
        WEEKENDS(0x60.toByte(), "Hafta Sonu")
    }

    data class WatchAlarm(
        val id: Int,
        var hour: Int,
        var minute: Int,
        var isEnabled: Boolean,
        var repeat: AlarmRepeat = AlarmRepeat.EVERY_DAY
    )

    data class ContactItem(
        val name: String,
        val number: String,
        val isSos: Boolean = false
    )

    enum class AlertMode(val value: Byte) {
        SOUND_AND_VIBRATION(0x03),
        VIBRATION_ONLY(0x01),
        SOUND_ONLY(0x02),
        SILENT(0x00)
    }

    private var latestStepsTimestamp: Long = 0L
    private var latestHrTimestamp: Long = 0L
    private var latestBpTimestamp: Long = 0L
    private var latestSpo2Timestamp: Long = 0L

    fun resetTimestamps() {
        latestStepsTimestamp = 0L
        latestHrTimestamp = 0L
        latestBpTimestamp = 0L
        latestSpo2Timestamp = 0L
    }

    private fun getTimestamp(data: ByteArray): Long {
        if (data.size < 11) return 0L
        val y = (data[6].toInt() and 0xFF).toLong()
        val m = (data[7].toInt() and 0xFF).toLong()
        val d = (data[8].toInt() and 0xFF).toLong()
        val h = (data[9].toInt() and 0xFF).toLong()
        val min = (data[10].toInt() and 0xFF).toLong()
        return (y * 100000000L) + (m * 1000000L) + (d * 10000L) + (h * 100L) + min
    }

    // Notification App Type IDs
    object AppId {
        const val CALL: Byte = 0x01
        const val CANCEL_CALL: Byte = 0x02
        const val SMS: Byte = 0x03
        const val EMAIL: Byte = 0x04
        const val WHATSAPP: Byte = 0x0A
        const val SKYPE: Byte = 0x08
        const val WECHAT: Byte = 0x09
        const val FACEBOOK: Byte = 0x10
        const val MESSENGER: Byte = 0x11
        const val INSTAGRAM: Byte = 0x12
        const val TELEGRAM: Byte = 0x18
        const val TIKTOK: Byte = 0x1D
        const val WEARFIT_GENERIC: Byte = 0x22
    }

    /**
     * Builds packet to synchronize phone's current time to the watch.
     * Format: AB 00 0B FF 93 80 00 [Year_H] [Year_L] [Month] [Day] [Hour] [Min] [Sec]
     */
    fun buildTimeSyncCommand(calendar: Calendar = Calendar.getInstance()): ByteArray {
        val year = calendar.get(Calendar.YEAR)
        val month = (calendar.get(Calendar.MONTH) + 1).toByte()
        val day = calendar.get(Calendar.DAY_OF_MONTH).toByte()
        val hour = calendar.get(Calendar.HOUR_OF_DAY).toByte()
        val minute = calendar.get(Calendar.MINUTE).toByte()
        val second = calendar.get(Calendar.SECOND).toByte()

        val yearH = (year shr 8).toByte()
        val yearL = (year and 0xFF).toByte()

        return byteArrayOf(
            HEADER,
            0x00,
            0x0B, // 11 bytes from FF
            DELIMITER,
            CMD_SET_TIME,
            0x80.toByte(),
            0x00,
            yearH,
            yearL,
            month,
            day,
            hour,
            minute,
            second
        )
    }

    /**
     * Builds packet to query the watch battery level.
     * Format: AB 00 04 FF 91 80 00
     */
    fun buildBatteryQueryCommand(): ByteArray {
        return byteArrayOf(
            HEADER,
            0x00,
            0x04,
            DELIMITER,
            CMD_BATTERY,
            0x80.toByte(),
            0x00
        )
    }

    /**
     * Builds packet to query today's steps and activity.
     * Format: AB 00 04 FF 51 80 00
     */
    fun buildStepsQueryCommand(): ByteArray {
        return byteArrayOf(
            HEADER,
            0x00,
            0x04,
            DELIMITER,
            CMD_STEPS_TODAY,
            0x80.toByte(),
            0x00
        )
    }

    /**
     * Builds packet to start real-time optical heart rate (BPM) measurement.
     * Format: AB 00 04 FF 31 80 01
     */
    fun buildHeartRateStartCommand(): ByteArray {
        return byteArrayOf(
            HEADER,
            0x00,
            0x04,
            DELIMITER,
            CMD_HEART_RATE,
            0x80.toByte(),
            0x01
        )
    }

    /**
     * Builds packet to stop optical heart rate measurement.
     * Format: AB 00 04 FF 31 80 00
     */
    fun buildHeartRateStopCommand(): ByteArray {
        return byteArrayOf(
            HEADER,
            0x00,
            0x04,
            DELIMITER,
            CMD_HEART_RATE,
            0x80.toByte(),
            0x00
        )
    }

    /**
     * Builds packet to query health data (heart rate, spO2).
     * Format: AB 00 04 FF 32 80 00
     */
    fun buildHealthQueryCommand(): ByteArray {
        return byteArrayOf(
            HEADER,
            0x00,
            0x04,
            DELIMITER,
            CMD_HEALTH_DATA,
            0x80.toByte(),
            0x00
        )
    }

    /**
     * Builds packet to trigger watch vibration (Find Watch / Saatimi Bul).
     * Format: AB 00 04 FF 71 80 01
     */
    fun buildFindDeviceCommand(enable: Boolean = true): ByteArray {
        return byteArrayOf(
            HEADER,
            0x00,
            0x04,
            DELIMITER,
            CMD_FIND_DEVICE,
            0x80.toByte(),
            if (enable) 0x01.toByte() else 0x00.toByte()
        )
    }

    /**
     * Builds packets to dismiss and stop active watch search/vibration.
     * Combines Find Stop (0x71 80 00), Find Cancel (0x71 80 02), and Alert Dismiss (0x72 80 02 02).
     */
    fun buildFindDeviceStopCommands(): List<ByteArray> {
        return listOf(
            byteArrayOf(HEADER, 0x00, 0x04, DELIMITER, CMD_FIND_DEVICE, 0x80.toByte(), 0x00),
            byteArrayOf(HEADER, 0x00, 0x04, DELIMITER, CMD_FIND_DEVICE, 0x80.toByte(), 0x02),
            byteArrayOf(HEADER, 0x00, 0x05, DELIMITER, CMD_NOTIFICATION, 0x80.toByte(), 0x02, 0x02)
        )
    }

    /**
     * Builds packet to set volume level on watch.
     * Format: AB 00 05 FF 99 80 A0 [level 0..100]
     */
    fun buildVolumeCommand(level: Int): ByteArray {
        val safeLevel = level.coerceIn(0, 100).toByte()
        return byteArrayOf(
            HEADER,
            0x00,
            0x05,
            DELIMITER,
            CMD_MEDIA_CONTROL,
            0x80.toByte(),
            0xA0.toByte(),
            safeLevel
        )
    }

    /**
     * Builds packet to enable or disable Quiet Hours (DND / Sessiz Mod) on watch.
     * Format: AB 00 08 FF 76 80 [01=enable/00=disable] [startH] [startM] [endH] [endM]
     */
    fun buildQuietHoursCommand(enabled: Boolean, startH: Int = 0, startM: Int = 0, endH: Int = 23, endM: Int = 59): ByteArray {
        return byteArrayOf(
            HEADER,
            0x00,
            0x08,
            DELIMITER,
            CMD_QUIET,
            0x80.toByte(),
            if (enabled) 0x01 else 0x00,
            startH.toByte(),
            startM.toByte(),
            endH.toByte(),
            endM.toByte()
        )
    }

    /**
     * Builds packet to set sound & vibration alert mode on the watch.
     * Format: AB 00 05 FF 7A 80 [Mode]
     */
    fun buildAlertModeCommand(mode: AlertMode): ByteArray {
        return byteArrayOf(
            HEADER,
            0x00,
            0x05,
            DELIMITER,
            CMD_ALERT_MODE,
            0x80.toByte(),
            mode.value
        )
    }

    /**
     * Builds packet to set vibration intensity on the watch.
     * Format: AB 00 05 FF 7D 80 [Level: 1=Soft, 2=Medium, 3=Strong]
     */
    fun buildVibrationIntensityCommand(level: Int): ByteArray {
        val safeLevel = level.coerceIn(1, 3).toByte()
        return byteArrayOf(
            HEADER,
            0x00,
            0x05,
            DELIMITER,
            CMD_VIBRATION,
            0x80.toByte(),
            safeLevel
        )
    }

    /**
     * Builds packet to initialize contact sync (contact count & SOS index).
     * Format: AB 00 05 FF A5 80 [sosIndex] [count]
     */
    fun buildContactInitCommand(count: Int, sosIndex: Int = 0): ByteArray {
        val safeCount = count.coerceIn(0, 20).toByte()
        val safeSos = sosIndex.coerceIn(0, 19).toByte()
        return byteArrayOf(
            HEADER,
            0x00,
            0x05,
            DELIMITER,
            CMD_CONTACT_INIT,
            0x80.toByte(),
            safeSos,
            safeCount
        )
    }

    /**
     * Builds contact name packet(s) for the specified slot index.
     * Format: AB 00 [lenD] FF A2 [pos] [nameBytes]
     * If name > 14 bytes, split into two BLE packets:
     * - Packet 1: 6 header bytes + 14 name bytes = 20 bytes
     * - Packet 2: 0x00 continuation byte + remaining name bytes
     */
    fun buildContactNameCommands(name: String, pos: Int): List<ByteArray> {
        val msg = name.toByteArray(StandardCharsets.UTF_8)
        val msgByte = if (msg.size > 33) msg.copyOfRange(0, 33) else msg
        val lenD = (msgByte.size + 3).toByte()

        return if (msgByte.size <= 14) {
            val packet = ByteArray(6 + msgByte.size)
            packet[0] = HEADER
            packet[1] = 0x00
            packet[2] = lenD
            packet[3] = DELIMITER
            packet[4] = CMD_CONTACT_NAME
            packet[5] = pos.toByte()
            System.arraycopy(msgByte, 0, packet, 6, msgByte.size)
            listOf(packet)
        } else {
            val p1 = ByteArray(20)
            p1[0] = HEADER
            p1[1] = 0x00
            p1[2] = lenD
            p1[3] = DELIMITER
            p1[4] = CMD_CONTACT_NAME
            p1[5] = pos.toByte()
            System.arraycopy(msgByte, 0, p1, 6, 14)

            val remSize = msgByte.size - 14
            val p2 = ByteArray(1 + remSize)
            p2[0] = 0x00.toByte()
            System.arraycopy(msgByte, 14, p2, 1, remSize)

            listOf(p1, p2)
        }
    }

    /**
     * Builds contact phone number packet for the specified slot index.
     * Phone digits and '+' are packed into nibbles:
     * '+' -> 0x0A, '0'..'9' -> 0x0..0x9
     * Format: AB 00 [lenD] FF A3 [pos] [lenN] [packedDigits...]
     */
    fun buildContactNumberCommand(rawNumber: String, pos: Int): ByteArray {
        var cleaned = rawNumber.replace("[^0-9+]".toRegex(), "")
        if (cleaned.length > 20) {
            cleaned = cleaned.substring(0, 20)
        }
        val lenN = cleaned.length.toByte()
        var padded = if (cleaned.length % 2 != 0) cleaned + "0" else cleaned
        padded = padded.replace("+", "A")

        val phoneBytes = ByteArray(padded.length / 2)
        for (x in 0 until padded.length / 2) {
            val pair = padded.substring(x * 2, (x + 1) * 2)
            phoneBytes[x] = pair.reversed().toInt(16).toByte()
        }

        val lenD = (4 + phoneBytes.size).toByte()
        val packet = ByteArray(7 + phoneBytes.size)
        packet[0] = HEADER
        packet[1] = 0x00
        packet[2] = lenD
        packet[3] = DELIMITER
        packet[4] = CMD_CONTACT_NUMBER
        packet[5] = pos.toByte()
        packet[6] = lenN
        System.arraycopy(phoneBytes, 0, packet, 7, phoneBytes.size)
        return packet
    }

    /**
     * Builds packet to set/enable/disable an alarm on the watch.
     * Format: AB 00 08 FF 73 80 [index 0..7] [enabled 01/00] [hour 0..23] [minute 0..59] [repeat]
     */
    fun buildAlarmCommand(
        index: Int,
        enabled: Boolean,
        hour: Int,
        minute: Int,
        repeat: Byte = AlarmRepeat.EVERY_DAY.value
    ): ByteArray {
        val safeIndex = index.coerceIn(0, 7).toByte()
        val safeHour = hour.coerceIn(0, 23).toByte()
        val safeMinute = minute.coerceIn(0, 59).toByte()
        return byteArrayOf(
            HEADER,
            0x00,
            0x08,
            DELIMITER,
            CMD_ALARM,
            0x80.toByte(),
            safeIndex,
            if (enabled) 0x01 else 0x00,
            safeHour,
            safeMinute,
            repeat
        )
    }

    /**
     * Builds queries to retrieve existing alarms from the watch.
     * Tries multiple known command variants for Wearfit / Howear RTOS firmware:
     * 1. 0x70 general config query with 0x73 target
     * 2. 0x73 with 0x80 sub-command
     * 3. 0x73 with 0x00 read flag
     * 4. 0x73 per-slot queries (0..7)
     */
    fun buildQueryAlarmsCommands(): List<ByteArray> {
        return listOf(
            // Variant 1: General settings query (CMD 0x70 -> read 0x73)
            byteArrayOf(HEADER, 0x00, 0x04, DELIMITER, 0x70.toByte(), 0x80.toByte(), CMD_ALARM),
            // Variant 2: Standard Wearfit alarm query
            byteArrayOf(HEADER, 0x00, 0x04, DELIMITER, CMD_ALARM, 0x80.toByte(), 0x00),
            // Variant 3: Alarm query with 0x00 flag
            byteArrayOf(HEADER, 0x00, 0x04, DELIMITER, CMD_ALARM, 0x00, 0x00),
            // Variant 4: Short alarm query
            byteArrayOf(HEADER, 0x00, 0x03, DELIMITER, CMD_ALARM, 0x00),
            // Variant 5: Query slot 0..7 with 0x80
            byteArrayOf(HEADER, 0x00, 0x05, DELIMITER, CMD_ALARM, 0x80.toByte(), 0x00, 0x00),
            byteArrayOf(HEADER, 0x00, 0x05, DELIMITER, CMD_ALARM, 0x80.toByte(), 0x01, 0x00),
            // Variant 6: General settings query with slot
            byteArrayOf(HEADER, 0x00, 0x05, DELIMITER, 0x70.toByte(), 0x80.toByte(), CMD_ALARM, 0x00)
        )
    }

    /**
     * Builds notification packets to display text on the watch.
     * Format: AB 00 [Length] FF 72 80 [AppId] 02 [Text...]
     */
    fun buildNotificationCommands(appId: Byte, text: String): List<ByteArray> {
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        val maxTextPerPacket = 40
        val safeText = if (textBytes.size > maxTextPerPacket) textBytes.copyOfRange(0, maxTextPerPacket) else textBytes

        // Length from FF: FF(1) + 72(1) + 80(1) + appId(1) + 02(1) + textBytes
        val lengthFromFf = (5 + safeText.size).toByte()
        val packet = ByteArray(3 + lengthFromFf)

        packet[0] = HEADER
        packet[1] = 0x00
        packet[2] = lengthFromFf
        packet[3] = DELIMITER
        packet[4] = CMD_NOTIFICATION
        packet[5] = 0x80.toByte()
        packet[6] = appId
        packet[7] = 0x02.toByte() // Action: Display Notification

        System.arraycopy(safeText, 0, packet, 8, safeText.size)
        return listOf(packet)
    }

    /**
     * Parses received byte array from watch notifications.
     */
    fun parseIncomingPacket(
        data: ByteArray,
        onBatteryReceived: (Int) -> Unit,
        onHealthReceived: (steps: Int, hr: Int, cal: Int, bpSys: Int, bpDia: Int, spO2: Int) -> Unit,
        onAlarmReceived: ((WatchAlarm) -> Unit)? = null
    ) {
        if (data.size < 5 || data[0] != HEADER) return

        val cmd = data[4]
        when (cmd) {
            CMD_ALARM, 0x70.toByte() -> {
                // Incoming alarm from watch:
                // Standard: AB 00 08 FF 73 80 [index] [enabled] [hour] [minute] [repeat]
                // Config wrapped: AB 00 09 FF 70 80 73 [index] [enabled] [hour] [minute] [repeat]
                var offset = 6
                if (cmd == 0x70.toByte()) {
                    if (data.size >= 12 && data[6] == CMD_ALARM) {
                        offset = 7
                    } else {
                        offset = -1
                    }
                }
                if (offset != -1 && data.size >= offset + 5) {
                    val index = data[offset].toInt() and 0xFF
                    val enabled = data[offset + 1].toInt() != 0
                    val hour = data[offset + 2].toInt() and 0xFF
                    val minute = data[offset + 3].toInt() and 0xFF
                    val repeatByte = data[offset + 4]
                    val repeat = when (repeatByte.toInt() and 0xFF) {
                        0x80 -> AlarmRepeat.ONCE
                        0x7F -> AlarmRepeat.EVERY_DAY
                        0x1F -> AlarmRepeat.WEEKDAYS
                        0x60 -> AlarmRepeat.WEEKENDS
                        else -> AlarmRepeat.EVERY_DAY
                    }
                    val alarm = WatchAlarm(index, hour, minute, enabled, repeat)
                    onAlarmReceived?.invoke(alarm)
                }
            }
            CMD_BATTERY -> {
                // Incoming: AB 00 05 FF 91 80 00 2F
                if (data.size >= 8) {
                    val battery = data[7].toInt() and 0xFF
                    onBatteryReceived(battery)
                } else if (data.size >= 7) {
                    val battery = data[6].toInt() and 0xFF
                    onBatteryReceived(battery)
                }
            }
            CMD_STEPS_TODAY, CMD_STEPS_HISTORY -> {
                // Wearfit Health Telemetry (0x51 / 0x52)
                val subType = if (data.size > 5) data[5].toInt() and 0xFF else -1
                val ts = getTimestamp(data)
                when (subType) {
                    0x20 -> {
                        // Steps & Calories packet
                        // Format: AB 00 19 FF 51 20 [YY] [MM] [DD] [HH] [StepH] [StepM] [StepL] [CalH] [CalM] [CalL] ...
                        if (data.size >= 16 && ts >= latestStepsTimestamp) {
                            latestStepsTimestamp = ts
                            val steps = ((data[10].toInt() and 0xFF) shl 16) or
                                        ((data[11].toInt() and 0xFF) shl 8) or
                                        (data[12].toInt() and 0xFF)
                            val cal = ((data[13].toInt() and 0xFF) shl 16) or
                                      ((data[14].toInt() and 0xFF) shl 8) or
                                      (data[15].toInt() and 0xFF)
                            if (steps in 0..300000) {
                                onHealthReceived(steps, -1, cal, -1, -1, -1)
                            }
                        }
                    }
                    0x11 -> {
                        // Heart Rate (BPM) record packet!
                        // Format: AB 00 0A FF 51 11 [YY] [MM] [DD] [HH] [00] [HR] [00]
                        if (data.size >= 12 && ts >= latestHrTimestamp) {
                            latestHrTimestamp = ts
                            val hr = data[11].toInt() and 0xFF
                            if (hr in 40..220) {
                                onHealthReceived(-1, hr, -1, -1, -1, -1)
                            }
                        }
                    }
                    0x12 -> {
                        // SpO2 (Blood Oxygen) record packet!
                        // Format: AB 00 0A FF 51 12 [YY] [MM] [DD] [HH] [00] [SpO2] [SpO2]
                        if (data.size >= 12 && ts >= latestSpo2Timestamp) {
                            latestSpo2Timestamp = ts
                            val spo2 = data[11].toInt() and 0xFF
                            if (spo2 in 70..100) {
                                onHealthReceived(-1, -1, -1, -1, -1, spo2)
                            }
                        }
                    }
                    0x14 -> {
                        // Blood Pressure record packet!
                        // Format: AB 00 0A FF 51 14 [YY] [MM] [DD] [HH] [00] [Sys] [Dia]
                        if (data.size >= 13 && ts >= latestBpTimestamp) {
                            latestBpTimestamp = ts
                            val sys = data[11].toInt() and 0xFF
                            val dia = data[12].toInt() and 0xFF
                            if (sys in 60..240 && dia in 30..150) {
                                onHealthReceived(-1, -1, -1, sys, dia, -1)
                            }
                        }
                    }
                }
            }
            CMD_HEART_RATE, CMD_HEALTH_DATA -> {
                // Heart rate / Health packet (0x31 / 0x32)
                var foundHr = -1
                for (i in 6 until data.size) {
                    val candidate = data[i].toInt() and 0xFF
                    if (candidate in 40..210) {
                        foundHr = candidate
                        break
                    }
                }
                if (foundHr > 0) {
                    onHealthReceived(-1, foundHr, -1, -1, -1, -1)
                }
            }
        }
    }
}
