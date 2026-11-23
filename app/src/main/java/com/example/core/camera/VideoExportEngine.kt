package com.example.core.camera

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.URL
import kotlin.coroutines.resume

data class VideoSegmentExportSpec(
    val uri: Uri,
    val trimStartMs: Long,
    val trimEndMs: Long,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val muteAudio: Boolean = false
)

data class VideoExportRequest(
    val primaryUri: Uri,
    val segments: List<VideoSegmentExportSpec>,
    val filterName: String,
    val filterIntensity: Float,
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val warmth: Float,
    val rotationDegrees: Int,
    val aspectRatioMode: String, // "9:16", "1:1", "4:5", "16:9"
    val reversePlayback: Boolean = false,
    val pipUri: Uri? = null,
    val voiceOverPath: String? = null,
    val musicUri: Uri? = null,
    val musicTrimStartMs: Long = 0L,
    val musicTrimEndMs: Long = 0L,
    val musicVolume: Float = 0.75f
)

object VideoExportEngine {

    internal fun shouldStripOriginalAudio(
        segments: List<VideoSegmentExportSpec>,
        hasMusic: Boolean
    ): Boolean = hasMusic || segments.any { it.muteAudio }

    @OptIn(UnstableApi::class)
    suspend fun exportVideo(
        context: Context,
        request: VideoExportRequest,
        onProgress: (Float) -> Unit
    ): Result<Uri> = withContext(Dispatchers.Main) {
        val wantsMusic = request.musicUri != null && request.musicVolume > 0.02f
        val (resolvedMusicUri, downloadedTempMusic) = if (wantsMusic && request.musicUri != null) {
            resolvePlayableMusicUri(context, request.musicUri)
        } else {
            null to null
        }

        if (wantsMusic && resolvedMusicUri == null) {
            runCatching { downloadedTempMusic?.delete() }
            return@withContext Result.failure(
                IllegalStateException("Unable to load the selected sound track. Please pick the sound again.")
            )
        }

        try {
            val hasResolvedMusic = resolvedMusicUri != null
            val stripOriginalAudio = shouldStripOriginalAudio(request.segments, hasResolvedMusic)

            val needsTransform = request.segments.size > 1 ||
                stripOriginalAudio ||
                request.rotationDegrees % 360 != 0 ||
                request.aspectRatioMode != "9:16" ||
                request.filterName != "Original" ||
                kotlin.math.abs(request.brightness) > 0.02f ||
                kotlin.math.abs(request.contrast - 1f) > 0.02f ||
                kotlin.math.abs(request.saturation - 1f) > 0.02f ||
                kotlin.math.abs(request.warmth) > 0.02f ||
                request.segments.any { kotlin.math.abs(it.speed - 1f) > 0.02f || it.muteAudio }

            if (!needsTransform && request.segments.size <= 1) {
                onProgress(1f)
                return@withContext Result.success(request.primaryUri)
            }

            val videoEffects = mutableListOf<Effect>()
            videoEffects.addAll(
                VideoFilterCatalog.buildMedia3Effects(
                    filterName = request.filterName,
                    filterIntensity = request.filterIntensity,
                    extraBrightness = request.brightness,
                    extraContrast = request.contrast,
                    extraSaturation = request.saturation,
                    extraWarmth = request.warmth
                )
            )
            if (request.rotationDegrees % 360 != 0) {
                videoEffects.add(
                    ScaleAndRotateTransformation.Builder()
                        .setRotationDegrees((360 - (request.rotationDegrees % 360)).toFloat())
                        .build()
                )
            }
            when (request.aspectRatioMode) {
                "1:1" -> videoEffects.add(Crop(-1f, 1f, -0.5625f, 0.5625f))
                "4:5" -> videoEffects.add(Crop(-1f, 1f, -0.703f, 0.703f))
                "16:9" -> videoEffects.add(Crop(-1f, 1f, -0.316f, 0.316f))
            }

            val segmentList = request.segments.ifEmpty {
                listOf(VideoSegmentExportSpec(request.primaryUri, 0L, 60_000L))
            }

            val editedItems = segmentList.map { seg ->
                val clipping = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(seg.trimStartMs.coerceAtLeast(0L))
                    .apply {
                        if (seg.trimEndMs > seg.trimStartMs) setEndPositionMs(seg.trimEndMs)
                    }
                    .build()
                val mediaItem = MediaItem.Builder()
                    .setUri(seg.uri)
                    .setClippingConfiguration(clipping)
                    .build()

                val audioProcessors = mutableListOf<androidx.media3.common.audio.AudioProcessor>()
                if (!hasResolvedMusic && !seg.muteAudio && kotlin.math.abs(seg.speed - 1f) > 0.02f) {
                    audioProcessors.add(SonicAudioProcessor().apply { setSpeed(seg.speed.coerceIn(0.3f, 3f)) })
                }

                EditedMediaItem.Builder(mediaItem)
                    .setRemoveAudio(seg.muteAudio || hasResolvedMusic)
                    .setEffects(Effects(audioProcessors, videoEffects))
                    .build()
            }

            val mainSequence = EditedMediaItemSequence.Builder(editedItems).build()

            if (hasResolvedMusic && resolvedMusicUri != null) {
                val musicClip = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(request.musicTrimStartMs.coerceAtLeast(0L))
                    .apply {
                        if (request.musicTrimEndMs > request.musicTrimStartMs) {
                            setEndPositionMs(request.musicTrimEndMs)
                        }
                    }
                    .build()
                val musicItem = MediaItem.Builder()
                    .setUri(resolvedMusicUri)
                    .setClippingConfiguration(musicClip)
                    .build()
                val editedMusic = EditedMediaItem.Builder(musicItem)
                    .setRemoveVideo(true)
                    .build()

                val loopedMusicSequence = EditedMediaItemSequence.Builder(listOf(editedMusic))
                    .setIsLooping(true)
                    .build()
                val loopedRes = withTimeoutOrNull(90_000L) {
                    runTransformer(
                        context = context,
                        composition = Composition.Builder(listOf(mainSequence, loopedMusicSequence)).build(),
                        onProgress = onProgress
                    )
                }
                if (loopedRes != null && loopedRes.isSuccess) return@withContext loopedRes

                val nonLoopedMusicSequence = EditedMediaItemSequence.Builder(listOf(editedMusic))
                    .setIsLooping(false)
                    .build()
                val nonLoopedRes = withTimeoutOrNull(90_000L) {
                    runTransformer(
                        context = context,
                        composition = Composition.Builder(listOf(mainSequence, nonLoopedMusicSequence)).build(),
                        onProgress = onProgress
                    )
                }
                return@withContext nonLoopedRes
                    ?: loopedRes
                    ?: Result.failure(IllegalStateException("Audio muxing timed out. Please try a shorter clip or another audio file."))
            }

            withTimeoutOrNull(90_000L) {
                runTransformer(
                    context = context,
                    composition = Composition.Builder(listOf(mainSequence)).build(),
                    onProgress = onProgress
                )
            } ?: Result.failure(IllegalStateException("Video rendering timed out. Please try again."))
        } finally {
            runCatching { downloadedTempMusic?.delete() }
        }
    }

    private suspend fun resolvePlayableMusicUri(
        context: Context,
        musicUri: Uri
    ): Pair<Uri?, File?> {
        val scheme = musicUri.scheme?.lowercase()
        if (scheme == "file" || scheme == "content") {
            return musicUri to null
        }
        if (scheme == "http" || scheme == "https") {
            return withContext(Dispatchers.IO) {
                var tmpFile: File? = null
                try {
                    val pathExt = musicUri.lastPathSegment
                        ?.substringAfterLast('.', "")
                        ?.lowercase()
                        ?.takeIf { it in setOf("mp3", "m4a", "aac", "wav", "ogg", "mp4", "webm") }
                        ?: "m4a"
                    val file = File(context.cacheDir, "blade_remote_audio_${System.currentTimeMillis()}.$pathExt")
                    tmpFile = file
                    val conn = URL(musicUri.toString()).openConnection().apply {
                        connectTimeout = 15_000
                        readTimeout = 20_000
                    }
                    conn.getInputStream().use { input ->
                        file.outputStream().use { out -> input.copyTo(out) }
                    }
                    if (file.exists() && file.length() > 0L) {
                        Uri.fromFile(file) to file
                    } else {
                        runCatching { file.delete() }
                        null to null
                    }
                } catch (_: Throwable) {
                    runCatching { tmpFile?.delete() }
                    null to null
                }
            }
        }
        return null to null
    }

    @OptIn(UnstableApi::class)
    private suspend fun runTransformer(
        context: Context,
        composition: Composition,
        onProgress: (Float) -> Unit
    ): Result<Uri> {
        val outFile = File(context.cacheDir, "blade_export_${System.currentTimeMillis()}.mp4")
        if (outFile.exists()) outFile.delete()

        return suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val progressHolder = ProgressHolder()
            var finished = false
            lateinit var transformer: Transformer

            val pollRunnable = object : Runnable {
                override fun run() {
                    if (finished) return
                    val state = transformer.getProgress(progressHolder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress((progressHolder.progress / 100f).coerceIn(0.05f, 0.98f))
                    }
                    handler.postDelayed(this, 120L)
                }
            }

            transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        finished = true
                        handler.removeCallbacks(pollRunnable)
                        onProgress(1f)
                        if (cont.isActive) cont.resume(Result.success(Uri.fromFile(outFile)))
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        finished = true
                        handler.removeCallbacks(pollRunnable)
                        runCatching { outFile.delete() }
                        if (cont.isActive) cont.resume(Result.failure(exportException))
                    }
                })
                .build()

            runCatching {
                transformer.start(composition, outFile.absolutePath)
                handler.post(pollRunnable)
            }.onFailure { err ->
                finished = true
                handler.removeCallbacks(pollRunnable)
                runCatching { outFile.delete() }
                if (cont.isActive) cont.resume(Result.failure(err))
            }

            cont.invokeOnCancellation {
                finished = true
                handler.removeCallbacks(pollRunnable)
                runCatching { transformer.cancel() }
                runCatching { outFile.delete() }
            }
        }
    }
}

class StudioVoiceOverRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun startRecording(): Result<File> = runCatching {
        stopRecording()
        val file = File(context.cacheDir, "blade_vo_${System.currentTimeMillis()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        rec.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44_100)
            setAudioEncodingBitRate(96_000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        recorder = rec
        outputFile = file
        file
    }

    fun stopRecording(): File? {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        return outputFile?.takeIf { it.exists() && it.length() > 0L }
    }

    fun release() {
        runCatching { recorder?.release() }
        recorder = null
    }
}
