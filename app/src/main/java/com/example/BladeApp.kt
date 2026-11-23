package com.example

import android.app.Application
import android.net.Uri
import androidx.annotation.OptIn
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.example.core.network.NetworkConnectivityObserver
import com.example.data.local.AppPreferences
import com.example.data.remote.SupabaseClient
import com.example.data.repository.BladeRepository
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class BladeApp : Application() {

    lateinit var preferences: AppPreferences
        private set

    lateinit var connectivityObserver: NetworkConnectivityObserver
        private set

    lateinit var repository: BladeRepository
        private set

    private val prefetchExecutor = Executors.newSingleThreadExecutor()
    private val prefetchedUrls = ConcurrentHashMap.newKeySet<String>()

    @OptIn(UnstableApi::class)
    val mediaCache: SimpleCache? by lazy {
        runCatching {
            val cacheDir = File(cacheDir, "blade_media_cache")
            val evictor = LeastRecentlyUsedCacheEvictor(150L * 1024L * 1024L)
            SimpleCache(cacheDir, evictor, StandaloneDatabaseProvider(this))
        }.getOrNull()
    }

    @Volatile
    var prewarmedCameraProvider: ProcessCameraProvider? = null
        private set

    @Volatile
    private var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>? = null

    fun getOrWarmCameraProviderFuture(): ListenableFuture<ProcessCameraProvider> {
        cameraProviderFuture?.let { return it }
        val future = ProcessCameraProvider.getInstance(applicationContext)
        cameraProviderFuture = future
        future.addListener(
            { runCatching { prewarmedCameraProvider = future.get() } },
            ContextCompat.getMainExecutor(applicationContext)
        )
        return future
    }

    @OptIn(UnstableApi::class)
    fun prefetchVideoHead(url: String) {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http", ignoreCase = true)) return
        if (!connectivityObserver.isOnline.value) return
        val cache = mediaCache ?: return
        if (!prefetchedUrls.add(cleanUrl)) return
        prefetchExecutor.execute {
            runCatching {
                val upstreamFactory = DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(8_000)
                    .setReadTimeoutMs(8_000)
                    .setAllowCrossProtocolRedirects(true)
                val cacheDataSource = CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(upstreamFactory)
                    .createDataSource()
                val dataSpec = DataSpec.Builder()
                    .setUri(Uri.parse(cleanUrl))
                    .setPosition(0L)
                    .setLength(512L * 1024L)
                    .build()
                CacheWriter(cacheDataSource, dataSpec, null, null).cache()
            }.onFailure {
                prefetchedUrls.remove(cleanUrl)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences(applicationContext)
        connectivityObserver = NetworkConnectivityObserver(applicationContext)
        val client = SupabaseClient(preferences)
        repository = BladeRepository(
            preferences = preferences,
            client = client,
            isOnlineFlow = connectivityObserver.isOnline,
            onPrefetchVideo = ::prefetchVideoHead
        )
    }
}
