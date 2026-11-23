package com.example.core.moderation

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

sealed interface ModerationResult {
    data object Safe : ModerationResult
    data class Blocked(val reason: String) : ModerationResult
}

data class NormalizedBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class FrameSkinMetrics(
    val totalSkinRatio: Float,
    val centerTorsoSkinRatio: Float,
    val largestClusterRatio: Float,
    val borderSkinRatio: Float = 0f,
    val skinLumaStdDev: Float = 20f,
    val faceCoverageRatio: Float = 0f,
    val likelyUniformBackdrop: Boolean = false,
    val lowerCenterSkinRatio: Float = 0f,
    val chestCoreSkinRatio: Float = -1f,
    val pelvicCoreSkinRatio: Float = -1f
) {
    private val effectiveChestCore: Float
        get() = if (chestCoreSkinRatio >= 0f) chestCoreSkinRatio else centerTorsoSkinRatio

    private val effectivePelvicCore: Float
        get() = if (pelvicCoreSkinRatio >= 0f) pelvicCoreSkinRatio else lowerCenterSkinRatio

    val isExplicitCandidate: Boolean
        get() = !likelyUniformBackdrop &&
            skinLumaStdDev >= 8.0f && (
                // Continuous unclothed torso + pelvic/genital region without swimwear/sportswear break
                (centerTorsoSkinRatio >= 0.58f &&
                    lowerCenterSkinRatio >= 0.56f &&
                    effectivePelvicCore >= 0.54f &&
                    effectiveChestCore >= 0.48f &&
                    largestClusterRatio >= 0.32f) ||
                // Full-frame close-up explicit bare body exposure
                (totalSkinRatio >= 0.64f &&
                    largestClusterRatio >= 0.50f &&
                    centerTorsoSkinRatio >= 0.58f)
            )
}

object VideoContentModerator {

    const val BLOCKED_MESSAGE =
        "Upload blocked: Pornographic, sexually explicit, or adult content is strictly prohibited on Blade."

    private data class CachedVerdict(
        val result: ModerationResult,
        val timestampMs: Long
    )

    private val recentVerdictsLock = Any()
    private val recentVerdicts = object : LinkedHashMap<String, CachedVerdict>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedVerdict>?): Boolean {
            return size > 16
        }
    }
    private const val CACHE_TTL_MS = 120_000L

    // Strictly unambiguous explicit / pornographic tokens
    private val unambiguousExplicitWords = setOf(
        "porn", "porno", "pornography", "pornographic", "pornhub", "xvideos", "xvideo", "xnxx",
        "xhamster", "redtube", "youporn", "brazzers", "spankbang", "tube8", "eporner", "txxx",
        "javhd", "beeg", "tnaflix", "sunporno", "motherless", "chaturbate", "stripchat",
        "bongacams", "livejasmin", "cam4", "myfreecams", "manyvids", "clips4sale",
        "onlyfans", "fansly", "nsfw", "nsfl", "xxx", "xxxx",
        "hentai", "ecchi", "erotic", "erotica", "softcore", "bdsm", "bondage", "dominatrix",
        "nudes", "nudity", "topless", "striptease", "camgirl",
        "brothel", "prostitute", "fuck", "fucking", "fucker", "motherfucker",
        "cock", "dick", "dickpic", "pussy", "vagina", "penis", "boobs", "boobies", "tits",
        "titties", "titty", "nipple", "nipples", "clit", "clitoris", "labia", "vulva", "scrotum",
        "blowjob", "handjob", "footjob", "rimjob", "cumshot", "creampie", "bukkake",
        "deepthroat", "cunnilingus", "fellatio", "doggystyle", "fisting", "lesbianporn", "gayporn",
        "orgasm", "masturbate", "masturbation", "dildo", "gangbang", "threesome",
        "orgy", "incest", "milf", "gilf", "anal", "buttplug", "pornvideo", "sexvideo", "sextape",
        "sexclip", "sexfilm", "nudevideo", "nudeclip", "nudepic", "nudeselfie", "hotsex",
        "sexyvideo", "bluefilm",
        "chuda", "chudi", "choda", "chodi", "chudai", "chudwana", "khanki", "nangta", "nengta",
        "nangi", "nanga", "beshya", "randi", "chut", "lund", "gaand", "bhosdi", "madarchod",
        "behenchod"
    )

    // Words that can be explicit OR completely innocent (e.g., "nude lipstick", "naked cake", "ford escort", "paint stripper")
    private val contextualAdultWords = setOf(
        "nude", "naked", "escort", "stripper", "fetish", "vibrator"
    )

    // Multi-word or character-sensitive explicit phrases matched with token boundaries
    private val explicitPhrases = listOf(
        "adult only", "adult video", "adult clip", "adult film", "adult content",
        "porn video", "porn clip", "explicit video", "uncensored video",
        "sex video", "sex clip", "sex tape", "sexy video", "hot sex video", "hot nude video",
        "hot girl nude", "nude dance", "strip dance", "live sex", "cam sex", "blue film",
        "xxx video", "xxx clip", "desisex", "desi sex", "desimms", "desi mms", "hot mms",
        "viral mms", "bhabhi sex", "hot bhabhi", "bangla choda", "bangla sex", "bangla noti",
        "noti magi", "indian sex", "onlyfans leak", "only fans leak", "only fans 18+",
        "mms video", "mms leak", "viral sex", "nude leak", "sex leak",
        "leaked nudes", "send nudes", "18+ only", "18+ adult", "18+ sex", "18+ clip",
        "18+ porn", "18+ nude", "18+ hot", "18+ sexy", "18+ mms", "18+ uncensored",
        "পর্ন", "সেক্স", "চোদা", "চুদি", "নগ্ন", "নেংটা", "ন্যুড", "ব্লু ফিল্ম"
    )

    private val benignNudeOrNakedCollocations = listOf(
        "nude lip", "nude lipstick", "nude gloss", "nude makeup", "nude shade",
        "nude palette", "nude nail", "nude polish", "nude heel", "nude shoe",
        "nude dress", "nude tone", "nude color", "nude beige", "nude look",
        "naked cake", "naked eye", "naked mole", "naked truth", "naked bike"
    )

    private val benignOtherCollocations = listOf(
        "ford escort", "police escort", "vip escort", "security escort", "motorcade escort",
        "paint stripper", "wire stripper", "concrete vibrator"
    )

    private val eighteenPlusAdultContextWords = setOf(
        "adult", "only", "sex", "sexy", "porn", "xxx", "nsfw", "nude", "naked",
        "hot", "mms", "leak", "leaked", "uncensored", "explicit", "erotic", "bhabhi", "desi"
    )

    fun checkMetadataSafety(
        title: String,
        caption: String,
        location: String,
        fileName: String? = null,
        additionalFileNames: List<String> = emptyList()
    ): ModerationResult {
        val combinedRaw = (listOfNotNull(title, caption, location, fileName) + additionalFileNames)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .lowercase(Locale.ROOT)

        if (combinedRaw.isBlank()) return ModerationResult.Safe

        // Normalize underscores/hyphens for phrase checks (while keeping raw for 18+ checks)
        val phraseSearchText = combinedRaw
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        // 1. Explicit multi-word & Bengali phrase matches (word-boundary safe: never matches "essex video" for "sex video")
        for (phrase in explicitPhrases) {
            if (containsWholePhrase(phraseSearchText, phrase)) {
                return ModerationResult.Blocked(BLOCKED_MESSAGE)
            }
        }

        // 2. Tokenize words & hashtags (preserving alphanumeric + Bengali unicode block)
        val tokens = Regex("[a-z0-9\\u0980-\\u09FF]+")
            .findAll(phraseSearchText)
            .map { it.value }
            .toList()

        // 3. Check standalone "18+" only when paired with adult/explicit context (not "18+ Cooking Tips" or "2018+")
        val hasBoundedEighteenPlus = Regex("(?<![a-z0-9\\u0980-\\u09FF])18\\+(?![a-z0-9\\u0980-\\u09FF])")
            .containsMatchIn(combinedRaw)
        if (hasBoundedEighteenPlus && tokens.any { it in eighteenPlusAdultContextWords }) {
            return ModerationResult.Blocked(BLOCKED_MESSAGE)
        }

        if (tokens.any { it in unambiguousExplicitWords }) {
            return ModerationResult.Blocked(BLOCKED_MESSAGE)
        }

        // 4. Contextual adult words ("nude", "naked", "escort", "stripper", etc.):
        // allow harmless beauty/baking/car/DIY collocations
        val matchedContextual = tokens.filter { it in contextualAdultWords }
        if (matchedContextual.isNotEmpty()) {
            val scrubbed = scrubCollocations(
                scrubCollocations(phraseSearchText, benignNudeOrNakedCollocations),
                benignOtherCollocations
            )
            val scrubbedTokens = Regex("[a-z0-9\\u0980-\\u09FF]+")
                .findAll(scrubbed)
                .map { it.value }
                .toList()
            if (scrubbedTokens.any { it in contextualAdultWords }) {
                return ModerationResult.Blocked(BLOCKED_MESSAGE)
            }
        }

        // 5. Compact punctuation obfuscation check within whitespace-separated segments (e.g. "p.o.r.n", "#x_x_x")
        val strippedTokens = combinedRaw
            .split(Regex("\\s+"))
            .map { it.replace(Regex("[^a-z0-9]"), "") }
        if (strippedTokens.any { it in unambiguousExplicitWords }) {
            return ModerationResult.Blocked(BLOCKED_MESSAGE)
        }

        return ModerationResult.Safe
    }

    private fun scrubCollocations(text: String, collocations: List<String>): String {
        var out = text
        for (col in collocations) {
            val pattern = Regex("(?<![a-z0-9])${Regex.escape(col)}(?![a-z0-9])")
            out = out.replace(pattern, " ")
        }
        return out
    }

    private fun containsWholePhrase(text: String, phrase: String): Boolean {
        val pattern = Regex("(?<![a-z0-9\\u0980-\\u09FF])${Regex.escape(phrase)}(?![a-z0-9\\u0980-\\u09FF])")
        return pattern.containsMatchIn(text)
    }

    suspend fun moderateRemoteVideo(
        remoteUrl: String,
        title: String,
        caption: String,
        location: String
    ): ModerationResult = withContext(Dispatchers.IO) {
        val cleanUrl = remoteUrl.trim()
        val parsedUri = runCatching { Uri.parse(cleanUrl) }.getOrNull()
        val parsedFileName = parsedUri?.lastPathSegment?.takeIf { it.isNotBlank() } ?: cleanUrl
        val urlExtras = listOfNotNull(parsedUri?.host, parsedUri?.path, parsedUri?.query)
        val metaResult = checkMetadataSafety(
            title = title,
            caption = caption,
            location = location,
            fileName = parsedFileName,
            additionalFileNames = urlExtras
        )
        if (metaResult is ModerationResult.Blocked) {
            return@withContext metaResult
        }

        if (cleanUrl.startsWith("http://", ignoreCase = true) ||
            cleanUrl.startsWith("https://", ignoreCase = true)
        ) {
            val remoteMetrics = withTimeoutOrNull(7_000L) {
                sampleAndAnalyzeRemoteFrames(cleanUrl)
            }.orEmpty()
            val frameVerdict = evaluateFrameMetrics(remoteMetrics)
            if (frameVerdict is ModerationResult.Blocked) {
                return@withContext frameVerdict
            }
        }
        ModerationResult.Safe
    }

    suspend fun moderateVideo(
        contentResolver: ContentResolver,
        videoUri: Uri,
        title: String,
        caption: String,
        location: String,
        durationMs: Long,
        isPhoto: Boolean = false,
        additionalFileNames: List<String> = emptyList()
    ): ModerationResult = withContext(Dispatchers.IO) {
        val fileName = resolveDisplayName(contentResolver, videoUri)
        val fileSize = resolveFileSize(contentResolver, videoUri)
        val extrasKey = additionalFileNames.joinToString(",") { it.trim() }
        val cacheKey = "$videoUri|$fileSize|${title.trim()}|${caption.trim()}|${location.trim()}|$durationMs|$isPhoto|$extrasKey"
        val now = System.currentTimeMillis()
        synchronized(recentVerdictsLock) {
            val cached = recentVerdicts[cacheKey]
            if (cached != null && now - cached.timestampMs <= CACHE_TTL_MS) {
                return@withContext cached.result
            }
        }

        val metaResult = checkMetadataSafety(
            title = title,
            caption = caption,
            location = location,
            fileName = fileName,
            additionalFileNames = additionalFileNames
        )
        if (metaResult is ModerationResult.Blocked) {
            cacheVerdict(cacheKey, metaResult, now)
            return@withContext metaResult
        }

        val frameMetrics = if (isPhoto) {
            samplePhotoFrame(contentResolver, videoUri)
        } else {
            sampleAndAnalyzeFrames(contentResolver, videoUri, durationMs)
        }
        val verdict = evaluateFrameMetrics(frameMetrics)
        cacheVerdict(cacheKey, verdict, now)
        verdict
    }

    fun resolveDisplayNameForModeration(contentResolver: ContentResolver, uri: Uri): String? {
        return resolveDisplayName(contentResolver, uri)
    }

    fun evaluateFrameMetrics(frameMetrics: List<FrameSkinMetrics>): ModerationResult {
        if (frameMetrics.isEmpty()) return ModerationResult.Safe
        val flaggedFrames = frameMetrics.count { it.isExplicitCandidate }
        val avgTotalSkin = frameMetrics.map { it.totalSkinRatio }.average().toFloat()
        val avgTorsoSkin = frameMetrics.map { it.centerTorsoSkinRatio }.average().toFloat()
        val avgLowerCenterSkin = frameMetrics.map { it.lowerCenterSkinRatio }.average().toFloat()
        val avgClusterSkin = frameMetrics.map { it.largestClusterRatio }.average().toFloat()

        val shouldBlock = when {
            frameMetrics.size == 1 -> {
                flaggedFrames == 1 &&
                    avgTotalSkin >= 0.54f &&
                    avgTorsoSkin >= 0.58f &&
                    avgClusterSkin >= 0.40f
            }
            frameMetrics.size == 2 -> {
                flaggedFrames == 2 &&
                    avgTorsoSkin >= 0.55f &&
                    avgLowerCenterSkin >= 0.52f &&
                    avgClusterSkin >= 0.32f
            }
            frameMetrics.size <= 5 -> {
                flaggedFrames >= 3 || (
                    flaggedFrames >= 2 &&
                        avgTotalSkin >= 0.42f &&
                        avgTorsoSkin >= 0.56f &&
                        avgLowerCenterSkin >= 0.52f
                )
            }
            else -> {
                flaggedFrames >= 3 || (
                    flaggedFrames >= 2 &&
                        avgTotalSkin >= 0.42f &&
                        avgTorsoSkin >= 0.54f &&
                        avgLowerCenterSkin >= 0.52f
                )
            }
        }
        return if (shouldBlock) {
            ModerationResult.Blocked(BLOCKED_MESSAGE)
        } else {
            ModerationResult.Safe
        }
    }

    private fun cacheVerdict(key: String, result: ModerationResult, now: Long) {
        synchronized(recentVerdictsLock) {
            recentVerdicts[key] = CachedVerdict(result = result, timestampMs = now)
        }
    }

    private fun resolveDisplayName(contentResolver: ContentResolver, uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            } ?: uri.lastPathSegment
        }.getOrNull()
    }

    private fun resolveFileSize(contentResolver: ContentResolver, uri: Uri): Long {
        if (uri.scheme == "file") {
            val f = uri.path?.let { File(it) }
            if (f != null && f.exists()) return f.length()
        }
        return runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
    }

    private suspend fun samplePhotoFrame(
        contentResolver: ContentResolver,
        photoUri: Uri
    ): List<FrameSkinMetrics> {
        val rawBitmap = runCatching {
            contentResolver.openInputStream(photoUri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull() ?: return emptyList()

        return try {
            listOf(analyzeBitmapWithFaceExclusion(rawBitmap))
        } finally {
            if (!rawBitmap.isRecycled) rawBitmap.recycle()
        }
    }

    private suspend fun sampleAndAnalyzeFrames(
        contentResolver: ContentResolver,
        videoUri: Uri,
        fallbackDurationMs: Long
    ): List<FrameSkinMetrics> {
        val retriever = MediaMetadataRetriever()
        val results = mutableListOf<FrameSkinMetrics>()
        var tempCopy: File? = null
        try {
            var sourceReady = runCatching {
                contentResolver.openFileDescriptor(videoUri, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                    true
                } ?: false
            }.getOrDefault(false)

            if (!sourceReady) {
                val copied = File.createTempFile("blade_mod_", ".mp4")
                tempCopy = copied
                contentResolver.openInputStream(videoUri)?.use { input ->
                    copied.outputStream().use { out -> input.copyTo(out) }
                }
                if (copied.length() > 0L) {
                    retriever.setDataSource(copied.absolutePath)
                    sourceReady = true
                }
            }

            if (!sourceReady) return emptyList()

            val actualDurationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: fallbackDurationMs.coerceAtLeast(1000L)

            val fractions = listOf(0.06f, 0.17f, 0.29f, 0.42f, 0.55f, 0.68f, 0.81f, 0.93f)
            for (fraction in fractions) {
                val timeUs = (actualDurationMs * fraction).toLong().coerceAtLeast(0L) * 1000L
                val rawBitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(timeUs)
                    ?: continue
                try {
                    results.add(analyzeBitmapWithFaceExclusion(rawBitmap))
                } finally {
                    if (!rawBitmap.isRecycled) rawBitmap.recycle()
                }
            }
        } catch (_: Exception) {
            // If codec/URI cannot be decoded on device, fallback cleanly to already-run metadata safety check
        } finally {
            runCatching { retriever.release() }
            runCatching { tempCopy?.delete() }
        }
        return results
    }

    private suspend fun sampleAndAnalyzeRemoteFrames(remoteUrl: String): List<FrameSkinMetrics> {
        val retriever = MediaMetadataRetriever()
        val results = mutableListOf<FrameSkinMetrics>()
        try {
            retriever.setDataSource(remoteUrl, emptyMap())
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: 10_000L

            val fractions = listOf(0.15f, 0.40f, 0.65f, 0.85f)
            for (fraction in fractions) {
                val timeUs = (durationMs * fraction).toLong().coerceAtLeast(0L) * 1000L
                val rawBitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(timeUs)
                    ?: continue
                try {
                    results.add(analyzeBitmapWithFaceExclusion(rawBitmap))
                } finally {
                    if (!rawBitmap.isRecycled) rawBitmap.recycle()
                }
            }
        } catch (_: Exception) {
            // Remote host or stream format unreadable by MediaMetadataRetriever -> metadata verdict applies
        } finally {
            runCatching { retriever.release() }
        }
        return results
    }

    private suspend fun analyzeBitmapWithFaceExclusion(rawBitmap: Bitmap): FrameSkinMetrics {
        val faceBitmap = scalePreservingAspect(rawBitmap, maxDimension = 320)
        val excludedFaces = try {
            detectNormalizedFaceBoxes(faceBitmap)
        } finally {
            if (faceBitmap !== rawBitmap && !faceBitmap.isRecycled) {
                faceBitmap.recycle()
            }
        }

        val analysisBitmap = scalePreservingAspect(rawBitmap, maxDimension = 144)
        return try {
            analyzeFrameBitmap(analysisBitmap, excludedFaces)
        } finally {
            if (analysisBitmap !== rawBitmap && !analysisBitmap.isRecycled) {
                analysisBitmap.recycle()
            }
        }
    }

    private fun scalePreservingAspect(source: Bitmap, maxDimension: Int): Bitmap {
        val w = source.width.coerceAtLeast(1)
        val h = source.height.coerceAtLeast(1)
        val maxSide = max(w, h)
        if (maxSide <= maxDimension) return source
        val scale = maxDimension.toFloat() / maxSide.toFloat()
        val targetW = (w * scale).roundToInt().coerceAtLeast(1)
        val targetH = (h * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, false)
    }

    private suspend fun detectNormalizedFaceBoxes(bitmap: Bitmap): List<NormalizedBox> {
        val w = bitmap.width.toFloat().coerceAtLeast(1f)
        val h = bitmap.height.toFloat().coerceAtLeast(1f)
        return withTimeoutOrNull(1200L) {
            runCatching {
                val options = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setMinFaceSize(0.10f)
                    .build()
                val detector = FaceDetection.getClient(options)
                try {
                    val faces = suspendCancellableCoroutine { cont ->
                        detector.process(InputImage.fromBitmap(bitmap, 0))
                            .addOnSuccessListener { list ->
                                if (cont.isActive) cont.resume(list)
                            }
                            .addOnFailureListener {
                                if (cont.isActive) cont.resume(emptyList())
                            }
                    }
                    faces.map { face ->
                        val box = face.boundingBox
                        val bw = box.width().toFloat()
                        val bh = box.height().toFloat()
                        // Pad slightly around forehead/hairline/cheeks and immediate chin/upper-neck
                        NormalizedBox(
                            left = ((box.left - bw * 0.08f) / w).coerceIn(0f, 1f),
                            top = ((box.top - bh * 0.14f) / h).coerceIn(0f, 1f),
                            right = ((box.right + bw * 0.08f) / w).coerceIn(0f, 1f),
                            bottom = ((box.bottom + bh * 0.14f) / h).coerceIn(0f, 1f)
                        )
                    }
                } finally {
                    runCatching { detector.close() }
                }
            }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    fun analyzeFrameBitmap(
        bitmap: Bitmap,
        excludedRegions: List<NormalizedBox> = emptyList()
    ): FrameSkinMetrics {
        val width = bitmap.width
        val height = bitmap.height
        val totalPixels = width * height
        if (totalPixels <= 0) return FrameSkinMetrics(0f, 0f, 0f)
        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return analyzeFramePixels(pixels, width, height, excludedRegions)
    }

    fun analyzeFramePixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        excludedRegions: List<NormalizedBox> = emptyList()
    ): FrameSkinMetrics {
        val totalPixels = width * height
        if (totalPixels <= 0 || pixels.size < totalPixels) return FrameSkinMetrics(0f, 0f, 0f)

        val skinMask = BooleanArray(totalPixels)
        var skinCount = 0
        var excludedCount = 0
        var torsoTotal = 0
        var torsoSkin = 0
        var lowerTotal = 0
        var lowerSkin = 0
        var chestCoreTotal = 0
        var chestCoreSkin = 0
        var pelvicCoreTotal = 0
        var pelvicCoreSkin = 0
        var borderTotal = 0
        var borderSkin = 0
        var lumaSum = 0.0
        var lumaSqSum = 0.0

        val rowMin = (height * 0.25f).toInt()
        val rowMax = (height * 0.88f).toInt()
        val colMin = (width * 0.15f).toInt()
        val colMax = (width * 0.85f).toInt()
        val lowerRowMin = (height * 0.45f).toInt()
        val lowerRowMax = (height * 0.92f).toInt()
        val lowerColMin = (width * 0.18f).toInt()
        val lowerColMax = (width * 0.82f).toInt()
        val coreColMin = (width * 0.28f).toInt()
        val coreColMax = (width * 0.72f).toInt()
        val chestCoreRowMin = (height * 0.30f).toInt()
        val chestCoreRowMax = (height * 0.48f).toInt()
        val pelvicCoreRowMin = (height * 0.54f).toInt()
        val pelvicCoreRowMax = (height * 0.78f).toInt()
        val rim = 2

        for (y in 0 until height) {
            val ny = (y + 0.5f) / height.toFloat()
            val isBorderY = y < rim || y >= height - rim
            for (x in 0 until width) {
                val idx = y * width + x
                val nx = (x + 0.5f) / width.toFloat()
                val inExcludedFace = excludedRegions.any { box ->
                    nx in box.left..box.right && ny in box.top..box.bottom
                }
                if (inExcludedFace) {
                    excludedCount++
                }

                val px = pixels[idx]
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8) and 0xFF
                val b = px and 0xFF

                val isRawSkin = isSkinPixel(r, g, b)
                val isBodySkin = !inExcludedFace && isRawSkin
                skinMask[idx] = isBodySkin
                if (isBodySkin) {
                    skinCount++
                    val luma = 0.299 * r + 0.587 * g + 0.114 * b
                    lumaSum += luma
                    lumaSqSum += luma * luma
                }

                if (y in rowMin..rowMax && x in colMin..colMax) {
                    torsoTotal++
                    if (isBodySkin) torsoSkin++
                }

                if (y in lowerRowMin..lowerRowMax && x in lowerColMin..lowerColMax) {
                    lowerTotal++
                    if (isBodySkin) lowerSkin++
                }

                if (x in coreColMin..coreColMax && y in chestCoreRowMin..chestCoreRowMax) {
                    chestCoreTotal++
                    if (isBodySkin) chestCoreSkin++
                }

                if (x in coreColMin..coreColMax && y in pelvicCoreRowMin..pelvicCoreRowMax) {
                    pelvicCoreTotal++
                    if (isBodySkin) pelvicCoreSkin++
                }

                if (isBorderY || x < rim || x >= width - rim) {
                    borderTotal++
                    if (isRawSkin) borderSkin++
                }
            }
        }

        val faceCoverageRatio = excludedCount.toFloat() / totalPixels.toFloat()
        val effectiveDenominator = if (faceCoverageRatio >= 0.30f) {
            totalPixels
        } else {
            (totalPixels - excludedCount).coerceAtLeast(1)
        }

        val largestCluster = findLargestConnectedSkinRegion(skinMask, width, height)
        val borderSkinRatio = if (borderTotal > 0) borderSkin.toFloat() / borderTotal.toFloat() else 0f
        val skinLumaStdDev = if (skinCount > 1) {
            val mean = lumaSum / skinCount.toDouble()
            val variance = (lumaSqSum / skinCount.toDouble()) - (mean * mean)
            sqrt(max(0.0, variance)).toFloat()
        } else {
            0f
        }

        // Uniform beige walls, wooden desks, sand, or full-screen warm color filters flood borders with flat variance
        val likelyUniformBackdrop = (borderSkinRatio >= 0.72f && skinLumaStdDev < 14f) ||
            (borderSkinRatio >= 0.96f && skinLumaStdDev < 18f)

        return FrameSkinMetrics(
            totalSkinRatio = skinCount.toFloat() / effectiveDenominator.toFloat(),
            centerTorsoSkinRatio = if (torsoTotal > 0) torsoSkin.toFloat() / torsoTotal.toFloat() else 0f,
            largestClusterRatio = largestCluster.toFloat() / effectiveDenominator.toFloat(),
            borderSkinRatio = borderSkinRatio,
            skinLumaStdDev = skinLumaStdDev,
            faceCoverageRatio = faceCoverageRatio,
            likelyUniformBackdrop = likelyUniformBackdrop,
            lowerCenterSkinRatio = if (lowerTotal > 0) lowerSkin.toFloat() / lowerTotal.toFloat() else 0f,
            chestCoreSkinRatio = if (chestCoreTotal > 0) chestCoreSkin.toFloat() / chestCoreTotal.toFloat() else 0f,
            pelvicCoreSkinRatio = if (pelvicCoreTotal > 0) pelvicCoreSkin.toFloat() / pelvicCoreTotal.toFloat() else 0f
        )
    }

    private fun isSkinPixel(r: Int, g: Int, b: Int): Boolean {
        if (r < 40 || g < 20 || b < 12) return false
        if (r > 246 && g > 238 && b > 228) return false
        val maxRgb = max(r, max(g, b))
        val minRgb = min(r, min(g, b))
        if (r != maxRgb || (r - g) < 8 || (maxRgb - minRgb) < 14) return false

        // Reject oversaturated neon red/orange sunsets, LED lights, and spicy curry/tomato tints
        val saturation = (maxRgb - minRgb).toFloat() / maxRgb.toFloat()
        if (saturation >= 0.72f) return false

        val y = 0.299 * r + 0.587 * g + 0.114 * b
        val cb = 128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b
        val cr = 128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b
        return y in 35.0..238.0 && cb in 75.0..129.0 && cr in 131.0..178.0
    }

    private fun findLargestConnectedSkinRegion(mask: BooleanArray, width: Int, height: Int): Int {
        val visited = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        var maxRegion = 0

        for (i in mask.indices) {
            if (!mask[i] || visited[i]) continue
            var head = 0
            var tail = 0
            queue[tail++] = i
            visited[i] = true
            var count = 0

            while (head < tail) {
                val cur = queue[head++]
                count++
                val cx = cur % width
                val cy = cur / width

                if (cx > 0) {
                    val left = cur - 1
                    if (mask[left] && !visited[left]) {
                        visited[left] = true
                        queue[tail++] = left
                    }
                }
                if (cx + 1 < width) {
                    val right = cur + 1
                    if (mask[right] && !visited[right]) {
                        visited[right] = true
                        queue[tail++] = right
                    }
                }
                if (cy > 0) {
                    val up = cur - width
                    if (mask[up] && !visited[up]) {
                        visited[up] = true
                        queue[tail++] = up
                    }
                }
                if (cy + 1 < height) {
                    val down = cur + width
                    if (mask[down] && !visited[down]) {
                        visited[down] = true
                        queue[tail++] = down
                    }
                }
            }
            if (count > maxRegion) maxRegion = count
        }
        return maxRegion
    }
}
