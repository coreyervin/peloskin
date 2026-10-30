package app.peloskin.data.hr

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import app.peloskin.domain.HeartRateMeasurement
import app.peloskin.domain.HeartRateMonitor
import java.util.UUID
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bluetooth heart rate monitors (the standard Heart Rate service, so any chest strap or
 * armband that works with the Peloton app). Settings scans for monitors to pair; the skin
 * calls [follow] with the riding rider's monitor, and this keeps it connected: it looks for
 * that monitor, connects, and goes back to looking if it drops.
 */
object HeartRateLink {
    private const val Tag = "PeloSkin"

    enum class Status { NoMonitor, Unavailable, Searching, Connected }

    /** [bpm] is null until a reading arrives, and again if readings stop for a few seconds. */
    data class State(
        val status: Status = Status.NoMonitor,
        val monitor: HeartRateMonitor? = null,
        val bpm: Int? = null,
        /** Why [Status.Unavailable]: Bluetooth off, or a missing permission. */
        val problem: String? = null,
    )

    /** A monitor found while pairing. */
    data class Found(val address: String, val name: String, val rssi: Int)

    /** A pretend monitor for the demo ride, on devices that aren't a Peloton. */
    val Demo = HeartRateMonitor("demo", "Demo heart rate monitor")

    private val HeartRateService = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val Measurement = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val ClientConfig = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Readings older than this don't count; straps send about one a second. */
    private const val StaleMs = 5_000L

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var following: HeartRateMonitor? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    private val adapter: BluetoothAdapter?
        get() = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    /** Permissions to ask for before scanning. On Android 11 and older that's location. */
    fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun granted(p: String) = appContext.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun canConnect() = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)

    /** Whether scanning will work; before Android 12 it also needs location turned on. */
    private fun canScan(): Boolean {
        if (!permissions().all(::granted)) return false
        if (Build.VERSION.SDK_INT >= 31) return true
        val lm = appContext.getSystemService(LocationManager::class.java)
        return Build.VERSION.SDK_INT < 28 || lm?.isLocationEnabled == true
    }

    /** What stops scanning right now, in words for the settings screen, or null if nothing does. */
    fun scanProblem(): String? = when {
        adapter == null -> "This tablet has no Bluetooth."
        !permissions().all(::granted) -> "PeloSkin needs permission to look for Bluetooth devices."
        adapter?.isEnabled != true -> "Bluetooth is off. Turn it on in the tablet's settings."
        !canScan() -> "Turn on Location in the tablet's settings. Android needs it to look for Bluetooth devices."
        else -> null
    }

    // ------------------------------------------------------------------ pairing

    /** Heart rate monitors nearby, strongest signal first, until the flow is cancelled. */
    @SuppressLint("MissingPermission")
    fun scan(includeDemo: Boolean = false): Flow<List<Found>> = callbackFlow {
        val found = linkedMapOf<String, Found>()
        if (includeDemo) found[Demo.address] = Found(Demo.address, Demo.name, -40)
        trySend(found.values.toList())
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || scanProblem() != null) {
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: "Heart rate monitor"
                found[result.device.address] = Found(result.device.address, name, result.rssi)
                trySend(found.values.sortedByDescending { it.rssi })
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(Tag, "Heart rate scan failed: $errorCode")
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(HeartRateService)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    // --------------------------------------------------------------- connection

    /**
     * Keeps [monitor] connected, dropping whichever one was connected before. Null
     * disconnects, for a rider without a monitor or when the skin stops.
     */
    fun follow(monitor: HeartRateMonitor?) {
        if (monitor == following && job?.isActive == true) return
        following = monitor
        job?.cancel()
        _state.value = State(if (monitor == null) Status.NoMonitor else Status.Searching, monitor)
        if (monitor == null) return
        job = scope.launch {
            if (monitor.address == Demo.address) return@launch runDemo(monitor)
            while (isActive) {
                val problem = when {
                    adapter == null -> "No Bluetooth"
                    !canConnect() -> "Needs Bluetooth permission"
                    adapter?.isEnabled != true -> "Bluetooth is off"
                    else -> null
                }
                if (problem != null) {
                    _state.value = State(Status.Unavailable, monitor, problem = problem)
                    delay(5_000)
                    continue
                }
                _state.value = State(Status.Searching, monitor)
                runCatching { connectOnce(monitor) }.onFailure { Log.w(Tag, "Heart rate monitor", it) }
                _state.value = State(Status.Searching, monitor)
                delay(3_000)
            }
        }
    }

    private sealed interface Event {
        object Connected : Event
        object Disconnected : Event
        object Discovered : Event
        class Beat(val bpm: Int?) : Event
    }

    /** Finds, connects to and reads from [monitor] until it disconnects. */
    @SuppressLint("MissingPermission")
    private suspend fun connectOnce(monitor: HeartRateMonitor) {
        val bt = adapter ?: return
        // With scan permission, look for this exact monitor first: connecting to a monitor
        // that's advertising is quick and reliable. Without it, let Android wait for it.
        val scanning = canScan()
        val device: BluetoothDevice = if (scanning) {
            findNearby(monitor.address) ?: return
        } else {
            bt.getRemoteDevice(monitor.address)
        }
        val events = Channel<Event>(Channel.UNLIMITED)
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                events.trySend(if (newState == BluetoothProfile.STATE_CONNECTED) Event.Connected else Event.Disconnected)
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                events.trySend(if (status == BluetoothGatt.GATT_SUCCESS) Event.Discovered else Event.Disconnected)
            }

            @Deprecated("Used up to Android 12")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, c: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                if (c.uuid == Measurement) events.trySend(Event.Beat(HeartRateMeasurement.bpm(c.value ?: return)))
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                if (c.uuid == Measurement) events.trySend(Event.Beat(HeartRateMeasurement.bpm(value)))
            }
        }
        val gatt = device.connectGatt(appContext, !scanning, callback, BluetoothDevice.TRANSPORT_LE) ?: return
        try {
            // A monitor that was just seen should connect within seconds.
            val first = if (scanning) withTimeoutOrNull(20_000) { events.receive() } else events.receive()
            if (first != Event.Connected) return
            gatt.discoverServices()
            var lastBeat = 0L
            while (true) {
                val e = withTimeoutOrNull(StaleMs) { events.receive() }
                when (e) {
                    null -> if (System.currentTimeMillis() - lastBeat > StaleMs) _state.value = _state.value.copy(bpm = null)
                    Event.Disconnected -> return
                    Event.Discovered -> {
                        if (!subscribe(gatt)) return
                        _state.value = State(Status.Connected, monitor)
                    }
                    is Event.Beat -> {
                        lastBeat = System.currentTimeMillis()
                        _state.value = State(Status.Connected, monitor, e.bpm)
                    }
                    Event.Connected -> {}
                }
            }
        } finally {
            runCatching { gatt.disconnect(); gatt.close() }
        }
    }

    /** Scans for up to 15 seconds for the monitor with this address. */
    @SuppressLint("MissingPermission")
    private suspend fun findNearby(address: String): BluetoothDevice? {
        val scanner = adapter?.bluetoothLeScanner ?: return null
        val result = Channel<BluetoothDevice>(1)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) { result.trySend(r.device) }
        }
        val filter = ScanFilter.Builder().setDeviceAddress(address).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        return try {
            scanner.startScan(listOf(filter), settings, callback)
            withTimeoutOrNull(15_000) { result.receive() }
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    /** Turns on heart rate notifications. @return false if this isn't a heart rate monitor. */
    @SuppressLint("MissingPermission")
    private fun subscribe(gatt: BluetoothGatt): Boolean {
        val c = gatt.getService(HeartRateService)?.getCharacteristic(Measurement) ?: return false
        gatt.setCharacteristicNotification(c, true)
        val config = c.getDescriptor(ClientConfig) ?: return true
        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(config, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            config.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(config)
        }
        return true
    }

    /** A made-up heart rate that drifts like a real one, for the demo ride. */
    private suspend fun runDemo(monitor: HeartRateMonitor) {
        delay(1_500)
        var t = 0.0
        while (true) {
            val bpm = (128 + 18 * sin(t / 40) + 6 * sin(t / 7)).toInt()
            _state.value = State(Status.Connected, monitor, bpm)
            t++
            delay(1_000)
        }
    }
}
