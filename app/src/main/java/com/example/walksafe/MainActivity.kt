package com.example.walksafe

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.walksafe.R
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    private lateinit var textViewStatus: TextView
    private lateinit var btnScan: Button

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGatt: BluetoothGatt? = null
    private lateinit var bleScanner: BluetoothLeScanner
    private val handler = Handler(Looper.getMainLooper())

    // Replace these UUIDs with the ones defined in your Arduino sketch
    private val SERVICE_UUID = UUID.fromString("00001234-0000-1000-8000-00805f9b34fb")
    private val CHARACTERISTIC_UUID = UUID.fromString("00005678-0000-1000-8000-00805f9b34fb")


    // Duration for scanning (in milliseconds)
    private val SCAN_PERIOD: Long = 10000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textViewStatus = findViewById(R.id.textViewStatus)
        btnScan = findViewById(R.id.btnScan)

        // Check that the device supports BLE.
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            textViewStatus.text = "BLE not supported on this device"
            finish()
        }

        // Initialize the Bluetooth adapter.
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Check for location permission (required for BLE scanning) and Android 12+ Bluetooth permissions.
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                1
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ),
                    101
                )
            }
        }

        bleScanner = bluetoothAdapter.bluetoothLeScanner

        btnScan.setOnClickListener {
            startBLEScan()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.d(TAG, "Bluetooth permissions granted")
            } else {
                textViewStatus.text = "Bluetooth permissions are required to scan"
            }
        }
    }

    // Start scanning for BLE devices.
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun startBLEScan() {
        // For Android 12+, ensure BLUETOOTH_SCAN permission is granted.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED) {
            textViewStatus.text = "BLUETOOTH_SCAN permission not granted"
            return
        }

        textViewStatus.text = "Scanning for Arduino..."
        Log.d(TAG, "Starting BLE Scan")

        // Automatically stop the scan after a set period.
        handler.postDelayed({
            bleScanner.stopScan(scanCallback)
            Log.d(TAG, "BLE Scan stopped")
            textViewStatus.text = "Scan stopped"
        }, SCAN_PERIOD)

        bleScanner.startScan(scanCallback)
    }


    // Callback for BLE scan results.
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                Log.d(TAG, "Found device: ${device.name} - ${device.address}")
                // Filter devices based on name (adjust as needed).
                if (device.name != null && device.name.contains("Arduino", ignoreCase = true)) {
                    bleScanner.stopScan(this)
                    textViewStatus.text = "Found ${device.name}, connecting..."
                    connectToDevice(device)
                }
            }
        }
    }

    // Connect to the selected BLE device.
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to device: ${device.name}")
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    // GATT callback to handle connection events, service discovery, and data notifications.
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when(newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server. Discovering services...")
                    runOnUiThread {
                        textViewStatus.text = "Connected, discovering services..."
                    }
                    bluetoothGatt?.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server.")
                    runOnUiThread {
                        textViewStatus.text = "Disconnected"
                    }
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered")
                val service = bluetoothGatt?.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                if (characteristic != null) {
                    // Enable notifications on the IMU data characteristic.
                    bluetoothGatt?.setCharacteristicNotification(characteristic, true)
                    val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    bluetoothGatt?.writeDescriptor(descriptor)
                    runOnUiThread {
                        textViewStatus.text = "Subscribed to IMU data"
                    }
                } else {
                    Log.e(TAG, "IMU characteristic not found")
                    runOnUiThread {
                        textViewStatus.text = "IMU characteristic not found"
                    }
                }
            } else {
                Log.e(TAG, "Service discovery failed with status: $status")
                runOnUiThread {
                    textViewStatus.text = "Service discovery failed"
                }
            }
        }

        // Called when the BLE characteristic sends new data.
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            if (characteristic?.uuid == CHARACTERISTIC_UUID) {
                val data = characteristic.value
                // Convert the raw byte array into a comma-separated string.
                val dataString = data.joinToString(separator = ",") { it.toString() }
                Log.d(TAG, "Received IMU data: $dataString")
                saveDataToFile(dataString)
            }
        }
    }

    // Save the received data into a CSV file in the app's external files directory.
    private fun saveDataToFile(data: String) {
        val file = File(getExternalFilesDir(null), "imu_data.csv")
        file.appendText("$data\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothGatt?.close()
    }
}
