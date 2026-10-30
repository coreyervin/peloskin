package app.peloskin.data.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Riders' profile pictures: one small square JPEG per rider, named by rider id, in the app's
 * own storage and mirrored next to the backup file so a reinstall gets them back.
 */
object RiderPhotos {
    private const val Tag = "PeloSkin"

    /** Pictures are stored this many pixels square; plenty for a bubble on a 1080p screen. */
    const val Size = 320

    private lateinit var appDir: File

    private val _photos = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    /** Loaded pictures by rider id. Riders without one aren't in the map. */
    val photos: StateFlow<Map<String, Bitmap>> = _photos

    private val io = Executors.newSingleThreadExecutor()

    private val backupDir: File get() = File(SettingsStore.backupFile.parentFile, "photos")

    @Synchronized
    fun init(context: Context) {
        if (::appDir.isInitialized) return
        appDir = File(context.filesDir, "photos")
        io.execute { reload() }
    }

    /**
     * Reads a picked picture, upright and cropped to a centered square. Runs on the caller's
     * thread, so call it off the main thread. @return null if it isn't a readable image.
     */
    fun decode(context: Context, uri: Uri): Bitmap? = try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            // Decode at no more than about twice the stored size, so a phone photo doesn't use 50 MB.
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= Size * 2) sample *= 2
            val raw = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
            val rotation = resolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0
            raw?.let { square(it, rotation) }
        }
    } catch (e: Exception) {
        Log.w(Tag, "Could not read picture $uri", e)
        null
    }

    /** Crops a picture (say a camera thumbnail) to a centered square of [Size]. */
    fun square(source: Bitmap, rotation: Int = 0): Bitmap {
        val side = minOf(source.width, source.height)
        val matrix = Matrix().apply {
            val scale = Size.toFloat() / side
            postScale(scale, scale)
            postRotate(rotation.toFloat())
        }
        return Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side, matrix, true)
    }

    private fun exifRotation(stream: java.io.InputStream): Int =
        when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    /** Sets a rider's picture, or removes it when [photo] is null. */
    fun set(riderId: String, photo: Bitmap?) {
        _photos.value = if (photo == null) _photos.value - riderId else _photos.value + (riderId to photo)
        io.execute {
            val dirs = listOfNotNull(appDir, backupDir.takeIf { SettingsStore.backupReady() })
            if (photo == null) {
                dirs.forEach { File(it, "$riderId.jpg").delete() }
            } else {
                val bytes = ByteArrayOutputStream().also { photo.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
                dirs.forEach { dir ->
                    runCatching { SettingsStore.writeBytes(File(dir, "$riderId.jpg"), bytes) }
                        .onFailure { Log.w(Tag, "Could not save picture in ${dir.path}", it) }
                }
            }
        }
    }

    /**
     * Brings the app's pictures and the backup's in line once the backup can be used: pictures
     * only in the backup come back (a reinstall), and ones only here are backed up.
     * @param replace take the backup's picture even where there's one here, for a restore.
     */
    fun syncWithBackup(replace: Boolean = false) = io.execute {
        if (!SettingsStore.backupReady()) return@execute
        backupDir.listFiles { f -> f.extension == "jpg" }?.forEach { f ->
            val mine = File(appDir, f.name)
            if (replace || !mine.exists()) runCatching { SettingsStore.writeBytes(mine, f.readBytes()) }
        }
        appDir.listFiles { f -> f.extension == "jpg" }?.forEach { f ->
            val theirs = File(backupDir, f.name)
            if (!theirs.exists()) runCatching { SettingsStore.writeBytes(theirs, f.readBytes()) }
        }
        reload()
    }

    /** Deletes pictures of riders that no longer exist, here and in the backup. */
    fun prune(riderIds: Set<String>) = io.execute {
        val dirs = listOfNotNull(appDir, backupDir.takeIf { SettingsStore.backupReady() })
        dirs.forEach { dir -> dir.listFiles()?.forEach { f -> if (f.nameWithoutExtension !in riderIds) f.delete() } }
        _photos.value = _photos.value.filterKeys { it in riderIds }
    }

    private fun reload() {
        _photos.value = appDir.listFiles { f -> f.extension == "jpg" }.orEmpty().mapNotNull { f ->
            BitmapFactory.decodeFile(f.path)?.let { f.nameWithoutExtension to it }
        }.toMap()
    }
}
