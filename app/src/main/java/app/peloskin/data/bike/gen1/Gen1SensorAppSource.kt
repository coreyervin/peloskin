package app.peloskin.data.bike.gen1

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.os.Messenger
import android.util.Log
import app.peloskin.data.bike.SensorSource
import app.peloskin.data.bike.ServiceBinding
import app.peloskin.data.bike.Tag
import app.peloskin.domain.BikeMath
import app.peloskin.domain.BikeSample

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
        // One reply handler per request, so a reply is matched to its metric by which
        // handler got it, not by the reply's own fields.
        fun replies(what: Int) = Messenger(object : Handler(t.looper) {
            private var latest = 0L

            override fun handleMessage(msg: Message) {
                val reply = msg.data ?: return
                if (msg.what < 0 || reply.getString(KeyRaw) == TimedOut || !reply.containsKey(KeyValue)) return
                // Replies can arrive out of order; skip any older than one we've used.
                val time = reply.getLong(KeyTime)
                if (time < latest) return
                latest = time
                val value = reply.getFloat(KeyValue)
                when (what) {
                    GetCadence -> cadence = value
                    GetResistance -> resistance = value
                    // Publish on each power reading, with the latest cadence and resistance.
                    GetPower -> { power = value / 100f; onSample(BikeMath.sample(power, cadence, resistance)) }
                }
            }
        })
        val requests = listOf(GetCadence, GetPower, GetResistance).associateWith(::replies)
        val b = ServiceBinding(
            context,
            Intent(Action).setPackage(Package),
            onConnected = { binder ->
                try {
                    val messenger = Messenger(binder)
                    for ((what, replyTo) in requests) {
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
        const val KeyTime = "time"
        const val KeyRaw = "responseHexString"
        const val TimedOut = "TIME_OUT"
    }
}
