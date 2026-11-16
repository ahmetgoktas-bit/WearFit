package com.wearfit.companion

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.app.Activity
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.wearfit.companion.ble.BleManager
import com.wearfit.companion.ble.BleScanner
import com.wearfit.companion.ble.ConnectionState
import com.wearfit.companion.ble.WearfitProtocol
import com.wearfit.companion.databinding.ActivityMainBinding
import com.wearfit.companion.ui.DeviceAdapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: BleManager
    private lateinit var bleScanner: BleScanner
    private lateinit var deviceAdapter: DeviceAdapter
    private var isFindingDevice = false

    private lateinit var audioManager: AudioManager
    private var isVolumeReceiverRegistered = false

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateVolumeUi()
        }
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothManager?.adapter
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startBleScan()
        } else {
            Toast.makeText(this, R.string.permission_bluetooth_required, Toast.LENGTH_LONG).show()
        }
    }

    private val contactList = mutableListOf<WearfitProtocol.ContactItem>()
    private val alarmList = mutableListOf<WearfitProtocol.WatchAlarm>()

    private val pickContactLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val contactUri: Uri = result.data?.data ?: return@registerForActivityResult
            parseAndAddContact(contactUri)
        }
    }

    private val contactPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Rehber izni verildi", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Rehberden kişi seçebilmek veya aktarmak için izin vermelisiniz", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bleManager = BleManager.getInstance(this)
        bleScanner = BleScanner(bluetoothAdapter)

        setupRecyclerView()
        setupListeners()
        setupVolumeControl()
        setupContactsSection()
        setupAlarmsSection()
        observeState()

        // Check & request background execution / battery optimization exemption
        requestBatteryOptimizationExemption(showDialogFirst = true)

        // Auto-connect to saved watch if Bluetooth is active and permissions are granted
        if (bleManager.connectionState.value == ConnectionState.DISCONNECTED && bluetoothAdapter?.isEnabled == true) {
            val hasBtPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (hasBtPerm) {
                bleManager.connectAddress("C9:01:00:08:5C:A5")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkNotificationListenerPermission()
        updateBatteryOptimizationCard()

        if (!isVolumeReceiverRegistered) {
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            registerReceiver(volumeReceiver, filter)
            isVolumeReceiverRegistered = true
        }
        updateVolumeUi()
    }

    override fun onPause() {
        super.onPause()
        if (isVolumeReceiverRegistered) {
            try {
                unregisterReceiver(volumeReceiver)
            } catch (_: Exception) {}
            isVolumeReceiverRegistered = false
        }
    }

    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter { selectedItem ->
            bleScanner.stopScan()
            updateScanUi(false)
            Toast.makeText(this, "${selectedItem.name} cihazına bağlanılıyor...", Toast.LENGTH_SHORT).show()
            bleManager.connect(selectedItem.device)
        }
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = deviceAdapter
    }

    private fun setupListeners() {
        binding.btnScan.setOnClickListener {
            if (bleScanner.isCurrentlyScanning()) {
                bleScanner.stopScan()
                updateScanUi(false)
            } else {
                checkAndRequestPermissionsThenScan()
            }
        }

        binding.btnDisconnect.setOnClickListener {
            bleManager.disconnect()
            binding.cardConnectedDevice.visibility = View.GONE
            binding.tvConnectionStatus.text = getString(R.string.status_disconnected)
            binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
            Toast.makeText(this, "Bağlantı kesildi", Toast.LENGTH_SHORT).show()
        }

        binding.btnSyncTime.setOnClickListener {
            bleManager.syncTime()
            Toast.makeText(this, "Saat ve tarih senkronize edildi", Toast.LENGTH_SHORT).show()
        }

        binding.btnFindDevice.setOnClickListener {
            isFindingDevice = !isFindingDevice
            bleManager.findDevice(isFindingDevice)
            if (isFindingDevice) {
                binding.btnFindDevice.text = "Durdur"
                binding.btnFindDevice.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
                Toast.makeText(this, "Saat aranıyor (Durdurmak için tekrar basın)", Toast.LENGTH_SHORT).show()
            } else {
                binding.btnFindDevice.text = getString(R.string.action_find_device)
                binding.btnFindDevice.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                Toast.makeText(this, "Arama durduruldu", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSyncHealth.setOnClickListener {
            bleManager.queryHealthData(triggerOptical = true)
            Toast.makeText(this, "Sensörler 15 sn okuyup otomatik kapanacak", Toast.LENGTH_SHORT).show()
        }

        binding.btnTestNotification.setOnClickListener {
            showTestNotificationDialog()
        }

        binding.btnGrantNotificationAccess.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                // Fallback to app details
                openAppDetailsSettings()
            }
        }

        binding.btnGrantBatteryOptimization.setOnClickListener {
            launchBatteryOptimizationIntent()
        }

        binding.btnQuickConnect.setOnClickListener {
            val address = "C9:01:00:08:5C:A5"
            Toast.makeText(this, "HK10 ULTRA 3 MAX saatine doğrudan bağlanılıyor...", Toast.LENGTH_SHORT).show()
            bleManager.connectAddress(address)
        }
    }

    private fun checkBatteryOptimizationStatus(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun updateBatteryOptimizationCard() {
        val isExempt = checkBatteryOptimizationStatus()
        binding.cardBatteryOptimization.visibility = if (isExempt) View.GONE else View.VISIBLE
    }

    private fun requestBatteryOptimizationExemption(showDialogFirst: Boolean = false) {
        if (checkBatteryOptimizationStatus()) return

        if (showDialogFirst) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Arka Planda Kesintisiz Çalışma")
                .setMessage("Uygulamanın ekran kapalıyken veya arka plandayken saatle bağlantısının kopmaması, WhatsApp bildirimlerini iletmesi ve 5 dakikalık sağlık ölçümlerinin aksamaması için pil kısıtlamasından muaf tutulması gerekir.\n\nLütfen açılan sistem penceresinde 'İzin Ver' seçeneğini onaylayın.")
                .setPositiveButton("İzin Ver") { _, _ ->
                    launchBatteryOptimizationIntent()
                }
                .setNegativeButton("Daha Sonra", null)
                .show()
        } else {
            launchBatteryOptimizationIntent()
        }
    }

    @SuppressLint("BatteryLife")
    private fun launchBatteryOptimizationIntent() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(fallback)
            } catch (_: Exception) {}
        }
    }

    private fun openAppDetailsSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    bleScanner.scannedDevices.collect { list ->
                        deviceAdapter.submitList(list)
                        binding.tvEmptyDevices.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    bleManager.connectionState.collect { state ->
                        when (state) {
                            ConnectionState.DISCONNECTED -> {
                                isFindingDevice = false
                                binding.btnFindDevice.text = getString(R.string.action_find_device)
                                binding.btnFindDevice.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                                binding.tvConnectionStatus.text = getString(R.string.status_disconnected)
                                binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_red))
                                binding.cardConnectedDevice.visibility = View.GONE
                                binding.cardSavedWatch.visibility = View.VISIBLE
                            }
                            ConnectionState.CONNECTING -> {
                                binding.tvConnectionStatus.text = getString(R.string.status_connecting)
                                binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_orange))
                                binding.cardConnectedDevice.visibility = View.GONE
                                binding.cardSavedWatch.visibility = View.VISIBLE
                            }
                            ConnectionState.CONNECTED -> {
                                binding.tvConnectionStatus.text = getString(R.string.status_connected)
                                binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_green))
                                binding.cardConnectedDevice.visibility = View.VISIBLE
                                binding.cardSavedWatch.visibility = View.GONE
                                bleManager.queryAlarms()
                            }
                        }
                    }
                }

                launch {
                    bleManager.watchAlarms.collect { incomingAlarm ->
                        Log.i("MainActivity", "Received alarm from watch: [${incomingAlarm.id}] ${incomingAlarm.hour}:${incomingAlarm.minute} enabled=${incomingAlarm.isEnabled}")
                        val existing = alarmList.find { it.id == incomingAlarm.id }
                        if (existing != null) {
                            existing.hour = incomingAlarm.hour
                            existing.minute = incomingAlarm.minute
                            existing.isEnabled = incomingAlarm.isEnabled
                            existing.repeat = incomingAlarm.repeat
                        } else {
                            if (incomingAlarm.isEnabled || incomingAlarm.hour != 0 || incomingAlarm.minute != 0) {
                                alarmList.add(incomingAlarm)
                            }
                        }
                        saveAlarmsToPrefs()
                        updateAlarmsUi()
                    }
                }

                launch {
                    bleManager.watchData.collect { data ->
                        if (data.isConnected) {
                            binding.tvConnectedDeviceName.text = data.deviceName.ifBlank { "H10 Smartwatch" }
                            binding.tvConnectedDeviceAddress.text = data.deviceAddress
                            binding.tvBattery.text = if (data.batteryPercent >= 0) "Pil: %${data.batteryPercent}" else "Pil: --%"
                            binding.tvSteps.text = data.steps.toString()
                            binding.tvHeartRate.text = if (data.heartRate > 0) "${data.heartRate} bpm" else "-- bpm"
                            binding.tvCalories.text = data.calories.toString()
                            binding.tvBloodPressure.text = if (data.bloodPressureSys > 0 && data.bloodPressureDia > 0) {
                                "${data.bloodPressureSys}/${data.bloodPressureDia}"
                            } else {
                                "--/--"
                            }
                            binding.tvSpO2.text = if (data.spO2 > 0) "%${data.spO2}" else "--%"
                            binding.tvLastHealthSync.text = if (data.lastSyncTime.isNotBlank()) {
                                "Otomatik Ölçüm: 5 dk'da bir | Son: ${data.lastSyncTime}"
                            } else {
                                "Otomatik Ölçüm: 5 dk'da bir | Bekleniyor…"
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkNotificationListenerPermission() {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(this)
        val hasPermission = enabledPackages.contains(packageName)
        binding.cardNotificationPermission.visibility = if (hasPermission) View.GONE else View.VISIBLE
    }

    private fun checkAndRequestPermissionsThenScan() {
        val neededPermissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (neededPermissions.isEmpty()) {
            startBleScan()
        } else {
            permissionLauncher.launch(neededPermissions.toTypedArray())
        }
    }

    private fun startBleScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Toast.makeText(this, "Lütfen telefonun Bluetooth'unu açın", Toast.LENGTH_SHORT).show()
            return
        }
        val started = bleScanner.startScan()
        updateScanUi(started)
    }

    private fun updateScanUi(isScanning: Boolean) {
        binding.progressScan.visibility = if (isScanning) View.VISIBLE else View.GONE
        binding.btnScan.text = if (isScanning) getString(R.string.btn_stop_scan) else getString(R.string.btn_scan)
    }

    private fun showTestNotificationDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_test_notification, null)
        val etTitle = dialogView.findViewById<TextInputEditText>(R.id.etTitle)
        val etMessage = dialogView.findViewById<TextInputEditText>(R.id.etMessage)

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_send) { _, _ ->
                val title = etTitle.text?.toString() ?: ""
                val msg = etMessage.text?.toString() ?: ""
                val fullText = if (title.isNotBlank()) "$title: $msg" else msg
                bleManager.sendNotification(WearfitProtocol.AppId.WHATSAPP, fullText)
                Toast.makeText(this, "Test bildirimi saate iletildi", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun setupVolumeControl() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        updateVolumeUi()

        binding.sliderVolume.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                setDeviceVolumePercent(value.toInt())
            }
        }

        binding.btnVolumeDown.setOnClickListener {
            adjustVolumeStep(-1)
        }

        binding.btnVolumeUp.setOnClickListener {
            adjustVolumeStep(1)
        }

        binding.btnVolMute.setOnClickListener {
            setDeviceVolumePercent(0)
        }

        binding.btnVol50.setOnClickListener {
            setDeviceVolumePercent(50)
        }

        binding.btnVolMax.setOnClickListener {
            setDeviceVolumePercent(100)
        }

        binding.btnOpenBtSettings.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            } catch (_: Exception) {}
        }

        binding.btnAlertSoundVib.setOnClickListener {
            bleManager.setAlertMode(WearfitProtocol.AlertMode.SOUND_AND_VIBRATION)
            bleManager.setQuietMode(false)
            Toast.makeText(this, "Bildirim Modu: Ses + Titreşim", Toast.LENGTH_SHORT).show()
        }

        binding.btnAlertVibOnly.setOnClickListener {
            bleManager.setAlertMode(WearfitProtocol.AlertMode.VIBRATION_ONLY)
            bleManager.setQuietMode(false)
            Toast.makeText(this, "Bildirim Modu: Yalnızca Titreşim", Toast.LENGTH_SHORT).show()
        }

        binding.btnAlertSilent.setOnClickListener {
            bleManager.setAlertMode(WearfitProtocol.AlertMode.SILENT)
            bleManager.setQuietMode(true)
            Toast.makeText(this, "Bildirim Modu: Sessiz (Rahatsız Etmeyin)", Toast.LENGTH_SHORT).show()
        }

        binding.btnVibSoft.setOnClickListener {
            bleManager.setVibrationIntensity(1)
            Toast.makeText(this, "Titreşim Gücü: Hafif", Toast.LENGTH_SHORT).show()
        }

        binding.btnVibStrong.setOnClickListener {
            bleManager.setVibrationIntensity(3)
            Toast.makeText(this, "Titreşim Gücü: Güçlü", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setDeviceVolumePercent(percent: Int) {
        val maxCallVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL).coerceAtLeast(1)
        val maxMusicVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

        val targetCallVol = (percent * maxCallVol / 100.0).roundToInt().coerceIn(0, maxCallVol)
        val targetMusicVol = (percent * maxMusicVol / 100.0).roundToInt().coerceIn(0, maxMusicVol)

        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, targetCallVol, 0)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetMusicVol, 0)

        // Send volume and quiet mode commands over BLE to watch
        bleManager.setWatchVolume(percent)
        if (percent == 0) {
            bleManager.setQuietMode(true)
        } else {
            bleManager.setQuietMode(false)
        }

        binding.tvVolumePercent.text = "%$percent"
        if (binding.sliderVolume.value.toInt() != percent) {
            binding.sliderVolume.value = percent.toFloat()
        }
    }

    private fun adjustVolumeStep(direction: Int) {
        val current = binding.sliderVolume.value.toInt()
        val next = (current + direction * 10).coerceIn(0, 100)
        setDeviceVolumePercent(next)
    }

    private fun updateVolumeUi() {
        if (!::audioManager.isInitialized) return
        val maxCallVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL).coerceAtLeast(1)
        val curCallVol = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        val percent = (curCallVol * 100.0 / maxCallVol).roundToInt().coerceIn(0, 100)

        binding.sliderVolume.value = percent.toFloat()
        binding.tvVolumePercent.text = "%$percent"

        checkBtAudioConnection()
    }

    private fun checkBtAudioConnection() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        val isCallAudioConnected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                adapter?.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED ||
                adapter?.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
            } else false
        } else {
            @Suppress("DEPRECATION")
            adapter?.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED ||
            @Suppress("DEPRECATION")
            adapter?.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
        }

        if (isCallAudioConnected) {
            binding.tvBtAudioStatus.text = "BT Hoparlör: Bağlı (HK10 Call)"
            binding.tvBtAudioStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
        } else {
            binding.tvBtAudioStatus.text = "BT Hoparlör: Bağlı Değil / Eşlenmedi"
            binding.tvBtAudioStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_orange))
        }
    }

    private fun setupContactsSection() {
        loadContactsFromPrefs()
        updateContactsUi()

        binding.btnPickContact.setOnClickListener {
            if (contactList.size >= 10) {
                Toast.makeText(this, "En fazla 10 kişi eklenebilir", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                contactPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            } else {
                val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                pickContactLauncher.launch(intent)
            }
        }

        binding.btnAutoImportContacts.setOnClickListener {
            autoImportTopContacts()
        }

        binding.btnClearContacts.setOnClickListener {
            contactList.clear()
            saveContactsToPrefs()
            updateContactsUi()
            Toast.makeText(this, "Rehber listesi temizlendi", Toast.LENGTH_SHORT).show()
        }

        binding.btnSyncContactsToWatch.setOnClickListener {
            if (!bleManager.isDeviceConnected()) {
                Toast.makeText(this, "Saat bağlı değil! Önce BLE bağlantısını kurun.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (contactList.isEmpty()) {
                Toast.makeText(this, "Gönderilecek kişi yok. Önce kişi ekleyin.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnSyncContactsToWatch.isEnabled = false
            binding.btnSyncContactsToWatch.text = "Saate Aktarılıyor..."

            lifecycleScope.launch {
                val success = bleManager.syncContacts(contactList) { current, total ->
                    binding.btnSyncContactsToWatch.text = "Aktarılıyor ($current/$total)..."
                }

                binding.btnSyncContactsToWatch.isEnabled = true
                binding.btnSyncContactsToWatch.text = "⌚ Saate Gönder (Senkronize Et)"

                if (success) {
                    Toast.makeText(
                        this@MainActivity,
                        "✅ ${contactList.size} kişi saate başarıyla gönderildi!\nSaatin 'Rehber / Kişiler' menüsünden kontrol edin.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this@MainActivity, "❌ Kişiler gönderilirken bir hata oluştu.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun parseAndAddContact(contactUri: Uri) {
        if (contactList.size >= 10) {
            Toast.makeText(this, "En fazla 10 kişi eklenebilir", Toast.LENGTH_SHORT).show()
            return
        }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        try {
            contentResolver.query(contactUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) ?: "İsimsiz" else "İsimsiz"
                    val rawNumber = if (numberIndex >= 0) cursor.getString(numberIndex) ?: "" else ""
                    val cleaned = rawNumber.replace("[^0-9+]".toRegex(), "")

                    if (cleaned.isNotEmpty()) {
                        contactList.add(WearfitProtocol.ContactItem(name, cleaned))
                        saveContactsToPrefs()
                        updateContactsUi()
                        Toast.makeText(this, "$name eklendi (${contactList.size}/10)", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Geçerli bir telefon numarası bulunamadı", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error reading contact: ${e.message}")
        }
    }

    private fun autoImportTopContacts() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            contactPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            return
        }

        contactList.clear()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val sortOrder = "${ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED} DESC, ${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        val seenNumbers = mutableSetOf<String>()

        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (cursor.moveToNext() && contactList.size < 10) {
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) ?: "İsimsiz" else "İsimsiz"
                    val rawNumber = if (numberIndex >= 0) cursor.getString(numberIndex) ?: "" else ""
                    val cleaned = rawNumber.replace("[^0-9+]".toRegex(), "")

                    if (cleaned.length >= 7 && seenNumbers.add(cleaned)) {
                        contactList.add(WearfitProtocol.ContactItem(name, cleaned))
                    }
                }
            }
            saveContactsToPrefs()
            updateContactsUi()
            Toast.makeText(this, "${contactList.size} kişi rehberden aktarıldı", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error auto-importing contacts: ${e.message}")
            Toast.makeText(this, "Rehber okunurken hata oluştu", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveContactsToPrefs() {
        val prefs = getSharedPreferences("wearfit_contacts", Context.MODE_PRIVATE)
        val array = JSONArray()
        for (c in contactList) {
            val obj = JSONObject()
            obj.put("name", c.name)
            obj.put("number", c.number)
            array.put(obj)
        }
        prefs.edit().putString("saved_contacts", array.toString()).apply()
    }

    private fun loadContactsFromPrefs() {
        contactList.clear()
        val prefs = getSharedPreferences("wearfit_contacts", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("saved_contacts", null) ?: return
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val name = obj.getString("name")
                val number = obj.getString("number")
                contactList.add(WearfitProtocol.ContactItem(name, number))
            }
        } catch (_: Exception) {}
    }

    private fun updateContactsUi() {
        binding.tvContactCount.text = "${contactList.size}/10"
        binding.tvEmptyContacts.visibility = if (contactList.isEmpty()) View.VISIBLE else View.GONE
        binding.btnPickContact.isEnabled = contactList.size < 10
        binding.btnAutoImportContacts.isEnabled = contactList.size < 10
        binding.layoutContactList.removeAllViews()

        for (i in contactList.indices) {
            val contact = contactList[i]
            val itemView = layoutInflater.inflate(R.layout.item_contact, binding.layoutContactList, false)

            itemView.findViewById<TextView>(R.id.tvIndex).text = (i + 1).toString()
            itemView.findViewById<TextView>(R.id.tvContactName).text = contact.name
            itemView.findViewById<TextView>(R.id.tvContactNumber).text = contact.number

            itemView.findViewById<View>(R.id.btnDeleteContact).setOnClickListener {
                contactList.removeAt(i)
                saveContactsToPrefs()
                updateContactsUi()
            }

            binding.layoutContactList.addView(itemView)
        }
    }

    private fun setupAlarmsSection() {
        loadAlarmsFromPrefs()
        updateAlarmsUi()

        binding.btnAddAlarm.setOnClickListener {
            if (alarmList.size >= 8) {
                Toast.makeText(this, "En fazla 8 alarm kurulabilir", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showTimePickerForAlarm()
        }

        binding.btnFetchAlarms.setOnClickListener {
            if (!bleManager.isDeviceConnected()) {
                Toast.makeText(this, "Saat bağlı değil! Önce BLE bağlantısını kurun.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val countBefore = alarmList.size
            bleManager.queryAlarms()
            Toast.makeText(this, "Saatteki alarmlar sorgulanıyor...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                delay(2500)
                if (alarmList.size == countBefore) {
                    Toast.makeText(
                        this@MainActivity,
                        "ℹ️ Saatiniz alarm listesini telefona geri aktarmadı.\nWearfit saatlerde alarmlar uygulamadan 'Yeni Alarm' ile kurulup saate gönderilir.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showTimePickerForAlarm(existingAlarm: WearfitProtocol.WatchAlarm? = null) {
        val initialHour = existingAlarm?.hour ?: 7
        val initialMinute = existingAlarm?.minute ?: 0

        val timePicker = TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                showRepeatSelectorDialog(hourOfDay, minute, existingAlarm)
            },
            initialHour,
            initialMinute,
            true
        )
        timePicker.setTitle(if (existingAlarm != null) "Alarmı Düzenle" else "Yeni Alarm Saati")
        timePicker.show()
    }

    private fun showRepeatSelectorDialog(
        hour: Int,
        minute: Int,
        existingAlarm: WearfitProtocol.WatchAlarm? = null
    ) {
        val options = arrayOf(
            "Her Gün",
            "Hafta İçi (Pazartesi - Cuma)",
            "Hafta Sonu (Cumartesi - Pazar)",
            "Bir Kez"
        )
        val repeatEnums = arrayOf(
            WearfitProtocol.AlarmRepeat.EVERY_DAY,
            WearfitProtocol.AlarmRepeat.WEEKDAYS,
            WearfitProtocol.AlarmRepeat.WEEKENDS,
            WearfitProtocol.AlarmRepeat.ONCE
        )

        var selectedIndex = when (existingAlarm?.repeat) {
            WearfitProtocol.AlarmRepeat.WEEKDAYS -> 1
            WearfitProtocol.AlarmRepeat.WEEKENDS -> 2
            WearfitProtocol.AlarmRepeat.ONCE -> 3
            else -> 0
        }

        AlertDialog.Builder(this)
            .setTitle(String.format("Alarm Tekrarı (%02d:%02d)", hour, minute))
            .setSingleChoiceItems(options, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton("Kaydet") { _, _ ->
                val chosenRepeat = repeatEnums[selectedIndex]
                val alarmToSave: WearfitProtocol.WatchAlarm
                if (existingAlarm != null) {
                    existingAlarm.hour = hour
                    existingAlarm.minute = minute
                    existingAlarm.repeat = chosenRepeat
                    existingAlarm.isEnabled = true
                    alarmToSave = existingAlarm
                } else {
                    val usedIds = alarmList.map { it.id }.toSet()
                    val newId = (0..7).firstOrNull { it !in usedIds } ?: alarmList.size
                    val newAlarm = WearfitProtocol.WatchAlarm(
                        id = newId,
                        hour = hour,
                        minute = minute,
                        isEnabled = true,
                        repeat = chosenRepeat
                    )
                    alarmList.add(newAlarm)
                    alarmToSave = newAlarm
                }

                saveAlarmsToPrefs()
                updateAlarmsUi()

                // Send to watch over BLE
                if (bleManager.isDeviceConnected()) {
                    bleManager.setAlarm(alarmToSave)
                    Toast.makeText(
                        this,
                        String.format("⏰ Alarm saate kuruldu: %02d:%02d (%s)", hour, minute, chosenRepeat.label),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        String.format("Alarm kaydedildi: %02d:%02d. Saat bağlandığında saate iletilecek.", hour, minute),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun saveAlarmsToPrefs() {
        val prefs = getSharedPreferences("wearfit_alarms", Context.MODE_PRIVATE)
        val array = JSONArray()
        for (a in alarmList) {
            val obj = JSONObject()
            obj.put("id", a.id)
            obj.put("hour", a.hour)
            obj.put("minute", a.minute)
            obj.put("enabled", a.isEnabled)
            obj.put("repeat", a.repeat.name)
            array.put(obj)
        }
        prefs.edit().putString("saved_alarms", array.toString()).apply()
    }

    private fun loadAlarmsFromPrefs() {
        alarmList.clear()
        val prefs = getSharedPreferences("wearfit_alarms", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("saved_alarms", null) ?: return
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.getInt("id")
                val hour = obj.getInt("hour")
                val minute = obj.getInt("minute")
                val enabled = obj.getBoolean("enabled")
                val repeatName = obj.optString("repeat", "EVERY_DAY")
                val repeat = try {
                    WearfitProtocol.AlarmRepeat.valueOf(repeatName)
                } catch (_: Exception) {
                    WearfitProtocol.AlarmRepeat.EVERY_DAY
                }
                alarmList.add(WearfitProtocol.WatchAlarm(id, hour, minute, enabled, repeat))
            }
        } catch (_: Exception) {}
    }

    private fun updateAlarmsUi() {
        binding.tvAlarmCount.text = "${alarmList.size}/8"
        binding.tvEmptyAlarms.visibility = if (alarmList.isEmpty()) View.VISIBLE else View.GONE
        binding.btnAddAlarm.isEnabled = alarmList.size < 8
        binding.layoutAlarmList.removeAllViews()

        for (i in alarmList.indices) {
            val alarm = alarmList[i]
            val itemView = layoutInflater.inflate(R.layout.item_alarm, binding.layoutAlarmList, false)

            itemView.findViewById<TextView>(R.id.tvAlarmTime).text = String.format("%02d:%02d", alarm.hour, alarm.minute)
            itemView.findViewById<TextView>(R.id.tvAlarmRepeat).text = alarm.repeat.label

            val switchAlarm = itemView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchAlarm)
            switchAlarm.isChecked = alarm.isEnabled

            switchAlarm.setOnCheckedChangeListener { _, isChecked ->
                alarm.isEnabled = isChecked
                saveAlarmsToPrefs()
                if (bleManager.isDeviceConnected()) {
                    bleManager.setAlarm(alarm)
                    val statusText = if (isChecked) "açıldı" else "kapatıldı"
                    Toast.makeText(this, String.format("Alarm %02d:%02d $statusText", alarm.hour, alarm.minute), Toast.LENGTH_SHORT).show()
                }
            }

            itemView.findViewById<View>(R.id.layoutAlarmText).setOnClickListener {
                showTimePickerForAlarm(alarm)
            }

            itemView.findViewById<View>(R.id.btnDeleteAlarm).setOnClickListener {
                if (bleManager.isDeviceConnected()) {
                    bleManager.deleteAlarm(alarm.id)
                }
                alarmList.removeAt(i)
                saveAlarmsToPrefs()
                updateAlarmsUi()
                Toast.makeText(this, "Alarm silindi", Toast.LENGTH_SHORT).show()
            }

            binding.layoutAlarmList.addView(itemView)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleScanner.stopScan()
    }
}
