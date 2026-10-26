package app.peloskin.data.bike.gen1

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.os.Messenger
import android.util.Log
import app.peloskin.domain.BikeMath
import app.peloskin.domain.BikeSample
import app.peloskin.data.bike.SensorSource
import app.peloskin.data.bike.ServiceBinding
import app.peloskin.data.bike.Tag

/**
 * Gen 1: Peloton's older sensor app (`com.peloton.service.SensorData`). We ask it for repeating
 * cadence, power and resistance updates; each arrives as its own message.
 */
class Gen1SensorAppSource(private val context: Context) : SensorSource {
    override val name = "Peloton sensor app"

    private var binding: ServiceBinding? = null
    private var thread: HandlerThread? = null

    override fun start(onSample: (BikeSample) -> Unit, onLost: () -> Unit): Boolean {
        val t = HandlerThread("PeloSkinSensors").also { it.start() }
        thread = t
        var power = 0f
        var cadence = 0f
        var resistance = 0f
        val handler = object : Handler(t.looper) {
            override fun handleMessage(msg: Message) {
                val reply = msg.data ?: return
                if (msg.what < 0 || reply.getString(KeyRaw) == TimedOut || !reply.containsKey(KeyValue)) return
                val value = reply.getFloat(KeyValue)
                when (msg.what) {
                    GetCadence -> cadence = value
                    GetResistance -> resistance = value
                    // Power is the last of the three to change, so publish on it.
                    GetPower -> { power = value / 100f; onSample(BikeMath.sample(power, cadence, resistance)) }
                }
            }
        }
        val replyTo = Messenger(handler)
        val b = ServiceBinding(
            context,
            Intent(Action).setPackage(Package),
            onConnected = { binder ->
                try {
                    val messenger = Messenger(binder)
                    for (what in listOf(GetCadence, GetPower, GetResistance)) {
                        messenger.send(Message.obtain().apply {
                            this.what = what
                            data = Bundle().apply { putString(KeyRequestId, "peloskin-$what") }
                            this.replyTo = replyTo
                        })
                    }
                } catch (e: Exception) {
                    Log.w(Tag, "Sensor app request failed", e)
                    onLost()
                }
            },
            onLost = onLost,
        )
        binding = b
        return b.bind()
    }

    override fun stop() {
        binding?.unbind()
        binding = null
        thread?.quitSafely()
        thread = null
    }

    private companion object {
        const val Package = "com.peloton.service.SensorData"
        const val Action = "android.intent.action.peloton.SensorData"
        const val GetCadence = 1
        const val GetPower = 2
        const val GetResistance = 3
        const val KeyRequestId = "requestId"
        const val KeyValue = "data"
        const val KeyRaw = "responseHexString"
        const val TimedOut = "TIME_OUT"
    }
}
