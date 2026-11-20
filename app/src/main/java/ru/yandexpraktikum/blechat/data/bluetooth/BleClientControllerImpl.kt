package ru.yandexpraktikum.blechat.data.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.yandexpraktikum.blechat.domain.bluetooth.BleClientController
import ru.yandexpraktikum.blechat.domain.model.ScannedBluetoothDevice
import ru.yandexpraktikum.blechat.presentation.notifications.NotificationsHelper
import ru.yandexpraktikum.blechat.utils.checkForConnectPermission
import ru.yandexpraktikum.blechat.utils.notifyCharUUID
import ru.yandexpraktikum.blechat.utils.serviceUUID
import ru.yandexpraktikum.blechat.utils.writeCharUUID
import java.nio.charset.Charset
import javax.inject.Inject

class BleClientControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val locationManager: LocationManager,
    private val viewModelScope: CoroutineScope,
    private val notificationsHelper: NotificationsHelper,
) : BleClientController {

    private var currentGatt: BluetoothGatt? = null

    private val bleScanner by lazy {
        bluetoothAdapter?.bluetoothLeScanner
    }

    private val _isBluetoothEnabled = MutableStateFlow(false)
    override val isBluetoothEnabled: StateFlow<Boolean>
        get() = _isBluetoothEnabled.asStateFlow()

    private val _isLocationEnabled = MutableStateFlow(false)
    override val isLocationEnabled: StateFlow<Boolean>
        get() = _isLocationEnabled.asStateFlow()

    init {
        updateBluetoothState()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            updateLocationState()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    context.checkForConnectPermission {
                        gatt.discoverServices()
                    }
                    _scannedDevices.update { devices ->
                        devices.map { scannedDevice ->
                            if (scannedDevice.address == gatt.device.address) {
                                scannedDevice.copy(isConnected = true)
                            } else {
                                scannedDevice
                            }
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _scannedDevices.update { devices ->
                        devices.map { scannedDevice ->
                            if (scannedDevice.address == gatt.device.address) {
                                scannedDevice.copy(isConnected = false)
                            } else {
                                scannedDevice
                            }
                        }
                    }
                    closeConnection()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                val service = gatt.getService(serviceUUID)
                val characteristic = service?.getCharacteristic(notifyCharUUID)
                if (characteristic != null) {
                    context.checkForConnectPermission {
                        gatt.setCharacteristicNotification(characteristic, true)
                    }
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
            val messageText = String(characteristic.value ?: byteArrayOf(), Charset.defaultCharset())
            viewModelScope.launch {
                _scannedDevices.update { devices ->
                    devices.map { scannedDevice ->
                        if (scannedDevice.address == gatt.device.address) {
                            scannedDevice.addRemoteMessage(
                                text = messageText,
                                senderAddress = gatt.device.address
                            )
                        } else {
                            scannedDevice
                        }
                    }
                }
                var title = gatt.device.address
                context.checkForConnectPermission {
                    gatt.device.name?.let { name -> title = name }
                }
                notificationsHelper.notifyOnMessageReceived(title, messageText)
            }
        }
    }

    override fun updateBluetoothState() {
        try {
            _isBluetoothEnabled.value = bluetoothAdapter?.isEnabled == true
        } catch (e: Exception) {
            Log.e("BLE", "Failed to initialize Bluetooth state", e)
        }
    }

    override fun updateLocationState() {
        try {
            _isLocationEnabled.value = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            Log.e("BLE", "Failed to initialize Location state", e)
        }
    }

    private val _scannedDevices = MutableStateFlow<List<ScannedBluetoothDevice>>(emptyList())
    override val scannedDevices: StateFlow<List<ScannedBluetoothDevice>>
        get() = _scannedDevices.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            context.checkForConnectPermission {
                val bluetoothDevice = ScannedBluetoothDevice(
                    name = device.name,
                    address = device.address
                )
                _scannedDevices.update { devices ->
                    if (devices.none { it.address == bluetoothDevice.address }) {
                        devices + bluetoothDevice
                    } else devices
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e("BLE", "Scan failed with error code: $errorCode")
        }
    }

    override fun startScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        } else {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_ADMIN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }
        bleScanner?.startScan(scanCallback)
    }

    override fun stopScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        } else {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_ADMIN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }
        bleScanner?.stopScan(scanCallback)
        _scannedDevices.update {
            it.filter { device ->
                device.isConnected
            }
        }
    }

    override fun connectToDevice(device: ScannedBluetoothDevice): Boolean {
        context.checkForConnectPermission {
            val remoteDevice = bluetoothAdapter?.getRemoteDevice(device.address)
            currentGatt = remoteDevice?.connectGatt(context, false, gattCallback)
        }
        return currentGatt != null
    }

    @SuppressLint("HardwareIds")
    override suspend fun sendMessage(message: String, deviceAddress: String): Boolean {
        val service = currentGatt?.getService(serviceUUID) ?: return false
        val characteristic = service.getCharacteristic(writeCharUUID) ?: return false

        @Suppress("DEPRECATION")
        characteristic.value = message.toByteArray(Charset.defaultCharset())

        var success = false
        context.checkForConnectPermission {
            @Suppress("DEPRECATION")
            success = currentGatt?.writeCharacteristic(characteristic) == true
        }

        if (success) {
            _scannedDevices.update { devices ->
                devices.map { scannedDevice ->
                    if (scannedDevice.address == deviceAddress) {
                        scannedDevice.addLocalMessage(
                            text = message,
                            localAddress = bluetoothAdapter?.address
                        )
                    } else {
                        scannedDevice
                    }
                }
            }
        }
        return success
    }

    override fun closeConnection() {
        context.checkForConnectPermission {
            currentGatt?.disconnect()
            currentGatt?.close()
        }
        currentGatt = null
    }

    override fun release() {
        closeConnection()
    }
}