package com.d4cx.streamflix

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.Executors

object JewEngineManager {
    const val ENGINE_PORT = 8811
    val HEALTH_URL = "http://127.0.0.1:$ENGINE_PORT/health"
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var token = ""
    @Volatile var playbackSession = ""
        private set
    private var process: Process? = null

    fun initialize(context: Context) {
        if (token.isNotEmpty()) return
        synchronized(this) {
            if (token.isEmpty()) {
                val prefs = context.applicationContext.getSharedPreferences("engine_private", Context.MODE_PRIVATE)
                token = prefs.getString("token", null) ?: (UUID.randomUUID().toString() + UUID.randomUUID()).also {
                    prefs.edit().putString("token", it).apply()
                }
            }
        }
    }
    fun beginPlayback(context: Context, session: String) { initialize(context); playbackSession = session }
    fun claimPlayback(session:String){ request("/claim?session="+URLEncoder.encode(session,"UTF-8"),true) }
    fun releasePlayback(session: String) {
        if (playbackSession == session) playbackSession = ""
        worker.execute { request("/release?session=" + URLEncoder.encode(session,"UTF-8"), true) }
    }
    fun authorize(conn: HttpURLConnection) {
        if (conn.url.host == "127.0.0.1" && conn.url.port == ENGINE_PORT) conn.setRequestProperty("X-Engine-Token", token)
    }
    fun streamQuery(): String = "&token=" + URLEncoder.encode(token,"UTF-8") + "&session=" + URLEncoder.encode(playbackSession,"UTF-8")
    private fun request(path: String, mutation: Boolean = false, timeout: Int = 900): JSONObject? {
        var c: HttpURLConnection? = null
        return try {
            c = URL("http://127.0.0.1:$ENGINE_PORT$path").openConnection() as HttpURLConnection
            c.connectTimeout=timeout; c.readTimeout=timeout; authorize(c)
            if (mutation) c.requestMethod="POST"
            if (c.responseCode != 200) null else JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) { null } finally { c?.disconnect() }
    }
    fun isEngineHealthy(): Boolean = request("/health",timeout=300)?.optString("version") == "3.0"
    @Synchronized fun ensureRunning(context: Context): Boolean {
        initialize(context)
        if (isEngineHealthy()) return true
        // Migrate only a positively identified legacy instance on the app's own port.
        try {
            val c=URL(HEALTH_URL).openConnection() as HttpURLConnection
            c.connectTimeout=300;c.readTimeout=300
            try { if(c.responseCode==200) {
                val old=JSONObject(c.inputStream.bufferedReader().use{it.readText()})
                if(old.optString("engine")=="jewflix-engine" && old.optString("version")=="2.0") {
                    val stop=URL("http://127.0.0.1:$ENGINE_PORT/shutdown").openConnection() as HttpURLConnection
                    stop.connectTimeout=300;stop.readTimeout=500
                    try { stop.responseCode } finally { stop.disconnect() }
                    Thread.sleep(400)
                }
            }} finally { c.disconnect() }
        } catch (_: Exception) { }
        if (process?.isAlive == true) { process?.destroy(); process?.waitFor() }
        val binary=File(context.applicationInfo.nativeLibraryDir,"libjewflix-engine.so")
        if(!binary.exists()) return false
        return try {
            val cache=File(context.cacheDir,"torrent_cache").apply{mkdirs()}
            val builder=ProcessBuilder(binary.absolutePath,"-port=$ENGINE_PORT","-cacheDir=${cache.absolutePath}","-maxCacheMB=${SettingsManager.getMaxCacheMB(context)}","-token=$token")
            builder.directory(context.cacheDir);builder.redirectErrorStream(true)
            builder.environment()["SSL_CERT_DIR"]="/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts"
            val running=builder.start();process=running
            Thread({running.inputStream.bufferedReader().useLines { lines -> lines.forEach { if(BuildConfig.DEBUG) Log.d("JewEngine",it) } }},"EngineLog").apply{isDaemon=true;start()}
            for(i in 0 until 40){if(Thread.currentThread().isInterrupted)return false;if(isEngineHealthy())return true;if(!running.isAlive)return false;Thread.sleep(100)}
            false
        } catch(e:Exception){Log.e("JewEngine","Engine startup failed",e);false}
    }
    fun prewarmStream(context:Context,stream:StreamItem){
        if(!SettingsManager.isPrewarmEnabled(context)||playbackSession.isNotEmpty())return
        val hash=stream.infoHash?:return
        val app=context.applicationContext
        worker.execute {
            if(playbackSession.isNotEmpty()||!ensureRunning(app))return@execute
            val trackers=stream.sources.take(10).joinToString(""){"&tr="+URLEncoder.encode(it,"UTF-8")}
            request("/prewarm?hash=$hash&idx=${stream.fileIdx?:0}$trackers",true)
        }
    }
    fun updateMaxCacheMB(mb:Int){worker.execute{request("/config?maxCacheMB=$mb",true)}}
    fun dropTorrent(hash:String){worker.execute{request("/drop?hash=$hash",true)}}
    fun fetchEngineStats():EngineStatsSnapshot? {
        val o=request("/stats")?:return null
        return EngineStatsSnapshot(o.optLong("cache_bytes"),o.optLong("cache_cap_bytes"),o.optInt("cache_items"),o.optInt("active_torrents"),o.optInt("active_streams"))
    }
    fun wipeCache(context:Context?=null,onComplete:(()->Unit)?=null){worker.execute{try{request("/wipe",true,2000)}finally{onComplete?.invoke()}}}
    fun stopEngine(){process?.destroy();process=null}
}
