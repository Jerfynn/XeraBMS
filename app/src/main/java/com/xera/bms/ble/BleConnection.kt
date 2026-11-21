package com.xera.bms.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

data class GattCharInfo(
    val service: UUID,
    val uuid: UUID,
    val canRead: Boolean,
    val canWrite: Boolean,
    val canWriteNoResponse: Boolean,
    val canNotify: Boolean,
    val canIndicate: Boolean,
) {
    val shortName: String get() = uuid.toString().substring(4, 8).uppercase()
    val writable: Boolean get() = canWrite || canWriteNoResponse
    val flags: String
        get() = buildList {
            if (canRead) add("read")
            if (canWrite) add("write")
            if (canWriteNoResponse) add("write-no-resp")
            if (canNotify) add("notify")
            if (canIndicate) add("indicate")
        }.joinToString(", ")
}

data class BleNotification(val characteristic: UUID, val value: ByteArray)

/**
 * One GATT link. Android only allows one outstanding GATT operation at a time, so every
 * operation goes through [op], which waits for its callback before the next one starts.
 */
@SuppressLint("MissingPermission")
class BleConnection(private val context: Context) {

    private var gatt: BluetoothGatt? = null
    private val opMutex = Mutex()

    @Volatile private var pending: CompletableDeferred<Int>? = null
    @Volatile private var connectWaiter: CompletableDeferred<Boolean>? = null
    @Volatile private var servicesWaiter: CompletableDeferred<Boolean>? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _notifications = MutableSharedFlow<BleNotification>(extraBufferCapacity = 512)
    val notifications: SharedFlow<BleNotification> = _notifications.asSharedFlow()

    @Volatile var mtu: Int = 23
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _connected.value = true
                connectWaiter?.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                _connected.value = false
                connectWaiter?.complete(false)
                servicesWaiter?.complete(false)
                pending?.complete(-1)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesWaiter?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            pending?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status)
        }

        // Android 13+ delivers the value directly.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            _notifications.tryEmit(BleNotification(c.uuid, value.copyOf()))
        }

        // Android 12 and older.
        @Deprecated("Used on Android 12 and older")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            val value = c.value ?: return
            _notifications.tryEmit(BleNotification(c.uuid, value.copyOf()))
        }
    }

    suspend fun connect(address: String, timeoutMs: Long = 15_000): Boolean {
        close()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val waiter = CompletableDeferred<Boolean>()
        connectWaiter = waiter
        mtu = 23
        val g = try {
            withContext(Dispatchers.Main) {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            }
        } catch (_: SecurityException) {
            null
        }
        if (g == null) {
            connectWaiter = null
            return false
        }
        gatt = g
        val ok = withTimeoutOrNull(timeoutMs) { waiter.await() } ?: false
        connectWaiter = null
        if (!ok) close()
        return ok
    }

    suspend fun discoverServices(): List<BluetoothGattService>? {
        val g = gatt ?: return null
        val waiter = CompletableDeferred<Boolean>()
        servicesWaiter = waiter
        val started = try {
            g.discoverServices()
        } catch (_: SecurityException) {
            false
        }
        if (!started) {
            servicesWaiter = null
            return null
        }
        val ok = withTimeoutOrNull(10_000) { waiter.await() } ?: false
        servicesWaiter = null
        return if (ok) g.services else null
    }

    suspend fun requestMtu(size: Int): Int {
        op(2_000) { it.requestMtu(size) }
        return mtu
    }

    fun characteristics(): List<GattCharInfo> {
        val g = gatt ?: return emptyList()
        return g.services.flatMap { s ->
            s.characteristics.map { c ->
                val p = c.properties
                GattCharInfo(
                    service = s.uuid,
                    uuid = c.uuid,
                    canRead = p and BluetoothGattCharacteristic.PROPERTY_READ != 0,
                    canWrite = p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0,
                    canWriteNoResponse = p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0,
                    canNotify = p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0,
                    canIndicate = p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0,
                )
            }
        }
    }

    fun hasCharacteristic(serviceUuid: String, charUuid: String): Boolean = find(serviceUuid, charUuid) != null

    suspend fun enableNotifications(serviceUuid: String, charUuid: String): Boolean {
        val c = find(serviceUuid, charUuid) ?: return false
        val g = gatt ?: return false
        try {
            g.setCharacteristicNotification(c, true)
        } catch (_: SecurityException) {
            return false
        }
        val cccd = c.getDescriptor(CCCD) ?: return true
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        return op(3_000) { gg ->
            if (Build.VERSION.SDK_INT >= 33) {
                gg.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                legacyWriteDescriptor(gg, cccd, value)
            }
        }
    }

    /** Writes [data], split into MTU-sized chunks. */
    suspend fun write(serviceUuid: String?, charUuid: String, data: ByteArray): Boolean {
        val c = find(serviceUuid, charUuid) ?: return false
        val p = c.properties
        val noResponse = p and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 &&
            p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val type = if (noResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val chunk = max(20, mtu - 3)
        var offset = 0
        var ok = true
        while (offset < data.size) {
            val part = data.copyOfRange(offset, min(data.size, offset + chunk))
            val sent = op(if (noResponse) 600 else 3_000, assumeSuccessOnTimeout = noResponse) { gg ->
                if (Build.VERSION.SDK_INT >= 33) {
                    gg.writeCharacteristic(c, part, type) == BluetoothStatusCodes.SUCCESS
                } else {
                    legacyWriteCharacteristic(gg, c, part, type)
                }
            }
            ok = ok && sent
            offset += chunk
        }
        return ok
    }

    fun close() {
        val g = gatt
        gatt = null
        _connected.value = false
        pending?.complete(-1)
        connectWaiter?.complete(false)
        servicesWaiter?.complete(false)
        try {
            g?.disconnect()
            g?.close()
        } catch (_: Exception) {
        }
    }

    private fun find(serviceUuid: String?, charUuid: String): BluetoothGattCharacteristic? {
        val g = gatt ?: return null
        val cu = runCatching { UUID.fromString(charUuid) }.getOrNull() ?: return null
        if (serviceUuid != null && serviceUuid.isNotEmpty()) {
            val su = runCatching { UUID.fromString(serviceUuid) }.getOrNull() ?: return null
            g.getService(su)?.getCharacteristic(cu)?.let { return it }
        }
        return g.services.firstNotNullOfOrNull { it.getCharacteristic(cu) }
    }

    private suspend fun op(
        timeoutMs: Long,
        assumeSuccessOnTimeout: Boolean = false,
        start: (BluetoothGatt) -> Boolean,
    ): Boolean = opMutex.withLock {
        val g = gatt ?: return@withLock false
        val deferred = CompletableDeferred<Int>()
        pending = deferred
        var started = false
        for (attempt in 0 until 3) {
            started = try {
                start(g)
            } catch (_: SecurityException) {
                false
            }
            if (started) break
            delay(60)
        }
        if (!started) {
            pending = null
            return@withLock false
        }
        val status = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pending = null
        when (status) {
            null -> assumeSuccessOnTimeout
            else -> status == BluetoothGatt.GATT_SUCCESS
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyWriteDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean {
        d.value = value
        return g.writeDescriptor(d)
    }

    @Suppress("DEPRECATION")
    private fun legacyWriteCharacteristic(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic,
        value: ByteArray,
        type: Int,
    ): Boolean {
        c.writeType = type
        c.value = value
        return g.writeCharacteristic(c)
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
