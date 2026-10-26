package app.peloskin.data.bike.gen1

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import app.peloskin.domain.BikeMath
import app.peloskin.domain.BikeSample
import app.peloskin.data.bike.SensorSource
import app.peloskin.data.bike.ServiceBinding
import app.peloskin.data.bike.Tag

/**
 * Gen 1: Peloton's current sensor service (`com.onepeloton.affernetservice`, "IV1" interface).
 * We register a callback and the service pushes a reading each time the bike reports one.
 */
class Gen1SensorServiceSource(private val context: Context) : SensorSource {
    override val name = "Peloton sensor service"

    private var binding: ServiceBinding? = null
    private var service: IBinder? = null
    private var callback: Binder? = null

    override fun start(onSample: (BikeSample) -> Unit, onLost: () -> Unit): Boolean {
        val cb = Callback(onSample)
        callback = cb
        val b = ServiceBinding(
            context,
            Intent(Action).setPackage(Package),
            onConnected = { binder ->
                service = binder
                if (!transact(binder, Register, cb)) onLost()
            },
            onLost = onLost,
        )
        binding = b
        return b.bind()
    }

    override fun stop() {
        val binder = service
        val cb = callback
        if (binder != null && cb != null) transact(binder, Unregister, cb)
        binding?.unbind()
        binding = null
        service = null
        callback = null
    }

    private fun transact(binder: IBinder, code: Int, cb: Binder): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(Descriptor)
            data.writeStrongBinder(cb)
            data.writeString(ClientName)
            binder.transact(code, data, reply, 0).also { if (it) reply.readException() }
        } catch (e: Exception) {
            Log.w(Tag, "Sensor service call $code failed", e)
            false
        } finally {
            data.recycle()
            reply.recycle()
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
