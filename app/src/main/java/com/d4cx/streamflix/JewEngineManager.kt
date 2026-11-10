package com.d4cx.streamflix

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object JewEngineManager {
    private const val TAG = "JewEngineManager"
    const val ENGINE_PORT = 8811
    val HEALTH_URL = "http://127.0.0.1:$ENGINE_PORT/health"

    private val executor = Executors.newCachedThreadPool()
    private val isStarting = AtomicBoolean(false)
    private var engineProcess: Process? = null

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            stopEngine()
        })
    }

    @Synchronized
    fun ensureRunning(context: Context): Boolean {
        if (isEngineHealthy()) {
            return true
        }

        if (isStarting.get()) {
            for (i in 0 until 30) {
                if (isEngineHealthy()) return true
                try { Thread.sleep(100) } catch (_: InterruptedException) {}
            }
            return isEngineHealthy()
        }

        isStarting.set(true)
        try {
            val binary = getEngineBinary(context) ?: run {
                Log.e(TAG, "Failed to locate libjewflix-engine.so in nativeLibraryDir.")
                return false
            }

            try {
                val oldCache = File(context.filesDir, "torrent_cache")
                if (oldCache.exists()) {
                    oldCache.deleteRecursively()
                }
            } catch (_: Exception) {}

            val cacheDir = File(context.cacheDir, "torrent_cache").apply { mkdirs() }
            val maxCacheMB = SettingsManager.getMaxCacheMB(context)
            Log.i(TAG, "Launching JewEngine: ${binary.absolutePath} (rolling cache cap: ${maxCacheMB}MB)")

            val pb = ProcessBuilder(
                binary.absolutePath,
                "-port=$ENGINE_PORT",
                "-cacheDir=${cacheDir.absolutePath}",
                "-maxCacheMB=$maxCacheMB"
            )
            pb.directory(context.cacheDir)
            pb.redirectErrorStream(true)

            val proc = pb.start()
            engineProcess = proc

            Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(proc.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.d("JewEngineNative", line ?: "")
                    }
                } catch (_: Exception) {}
            }.apply {
                isDaemon = true
                name = "JewEngine-LogDrainer"
                start()
            }

            for (i in 0 until 35) {
                try { Thread.sleep(100) } catch (_: InterruptedException) {}
                if (isEngineHealthy()) {
                    Log.i(TAG, "JewEngine v2.0 ready on 127.0.0.1:$ENGINE_PORT (${(i + 1) * 100}ms)")
                    return true
                }
            }

            Log.e(TAG, "JewEngine started but failed to answer /health in time.")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error starting JewEngine: ${e.message}", e)
            return false
        } finally {
            isStarting.set(false)
        }
    }

    fun isEngineHealthy(): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(HEALTH_URL)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 300
            conn.readTimeout = 300
            conn.requestMethod = "GET"
            conn.responseCode == 200
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    fun prewarmStream(context: Context, stream: StreamItem) {
        if (!SettingsManager.isPrewarmEnabled(context)) return
        val hash = stream.infoHash ?: return
        val idx = stream.fileIdx ?: 0
        executor.execute {
            if (!ensureRunning(context)) return@execute
            try {
                val sb = StringBuilder("http://127.0.0.1:$ENGINE_PORT/prewarm?hash=$hash&idx=$idx")
                if (stream.sources.isNotEmpty()) {
                    val trackers = stream.sources.take(10).joinToString("&") { src ->
                        "tr=" + URLEncoder.encode(src, "UTF-8")
                    }
                    sb.append("&").append(trackers)
                }
                val conn = URL(sb.toString()).openConnection() as HttpURLConnection
                conn.connectTimeout = 800
                conn.readTimeout = 800
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    fun updateMaxCacheMB(mb: Int) {
        executor.execute {
            try {
                val url = URL("http://127.0.0.1:$ENGINE_PORT/config?maxCacheMB=$mb")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 800
                conn.readTimeout = 800
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    fun fetchEngineStats(): EngineStatsSnapshot? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("http://127.0.0.1:$ENGINE_PORT/stats")
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 500
            conn.readTimeout = 500
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val o = JSONObject(body)
                EngineStatsSnapshot(
                    cacheBytes = o.optLong("cache_bytes", 0L),
                    cacheCapBytes = o.optLong("cache_cap_bytes", 400L * 1024 * 1024),
                    cacheItems = o.optInt("cache_items", 0),
                    activeTorrents = o.optInt("active_torrents", 0),
                    activeStreams = o.optInt("active_streams", 0)
                )
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun dropTorrent(infoHash: String) {
        executor.execute {
            try {
                val url = URL("http://127.0.0.1:$ENGINE_PORT/drop?hash=$infoHash")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 500
                conn.readTimeout = 500
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    fun wipeCache(context: Context? = null, onComplete: (() -> Unit)? = null) {
        executor.execute {
            try {
                val url = URL("http://127.0.0.1:$ENGINE_PORT/wipe")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                conn.requestMethod = "GET"
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
            try {
                context?.let { ctx ->
                    val cacheDir = File(ctx.cacheDir, "torrent_cache")
                    if (cacheDir.exists()) {
                        cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                    }
                }
            } catch (_: Exception) {}
            onComplete?.invoke()
        }
    }

    private fun getEngineBinary(context: Context): File? {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val nativeLib = File(nativeLibDir, "libjewflix-engine.so")
        if (nativeLib.exists()) {
            if (!nativeLib.canExecute()) {
                nativeLib.setExecutable(true, false)
            }
            return nativeLib
        }
        return null
    }

    fun stopEngine() {
        try {
            engineProcess?.destroy()
            engineProcess = null
        } catch (_: Exception) {}
    }
}
