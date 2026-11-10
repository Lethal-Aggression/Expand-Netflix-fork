package com.d4cx.streamflix

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

object ImageLoader {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(5)

    private const val DISK_CACHE_MAX_BYTES = 28L * 1024L * 1024L
    @Volatile
    private var diskCacheDir: File? = null

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceIn(16384, 32768)

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    fun init(context: Context) {
        val dir = File(context.applicationContext.cacheDir, "img_cache")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        diskCacheDir = dir
        executor.execute { trimDiskCache() }
    }

    fun load(url: String?, imageView: ImageView, reqWidth: Int = 300, reqHeight: Int = 450, keepPreviousWhileLoading: Boolean = false) {
        if (url.isNullOrEmpty()) {
            imageView.tag = null
            imageView.setImageDrawable(null)
            return
        }

        imageView.tag = url

        val memKey = "${reqWidth}x${reqHeight}_$url"
        val cached = memoryCache.get(memKey)
        if (cached != null && !cached.isRecycled) {
            imageView.animate().cancel()
            imageView.alpha = 1f
            imageView.setImageBitmap(cached)
            return
        }

        if (!keepPreviousWhileLoading) {
            imageView.setImageDrawable(null)
        }

        executor.execute {
            try {
                val bitmap = loadFromDiskOrNetwork(url, reqWidth, reqHeight)
                if (bitmap != null) {
                    memoryCache.put(memKey, bitmap)
                    mainHandler.post {
                        if (imageView.tag == url) {
                            if (keepPreviousWhileLoading) {
                                imageView.setImageBitmap(bitmap)
                            } else {
                                imageView.alpha = 0f
                                imageView.setImageBitmap(bitmap)
                                imageView.animate().alpha(1f).setDuration(140).start()
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun preload(url: String?, reqWidth: Int = 960, reqHeight: Int = 540) {
        if (url.isNullOrEmpty()) return
        val memKey = "${reqWidth}x${reqHeight}_$url"
        if (memoryCache.get(memKey) != null) return
        executor.execute {
            try {
                loadFromDiskOrNetwork(url, reqWidth, reqHeight)?.let { bmp ->
                    memoryCache.put(memKey, bmp)
                }
            } catch (_: Exception) {}
        }
    }

    private fun loadFromDiskOrNetwork(urlString: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val diskFile = cacheFileFor(urlString)
        if (diskFile != null && diskFile.exists() && diskFile.length() > 128) {
            try {
                diskFile.setLastModified(System.currentTimeMillis())
                val bytes = diskFile.readBytes()
                decodeSampled(bytes, reqWidth, reqHeight)?.let { return it }
            } catch (_: Exception) {}
        }

        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 4500
            connection.readTimeout = 4500
            connection.instanceFollowRedirects = true
            connection.useCaches = true
            connection.setRequestProperty("User-Agent", "JewFlix-Native/2.0")

            if (connection.responseCode !in 200..299) {
                return null
            }

            val bytes = connection.inputStream.readBytes()
            if (bytes.isEmpty()) return null

            if (diskFile != null && bytes.size < 2 * 1024 * 1024) {
                try {
                    FileOutputStream(diskFile).use { it.write(bytes) }
                } catch (_: Exception) {}
            }

            decodeSampled(bytes, reqWidth, reqHeight)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun decodeSampled(bytes: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.RGB_565
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun cacheFileFor(url: String): File? {
        val dir = diskCacheDir ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }.take(32)
        return File(dir, "$hex.img")
    }

    private fun trimDiskCache() {
        val dir = diskCacheDir ?: return
        val files = dir.listFiles()?.toList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_CACHE_MAX_BYTES) return
        val sortedOldestFirst = files.sortedBy { it.lastModified() }
        for (f in sortedOldestFirst) {
            if (total <= DISK_CACHE_MAX_BYTES * 3 / 4) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}
