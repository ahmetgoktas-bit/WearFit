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
    const val CMD_HEALTH_DATA: Byte = 0x32.toByte()
    const val CMD_STEPS_TODAY: Byte = 0x51.toByte()
    const val CMD_STEPS_HISTORY: Byte = 0x52.toByte()
    const val CMD_FIND_DEVICE: Byte = 0x71.toByte()
    const val CMD_NOTIFICATION: Byte = 0x72.toByte()
    const val CMD_BATTERY: Byte = 0x91.toByte()
    const val CMD_SET_TIME: Byte = 0x93.toByte()

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
    fun buildFindDeviceCommand(): ByteArray {
        return byteArrayOf(
            HEADER,
            0x00,
            0x04,
            DELIMITER,
            CMD_FIND_DEVICE,
            0x80.toByte(),
            0x01
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
        onHealthReceived: (steps: Int, hr: Int, cal: Int) -> Unit
    ) {
        if (data.size < 5 || data[0] != HEADER) return

        val cmd = data[4]
        when (cmd) {
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
                // Steps data packet (0x51 / 0x52)
                // Format: AB 00 19 FF 51 20 [YY] [MM] [DD] [HH] [StepH] [StepM] [StepL] [CalH] [CalM] [CalL] ...
                if (data.size >= 16 && data[5] == 0x20.toByte()) {
                    val steps = ((data[10].toInt() and 0xFF) shl 16) or
                                ((data[11].toInt() and 0xFF) shl 8) or
                                (data[12].toInt() and 0xFF)
                    val cal = ((data[13].toInt() and 0xFF) shl 16) or
                              ((data[14].toInt() and 0xFF) shl 8) or
                              (data[15].toInt() and 0xFF)
                    if (steps in 0..300000) {
                        onHealthReceived(steps, -1, cal)
                    }
                } else if (data.size >= 12) {
                    val steps = ((data[6].toInt() and 0xFF) shl 16) or
                                ((data[7].toInt() and 0xFF) shl 8) or
                                (data[8].toInt() and 0xFF)
                    if (steps in 0..300000) {
                        onHealthReceived(steps, -1, -1)
                    }
                }
            }
            CMD_HEALTH_DATA -> {
                // Health telemetry packet (0x32)
                if (data.size >= 10) {
                    val hr = data[data.size - 1].toInt() and 0xFF
                    if (hr in 30..220) {
                        onHealthReceived(-1, hr, -1)
                    }
                }
            }
        }
    }
}
