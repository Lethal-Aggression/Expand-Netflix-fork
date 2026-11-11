package com.d4cx.streamflix

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
object PlaybackCache {
 private var cache:SimpleCache?=null
 @Synchronized fun factory(context:Context,upstream:DataSource.Factory):DataSource.Factory {
  val app=context.applicationContext
  val storage=cache?:SimpleCache(File(app.cacheDir,"http_media_cache"),LeastRecentlyUsedCacheEvictor(64L*1024*1024),StandaloneDatabaseProvider(app)).also{cache=it}
  return CacheDataSource.Factory().setCache(storage).setUpstreamDataSourceFactory(upstream)
   .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
 }
 @Synchronized fun clear(){cache?.let{c->c.keys.toList().forEach{c.removeResource(it)}}}
}
