package dev.liteluncher.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import dev.liteluncher.data.BackupIcon
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** Shared by every icon surface; decoding never holds the cache lock. */
internal class IconBitmapLoader(private val context: Context) {
    val dispatcher = Dispatchers.IO.limitedParallelism(2)
    private val lock = Any()
    private var generation = 0L
    private val loading = mutableMapOf<Any, FutureTask<Bitmap?>>()
    private val bitmaps = object : LruCache<Any, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: Any, value: Bitmap): Int =
            value.byteCount + ((key as? BackupIcon)?.bitmap?.length ?: 0) * 2
    }
    private data class CustomIcon(val uri: String)

    fun cached(key: Any): Bitmap? = bitmaps.get(key)

    fun load(key: Any, decode: () -> Bitmap?): Bitmap? {
        cached(key)?.let { return it }
        val task = synchronized(lock) {
            cached(key)?.let { return it }
            loading.getOrPut(key) {
                val revision = generation
                FutureTask {
                    val bitmap = decode()?.apply { prepareToDraw() }
                    synchronized(lock) {
                        if (bitmap != null && revision == generation) bitmaps.put(key, bitmap)
                    }
                    bitmap
                }
            }
        }
        return try {
            task.run()
            task.get()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (failure: ExecutionException) {
            null
        } finally {
            synchronized(lock) {
                if (task.isDone && loading[key] === task) loading.remove(key)
            }
        }
    }

    fun cachedCustom(uri: String): Bitmap? = cached(CustomIcon(uri))

    fun custom(uri: String): Bitmap? {
        if (!uri.startsWith("content://")) return null
        return load(CustomIcon(uri)) {
            runCatching {
                val parsed = Uri.parse(uri)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                require(bounds.outWidth > 0 && bounds.outHeight > 0)
                var sample = 1
                while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
                context.contentResolver.openInputStream(parsed)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }.getOrNull()
        }
    }

    fun backup(icon: BackupIcon): Bitmap? = load(icon) {
        runCatching {
            if (icon.bitmap.isNotEmpty()) {
                val bytes = Base64.decode(icon.bitmap, Base64.DEFAULT)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
                var sample = 1
                while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            } else if (icon.packageName.isNotBlank() && icon.resourceName.isNotBlank()) {
                val resources = context.packageManager.getResourcesForApplication(icon.packageName)
                val type = icon.resourceName.substringBeforeLast('/', "drawable").substringAfter(':')
                val name = icon.resourceName.substringAfterLast('/')
                val id = resources.getIdentifier(name, type, icon.packageName).takeIf { it != 0 }
                    ?: resources.getIdentifier(name, "mipmap", icon.packageName)
                if (id == 0) null else resources.getDrawable(id, null).toBitmap(168, 168)
            } else null
        }.getOrNull()
    }

    /** Leave masking to the launcher so the device's icon mask is not baked in first. */
    fun adaptive(drawable: Drawable): Bitmap {
        val size = 168
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (drawable is AdaptiveIconDrawable) {
            val inset = (size * AdaptiveIconDrawable.getExtraInsetFraction()).toInt()
            listOf(drawable.background, drawable.foreground).forEach { layer ->
                layer?.apply {
                    setBounds(-inset, -inset, size + inset, size + inset)
                    draw(canvas)
                }
            }
        } else {
            canvas.drawColor(Color.WHITE)
            val inset = (size * .12f).toInt()
            drawable.setBounds(inset, inset, size - inset, size - inset)
            drawable.draw(canvas)
        }
        return bitmap
    }

    fun clear() {
        synchronized(lock) {
            generation++
            loading.clear()
            bitmaps.evictAll()
        }
    }
}
