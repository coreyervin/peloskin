package app.peloskin.data.bike

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import app.peloskin.domain.BikeSample

/**
 * A way of reading the bike from the Peloton software on the tablet.
 * [onSample] and [onLost] may be called from any thread.
 */
interface SensorSource {
    val name: String
    /** @return false if the Peloton service isn't on this tablet or refused the binding. */
    fun start(onSample: (BikeSample) -> Unit, onLost: () -> Unit): Boolean
    fun stop()
}

internal const val Tag = "PeloSkin"

/** Binds a Peloton service and reports when it connects or goes away. */
internal class ServiceBinding(
    private val context: Context,
    private val intent: Intent,
    private val onConnected: (IBinder) -> Unit,
    private val onLost: () -> Unit,
) : ServiceConnection {
    private var bound = false

    fun bind(): Boolean {
        bound = try {
            context.bindService(intent, this, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Log.w(Tag, "Not allowed to bind ${intent.`package`}", e)
            false
        }
        if (!bound) runCatching { context.unbindService(this) }
        return bound
    }

    fun unbind() {
        if (bound) runCatching { context.unbindService(this) }
        bound = false
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        if (service == null) onLost() else onConnected(service)
    }

    override fun onServiceDisconnected(name: ComponentName?) = onLost()
    override fun onBindingDied(name: ComponentName?) = onLost()
    override fun onNullBinding(name: ComponentName?) = onLost()
}
