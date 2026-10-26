package app.peloskin.data.bike.gen1

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import app.peloskin.data.bike.SensorSource
import app.peloskin.data.bike.ServiceBinding
import app.peloskin.data.bike.Tag
import app.peloskin.domain.BikeMath
import app.peloskin.domain.BikeSample
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Gen 1: Peloton's current sensor service (`com.onepeloton.affernetservice`, "IV1" interface).
 * We register a callback and the service pushes a reading each time the bike reports one.
 */
class Gen1SensorServiceSource(private val context: Context) : SensorSource {
    override val name = "Peloton sensor service"

    private var binding: ServiceBinding? = null
    @Volatile private var service: IBinder? = null
    private var callback: Binder? = null

    // Registering waits for the service to answer, and a wedged service could hang it,
    // so that call runs here instead of on the main thread.
    private var calls: ExecutorService? = null

    override fun start(onSample: (BikeSample) -> Unit, onLost: () -> Unit): Boolean {
        val cb = Callback(onSample)
        callback = cb
        val executor = Executors.newSingleThreadExecutor().also { calls = it }
        val b = ServiceBinding(
            context,
            Intent(Action).setPackage(Package),
            onConnected = { binder ->
                service = binder
                executor.execute { if (!transact(binder, Register, cb, oneWay = false)) onLost() }
            },
            onLost = onLost,
        )
        binding = b
        return b.bind()
    }

    override fun stop() {
        val binder = service
        val cb = callback
        // One-way, so stopping never waits on the service.
        if (binder != null && cb != null) transact(binder, Unregister, cb, oneWay = true)
        calls?.shutdownNow()
        calls = null
        binding?.unbind()
        binding = null
        service = null
        callback = null
    }

    private fun transact(binder: IBinder, code: Int, cb: Binder, oneWay: Boolean): Boolean {
        val data = Parcel.obtain()
        val reply = if (oneWay) null else Parcel.obtain()
        return try {
            data.writeInterfaceToken(Descriptor)
            data.writeStrongBinder(cb)
            data.writeString(ClientName)
            val flags = if (oneWay) IBinder.FLAG_ONEWAY else 0
            binder.transact(code, data, reply, flags).also { if (it) reply?.readException() }
        } catch (e: Exception) {
            Log.w(Tag, "Sensor service call $code failed", e)
            false
        } finally {
            data.recycle()
            reply?.recycle()
        }
    }

    private class Callback(private val onSample: (BikeSample) -> Unit) : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != OnSensorData) return super.onTransact(code, data, reply, flags)
            return try {
                data.enforceInterface(CallbackDescriptor)
                if (data.readInt() != 0) {
                    // Bike data record: rpm, power (centiwatts), stepper position, load cell,
                    // current resistance, target resistance.
                    val rpm = data.readLong()
                    val centiwatts = data.readLong()
                    data.readLong()
                    data.readLong()
                    val resistance = data.readInt()
                    onSample(BikeMath.sample(centiwatts / 100f, rpm.toFloat(), resistance.toFloat()))
                }
                true
            } catch (e: Exception) {
                Log.w(Tag, "Unreadable sensor data", e)
                false
            }
        }
    }

    private companion object {
        const val Package = "com.onepeloton.affernetservice"
        const val Action = "com.onepeloton.affernetservice.IV1Interface"
        const val Descriptor = "com.onepeloton.affernetservice.IV1Interface"
        const val CallbackDescriptor = "com.onepeloton.affernetservice.IV1Callback"
        const val Register = 1
        const val Unregister = 2
        const val OnSensorData = 1
        const val ClientName = "PeloSkin"
    }
}
