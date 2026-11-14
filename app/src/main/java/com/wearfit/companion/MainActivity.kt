package com.wearfit.companion

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.textfield.TextInputEditText
import com.wearfit.companion.ble.BleManager
import com.wearfit.companion.ble.BleScanner
import com.wearfit.companion.ble.ConnectionState
import com.wearfit.companion.ble.WearfitProtocol
import com.wearfit.companion.databinding.ActivityMainBinding
import com.wearfit.companion.ui.DeviceAdapter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: BleManager
    private lateinit var bleScanner: BleScanner
    private lateinit var deviceAdapter: DeviceAdapter
    private var isFindingDevice = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bleManager = BleManager.getInstance(this)
        bleScanner = BleScanner(bluetoothAdapter)

        setupRecyclerView()
        setupListeners()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        checkNotificationListenerPermission()
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
            bleManager.queryHealth()
            bleManager.queryBattery()
            Toast.makeText(this, "Sensör verileri talep edildi", Toast.LENGTH_SHORT).show()
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
                            }
                            ConnectionState.CONNECTING -> {
                                binding.tvConnectionStatus.text = getString(R.string.status_connecting)
                                binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_orange))
                                binding.cardConnectedDevice.visibility = View.GONE
                            }
                            ConnectionState.CONNECTED -> {
                                binding.tvConnectionStatus.text = getString(R.string.status_connected)
                                binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_green))
                                binding.cardConnectedDevice.visibility = View.VISIBLE
                            }
                        }
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

    override fun onDestroy() {
        super.onDestroy()
        bleScanner.stopScan()
    }
}
