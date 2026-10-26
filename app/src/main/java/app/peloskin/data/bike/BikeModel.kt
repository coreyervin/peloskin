package app.peloskin.data.bike

import android.content.Context
import android.provider.Settings
import app.peloskin.data.bike.gen1.Gen1SensorAppSource
import app.peloskin.data.bike.gen1.Gen1SensorServiceSource

/**
 * Peloton machines PeloSkin knows about. To support a new one, add a package next to
 * `gen1/` with its [SensorSource]s and list them in [sources].
 */
enum class BikeModel(val displayName: String) {
    Gen1("Peloton Bike"),
    BikePlus("Peloton Bike+"),
    Other("This Peloton"),
    NotPeloton("This device");

    /** Readers to try, in order. Empty means the model isn't supported yet. */
    fun sources(context: Context): List<SensorSource> = when (this) {
        Gen1 -> listOf(Gen1SensorServiceSource(context), Gen1SensorAppSource(context))
        BikePlus, Other, NotPeloton -> emptyList()
    }

    companion object {
        /**
         * Peloton's software records which machine the tablet is attached to in the global
         * setting `peloton_platform` (`v1` Gen 1 Bike, `titan` Bike+, `prism` Tread, `caesar` Row).
         */
        fun detect(context: Context): BikeModel {
            val platform = try {
                Settings.Global.getString(context.contentResolver, "peloton_platform")
            } catch (e: Exception) {
                null
            }?.trim()?.lowercase()
            return when {
                platform == "v1" -> Gen1
                platform == "titan" -> BikePlus
                platform != null -> Other
                isInstalled(context, "com.onepeloton.affernetservice") ||
                    isInstalled(context, "com.peloton.service.SensorData") -> Gen1
                else -> NotPeloton
            }
        }

        private fun isInstalled(context: Context, pkg: String) = try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
    }
}
