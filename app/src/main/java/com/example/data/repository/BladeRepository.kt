package com.example.data.repository

import android.content.ContentResolver
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import com.example.core.moderation.ModerationResult
import com.example.core.moderation.VideoContentModerator
import com.example.data.local.AppPreferences
import com.example.data.local.FeedReloadState
import com.example.data.local.StoredAdminSession
import com.example.data.local.StoredSession
import com.example.data.model.AdItem
import com.example.data.model.AdminAppSettings
import com.example.data.model.AdminCommentEntry
import com.example.data.model.AdminDashboardStats
import com.example.data.model.AppSettingRecord
import com.example.data.model.AuthSession
import com.example.data.model.AuthUserDto
import com.example.data.model.AuthUserMetadataUpdateRequest
import com.example.data.model.BlockUserRequest
import com.example.data.model.CommentItem
import com.example.data.model.ConversationItem
import com.example.data.model.CreateCommentRequest
import com.example.data.model.CreateConversationRequest
import com.example.data.model.CreateMessageLegacyRequest
import com.example.data.model.CreateMessageRequest
import com.example.data.model.CreateNotificationRequest
import com.example.data.model.CreateReportRequest
import com.example.data.model.CreateVideoLegacyRequest
import com.example.data.model.CreateVideoRequest
import com.example.data.model.FollowerRecord
import com.example.data.model.GiftItem
import com.example.data.model.HashtagItem
import com.example.data.model.IdTokenRequest
import com.example.data.model.LikeRecord
import com.example.data.model.LiveStreamItem
import com.example.data.model.LocationCatalog
import com.example.data.model.LocationPreferences
import com.example.data.model.MessageItem
import com.example.data.model.NotificationItem
import com.example.data.model.PkceTokenRequest
import com.example.data.model.RecoverPasswordRequest
import com.example.data.model.RefreshTokenRequest
import com.example.data.model.SavedRecord
import com.example.data.model.SignInPasswordRequest
import com.example.data.model.SignInPhonePasswordRequest
import com.example.data.model.SignUpPhoneRequest
import com.example.data.model.SignUpRequest
import com.example.data.model.SoundItem
import com.example.data.model.TransactionItem
import com.example.data.model.UserProfile
import com.example.data.model.UserProfileLegacyUpsert
import com.example.data.model.UserProfileUpsert
import com.example.data.model.VideoItem
import com.example.data.remote.RealtimeChatEvent
import com.example.data.remote.SupabaseClient
import com.example.data.remote.SupabaseConfig
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import java.util.UUID

private data class SupabaseErrorPayload(
    @Json(name = "error_code") val errorCode: String? = null,
    val code: String? = null,
    val msg: String? = null,
    @Json(name = "error_description") val errorDescription: String? = null,
    val message: String? = null,
    val error: String? = null,
    val hint: String? = null
)

data class FeedBatchResult(
    val videos: List<VideoItem>,
    val rawFetchedCount: Int
)

class MissingCountryException(
    message: String = "Account country is not set. Please select your country to continue."
) : IllegalStateException(message)

object AuthInputValidator {
    private val emailRegex = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    private val e164PhoneRegex = Regex("^\\+[1-9][0-9]{7,14}$")

    fun isValidEmail(raw: String): Boolean = emailRegex.matches(raw.trim())

    fun normalizePhone(raw: String): String {
        val stripped = raw.trim().replace(Regex("[\\s\\-().]"), "")
        if (stripped.isEmpty()) return ""
        val withPlus = when {
            stripped.startsWith("00") -> "+${stripped.drop(2)}"
            stripped.startsWith("+") -> stripped
            else -> "+$stripped"
        }
        return "+" + withPlus.drop(1).filter { it.isDigit() }
    }

    fun isValidPhone(raw: String): Boolean = e164PhoneRegex.matches(normalizePhone(raw))
}

class BladeRepository(
    private val preferences: AppPreferences,
    private val client: SupabaseClient,
    private val isOnlineFlow: StateFlow<Boolean> = MutableStateFlow(true),
    private val onPrefetchVideo: (String) -> Unit = {}
) {
    companion object {
        internal fun validSoundUuidOrNull(raw: String?): String? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            return runCatching { UUID.fromString(trimmed).toString() }.getOrNull()
        }

        private val errorPayloadAdapter by lazy {
            Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
                .adapter(SupabaseErrorPayload::class.java)
        }

        private fun String?.cleanNonNull(): String =
            this?.trim()?.takeUnless { it.equals("null", ignoreCase = true) }.orEmpty()

        internal fun extractErrorDetails(raw: String?): Pair<String, String> {
            if (raw.isNullOrBlank()) return "" to ""
            return try {
                val payload = errorPayloadAdapter.fromJson(raw) ?: return "" to ""
                val code = payload.errorCode.cleanNonNull()
                    .ifBlank { payload.code.cleanNonNull() }
                val msg = payload.msg.cleanNonNull()
                    .ifBlank { payload.errorDescription.cleanNonNull() }
                    .ifBlank { payload.message.cleanNonNull() }
                    .ifBlank { payload.error.cleanNonNull() }
                val hint = payload.hint.cleanNonNull()
                val combined = listOf(msg, hint).filter { it.isNotBlank() }.joinToString(" — ")
                code to combined
            } catch (_: Exception) {
                "" to ""
            }
        }

        internal fun isMissingColumnSchemaError(rawError: String?, httpCode: Int?): Boolean {
            if (httpCode != null && httpCode != 400 && httpCode != 404) return false
            val lower = rawError.orEmpty().lowercase()
            return lower.contains("pgrst204") ||
                lower.contains("schema cache") ||
                (lower.contains("could not find") && lower.contains("column"))
        }
    }
    val isOnline: StateFlow<Boolean> = isOnlineFlow
    val isDarkMode: Flow<Boolean> = preferences.isDarkMode
    val languageCode: Flow<String> = preferences.languageCode
    val sessionFlow: Flow<StoredSession> = preferences.sessionFlow
    val adminSessionFlow: Flow<StoredAdminSession> = preferences.adminSessionFlow
    val appSettingsFlow: Flow<AdminAppSettings> = preferences.appSettingsFlow
    val userSettingsFlow: Flow<com.example.data.local.UserSettingsPrefs> = preferences.userSettingsFlow
    val locationPreferencesFlow: Flow<LocationPreferences> = preferences.locationPreferencesFlow

    suspend fun getLocationPreferencesSnapshot(): LocationPreferences =
        preferences.getLocationPreferencesSnapshot()

    fun prefetchVideos(urls: List<String>) {
        if (!isOnlineFlow.value) return
        urls.forEach { url ->
            if (url.isNotBlank()) onPrefetchVideo(url)
        }
    }

    private fun locationMetadataMap(prefs: LocationPreferences): Map<String, String> = mapOf(
        "country" to prefs.country,
        "preferred_language" to prefs.preferredLanguage,
        "preferred_region" to prefs.preferredRegion,
        "preferred_state" to prefs.preferredState,
        "preferred_division" to prefs.preferredDivision
    )

    private fun extractLocationFromAuthUser(authUser: AuthUserDto?): LocationPreferences {
        val meta = authUser?.userMetadata ?: return LocationPreferences()
        fun str(key: String): String = (meta[key] as? String)?.trim().orEmpty()
        return LocationPreferences(
            country = str("country"),
            preferredLanguage = str("preferred_language"),
            preferredRegion = str("preferred_region"),
            preferredState = str("preferred_state"),
            preferredDivision = str("preferred_division")
        ).normalized()
    }

    suspend fun updateLocationPreferences(
        locationPrefs: LocationPreferences
    ): Result<LocationPreferences> = withContext(Dispatchers.IO) {
        val normalized = locationPrefs.normalized()
        if (normalized.country.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Please select your country."))
        }
        preferences.saveLocationPreferences(normalized)
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn || !SupabaseConfig.isConfigured) {
            return@withContext Result.success(normalized)
        }
        try {
            val rpcResp = runCatching {
                client.restApi.updateUserLocationPreferences(
                    mapOf(
                        "p_country" to normalized.country,
                        "p_preferred_language" to normalized.preferredLanguage,
                        "p_preferred_region" to normalized.preferredRegion,
                        "p_preferred_state" to normalized.preferredState,
                        "p_preferred_division" to normalized.preferredDivision
                    )
                )
            }.getOrNull()
            if (rpcResp != null && rpcResp.isSuccessful) {
                runCatching {
                    client.authApi.updateAuthUser(
                        AuthUserMetadataUpdateRequest(locationMetadataMap(normalized))
                    )
                }
                return@withContext Result.success(normalized)
            }

            try {
                client.restApi.updateUserProfile(
                    "eq.${session.userId}",
                    locationMetadataMap(normalized)
                )
                runCatching {
                    client.authApi.updateAuthUser(
                        AuthUserMetadataUpdateRequest(locationMetadataMap(normalized))
                    )
                }
            } catch (e: HttpException) {
                val raw = readHttpErrorBody(e)
                if (isMissingColumnSchemaError(raw ?: e.message(), e.code())) {
                    val authUpdate = runCatching {
                        client.authApi.updateAuthUser(
                            AuthUserMetadataUpdateRequest(locationMetadataMap(normalized))
                        )
                    }.getOrNull()
                    if (authUpdate != null && authUpdate.code() == 401) {
                        return@withContext Result.failure(
                            IllegalStateException("Session expired. Please sign in again.")
                        )
                    }
                    return@withContext Result.success(normalized)
                } else {
                    return@withContext Result.failure(friendlyException(e, rawOverride = raw))
                }
            }
            Result.success(normalized)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun setDarkMode(enabled: Boolean) = preferences.setDarkMode(enabled)
    suspend fun setLanguageCode(code: String) = preferences.setLanguageCode(code)
    suspend fun updateUserSettings(
        transform: (com.example.data.local.UserSettingsPrefs) -> com.example.data.local.UserSettingsPrefs
    ) {
        val current = preferences.getUserSettingsSnapshot()
        preferences.saveUserSettings(transform(current))
    }

    suspend fun clearWatchHistory() = preferences.clearWatchHistory()

    fun isSupabaseConfigured(): Boolean = SupabaseConfig.isConfigured

    private fun readHttpErrorBody(e: HttpException): String? =
        runCatching { e.response()?.errorBody()?.string() }.getOrNull()

    private fun format404Message(code: String, detail: String, raw: String?): String {
        val lower = "$detail ${raw.orEmpty()}".lowercase()
        return when {
            code == "PGRST205" || lower.contains("could not find the table") || lower.contains("does not exist") ->
                "Database tables not found (HTTP 404). Open Supabase SQL Editor and run supabase/schema.sql."
            code == "PGRST202" || lower.contains("could not find the function") ->
                "Supabase RPC function missing (HTTP 404). Open Supabase SQL Editor and run supabase/schema.sql."
            code == "PGRST204" || (lower.contains("schema cache") && lower.contains("column")) ->
                "Database schema is out of date. Open Supabase SQL Editor and run the updated supabase/schema.sql."
            code == "PGRST200" || lower.contains("could not find a relationship") ->
                "Supabase table relationships need setup (HTTP 404). Run supabase/schema.sql in Supabase SQL Editor."
            lower.contains("bucket not found") ->
                "Supabase Storage bucket missing (HTTP 404). Run supabase/schema.sql to create 'videos' and 'avatars' buckets."
            else ->
                "Supabase endpoint returned HTTP 404. Verify SUPABASE_URL in Secrets is https://<project-ref>.supabase.co and run supabase/schema.sql."
        }
    }

    private fun parseSupabaseError(raw: String?, fallback: String, httpCode: Int? = null): String {
        val (code, detail) = extractErrorDetails(raw)
        if (httpCode == 404 || code == "PGRST205" || code == "PGRST202") {
            return format404Message(code, detail, raw)
        }
        val lower = "$code $detail".lowercase()
        if (code == "email_not_confirmed" || lower.contains("email not confirmed")) {
            return "Email confirmation is enabled in Supabase. Turn OFF 'Confirm email' in Supabase -> Authentication -> Providers -> Email."
        }
        if (code == "phone_not_confirmed" || lower.contains("phone not confirmed")) {
            return "Phone confirmation is enabled in Supabase. Run the updated supabase/schema.sql or turn off Phone confirmation in Supabase Auth."
        }
        if (code == "phone_provider_disabled" || lower.contains("phone logins are disabled")) {
            return "Phone sign-in is disabled in Supabase Auth settings. Enable the Phone provider in Supabase -> Authentication -> Providers -> Phone."
        }
        if (code == "invalid_credentials" || lower.contains("invalid login credentials")) {
            return "Invalid credentials. If you haven't registered yet, tap Create Account below."
        }
        return detail.ifBlank { fallback }
    }

    private fun friendlyException(e: Throwable, rawOverride: String? = null): Throwable {
        if (!isOnlineFlow.value || e is IOException) {
            return IllegalStateException("Internet Connection Failed", e)
        }
        if (e is HttpException) {
            val httpCode = e.code()
            val raw = rawOverride ?: readHttpErrorBody(e)
            val (pgCode, detail) = extractErrorDetails(raw)
            val message = when (httpCode) {
                404 -> format404Message(pgCode, detail, raw)
                401 -> detail.ifBlank { "Session expired or invalid Supabase key (HTTP 401). Please sign in again." }
                403 -> detail.ifBlank { "Permission denied by Supabase security policy (HTTP 403)." }
                else -> detail.ifBlank { "Supabase request failed (HTTP $httpCode)." }
            }
            return IllegalStateException(message, e)
        }
        return e
    }

    private suspend fun syncLocationPreferencesFromUser(user: UserProfile) {
        val cleanCountry = LocationCatalog.canonicalizeCountry(user.country)
        if (cleanCountry.isBlank()) {
            preferences.saveLocationPreferences(LocationPreferences())
            return
        }
        preferences.saveLocationPreferences(
            LocationPreferences(
                country = cleanCountry,
                preferredLanguage = user.preferredLanguage,
                preferredRegion = user.preferredRegion,
                preferredState = user.preferredState,
                preferredDivision = user.preferredDivision
            )
        )
    }

    private suspend fun saveAndHydrateProfile(
        session: AuthSession,
        displayNameHint: String? = null,
        phoneHint: String? = null,
        countryHint: String? = null
    ): Result<UserProfile> {
        val uid = session.user?.id ?: return Result.failure(IllegalStateException("Missing user ID"))
        val cleanEmail = session.user.email?.trim()?.lowercase().orEmpty()
        val cleanPhone = session.user.phone?.trim().orEmpty().ifBlank { phoneHint?.trim().orEmpty() }
        val authMetaLocation = extractLocationFromAuthUser(session.user)
        val cleanCountryHint = LocationCatalog.canonicalizeCountry(
            countryHint?.trim().orEmpty().ifBlank { authMetaLocation.country }
        )
        preferences.saveSession(
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
            userId = uid,
            email = cleanEmail.ifBlank { cleanPhone }
        )
        val existing = runCatching { client.restApi.getUserById("eq.$uid") }.getOrDefault(emptyList())
        if (existing.isNotEmpty()) {
            var current = existing.first()
            if (current.isBlocked) {
                preferences.clearSession()
                return Result.failure(IllegalStateException("Your account has been suspended by an administrator."))
            }
            val patchUpdates = mutableMapOf<String, Any>()
            if (cleanEmail.isNotBlank() && current.email.isNullOrBlank()) {
                patchUpdates["email"] = cleanEmail
                current = current.copy(email = cleanEmail)
            }
            if (cleanPhone.isNotBlank() && current.phone.isNullOrBlank()) {
                patchUpdates["phone"] = cleanPhone
                current = current.copy(phone = cleanPhone)
            }
            if (cleanCountryHint.isNotBlank() && current.country.isBlank()) {
                patchUpdates["country"] = cleanCountryHint
                current = current.copy(country = cleanCountryHint)
            }
            if (current.country.isBlank() || current.country.equals(authMetaLocation.country, ignoreCase = true)) {
                current = current.copy(
                    country = current.country.ifBlank { authMetaLocation.country },
                    preferredLanguage = current.preferredLanguage.ifBlank { authMetaLocation.preferredLanguage },
                    preferredRegion = current.preferredRegion.ifBlank { authMetaLocation.preferredRegion },
                    preferredState = current.preferredState.ifBlank { authMetaLocation.preferredState },
                    preferredDivision = current.preferredDivision.ifBlank { authMetaLocation.preferredDivision }
                )
            }
            if (patchUpdates.isNotEmpty()) {
                val patchRes = runCatching { client.restApi.updateUserProfile("eq.$uid", patchUpdates) }
                val err = patchRes.exceptionOrNull()
                if (err != null && patchUpdates.containsKey("country")) {
                    val remaining = patchUpdates - "country"
                    if (remaining.isNotEmpty()) {
                        runCatching { client.restApi.updateUserProfile("eq.$uid", remaining) }
                    }
                }
            }
            syncLocationPreferencesFromUser(current)
            return Result.success(current)
        }
        val cleanName = displayNameHint?.trim().orEmpty().ifBlank {
            cleanEmail.substringBefore("@").ifBlank { cleanPhone }.ifBlank { "Creator" }
        }
        val baseHandle = cleanName
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")
            .ifBlank { cleanEmail.substringBefore("@").lowercase().replace(Regex("[^a-z0-9]"), "") }
            .ifBlank { cleanPhone.filter { it.isDigit() }.takeLast(6) }
            .ifBlank { "blade" }
        val internalHandle = "${baseHandle}_${uid.take(6)}"
        val upsert = UserProfileUpsert(
            id = uid,
            username = internalHandle,
            displayName = cleanName,
            email = cleanEmail.ifBlank { null },
            phone = cleanPhone.ifBlank { null },
            country = cleanCountryHint
        )
        val created = try {
            client.restApi.upsertUserProfile(upsert)
        } catch (e: HttpException) {
            val raw = readHttpErrorBody(e)
            when {
                isMissingColumnSchemaError(raw ?: e.message(), e.code()) -> {
                    val legacyUpsert = UserProfileLegacyUpsert(
                        id = uid,
                        username = internalHandle,
                        displayName = cleanName,
                        email = cleanEmail.ifBlank { null },
                        phone = cleanPhone.ifBlank { null }
                    )
                    try {
                        client.restApi.upsertUserProfileLegacy(legacyUpsert)
                    } catch (legacyErr: HttpException) {
                        if (legacyErr.code() == 409) {
                            val uniqueHandle = "${baseHandle}_${uid.take(8)}"
                            client.restApi.upsertUserProfileLegacy(legacyUpsert.copy(username = uniqueHandle))
                        } else {
                            throw legacyErr
                        }
                    }
                }
                e.code() == 409 -> {
                    val uniqueHandle = "${baseHandle}_${uid.take(8)}"
                    client.restApi.upsertUserProfile(upsert.copy(username = uniqueHandle))
                }
                else -> throw IllegalStateException(
                    parseSupabaseError(raw, "Unable to create user profile.", e.code()),
                    e
                )
            }
        }
        var resolvedProfile = created.firstOrNull() ?: UserProfile(
            id = uid,
            username = internalHandle,
            displayName = cleanName,
            email = cleanEmail.ifBlank { null },
            phone = cleanPhone.ifBlank { null },
            country = cleanCountryHint
        )
        if (resolvedProfile.country.isBlank() && cleanCountryHint.isNotBlank()) {
            resolvedProfile = resolvedProfile.copy(country = cleanCountryHint)
        }
        if (resolvedProfile.country.equals(authMetaLocation.country, ignoreCase = true)) {
            resolvedProfile = resolvedProfile.copy(
                preferredLanguage = resolvedProfile.preferredLanguage.ifBlank { authMetaLocation.preferredLanguage },
                preferredRegion = resolvedProfile.preferredRegion.ifBlank { authMetaLocation.preferredRegion },
                preferredState = resolvedProfile.preferredState.ifBlank { authMetaLocation.preferredState },
                preferredDivision = resolvedProfile.preferredDivision.ifBlank { authMetaLocation.preferredDivision }
            )
        }
        syncLocationPreferencesFromUser(resolvedProfile)
        return Result.success(resolvedProfile)
    }

    // =========================================================================
    // AUTHENTICATION
    // =========================================================================
    suspend fun signInWithEmail(email: String, password: String): Result<UserProfile> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Supabase is not configured in Secrets."))
        }
        val cleanEmail = email.trim().lowercase()
        try {
            val resp = client.authApi.signInWithPassword(SignInPasswordRequest(cleanEmail, password))
            if (!resp.isSuccessful || resp.body() == null) {
                return@withContext Result.failure(
                    IllegalStateException(
                        parseSupabaseError(resp.errorBody()?.string(), "Invalid email or password.", resp.code())
                    )
                )
            }
            saveAndHydrateProfile(resp.body()!!)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun signUpWithEmail(
        name: String,
        email: String,
        password: String,
        country: String
    ): Result<UserProfile> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Supabase is not configured in Secrets."))
        }
        val cleanCountry = country.trim()
        if (cleanCountry.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Please select your country."))
        }
        val settings = fetchAppSettings()
        if (!settings.allowRegistrations) {
            return@withContext Result.failure(
                IllegalStateException("New account registrations are temporarily paused by an administrator.")
            )
        }
        val cleanName = name.trim()
        val cleanEmail = email.trim().lowercase()
        val alreadyRegisteredMessage =
            "An account with this email address is already registered. Please sign in instead."

        try {
            val existingUsers = runCatching {
                client.restApi.getUsersByEmail(emailFilter = "ilike.$cleanEmail", limit = 10)
            }.getOrDefault(emptyList())
            if (existingUsers.any { it.email?.trim()?.equals(cleanEmail, ignoreCase = true) == true }) {
                return@withContext Result.failure(IllegalStateException(alreadyRegisteredMessage))
            }

            val resp = client.authApi.signUp(
                SignUpRequest(
                    email = cleanEmail,
                    password = password,
                    data = mapOf(
                        "display_name" to cleanName,
                        "full_name" to cleanName,
                        "country" to cleanCountry
                    )
                )
            )
            if (!resp.isSuccessful || resp.body() == null) {
                val errRaw = resp.errorBody()?.string()
                val (errCode, errDetail) = extractErrorDetails(errRaw)
                val lowerDetail = errDetail.lowercase()
                val alreadyExists = errCode == "user_already_exists" ||
                    errCode == "email_exists" ||
                    lowerDetail.contains("already registered") ||
                    lowerDetail.contains("already exists")
                if (alreadyExists) {
                    return@withContext Result.failure(IllegalStateException(alreadyRegisteredMessage))
                }
                return@withContext Result.failure(
                    IllegalStateException(
                        parseSupabaseError(errRaw, "Unable to create account.", resp.code())
                    )
                )
            }

            val body = resp.body()!!
            if (body.accessToken.isBlank() && body.user?.identities != null && body.user.identities.isEmpty()) {
                return@withContext Result.failure(IllegalStateException(alreadyRegisteredMessage))
            }

            if (body.accessToken.isNotBlank()) {
                return@withContext saveAndHydrateProfile(
                    body,
                    displayNameHint = cleanName,
                    countryHint = cleanCountry
                )
            }

            // Email verification is off: immediately sign in so newly registered user enters the app
            val loginResp = client.authApi.signInWithPassword(SignInPasswordRequest(cleanEmail, password))
            if (loginResp.isSuccessful && loginResp.body() != null && loginResp.body()!!.accessToken.isNotBlank()) {
                return@withContext saveAndHydrateProfile(
                    loginResp.body()!!,
                    displayNameHint = cleanName,
                    countryHint = cleanCountry
                )
            }
            val errMsg = parseSupabaseError(
                loginResp.errorBody()?.string(),
                "Turn off 'Confirm email' in Supabase Auth -> Providers -> Email to allow instant sign-up.",
                loginResp.code()
            )
            Result.failure(IllegalStateException(errMsg))
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun signInWithPhone(phone: String, password: String): Result<UserProfile> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Supabase is not configured in Secrets."))
        }
        val normalizedPhone = AuthInputValidator.normalizePhone(phone)
        try {
            val resp = client.authApi.signInWithPhonePassword(SignInPhonePasswordRequest(normalizedPhone, password))
            if (!resp.isSuccessful || resp.body() == null) {
                return@withContext Result.failure(
                    IllegalStateException(
                        parseSupabaseError(resp.errorBody()?.string(), "Invalid phone number or password.", resp.code())
                    )
                )
            }
            saveAndHydrateProfile(resp.body()!!, phoneHint = normalizedPhone)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun signUpWithPhone(
        name: String,
        phone: String,
        password: String,
        country: String
    ): Result<UserProfile> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Supabase is not configured in Secrets."))
        }
        val cleanCountry = country.trim()
        if (cleanCountry.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Please select your country."))
        }
        val settings = fetchAppSettings()
        if (!settings.allowRegistrations) {
            return@withContext Result.failure(
                IllegalStateException("New account registrations are temporarily paused by an administrator.")
            )
        }
        val cleanName = name.trim()
        val normalizedPhone = AuthInputValidator.normalizePhone(phone)
        val alreadyRegisteredMessage =
            "An account with this phone number is already registered. Please sign in instead."

        try {
            val existingUsers = runCatching {
                client.restApi.getUsersByPhone(phoneFilter = "eq.$normalizedPhone", limit = 10)
            }.getOrDefault(emptyList())
            if (existingUsers.any { it.phone?.trim() == normalizedPhone }) {
                return@withContext Result.failure(IllegalStateException(alreadyRegisteredMessage))
            }

            val resp = client.authApi.signUpWithPhone(
                SignUpPhoneRequest(
                    phone = normalizedPhone,
                    password = password,
                    data = mapOf(
                        "display_name" to cleanName,
                        "full_name" to cleanName,
                        "country" to cleanCountry
                    )
                )
            )
            if (!resp.isSuccessful || resp.body() == null) {
                val errRaw = resp.errorBody()?.string()
                val (errCode, errDetail) = extractErrorDetails(errRaw)
                val lowerDetail = errDetail.lowercase()
                val alreadyExists = errCode == "user_already_exists" ||
                    errCode == "phone_exists" ||
                    lowerDetail.contains("already registered") ||
                    lowerDetail.contains("already exists")
                if (alreadyExists) {
                    return@withContext Result.failure(IllegalStateException(alreadyRegisteredMessage))
                }
                return@withContext Result.failure(
                    IllegalStateException(
                        parseSupabaseError(errRaw, "Unable to create account.", resp.code())
                    )
                )
            }

            val body = resp.body()!!
            if (body.accessToken.isBlank() && body.user?.identities != null && body.user.identities.isEmpty()) {
                return@withContext Result.failure(IllegalStateException(alreadyRegisteredMessage))
            }

            if (body.accessToken.isNotBlank()) {
                return@withContext saveAndHydrateProfile(
                    body,
                    displayNameHint = cleanName,
                    phoneHint = normalizedPhone,
                    countryHint = cleanCountry
                )
            }

            val loginResp = client.authApi.signInWithPhonePassword(SignInPhonePasswordRequest(normalizedPhone, password))
            if (loginResp.isSuccessful && loginResp.body() != null && loginResp.body()!!.accessToken.isNotBlank()) {
                return@withContext saveAndHydrateProfile(
                    loginResp.body()!!,
                    displayNameHint = cleanName,
                    phoneHint = normalizedPhone,
                    countryHint = cleanCountry
                )
            }
            val errMsg = parseSupabaseError(
                loginResp.errorBody()?.string(),
                "Turn off Phone confirmation in Supabase Auth -> Providers -> Phone (or run updated supabase/schema.sql) for instant phone sign-up.",
                loginResp.code()
            )
            Result.failure(IllegalStateException(errMsg))
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun signInWithGoogleIdToken(idToken: String): Result<UserProfile> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Supabase is not configured in Secrets."))
        }
        try {
            val resp = client.authApi.signInWithIdToken(IdTokenRequest(idToken = idToken))
            if (!resp.isSuccessful || resp.body() == null) {
                return@withContext Result.failure(
                    IllegalStateException(
                        parseSupabaseError(resp.errorBody()?.string(), "Google sign-in failed.", resp.code())
                    )
                )
            }
            saveAndHydrateProfile(resp.body()!!)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun prepareGoogleBrowserOAuthUrl(): Result<String> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Supabase is not configured in Secrets."))
        }
        val verifierBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val codeVerifier = Base64.encodeToString(
            verifierBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        preferences.savePendingOAuthVerifier(codeVerifier)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        val codeChallenge = Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val redirectTo = Uri.encode("com.aistudio.blade.kxmqpz://auth-callback")
        val authorizeUrl =
            "${SupabaseConfig.url}/auth/v1/authorize?provider=google" +
                "&redirect_to=$redirectTo" +
                "&code_challenge=${Uri.encode(codeChallenge)}" +
                "&code_challenge_method=s256"
        Result.success(authorizeUrl)
    }

    suspend fun completeOAuthCallbackUri(uri: Uri): Result<UserProfile> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Supabase is not configured in Secrets."))
        }
        try {
            val fragmentParams = parseQueryString(uri.encodedFragment)
            val errDesc = uri.getQueryParameter("error_description")
                ?: fragmentParams["error_description"]
                ?: uri.getQueryParameter("error")
                ?: fragmentParams["error"]
            if (!errDesc.isNullOrBlank()) {
                return@withContext Result.failure(IllegalStateException(errDesc.replace('+', ' ')))
            }

            val authCode = uri.getQueryParameter("code") ?: fragmentParams["code"]
            if (!authCode.isNullOrBlank()) {
                val verifier = preferences.consumePendingOAuthVerifier()
                if (verifier.isBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException("Google sign-in session expired. Please try again.")
                    )
                }
                val resp = client.authApi.exchangePkceCode(
                    PkceTokenRequest(authCode = authCode, codeVerifier = verifier)
                )
                if (!resp.isSuccessful || resp.body() == null) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            parseSupabaseError(resp.errorBody()?.string(), "Google sign-in failed.", resp.code())
                        )
                    )
                }
                return@withContext saveAndHydrateProfile(resp.body()!!)
            }

            val accessToken = fragmentParams["access_token"] ?: uri.getQueryParameter("access_token")
            val refreshToken = fragmentParams["refresh_token"] ?: uri.getQueryParameter("refresh_token").orEmpty()
            val expiresIn = (fragmentParams["expires_in"] ?: uri.getQueryParameter("expires_in"))
                ?.toLongOrNull() ?: 3600L

            if (!accessToken.isNullOrBlank()) {
                val userResp = client.authApi.getAuthUser("Bearer $accessToken")
                val authUser = if (userResp.isSuccessful) userResp.body() else null
                if (authUser == null) {
                    return@withContext Result.failure(IllegalStateException("Could not verify Google account session."))
                }
                return@withContext saveAndHydrateProfile(
                    AuthSession(
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        expiresIn = expiresIn,
                        user = authUser
                    )
                )
            }

            Result.failure(IllegalStateException("Google sign-in did not return an authorization token."))
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    private fun parseQueryString(encoded: String?): Map<String, String> {
        if (encoded.isNullOrBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        encoded.split('&').forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val k = runCatching { URLDecoder.decode(pair.substring(0, idx), "UTF-8") }.getOrDefault(pair.substring(0, idx))
                val v = runCatching { URLDecoder.decode(pair.substring(idx + 1), "UTF-8") }.getOrDefault(pair.substring(idx + 1))
                map[k] = v
            }
        }
        return map
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Supabase is not configured in Secrets."))
        }
        try {
            val resp = client.authApi.recoverPassword(RecoverPasswordRequest(email.trim()))
            if (!resp.isSuccessful) {
                return@withContext Result.failure(
                    IllegalStateException(
                        parseSupabaseError(resp.errorBody()?.string(), "Could not send reset email.", resp.code())
                    )
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun restoreCurrentUser(): UserProfile? = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext null
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext null
        try {
            val list = client.restApi.getUserById("eq.${session.userId}")
            if (list.isNotEmpty()) {
                var user = list.first()
                if (user.isBlocked) {
                    preferences.clearSession()
                    return@withContext null
                }
                if (user.country.isBlank()) {
                    val authLoc = runCatching {
                        val resp = client.authApi.getAuthUser("Bearer ${session.accessToken}")
                        if (resp.isSuccessful) extractLocationFromAuthUser(resp.body()) else LocationPreferences()
                    }.getOrDefault(LocationPreferences())
                    if (authLoc.country.isNotBlank()) {
                        user = user.copy(
                            country = authLoc.country,
                            preferredLanguage = authLoc.preferredLanguage,
                            preferredRegion = authLoc.preferredRegion,
                            preferredState = authLoc.preferredState,
                            preferredDivision = authLoc.preferredDivision
                        )
                        runCatching {
                            client.restApi.updateUserProfile("eq.${session.userId}", locationMetadataMap(authLoc))
                        }
                    }
                } else {
                    val canonical = LocationCatalog.canonicalizeCountry(user.country)
                    if (canonical != user.country) {
                        user = user.copy(country = canonical)
                    }
                }
                syncLocationPreferencesFromUser(user)
                return@withContext user
            }
        } catch (_: Exception) {
            // Try refreshing expired token
            if (session.refreshToken.isNotBlank()) {
                val refreshResp = runCatching {
                    client.authApi.refreshSession(RefreshTokenRequest(session.refreshToken))
                }.getOrNull()
                if (refreshResp != null && refreshResp.isSuccessful && refreshResp.body() != null) {
                    return@withContext saveAndHydrateProfile(refreshResp.body()!!).getOrNull()
                }
            }
        }
        null
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        runCatching { client.authApi.signOut() }
        preferences.clearSession()
    }

    // =========================================================================
    // FEED & RECOMMENDATION ENGINE
    // =========================================================================
    suspend fun getFeedReloadState(): FeedReloadState = preferences.getFeedReloadState()

    suspend fun updateFeedReloadState(
        forYou: Boolean,
        newlySurfacedIds: List<String>,
        incrementCycle: Boolean,
        nextOffset: Int
    ): FeedReloadState = preferences.updateFeedReloadState(
        forYou = forYou,
        newlySurfacedIds = newlySurfacedIds,
        incrementCycle = incrementCycle,
        nextOffset = nextOffset
    )

    private suspend fun resolveAuthoritativeLocationPreferences(
        session: StoredSession,
        preferProfile: Boolean = false
    ): LocationPreferences {
        val local = preferences.getLocationPreferencesSnapshot().normalized()
        if (!session.isLoggedIn) return local
        if (!preferProfile && local.country.isNotBlank()) return local

        val profileUser = runCatching {
            client.restApi.getUserById("eq.${session.userId}").firstOrNull()
        }.getOrNull()
        if (profileUser != null && profileUser.country.isNotBlank()) {
            val resolved = profileUser.toLocationPreferences()
            preferences.saveLocationPreferences(resolved)
            return resolved
        }

        val authLoc = runCatching {
            val resp = client.authApi.getAuthUser("Bearer ${session.accessToken}")
            if (resp.isSuccessful) extractLocationFromAuthUser(resp.body()) else LocationPreferences()
        }.getOrDefault(LocationPreferences())
        if (authLoc.country.isNotBlank()) {
            runCatching {
                client.restApi.updateUserProfile("eq.${session.userId}", locationMetadataMap(authLoc))
            }
            preferences.saveLocationPreferences(authLoc)
            return authLoc
        }

        return local
    }

    suspend fun fetchFeed(
        forYou: Boolean,
        offset: Int = 0,
        demoteIds: Set<String> = emptySet(),
        reloadCycle: Int = 0,
        currentSeq: Int = 0
    ): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.success(emptyList())
        }
        if (!isOnlineFlow.value) {
            return@withContext Result.failure(IllegalStateException("Internet Connection Failed"))
        }
        try {
            val session = preferences.getSessionSnapshot()
            val locationPrefs = resolveAuthoritativeLocationPreferences(session)
            if (session.isLoggedIn && locationPrefs.country.isBlank()) {
                return@withContext Result.failure(
                    MissingCountryException("Your account country is not set. Please select your country to load videos for your region.")
                )
            }
            val blockedIds = if (session.isLoggedIn) {
                runCatching { client.restApi.getBlockedUsers("eq.${session.userId}").map { it.blockedId }.toSet() }
                    .getOrDefault(emptySet())
            } else emptySet()

            val followingIds = if (session.isLoggedIn) {
                runCatching { client.restApi.getFollowingIds("eq.${session.userId}").map { it.followingId }.toSet() }
                    .getOrDefault(emptySet())
            } else emptySet()

            val likedIds = if (session.isLoggedIn) {
                runCatching { client.restApi.getLikesForUser("eq.${session.userId}").map { it.videoId }.toSet() }
                    .getOrDefault(emptySet())
            } else emptySet()

            val savedIds = if (session.isLoggedIn) {
                runCatching { client.restApi.getSavedForUser("eq.${session.userId}").map { it.videoId }.toSet() }
                    .getOrDefault(emptySet())
            } else emptySet()

            val rawVideos = if (!forYou) {
                if (followingIds.isEmpty()) emptyList()
                else {
                    val primary = fetchFollowingPage(
                        followingIds = followingIds,
                        limit = if (offset > 0) 60 else 100,
                        offset = offset,
                        locationPrefs = locationPrefs
                    )
                    if (offset > 0) {
                        val latestLimit = if (primary.size < 15) 100 else 50
                        val latest = fetchFollowingPage(
                            followingIds = followingIds,
                            limit = latestLimit,
                            offset = 0,
                            locationPrefs = locationPrefs
                        )
                        if (primary.size < 60) {
                            preferences.updateFeedReloadState(
                                forYou = false,
                                newlySurfacedIds = emptyList(),
                                incrementCycle = false,
                                nextOffset = 0
                            )
                        }
                        (latest + primary).distinctBy { it.id }
                    } else {
                        primary
                    }
                }
            } else {
                val primary = fetchPublicPage(limit = if (offset > 0) 60 else 100, offset = offset, locationPrefs = locationPrefs)
                if (offset > 0) {
                    val latestLimit = if (primary.size < 15) 100 else 50
                    val latest = fetchPublicPage(limit = latestLimit, offset = 0, locationPrefs = locationPrefs)
                    if (primary.size < 60) {
                        preferences.updateFeedReloadState(
                            forYou = true,
                            newlySurfacedIds = emptyList(),
                            incrementCycle = false,
                            nextOffset = 0
                        )
                    }
                    (latest + primary).distinctBy { it.id }
                } else {
                    primary
                }
            }

            val visible = rawVideos
                .filter {
                    it.userId !in blockedIds &&
                        it.creator?.isBlocked != true &&
                        LocationCatalog.matchesVideo(it, locationPrefs)
                }
                .map { video ->
                    video.copy(
                        isLiked = video.id in likedIds,
                        isSaved = video.id in savedIds,
                        isFollowingCreator = video.userId in followingIds
                    )
                }

            val affinity = preferences.getWatchAffinity()
            val effectiveSeq = maxOf(
                currentSeq,
                affinity.lastShownSeq.values.maxOrNull() ?: 0
            )
            val ranked = rankVideosWithSignals(
                videos = visible,
                forYou = forYou,
                followingIds = followingIds,
                affinity = affinity,
                demoteIds = demoteIds,
                reloadCycle = reloadCycle,
                currentSeq = effectiveSeq
            )

            Result.success(ranked)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun fetchMoreVideos(
        forYou: Boolean,
        offset: Int,
        existingIds: Set<String>,
        reloadCycle: Int = 0
    ): Result<FeedBatchResult> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.success(FeedBatchResult(emptyList(), 0))
        }
        if (!isOnlineFlow.value) {
            return@withContext Result.failure(IllegalStateException("Internet Connection Failed"))
        }
        try {
            val session = preferences.getSessionSnapshot()
            val locationPrefs = resolveAuthoritativeLocationPreferences(session)
            if (session.isLoggedIn && locationPrefs.country.isBlank()) {
                return@withContext Result.failure(
                    MissingCountryException("Your account country is not set. Please select your country to load videos for your region.")
                )
            }
            val blockedIds = if (session.isLoggedIn) {
                runCatching { client.restApi.getBlockedUsers("eq.${session.userId}").map { it.blockedId }.toSet() }
                    .getOrDefault(emptySet())
            } else emptySet()

            val followingIds = if (session.isLoggedIn) {
                runCatching { client.restApi.getFollowingIds("eq.${session.userId}").map { it.followingId }.toSet() }
                    .getOrDefault(emptySet())
            } else emptySet()

            val likedIds = if (session.isLoggedIn) {
                runCatching { client.restApi.getLikesForUser("eq.${session.userId}").map { it.videoId }.toSet() }
                    .getOrDefault(emptySet())
            } else emptySet()

            val savedIds = if (session.isLoggedIn) {
                runCatching { client.restApi.getSavedForUser("eq.${session.userId}").map { it.videoId }.toSet() }
                    .getOrDefault(emptySet())
            } else emptySet()

            val rawVideos = if (!forYou) {
                if (followingIds.isEmpty()) emptyList()
                else fetchFollowingPage(followingIds, limit = 50, offset = offset, locationPrefs = locationPrefs)
            } else {
                fetchPublicPage(limit = 50, offset = offset, locationPrefs = locationPrefs)
            }

            if (rawVideos.isEmpty()) {
                return@withContext Result.success(FeedBatchResult(emptyList(), 0))
            }

            val visible = rawVideos
                .filter {
                    it.userId !in blockedIds &&
                        it.id !in existingIds &&
                        it.creator?.isBlocked != true &&
                        LocationCatalog.matchesVideo(it, locationPrefs)
                }
                .map { video ->
                    video.copy(
                        isLiked = video.id in likedIds,
                        isSaved = video.id in savedIds,
                        isFollowingCreator = video.userId in followingIds
                    )
                }

            val affinity = preferences.getWatchAffinity()
            val effectiveSeq = affinity.lastShownSeq.values.maxOrNull() ?: 0
            val ranked = rankVideosWithSignals(
                videos = visible,
                forYou = forYou,
                followingIds = followingIds,
                affinity = affinity,
                demoteIds = emptySet(),
                reloadCycle = reloadCycle,
                currentSeq = effectiveSeq
            )

            Result.success(FeedBatchResult(videos = ranked, rawFetchedCount = rawVideos.size))
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun fetchOlderReplayBatch(
        forYou: Boolean,
        excludeTailIds: Set<String>,
        reloadCycle: Int = 0
    ): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext Result.success(emptyList())
        if (!isOnlineFlow.value) return@withContext Result.failure(IllegalStateException("Internet Connection Failed"))
        try {
            val session = preferences.getSessionSnapshot()
            val locationPrefs = resolveAuthoritativeLocationPreferences(session)
            if (session.isLoggedIn && locationPrefs.country.isBlank()) {
                return@withContext Result.failure(
                    MissingCountryException("Your account country is not set. Please select your country to load videos for your region.")
                )
            }
            val blockedIds = if (session.isLoggedIn) {
                runCatching { client.restApi.getBlockedUsers("eq.${session.userId}").map { it.blockedId }.toSet() }
                    .getOrDefault(emptySet())
            } else emptySet()
            val followingIds = if (session.isLoggedIn) {
                runCatching { client.restApi.getFollowingIds("eq.${session.userId}").map { it.followingId }.toSet() }
                    .getOrDefault(emptySet())
            } else emptySet()
            val likedIds = if (session.isLoggedIn) {
                runCatching { client.restApi.getLikesForUser("eq.${session.userId}").map { it.videoId }.toSet() }
                    .getOrDefault(emptySet())
            } else emptySet()
            val savedIds = if (session.isLoggedIn) {
                runCatching { client.restApi.getSavedForUser("eq.${session.userId}").map { it.videoId }.toSet() }
                    .getOrDefault(emptySet())
            } else emptySet()

            val rawVideos = if (!forYou) {
                if (followingIds.isEmpty()) emptyList()
                else fetchFollowingPage(followingIds, limit = 60, offset = 0, locationPrefs = locationPrefs)
            } else {
                fetchPublicPage(limit = 60, offset = 0, locationPrefs = locationPrefs)
            }

            val visible = rawVideos
                .filter {
                    it.userId !in blockedIds &&
                        it.creator?.isBlocked != true &&
                        LocationCatalog.matchesVideo(it, locationPrefs)
                }
                .map { video ->
                    video.copy(
                        isLiked = video.id in likedIds,
                        isSaved = video.id in savedIds,
                        isFollowingCreator = video.userId in followingIds
                    )
                }

            if (visible.size <= 1) return@withContext Result.success(emptyList())

            val affinity = preferences.getWatchAffinity()
            val effectiveSeq = affinity.lastShownSeq.values.maxOrNull() ?: 0
            val ranked = rankVideosWithSignals(
                videos = visible,
                forYou = forYou,
                followingIds = followingIds,
                affinity = affinity,
                demoteIds = excludeTailIds,
                reloadCycle = reloadCycle + 1,
                currentSeq = effectiveSeq
            )
            // Keep immediate tail video out of index 0 of replay batch
            val filtered = ranked.filterNot { it.id in excludeTailIds }
            val result = if (filtered.isNotEmpty()) filtered.take(4) else ranked.take(4)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    private suspend fun fetchPublicPage(
        limit: Int,
        offset: Int,
        locationPrefs: LocationPreferences = LocationPreferences()
    ): List<VideoItem> {
        val normalized = locationPrefs.normalized()
        if (normalized.country.isNotBlank()) {
            val rpcVideos = try {
                val list = client.restApi.getEligibleFeedVideos(
                    mapOf(
                        "p_country" to normalized.country,
                        "p_language" to normalized.preferredLanguage,
                        "p_region" to normalized.preferredRegion,
                        "p_state" to normalized.preferredState,
                        "p_division" to normalized.preferredDivision,
                        "p_limit" to limit,
                        "p_offset" to offset
                    )
                )
                val creatorIds = list.map { it.userId }.filter { it.isNotBlank() }.distinct()
                val creatorsById = if (creatorIds.isNotEmpty()) {
                    runCatching {
                        client.restApi.getUsersByIds("in.(${creatorIds.joinToString(",")})").associateBy { it.id }
                    }.getOrDefault(emptyMap())
                } else emptyMap()
                list.map { v -> if (v.creator == null) v.copy(creator = creatorsById[v.userId]) else v }
            } catch (e: HttpException) {
                if (e.code() == 400 || e.code() == 404) null else throw e
            }
            if (rpcVideos != null) return rpcVideos
        }
        val countryFilter = LocationCatalog.sanitizedPostgrestCountryIlike(normalized.country)
        return try {
            client.restApi.getVideos(countryFilter = countryFilter, limit = limit, offset = offset)
        } catch (e: HttpException) {
            if (e.code() == 400 || e.code() == 404) {
                runCatching {
                    client.restApi.getVideos(
                        select = "*",
                        countryFilter = countryFilter,
                        limit = limit,
                        offset = offset
                    )
                }.recoverCatching { inner ->
                    val raw = (inner as? HttpException)?.let { readHttpErrorBody(it) }
                    if (countryFilter != null && isMissingColumnSchemaError(raw, (inner as? HttpException)?.code())) {
                        client.restApi.getVideos(select = "*", limit = limit, offset = offset)
                    } else {
                        throw inner
                    }
                }.getOrElse { throw e }
            } else throw e
        }
    }

    private suspend fun fetchFollowingPage(
        followingIds: Set<String>,
        limit: Int,
        offset: Int,
        locationPrefs: LocationPreferences = LocationPreferences()
    ): List<VideoItem> {
        val filter = "in.(${followingIds.joinToString(",")})"
        val countryFilter = LocationCatalog.sanitizedPostgrestCountryIlike(locationPrefs.country)
        return try {
            client.restApi.getVideosByFollowing(
                userIdInFilter = filter,
                countryFilter = countryFilter,
                limit = limit,
                offset = offset
            )
        } catch (e: HttpException) {
            if (e.code() == 400 || e.code() == 404) {
                runCatching {
                    client.restApi.getVideosByFollowing(
                        userIdInFilter = filter,
                        select = "*",
                        countryFilter = countryFilter,
                        limit = limit,
                        offset = offset
                    )
                }.recoverCatching { inner ->
                    val raw = (inner as? HttpException)?.let { readHttpErrorBody(it) }
                    if (countryFilter != null && isMissingColumnSchemaError(raw, (inner as? HttpException)?.code())) {
                        client.restApi.getVideosByFollowing(
                            userIdInFilter = filter,
                            select = "*",
                            limit = limit,
                            offset = offset
                        )
                    } else {
                        throw inner
                    }
                }.getOrElse { throw e }
            } else throw e
        }
    }

    suspend fun rankUpcomingVideos(
        videos: List<VideoItem>,
        forYou: Boolean,
        demoteIds: Set<String> = emptySet(),
        reloadCycle: Int = 0
    ): List<VideoItem> = withContext(Dispatchers.IO) {
        if (videos.size <= 1) return@withContext videos
        val affinity = preferences.getWatchAffinity()
        val effectiveSeq = affinity.lastShownSeq.values.maxOrNull() ?: 0
        val followingIds = videos.filter { it.isFollowingCreator }.map { it.userId }.toSet()
        rankVideosWithSignals(
            videos = videos,
            forYou = forYou,
            followingIds = followingIds,
            affinity = affinity,
            demoteIds = demoteIds,
            reloadCycle = reloadCycle,
            currentSeq = effectiveSeq
        )
    }

    private val relatedCategoryMap: Map<String, Map<String, Double>> = mapOf(
        "comedy" to mapOf("dance" to 0.45, "gaming" to 0.35, "music" to 0.30),
        "music" to mapOf("dance" to 0.70, "comedy" to 0.30),
        "dance" to mapOf("music" to 0.70, "comedy" to 0.40, "travel" to 0.25),
        "cricket" to mapOf("gaming" to 0.35, "travel" to 0.20),
        "tech" to mapOf("gaming" to 0.65),
        "gaming" to mapOf("tech" to 0.65, "comedy" to 0.35, "cricket" to 0.30),
        "food" to mapOf("travel" to 0.60),
        "travel" to mapOf("food" to 0.60, "dance" to 0.25)
    )

    private val topicKeywords: Map<String, List<String>> = mapOf(
        "comedy" to listOf("funny", "comedy", "joke", "meme", "prank", "laugh", "humor", "roast"),
        "music" to listOf("music", "song", "sing", "cover", "guitar", "piano", "beat", "remix", "track"),
        "dance" to listOf("dance", "choreo", "dancing", "step", "moves"),
        "cricket" to listOf("cricket", "ipl", "bpl", "wicket", "bat", "sixer", "match", "sports", "football", "goal"),
        "tech" to listOf("tech", "phone", "android", "iphone", "code", "coding", "ai", "app", "gadget", "review"),
        "gaming" to listOf("game", "gaming", "gamer", "pubg", "freefire", "valorant", "fifa", "esports", "play"),
        "food" to listOf("food", "recipe", "cook", "cooking", "biryani", "eat", "taste", "streetfood", "kitchen"),
        "travel" to listOf("travel", "trip", "vlog", "tour", "beach", "mountain", "dhaka", "explore", "journey")
    )

    internal fun extractVideoCategories(video: VideoItem): List<String> {
        val result = LinkedHashSet<String>()
        val explicit = video.category.trim().lowercase()
        if (explicit.isNotBlank() && explicit != "all") {
            result.add(explicit)
        }
        val textBlob = buildString {
            append(video.title.lowercase()).append(' ')
            append(video.caption.lowercase()).append(' ')
            append(video.soundTitle.lowercase()).append(' ')
            video.hashtags.forEach { append(it.lowercase()).append(' ') }
        }
        topicKeywords.forEach { (cat, words) ->
            if (words.any { kw -> textBlob.contains(kw) }) {
                result.add(cat)
            }
        }
        return result.toList()
    }

    private fun extractTopicHashtags(video: VideoItem): List<String> {
        val tags = LinkedHashSet<String>()
        video.hashtags.forEach { raw ->
            val clean = raw.trim().removePrefix("#").lowercase()
            if (clean.isNotBlank()) tags.add(clean)
        }
        val textTokens = "${video.title} ${video.caption}".lowercase()
            .split(Regex("[^a-z0-9_]+"))
            .filter { it.length >= 4 && it !in setOf("this", "that", "with", "from", "have", "your", "what", "when", "they", "watch", "blade", "video") }
        textTokens.take(6).forEach { tags.add(it) }
        return tags.toList()
    }

    private fun computeCategoryAffinity(
        videoCategories: List<String>,
        userCategoryWeights: Map<String, Int>,
        likedCategoryCounts: Map<String, Int>
    ): Double {
        if (videoCategories.isEmpty()) return 0.0
        var total = 0.0
        videoCategories.forEach { cat ->
            val direct = userCategoryWeights[cat] ?: 0
            val likedBoost = (likedCategoryCounts[cat] ?: 0) * 2
            total += (direct + likedBoost) * 42.0
            relatedCategoryMap[cat]?.forEach { (relatedCat, similarity) ->
                val relWeight = (userCategoryWeights[relatedCat] ?: 0) + (likedCategoryCounts[relatedCat] ?: 0)
                if (relWeight != 0) {
                    total += relWeight * similarity * 24.0
                }
            }
        }
        return total
    }

    internal fun rankVideosWithSignals(
        videos: List<VideoItem>,
        forYou: Boolean,
        followingIds: Set<String>,
        affinity: com.example.data.local.WatchAffinitySnapshot,
        demoteIds: Set<String> = emptySet(),
        reloadCycle: Int = 0,
        currentSeq: Int = 0
    ): List<VideoItem> {
        if (videos.isEmpty()) return emptyList()
        val effectiveSeq = maxOf(currentSeq, affinity.lastShownSeq.values.maxOrNull() ?: 0)
        // If every video in a small catalog was in the previous batch, only put the most recent one on cooldown
        val activeDemoteIds = if (videos.size > 1 && videos.all { it.id in demoteIds }) {
            demoteIds.lastOrNull()?.let { setOf(it) } ?: emptySet()
        } else {
            demoteIds
        }

        val likedCategories = mutableMapOf<String, Int>()
        videos.filter { it.isLiked || it.isSaved }.forEach { v ->
            extractVideoCategories(v).forEach { cat ->
                likedCategories[cat] = (likedCategories[cat] ?: 0) + 1
            }
        }
        val likedCreators = videos.filter { it.isLiked || it.isSaved }
            .map { it.userId }
            .groupingBy { it }
            .eachCount()

        if (!forYou) {
            return videos.sortedWith(
                compareByDescending<VideoItem> { v ->
                    val seenCount = affinity.seenVideos[v.id] ?: 0
                    val lastSeq = affinity.lastShownSeq[v.id] ?: 0
                    val isJustShown = v.id in activeDemoteIds && videos.size > 1
                    val isUnseen = seenCount == 0 && lastSeq == 0 && !isJustShown
                    when {
                        isUnseen -> 2
                        !isJustShown -> 1
                        else -> 0
                    }
                }.thenByDescending { v ->
                    val lastSeq = affinity.lastShownSeq[v.id] ?: 0
                    val cats = extractVideoCategories(v)
                    val affinityBoost = computeCategoryAffinity(cats, affinity.categories, likedCategories) +
                        (affinity.creators[v.userId] ?: 0) * 28.0
                    val seenCount = affinity.seenVideos[v.id] ?: 0
                    if (seenCount == 0 && lastSeq == 0) {
                        affinityBoost
                    } else {
                        ((effectiveSeq - lastSeq).coerceAtLeast(0) * 240.0) + affinityBoost.coerceIn(-600.0, 900.0)
                    }
                }.thenBy { v ->
                    if (reloadCycle > 0) {
                        val mixed = v.id.hashCode() xor (reloadCycle * 0x9E3779B1.toInt())
                        (mixed ushr 1) % 1000
                    } else 0
                }
            )
        }

        val total = videos.size.coerceAtLeast(1)
        data class ScoredVideo(val video: VideoItem, val tier: Int, val score: Double)

        return videos.mapIndexed { index, v ->
            val seenCount = affinity.seenVideos[v.id] ?: 0
            val lastSeq = affinity.lastShownSeq[v.id] ?: 0
            val isJustShown = v.id in activeDemoteIds && videos.size > 1
            val isUnseen = seenCount == 0 && lastSeq == 0 && !isJustShown

            // Strict 3-tier guarantee:
            // Tier 2: Unseen / new videos always come first
            // Tier 1: Older videos rotate one by one by age + category/interaction affinity
            // Tier 0: Videos just shown right before reload go on cooldown at the end
            val tier = when {
                isUnseen -> 2
                !isJustShown -> 1
                else -> 0
            }

            val videoCategories = extractVideoCategories(v)
            val catScore = computeCategoryAffinity(videoCategories, affinity.categories, likedCategories)
            val creatorScore = ((affinity.creators[v.userId] ?: 0) + (likedCreators[v.userId] ?: 0) * 2) * 30.0
            val videoTags = extractTopicHashtags(v)
            val hashtagScore = videoTags.sumOf { tag -> affinity.hashtags[tag] ?: 0 } * 20.0
            val totalAffinity = catScore + creatorScore + hashtagScore
            val followBoost = if (v.userId in followingIds) 35.0 else 0.0

            val weightedEngagement = (v.likesCount * 3.0) + (v.commentsCount * 4.0) +
                (v.sharesCount * 5.0) + (v.savesCount * 3.0) + (v.viewsCount * 0.25)
            val engagementScore = kotlin.math.ln(1.0 + weightedEngagement.coerceAtLeast(0.0)) * 14.0

            val reloadJitter = if (reloadCycle > 0) {
                val mixed = v.id.hashCode() xor (reloadCycle * 0x9E3779B1.toInt())
                (((mixed ushr 1) % 1000) / 1000.0) * 12.0
            } else 0.0

            val tierScore = when (tier) {
                2 -> {
                    val recencyInServerOrder = ((total - index).toDouble() / total.toDouble()) * 25.0
                    totalAffinity + followBoost + engagementScore + recencyInServerOrder + reloadJitter
                }
                1 -> {
                    val recencyGap = (effectiveSeq - lastSeq).coerceAtLeast(0)
                    val rotationAge = (recencyGap * 240.0) - (seenCount.coerceAtMost(8) * 18.0)
                    rotationAge + totalAffinity.coerceIn(-600.0, 900.0) +
                        followBoost + engagementScore + reloadJitter
                }
                else -> {
                    val demotePos = activeDemoteIds.indexOf(v.id).coerceAtLeast(0)
                    totalAffinity.coerceIn(-600.0, 400.0) - (demotePos * 20.0) + reloadJitter
                }
            }

            ScoredVideo(video = v, tier = tier, score = tierScore)
        }
            .sortedWith(
                compareByDescending<ScoredVideo> { it.tier }
                    .thenByDescending { it.score }
            )
            .map { it.video }
    }

    suspend fun fetchFeedAds(): List<AdItem> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext emptyList()
        runCatching { client.restApi.getActiveAds() }.getOrDefault(emptyList())
    }

    suspend fun recordVideoView(video: VideoItem) = withContext(Dispatchers.IO) {
        preferences.markVideoSeen(video.id)
        if (!SupabaseConfig.isConfigured) return@withContext
        runCatching { client.restApi.incrementVideoViews(mapOf("p_video_id" to video.id)) }
    }

    suspend fun recordWatchSignal(video: VideoItem, weight: Int) = withContext(Dispatchers.IO) {
        preferences.recordWatchAffinity(
            creatorId = video.userId,
            categories = extractVideoCategories(video),
            hashtags = extractTopicHashtags(video),
            weight = weight
        )
    }

    suspend fun getVideoById(videoId: String): Result<VideoItem> = withContext(Dispatchers.IO) {
        val cleanId = videoId.trim()
        if (cleanId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Missing video reference."))
        }
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Supabase is not configured in Secrets."))
        }
        try {
            val filter = "in.($cleanId)"
            val raw = try {
                client.restApi.getVideosByIds(filter)
            } catch (e: HttpException) {
                if (e.code() == 400 || e.code() == 404) {
                    runCatching { client.restApi.getVideosByIds(filter, select = "*") }
                        .getOrElse { throw e }
                } else throw e
            }
            val item = raw.firstOrNull()
                ?: return@withContext Result.failure(
                    IllegalStateException("This video is no longer available or has been deleted.")
                )
            val withCreator = hydrateCreatorIfMissing(item, item.userId)
            val session = preferences.getSessionSnapshot()
            val liked = if (session.isLoggedIn) {
                runCatching {
                    client.restApi.getLikesForUser("eq.${session.userId}").any { it.videoId == cleanId }
                }.getOrDefault(false)
            } else false
            val saved = if (session.isLoggedIn) {
                runCatching {
                    client.restApi.getSavedForUser("eq.${session.userId}").any { it.videoId == cleanId }
                }.getOrDefault(false)
            } else false
            val following = if (session.isLoggedIn && withCreator.userId != session.userId) {
                runCatching {
                    client.restApi.getFollowingIds("eq.${session.userId}").any { it.followingId == withCreator.userId }
                }.getOrDefault(false)
            } else false
            Result.success(
                withCreator.copy(
                    isLiked = liked,
                    isSaved = saved,
                    isFollowingCreator = following
                )
            )
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun getVideoLikers(videoId: String): Result<List<UserProfile>> = withContext(Dispatchers.IO) {
        val cleanId = videoId.trim()
        if (cleanId.isBlank() || !SupabaseConfig.isConfigured) {
            return@withContext Result.success(emptyList())
        }
        try {
            val records = try {
                client.restApi.getLikesForVideo("eq.$cleanId")
            } catch (e: HttpException) {
                if (e.code() == 400 || e.code() == 404) {
                    runCatching {
                        client.restApi.getLikesForVideo("eq.$cleanId", select = "video_id,user_id")
                    }.getOrElse { throw e }
                } else throw e
            }
            if (records.isEmpty()) return@withContext Result.success(emptyList())

            val orderedUserIds = records.map { it.userId }.filter { it.isNotBlank() }.distinct()
            val embeddedById = records.mapNotNull { rec -> rec.user?.let { rec.userId to it } }.toMap()
            val missingIds = orderedUserIds.filterNot { it in embeddedById }
            val fetchedMissingById = if (missingIds.isNotEmpty()) {
                runCatching {
                    client.restApi.getUsersByIds("in.(${missingIds.joinToString(",")})")
                        .associateBy { it.id }
                }.getOrDefault(emptyMap())
            } else {
                emptyMap()
            }
            val combined = embeddedById + fetchedMissingById
            Result.success(orderedUserIds.mapNotNull { combined[it] })
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun recordVideoShare(
        videoId: String,
        currentSharesCount: Int = 0,
        creatorId: String? = null
    ) = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext
        val rpcResp = runCatching {
            client.restApi.incrementVideoShares(mapOf("p_video_id" to videoId))
        }.getOrNull()
        if (rpcResp == null || !rpcResp.isSuccessful) {
            runCatching {
                val remoteCount = client.restApi.getVideosByIds("in.($videoId)", select = "*")
                    .firstOrNull()?.sharesCount ?: currentSharesCount
                client.restApi.updateVideoCounts(
                    "eq.$videoId",
                    mapOf("shares_count" to maxOf(remoteCount, currentSharesCount) + 1)
                )
            }
        }
        val session = preferences.getSessionSnapshot()
        if (session.isLoggedIn) {
            val ownerId = creatorId?.takeIf { it.isNotBlank() } ?: runCatching {
                client.restApi.getVideosByIds("in.($videoId)", select = "*").firstOrNull()?.userId
            }.getOrNull()
            if (!ownerId.isNullOrBlank() && ownerId != session.userId) {
                runCatching {
                    client.restApi.createNotification(
                        CreateNotificationRequest(
                            userId = ownerId,
                            actorId = session.userId,
                            type = "share",
                            title = "Video Shared",
                            body = "shared your Blade video",
                            videoId = videoId
                        )
                    )
                }
            }
        }
    }

    suspend fun toggleLike(videoId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in to like videos"))
        try {
            val liked = client.restApi.toggleVideoLike(mapOf("p_video_id" to videoId))
            Result.success(liked)
        } catch (e: HttpException) {
            if (e.code() == 404) {
                return@withContext runCatching {
                    val alreadyLiked = runCatching {
                        client.restApi.getLikesForUser("eq.${session.userId}").any { it.videoId == videoId }
                    }.getOrDefault(false)
                    val nowLiked = if (alreadyLiked) {
                        val resp = client.restApi.deleteLike("eq.$videoId", "eq.${session.userId}")
                        if (!resp.isSuccessful) throw HttpException(resp)
                        false
                    } else {
                        val resp = client.restApi.createLike(LikeRecord(videoId = videoId, userId = session.userId))
                        if (!resp.isSuccessful) throw HttpException(resp)
                        true
                    }
                    runCatching {
                        val realCount = client.restApi.getLikesForVideo(
                            "eq.$videoId",
                            select = "video_id,user_id"
                        ).size
                        client.restApi.updateVideoCounts("eq.$videoId", mapOf("likes_count" to realCount))
                    }
                    if (nowLiked) {
                        val ownerId = runCatching {
                            client.restApi.getVideosByIds("in.($videoId)", select = "*").firstOrNull()?.userId
                        }.getOrNull()
                        if (!ownerId.isNullOrBlank() && ownerId != session.userId) {
                            runCatching {
                                client.restApi.createNotification(
                                    CreateNotificationRequest(
                                        userId = ownerId,
                                        actorId = session.userId,
                                        type = "like",
                                        title = "New Like",
                                        body = "liked your Blade video",
                                        videoId = videoId
                                    )
                                )
                            }
                        }
                    }
                    nowLiked
                }.recoverCatching { throw friendlyException(it) }
            }
            Result.failure(friendlyException(e))
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun toggleSave(videoId: String, currentlySaved: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in to save videos"))
        try {
            if (currentlySaved) {
                val resp = client.restApi.unsaveVideo("eq.$videoId", "eq.${session.userId}")
                if (!resp.isSuccessful) throw HttpException(resp)
                Result.success(false)
            } else {
                val resp = client.restApi.saveVideo(SavedRecord(videoId = videoId, userId = session.userId))
                if (!resp.isSuccessful) throw HttpException(resp)
                Result.success(true)
            }
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun toggleFollow(targetUserId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in to follow creators"))
        try {
            val following = client.restApi.toggleFollowUser(mapOf("p_target_id" to targetUserId))
            Result.success(following)
        } catch (e: HttpException) {
            if (e.code() == 404) {
                return@withContext runCatching {
                    val alreadyFollowing = runCatching {
                        client.restApi.getFollowingIds("eq.${session.userId}").any { it.followingId == targetUserId }
                    }.getOrDefault(false)
                    if (alreadyFollowing) {
                        val resp = client.restApi.deleteFollow("eq.${session.userId}", "eq.$targetUserId")
                        if (!resp.isSuccessful) throw HttpException(resp)
                        false
                    } else {
                        val resp = client.restApi.createFollow(FollowerRecord(followerId = session.userId, followingId = targetUserId))
                        if (!resp.isSuccessful) throw HttpException(resp)
                        true
                    }
                }.recoverCatching { throw friendlyException(it) }
            }
            Result.failure(friendlyException(e))
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    // =========================================================================
    // COMMENTS
    // =========================================================================
    suspend fun getComments(videoId: String): Result<List<CommentItem>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext Result.success(emptyList())
        try {
            val comments = try {
                client.restApi.getComments("eq.$videoId")
            } catch (e: HttpException) {
                if (e.code() == 400 || e.code() == 404) {
                    runCatching { client.restApi.getComments("eq.$videoId", select = "*") }
                        .getOrElse { throw e }
                } else throw e
            }
            Result.success(comments)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun postComment(
        videoId: String,
        content: String,
        parentId: String? = null,
        currentCount: Int
    ): Result<CommentItem> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in to comment"))
        if (!fetchAppSettings().allowComments) {
            return@withContext Result.failure(IllegalStateException("Comments are temporarily disabled by an administrator."))
        }
        try {
            val trimmedContent = content.trim()
            val created = client.restApi.postComment(
                CreateCommentRequest(
                    videoId = videoId,
                    userId = session.userId,
                    parentId = parentId,
                    content = trimmedContent
                )
            )
            runCatching {
                val realCount = client.restApi.getComments("eq.$videoId", select = "id", limit = 1000).size
                client.restApi.updateVideoCounts(
                    "eq.$videoId",
                    mapOf("comments_count" to maxOf(realCount, currentCount + 1))
                )
            }
            val ownerId = runCatching {
                client.restApi.getVideosByIds("in.($videoId)", select = "*").firstOrNull()?.userId
            }.getOrNull()
            if (!ownerId.isNullOrBlank() && ownerId != session.userId) {
                val preview = trimmedContent.take(60)
                runCatching {
                    client.restApi.createNotification(
                        CreateNotificationRequest(
                            userId = ownerId,
                            actorId = session.userId,
                            type = "comment",
                            title = "New Comment",
                            body = "commented: \"$preview\"",
                            videoId = videoId
                        )
                    )
                }
            }
            Result.success(created.first())
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    // =========================================================================
    // UPLOAD & PUBLISH VIDEO
    // =========================================================================
    suspend fun publishVideo(
        contentResolver: ContentResolver,
        videoUri: Uri,
        title: String,
        caption: String,
        category: String,
        location: String,
        privacy: String,
        sound: SoundItem?,
        filterName: String,
        trimStartMs: Long,
        trimEndMs: Long,
        durationMs: Long,
        isPhoto: Boolean = false,
        coverFrameMs: Long = 0L,
        overlayText: String = "",
        uploadKey: String = UUID.randomUUID().toString(),
        existingVideoUrl: String? = null,
        existingThumbUrl: String? = null,
        onStageUrlsResolved: (videoUrl: String, thumbUrl: String) -> Unit = { _, _ -> },
        onStatusUpdate: (String) -> Unit = {},
        onProgress: (Float) -> Unit
    ): Result<VideoItem> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in to upload videos"))
        if (!fetchAppSettings().allowVideoUploads) {
            return@withContext Result.failure(IllegalStateException("Video uploads are temporarily paused by an administrator."))
        }
        val authoritativeLocation = resolveAuthoritativeLocationPreferences(session, preferProfile = true)
        if (authoritativeLocation.country.isBlank()) {
            return@withContext Result.failure(
                MissingCountryException("Your account country is not set. Please select your country in Settings before uploading.")
            )
        }

        val extraMetaContext = listOfNotNull(
            sound?.title?.takeIf { it.isNotBlank() },
            overlayText.takeIf { it.isNotBlank() }
        )
        val moderation = VideoContentModerator.moderateVideo(
            contentResolver = contentResolver,
            videoUri = videoUri,
            title = title,
            caption = caption,
            location = location,
            durationMs = durationMs,
            isPhoto = isPhoto,
            additionalFileNames = extraMetaContext
        )
        if (moderation is ModerationResult.Blocked) {
            return@withContext Result.failure(IllegalStateException(moderation.reason))
        }

        val safeKey = uploadKey.replace(Regex("[^A-Za-z0-9_-]"), "").ifBlank { UUID.randomUUID().toString() }
        val ext = if (isPhoto) "jpg" else "mp4"
        val mime = if (isPhoto) "image/jpeg" else "video/mp4"
        val fileName = "${session.userId}/vid_${safeKey}.$ext"

        val videoPublicUrl = if (!existingVideoUrl.isNullOrBlank()) {
            onProgress(0.82f)
            existingVideoUrl
        } else {
            onStatusUpdate("Uploading video...")
            val uploadResult = client.uploadToStorage(
                contentResolver = contentResolver,
                fileUri = videoUri,
                bucket = "videos",
                objectPath = fileName,
                mimeType = mime,
                onProgress = { pct ->
                    val scaled = (pct * 0.82f).coerceIn(0.01f, 0.82f)
                    onProgress(scaled)
                    onStatusUpdate("Uploading video (${(scaled * 100).toInt()}%)...")
                }
            )
            if (uploadResult.isFailure) {
                return@withContext Result.failure(uploadResult.exceptionOrNull() ?: IllegalStateException("Upload failed"))
            }
            val uploadedUrl = uploadResult.getOrThrow()
            onStageUrlsResolved(uploadedUrl, existingThumbUrl.orEmpty())
            uploadedUrl
        }

        val thumbPublicUrl = if (!existingThumbUrl.isNullOrBlank()) {
            onProgress(0.90f)
            existingThumbUrl
        } else if (isPhoto) {
            videoPublicUrl
        } else {
            onStatusUpdate("Generating thumbnail...")
            onProgress(0.85f)
            val generatedThumb = extractAndUploadThumbnail(
                contentResolver = contentResolver,
                videoUri = videoUri,
                userId = session.userId,
                frameTimeMs = coverFrameMs,
                uploadKey = safeKey
            )
            onStageUrlsResolved(videoPublicUrl, generatedThumb)
            onProgress(0.90f)
            generatedThumb
        }

        val cleanTitle = title.trim()
        val cleanCaption = caption.trim()
        val combinedTextForTags = "$cleanTitle $cleanCaption"
        val extractedHashtags = Regex("#([A-Za-z0-9_\\u0980-\\u09FF]+)")
            .findAll(combinedTextForTags)
            .map { it.groupValues[1].lowercase() }
            .distinct()
            .toList()

        try {
            onStatusUpdate("Saving & syncing video...")
            onProgress(0.94f)

            // Idempotency guard: if a previous timed-out retry actually created this DB row, return it without duplicating
            val existingRows = runCatching {
                client.restApi.getVideoByUrl(
                    videoUrlFilter = "eq.$videoPublicUrl",
                    userIdFilter = "eq.${session.userId}"
                )
            }.getOrDefault(emptyList())
            if (existingRows.isNotEmpty()) {
                val hydrated = hydrateCreatorIfMissing(existingRows.first(), session.userId)
                onProgress(1f)
                return@withContext Result.success(hydrated)
            }

            val sanitizedSoundId = validSoundUuidOrNull(sound?.id)
            val resolvedSoundTitle = sound?.let {
                if (it.artist.isNotBlank() && !it.artist.equals("Phone Storage", ignoreCase = true)) {
                    "${it.title} - ${it.artist}"
                } else {
                    it.title
                }
            } ?: "Original Sound"

            val resolvedCountry = authoritativeLocation.country
            val isIndiaUploader = resolvedCountry.equals("India", ignoreCase = true)

            val created = try {
                client.restApi.createVideo(
                    CreateVideoRequest(
                        userId = session.userId,
                        videoUrl = videoPublicUrl,
                        thumbnailUrl = thumbPublicUrl,
                        title = cleanTitle,
                        caption = cleanCaption,
                        hashtags = extractedHashtags,
                        category = category,
                        location = location.trim(),
                        country = resolvedCountry,
                        region = if (isIndiaUploader) authoritativeLocation.preferredRegion else "",
                        state = if (isIndiaUploader) authoritativeLocation.preferredState else "",
                        division = if (isIndiaUploader) authoritativeLocation.preferredDivision else "",
                        language = if (isIndiaUploader) authoritativeLocation.preferredLanguage else "",
                        privacy = privacy.lowercase(),
                        soundId = sanitizedSoundId,
                        soundTitle = resolvedSoundTitle,
                        filterName = filterName,
                        overlayText = overlayText.trim(),
                        trimStartMs = if (isPhoto) 0L else trimStartMs,
                        trimEndMs = if (isPhoto) 0L else trimEndMs,
                        durationMs = if (isPhoto) 5000L else durationMs
                    )
                )
            } catch (e: HttpException) {
                // Fallback if the Supabase `videos` table does not yet have optional columns like `title`, preserving `country`
                if (e.code() == 400 || e.code() == 404) {
                    val mergedCaption = when {
                        cleanTitle.isNotBlank() && cleanCaption.isNotBlank() -> "$cleanTitle\n$cleanCaption"
                        cleanTitle.isNotBlank() -> cleanTitle
                        else -> cleanCaption
                    }
                    client.restApi.createVideoLegacy(
                        CreateVideoLegacyRequest(
                            userId = session.userId,
                            videoUrl = videoPublicUrl,
                            thumbnailUrl = thumbPublicUrl,
                            caption = mergedCaption,
                            hashtags = extractedHashtags,
                            category = category,
                            location = location.trim(),
                            country = resolvedCountry,
                            privacy = privacy.lowercase(),
                            soundId = sanitizedSoundId,
                            soundTitle = resolvedSoundTitle,
                            filterName = filterName,
                            overlayText = overlayText.trim(),
                            trimStartMs = if (isPhoto) 0L else trimStartMs,
                            trimEndMs = if (isPhoto) 0L else trimEndMs,
                            durationMs = if (isPhoto) 5000L else durationMs
                        )
                    )
                } else {
                    throw e
                }
            }
            extractedHashtags.forEach { tag ->
                runCatching {
                    client.restApi.upsertHashtag(
                        mapOf("tag" to tag, "videos_count" to 1, "views_count" to 1, "is_trending" to true)
                    )
                }
            }
            val rawItem = created.firstOrNull()
                ?: return@withContext Result.failure(IllegalStateException("Video saved, but server returned empty response. Refresh feed to verify."))
            val item = if (rawItem.country.isBlank()) rawItem.copy(country = resolvedCountry) else rawItem
            val hydrated = hydrateCreatorIfMissing(item, session.userId)
            onProgress(1f)
            onStatusUpdate("Published!")
            Result.success(hydrated)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    private suspend fun hydrateCreatorIfMissing(video: VideoItem, userId: String): VideoItem {
        if (video.creator != null) return video
        val creator = runCatching { client.restApi.getUserById("eq.$userId").firstOrNull() }.getOrNull()
        return if (creator != null) video.copy(creator = creator) else video
    }

    // =========================================================================
    // DISCOVER & SEARCH
    // =========================================================================
    suspend fun searchDiscover(
        query: String,
        category: String,
        hashtag: String?,
        soundId: String?
    ): Result<Triple<List<VideoItem>, List<UserProfile>, List<HashtagItem>>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.success(Triple(emptyList(), emptyList(), emptyList()))
        }
        if (!isOnlineFlow.value) {
            return@withContext Result.failure(IllegalStateException("Internet Connection Failed"))
        }
        try {
            val session = preferences.getSessionSnapshot()
            val locationPrefs = resolveAuthoritativeLocationPreferences(session)
            if (session.isLoggedIn && locationPrefs.country.isBlank()) {
                return@withContext Result.failure(
                    MissingCountryException("Your account country is not set. Please select your country to browse videos in your region.")
                )
            }
            val q = query.trim().removePrefix("#")
            val orVideoWithTitle = if (q.isNotBlank()) "(title.ilike.*$q*,caption.ilike.*$q*,location.ilike.*$q*)" else null
            val orVideoFallback = if (q.isNotBlank()) "(caption.ilike.*$q*,location.ilike.*$q*)" else null
            val catFilter = if (category != "All" && q.isBlank() && hashtag.isNullOrBlank()) "eq.$category" else null
            val tagFilter = hashtag?.takeIf { it.isNotBlank() }?.let { "cs.{${it.lowercase().removePrefix("#")}}" }
            val soundFilter = soundId?.takeIf { it.isNotBlank() }?.let { "eq.$it" }
            val countryFilter = LocationCatalog.sanitizedPostgrestCountryIlike(locationPrefs.country)

            val videos = try {
                client.restApi.searchVideos(
                    orFilter = orVideoWithTitle,
                    categoryFilter = catFilter,
                    hashtagFilter = tagFilter,
                    soundIdFilter = soundFilter,
                    countryFilter = countryFilter
                )
            } catch (e: HttpException) {
                if (e.code() == 400 || e.code() == 404) {
                    runCatching {
                        client.restApi.searchVideos(
                            orFilter = orVideoFallback,
                            categoryFilter = catFilter,
                            hashtagFilter = tagFilter,
                            soundIdFilter = soundFilter,
                            countryFilter = countryFilter,
                            select = "*"
                        )
                    }.recoverCatching { inner ->
                        val raw = (inner as? HttpException)?.let { readHttpErrorBody(it) }
                        if (countryFilter != null && isMissingColumnSchemaError(raw, (inner as? HttpException)?.code())) {
                            client.restApi.searchVideos(
                                orFilter = orVideoFallback,
                                categoryFilter = catFilter,
                                hashtagFilter = tagFilter,
                                soundIdFilter = soundFilter,
                                select = "*"
                            )
                        } else {
                            throw inner
                        }
                    }.getOrElse { throw e }
                } else throw e
            }
            val blockedIds = if (session.isLoggedIn) {
                runCatching { client.restApi.getBlockedUsers("eq.${session.userId}").map { it.blockedId }.toSet() }
                    .getOrDefault(emptySet())
            } else emptySet()
            val followingIds = if (session.isLoggedIn) {
                runCatching { client.restApi.getFollowingIds("eq.${session.userId}").map { it.followingId }.toSet() }
                    .getOrDefault(emptySet())
            } else emptySet()
            val likedIds = if (session.isLoggedIn) {
                runCatching { client.restApi.getLikesForUser("eq.${session.userId}").map { it.videoId }.toSet() }
                    .getOrDefault(emptySet())
            } else emptySet()
            val savedIds = if (session.isLoggedIn) {
                runCatching { client.restApi.getSavedForUser("eq.${session.userId}").map { it.videoId }.toSet() }
                    .getOrDefault(emptySet())
            } else emptySet()

            val hydratedVideos = videos
                .filter {
                    it.userId !in blockedIds &&
                        it.creator?.isBlocked != true &&
                        LocationCatalog.matchesVideo(it, locationPrefs)
                }
                .map { video ->
                    video.copy(
                        isLiked = video.id in likedIds,
                        isSaved = video.id in savedIds,
                        isFollowingCreator = video.userId in followingIds
                    )
                }

            val users = runCatching {
                if (q.isNotBlank()) {
                    client.restApi.searchUsers("(username.ilike.*$q*,display_name.ilike.*$q*)")
                } else {
                    client.restApi.getUsers(limit = 20)
                }
            }.getOrDefault(emptyList())
            val hashtags = runCatching { client.restApi.getHashtags() }.getOrDefault(emptyList())
            Result.success(Triple(hydratedVideos, users, hashtags))
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun getSounds(): List<SoundItem> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext emptyList()
        runCatching { client.restApi.getSounds() }.getOrDefault(emptyList())
    }

    suspend fun getSoundById(soundId: String): SoundItem? = withContext(Dispatchers.IO) {
        val cleanId = soundId.trim()
        if (cleanId.isBlank() || !SupabaseConfig.isConfigured) return@withContext null
        runCatching { client.restApi.getSounds().firstOrNull { it.id == cleanId } }.getOrNull()
    }

    // =========================================================================
    // PROFILE, WALLET & MONETIZATION
    // =========================================================================
    suspend fun getUserProfile(userId: String): Result<UserProfile> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext Result.failure(IllegalStateException("Supabase not configured"))
        try {
            val users = client.restApi.getUserById("eq.$userId")
            users.firstOrNull()?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("User not found"))
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    private fun extractVideoDurationMs(contentResolver: ContentResolver, videoUri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        var tempCopy: File? = null
        return try {
            val configured = runCatching {
                contentResolver.openFileDescriptor(videoUri, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                    true
                } ?: false
            }.getOrDefault(false)
            if (!configured) {
                val copied = File.createTempFile("blade_dur_", ".mp4")
                tempCopy = copied
                contentResolver.openInputStream(videoUri)?.use { input ->
                    copied.outputStream().use { out -> input.copyTo(out) }
                }
                if (copied.length() > 0L) {
                    retriever.setDataSource(copied.absolutePath)
                }
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
            runCatching { tempCopy?.delete() }
        }
    }

    private suspend fun extractAndUploadThumbnail(
        contentResolver: ContentResolver,
        videoUri: Uri,
        userId: String,
        allowAnonFallback: Boolean = false,
        frameTimeMs: Long = 500L,
        uploadKey: String = UUID.randomUUID().toString()
    ): String {
        var tempThumbFile: File? = null
        var tempVideoCopy: File? = null
        val retriever = MediaMetadataRetriever()
        return try {
            var sourceReady = runCatching {
                contentResolver.openFileDescriptor(videoUri, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                    true
                } ?: false
            }.getOrDefault(false)

            if (!sourceReady) {
                val copied = File.createTempFile("blade_src_vid_", ".mp4")
                tempVideoCopy = copied
                contentResolver.openInputStream(videoUri)?.use { input ->
                    copied.outputStream().use { out -> input.copyTo(out) }
                }
                if (copied.length() > 0L) {
                    retriever.setDataSource(copied.absolutePath)
                    sourceReady = true
                }
            }
            if (!sourceReady) return ""

            val candidateTimesUs = listOf(
                frameTimeMs.coerceAtLeast(0L) * 1000L,
                500_000L,
                1_000_000L,
                0L
            ).distinct()
            var frame: Bitmap? = null
            for (tUs in candidateTimesUs) {
                frame = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) break
            }
            if (frame == null) {
                frame = retriever.frameAtTime
            }
            val bitmap = frame ?: return ""

            val extractedFile = File.createTempFile("blade_thumb_", ".jpg").also { file ->
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 84, out)
                }
                bitmap.recycle()
            }
            tempThumbFile = extractedFile
            val thumbUri = Uri.fromFile(extractedFile)
            val thumbPath = "$userId/thumb_${uploadKey}.jpg"
            client.uploadToStorage(
                contentResolver = contentResolver,
                fileUri = thumbUri,
                bucket = "videos",
                objectPath = thumbPath,
                mimeType = "image/jpeg",
                onProgress = {},
                allowAnonFallback = allowAnonFallback
            ).getOrDefault("")
        } catch (_: Exception) {
            ""
        } finally {
            runCatching { retriever.release() }
            runCatching { tempThumbFile?.delete() }
            runCatching { tempVideoCopy?.delete() }
        }
    }

    suspend fun getUserVideos(userId: String): List<VideoItem> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext emptyList()
        val raw = try {
            client.restApi.getVideosByUser("eq.$userId")
        } catch (e: HttpException) {
            if (e.code() == 400 || e.code() == 404) {
                runCatching { client.restApi.getVideosByUser("eq.$userId", select = "*") }
                    .getOrDefault(emptyList())
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val creator = raw.firstOrNull()?.creator
            ?: runCatching { client.restApi.getUserById("eq.$userId").firstOrNull() }.getOrNull()
        val session = preferences.getSessionSnapshot()
        val likedIds = if (session.isLoggedIn) {
            runCatching { client.restApi.getLikesForUser("eq.${session.userId}").map { it.videoId }.toSet() }
                .getOrDefault(emptySet())
        } else emptySet()
        val savedIds = if (session.isLoggedIn) {
            runCatching { client.restApi.getSavedForUser("eq.${session.userId}").map { it.videoId }.toSet() }
                .getOrDefault(emptySet())
        } else emptySet()
        raw.map { video ->
            video.copy(
                creator = video.creator ?: creator,
                isLiked = video.id in likedIds,
                isSaved = video.id in savedIds
            )
        }
    }

    suspend fun getUserLikedVideos(userId: String): List<VideoItem> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext emptyList()
        val likedIds = runCatching {
            client.restApi.getLikesForUser("eq.$userId").map { it.videoId }.distinct()
        }.getOrDefault(emptyList())
        if (likedIds.isEmpty()) return@withContext emptyList()
        val filter = "in.(${likedIds.joinToString(",")})"
        val raw = try {
            client.restApi.getVideosByIds(filter)
        } catch (e: HttpException) {
            if (e.code() == 400 || e.code() == 404) {
                runCatching { client.restApi.getVideosByIds(filter, select = "*") }
                    .getOrDefault(emptyList())
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val session = preferences.getSessionSnapshot()
        val myLiked = if (session.isLoggedIn) {
            if (session.userId == userId) likedIds.toSet()
            else runCatching { client.restApi.getLikesForUser("eq.${session.userId}").map { it.videoId }.toSet() }.getOrDefault(emptySet())
        } else emptySet()
        val mySaved = if (session.isLoggedIn) {
            runCatching { client.restApi.getSavedForUser("eq.${session.userId}").map { it.videoId }.toSet() }.getOrDefault(emptySet())
        } else emptySet()
        raw.map { v -> v.copy(isLiked = v.id in myLiked, isSaved = v.id in mySaved) }
    }

    suspend fun getUserSavedVideos(userId: String): List<VideoItem> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext emptyList()
        val savedIds = runCatching {
            client.restApi.getSavedForUser("eq.$userId").map { it.videoId }.distinct()
        }.getOrDefault(emptyList())
        if (savedIds.isEmpty()) return@withContext emptyList()
        val filter = "in.(${savedIds.joinToString(",")})"
        val raw = try {
            client.restApi.getVideosByIds(filter)
        } catch (e: HttpException) {
            if (e.code() == 400 || e.code() == 404) {
                runCatching { client.restApi.getVideosByIds(filter, select = "*") }
                    .getOrDefault(emptyList())
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val session = preferences.getSessionSnapshot()
        val myLiked = if (session.isLoggedIn) {
            runCatching { client.restApi.getLikesForUser("eq.${session.userId}").map { it.videoId }.toSet() }.getOrDefault(emptySet())
        } else emptySet()
        val mySaved = if (session.isLoggedIn) {
            if (session.userId == userId) savedIds.toSet()
            else runCatching { client.restApi.getSavedForUser("eq.${session.userId}").map { it.videoId }.toSet() }.getOrDefault(emptySet())
        } else emptySet()
        raw.map { v -> v.copy(isLiked = v.id in myLiked, isSaved = v.id in mySaved) }
    }

    suspend fun isFollowingUser(targetUserId: String): Boolean = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn || !SupabaseConfig.isConfigured || session.userId == targetUserId) {
            return@withContext false
        }
        runCatching {
            client.restApi.getFollowingIds("eq.${session.userId}").any { it.followingId == targetUserId }
        }.getOrDefault(false)
    }

    suspend fun getFollowerProfiles(userId: String): Result<List<UserProfile>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext Result.success(emptyList())
        try {
            val ids = client.restApi.getFollowerIds("eq.$userId").map { it.followerId }.distinct()
            if (ids.isEmpty()) return@withContext Result.success(emptyList())
            val users = client.restApi.getUsersByIds("in.(${ids.joinToString(",")})")
            Result.success(users)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun getFollowingProfiles(userId: String): Result<List<UserProfile>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext Result.success(emptyList())
        try {
            val ids = client.restApi.getFollowingIds("eq.$userId").map { it.followingId }.distinct()
            if (ids.isEmpty()) return@withContext Result.success(emptyList())
            val users = client.restApi.getUsersByIds("in.(${ids.joinToString(",")})")
            Result.success(users)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun updateProfile(
        displayName: String,
        username: String,
        bio: String,
        isPrivate: Boolean,
        newAvatarUri: Uri?,
        contentResolver: ContentResolver
    ): Result<UserProfile> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Not signed in"))

        val updates = mutableMapOf<String, Any>(
            "display_name" to displayName.trim(),
            "username" to username.trim().lowercase().replace(Regex("[^a-z0-9_.]"), ""),
            "bio" to bio.trim(),
            "is_private" to isPrivate
        )
        if (newAvatarUri != null) {
            val mimeType = contentResolver.getType(newAvatarUri) ?: "image/jpeg"
            val ext = when {
                mimeType.contains("png", ignoreCase = true) -> "png"
                mimeType.contains("webp", ignoreCase = true) -> "webp"
                else -> "jpg"
            }
            val avatarPath = "${session.userId}/avatar_${System.currentTimeMillis()}.$ext"
            val uploadRes = client.uploadToStorage(
                contentResolver = contentResolver,
                fileUri = newAvatarUri,
                bucket = "avatars",
                objectPath = avatarPath,
                mimeType = mimeType,
                onProgress = {}
            )
            if (uploadRes.isFailure) {
                return@withContext Result.failure(
                    uploadRes.exceptionOrNull() ?: IllegalStateException("Profile photo upload failed")
                )
            }
            updates["avatar_url"] = uploadRes.getOrThrow()
        }
        try {
            val updated = client.restApi.updateUserProfile("eq.${session.userId}", updates)
            val profile = updated.firstOrNull()
                ?: client.restApi.getUserById("eq.${session.userId}").firstOrNull()
                ?: return@withContext Result.failure(IllegalStateException("Profile not found"))
            Result.success(profile)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun updateAccountPrivacy(isPrivate: Boolean): Result<UserProfile> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Not signed in"))
        try {
            val updated = client.restApi.updateUserProfile(
                "eq.${session.userId}",
                mapOf("is_private" to isPrivate)
            )
            val profile = updated.firstOrNull()
                ?: client.restApi.getUserById("eq.${session.userId}").firstOrNull()
                ?: return@withContext Result.failure(IllegalStateException("Profile not found"))
            Result.success(profile)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun uploadProfilePhoto(
        photoUri: Uri,
        contentResolver: ContentResolver
    ): Result<UserProfile> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in to upload a profile photo"))
        if (!SupabaseConfig.isConfigured) return@withContext Result.failure(IllegalStateException("Supabase is not configured in Secrets."))

        val mimeType = contentResolver.getType(photoUri) ?: "image/jpeg"
        val ext = when {
            mimeType.contains("png", ignoreCase = true) -> "png"
            mimeType.contains("webp", ignoreCase = true) -> "webp"
            else -> "jpg"
        }
        val avatarPath = "${session.userId}/avatar_${System.currentTimeMillis()}.$ext"
        val uploadRes = client.uploadToStorage(
            contentResolver = contentResolver,
            fileUri = photoUri,
            bucket = "avatars",
            objectPath = avatarPath,
            mimeType = mimeType,
            onProgress = {}
        )
        if (uploadRes.isFailure) {
            return@withContext Result.failure(
                uploadRes.exceptionOrNull() ?: IllegalStateException("Profile photo upload failed")
            )
        }
        val publicUrl = uploadRes.getOrThrow()
        try {
            val updated = client.restApi.updateUserProfile(
                "eq.${session.userId}",
                mapOf("avatar_url" to publicUrl)
            )
            val profile = updated.firstOrNull()
                ?: client.restApi.getUserById("eq.${session.userId}").firstOrNull()
                ?: return@withContext Result.failure(IllegalStateException("Profile not found"))
            Result.success(profile)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun requestUserVerification(): Result<UserProfile> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in first"))
        if (!SupabaseConfig.isConfigured) return@withContext Result.failure(IllegalStateException("Supabase is not configured in Secrets."))
        try {
            try {
                val resp = client.restApi.requestUserVerification()
                if (!resp.isSuccessful) throw HttpException(resp)
            } catch (e: HttpException) {
                if (e.code() != 404 && e.code() != 400) throw e
                val updated = client.restApi.updateUserProfile(
                    "eq.${session.userId}",
                    mapOf("verification_status" to "pending", "is_verified" to false)
                )
                if (updated.isEmpty()) {
                    throw IllegalStateException("Unable to submit verification request.")
                }
            }
            val profile = client.restApi.getUserById("eq.${session.userId}").firstOrNull()
                ?: return@withContext Result.failure(IllegalStateException("Profile not found"))
            Result.success(profile)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun getGifts(): List<GiftItem> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext emptyList()
        runCatching { client.restApi.getGifts() }.getOrDefault(emptyList())
    }

    suspend fun sendGift(receiverId: String, videoId: String, gift: GiftItem): Result<Int> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in to send gifts"))
        try {
            val remaining = client.restApi.sendVirtualGift(
                mapOf(
                    "p_receiver_id" to receiverId,
                    "p_video_id" to videoId,
                    "p_coins" to gift.coinCost,
                    "p_gift_name" to gift.name
                )
            )
            Result.success(remaining)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun claimRewardCoins(amount: Int, source: String): Result<Int> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in to claim coins"))
        try {
            val newBalance = client.restApi.claimRewardCoins(
                mapOf("p_coins" to amount, "p_source" to source)
            )
            Result.success(newBalance)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun getTransactions(): List<TransactionItem> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn || !SupabaseConfig.isConfigured) return@withContext emptyList()
        runCatching {
            client.restApi.getTransactions("(sender_id.eq.${session.userId},receiver_id.eq.${session.userId})")
        }.getOrDefault(emptyList())
    }

    // =========================================================================
    // INBOX: NOTIFICATIONS & DIRECT MESSAGING
    // =========================================================================
    suspend fun getNotifications(markAsRead: Boolean = true): Result<List<NotificationItem>> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn || !SupabaseConfig.isConfigured) return@withContext Result.success(emptyList())
        try {
            val list = try {
                client.restApi.getNotifications("eq.${session.userId}")
            } catch (e: HttpException) {
                if (e.code() == 400 || e.code() == 404) {
                    runCatching {
                        client.restApi.getNotifications("eq.${session.userId}", select = "*")
                    }.getOrElse { throw e }
                } else throw e
            }
            if (markAsRead) {
                runCatching { client.restApi.markAllNotificationsRead("eq.${session.userId}") }
            }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun getUnreadNotificationsCount(): Int = withContext(Dispatchers.IO) {
        getNotifications(markAsRead = false).getOrNull()?.count { !it.isRead } ?: 0
    }

    suspend fun getUnreadInboxCount(): Int = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn || !SupabaseConfig.isConfigured) return@withContext 0
        val rpcCount = runCatching { client.restApi.getUnreadInboxCount() }.getOrNull()
        if (rpcCount != null) return@withContext rpcCount.coerceAtLeast(0)

        val unreadNotifs = getUnreadNotificationsCount()
        val unreadMessages = runCatching {
            val convIds = client.restApi
                .getConversations("(participant_one.eq.${session.userId},participant_two.eq.${session.userId})")
                .map { it.id }
                .toSet()
            if (convIds.isEmpty()) 0
            else client.restApi
                .getUnreadMessagesForRecipient(notSenderFilter = "neq.${session.userId}")
                .count { it.conversationId in convIds }
        }.getOrDefault(0)
        (unreadNotifs + unreadMessages).coerceAtLeast(0)
    }

    suspend fun getConversations(): Result<List<ConversationItem>> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn || !SupabaseConfig.isConfigured) return@withContext Result.success(emptyList())
        try {
            runCatching { markInboxDelivered() }
            val raw = client.restApi.getConversations("(participant_one.eq.${session.userId},participant_two.eq.${session.userId})")
            val unreadByConv = runCatching {
                client.restApi.getUnreadMessagesForRecipient(notSenderFilter = "neq.${session.userId}")
                    .groupBy { it.conversationId }
                    .mapValues { it.value.size }
            }.getOrDefault(emptyMap())
            val hydrated = raw.map { conv ->
                val otherId = if (conv.participantOne == session.userId) conv.participantTwo else conv.participantOne
                val otherUser = runCatching { client.restApi.getUserById("eq.$otherId").firstOrNull() }.getOrNull()
                conv.copy(
                    otherUser = otherUser,
                    unreadCount = unreadByConv[conv.id] ?: 0
                )
            }
            Result.success(hydrated)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun getConversationById(conversationId: String): ConversationItem? = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn || !SupabaseConfig.isConfigured) return@withContext null
        runCatching {
            val conv = client.restApi.getConversations("(participant_one.eq.${session.userId},participant_two.eq.${session.userId})")
                .firstOrNull { it.id == conversationId } ?: return@runCatching null
            val otherId = if (conv.participantOne == session.userId) conv.participantTwo else conv.participantOne
            val otherUser = runCatching { client.restApi.getUserById("eq.$otherId").firstOrNull() }.getOrNull()
            conv.copy(otherUser = otherUser)
        }.getOrNull()
    }

    suspend fun startOrGetConversation(targetUserId: String): Result<ConversationItem> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in to message creators"))
        try {
            val existing = client.restApi.getConversations("(participant_one.eq.${session.userId},participant_two.eq.${session.userId})")
                .firstOrNull {
                    (it.participantOne == session.userId && it.participantTwo == targetUserId) ||
                        (it.participantTwo == session.userId && it.participantOne == targetUserId)
                }
            if (existing != null) {
                val other = runCatching { client.restApi.getUserById("eq.$targetUserId").firstOrNull() }.getOrNull()
                return@withContext Result.success(existing.copy(otherUser = other))
            }
            val created = client.restApi.createConversation(
                CreateConversationRequest(
                    participantOne = session.userId,
                    participantTwo = targetUserId
                )
            ).first()
            val other = runCatching { client.restApi.getUserById("eq.$targetUserId").firstOrNull() }.getOrNull()
            Result.success(created.copy(otherUser = other))
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    @Volatile
    private var messagesSupportsRichColumns: Boolean = true

    suspend fun getMessages(conversationId: String): Result<List<MessageItem>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext Result.success(emptyList())
        try {
            val list = client.restApi.getMessages("eq.$conversationId").map { it.normalized() }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun uploadChatImage(
        contentResolver: ContentResolver,
        imageUri: Uri
    ): Result<String> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in to send photos"))
        val detectedMime = contentResolver.getType(imageUri)?.lowercase()?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
        val ext = when {
            detectedMime.contains("png") -> "png"
            detectedMime.contains("webp") -> "webp"
            else -> "jpg"
        }
        val path = "${session.userId}/img_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.$ext"
        val primary = client.uploadToStorage(
            contentResolver = contentResolver,
            fileUri = imageUri,
            bucket = "chat-media",
            objectPath = path,
            mimeType = detectedMime,
            onProgress = {}
        )
        if (primary.isSuccess) return@withContext primary
        // Fallback to public videos bucket if chat-media bucket wasn't provisioned yet
        client.uploadToStorage(
            contentResolver = contentResolver,
            fileUri = imageUri,
            bucket = "videos",
            objectPath = "chat/$path",
            mimeType = detectedMime,
            onProgress = {}
        )
    }

    suspend fun uploadChatVoice(
        contentResolver: ContentResolver,
        voiceFile: File
    ): Result<String> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in to send voice messages"))
        if (!voiceFile.exists() || voiceFile.length() <= 0L) {
            return@withContext Result.failure(IllegalStateException("Recorded voice message is empty"))
        }
        val uri = Uri.fromFile(voiceFile)
        val path = "${session.userId}/voice_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.m4a"
        val primary = client.uploadToStorage(
            contentResolver = contentResolver,
            fileUri = uri,
            bucket = "chat-media",
            objectPath = path,
            mimeType = "audio/mp4",
            onProgress = {}
        )
        if (primary.isSuccess) return@withContext primary
        client.uploadToStorage(
            contentResolver = contentResolver,
            fileUri = uri,
            bucket = "videos",
            objectPath = "chat/$path",
            mimeType = "audio/mp4",
            onProgress = {}
        )
    }

    suspend fun sendMessage(
        conversationId: String,
        content: String,
        clientId: String = UUID.randomUUID().toString(),
        messageType: String = "text",
        mediaUrl: String = "",
        mediaDurationMs: Long = 0L
    ): Result<MessageItem> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in to send messages"))
        val cleanContent = content.trim()
        val request = CreateMessageRequest(
            conversationId = conversationId,
            senderId = session.userId,
            clientId = clientId,
            messageType = messageType,
            content = cleanContent,
            mediaUrl = mediaUrl,
            mediaDurationMs = mediaDurationMs,
            status = "sent"
        )
        val legacyContent = MessageItem.encodeLegacyContent(
            messageType = messageType,
            content = cleanContent,
            mediaUrl = mediaUrl,
            mediaDurationMs = mediaDurationMs
        )
        val legacyRequest = CreateMessageLegacyRequest(
            conversationId = conversationId,
            senderId = session.userId,
            content = legacyContent
        )
        try {
            val insertedRaw = if (messagesSupportsRichColumns) {
                try {
                    client.restApi.sendMessage(request).first()
                } catch (e: HttpException) {
                    when (e.code()) {
                        409 -> {
                            if (clientId.isNotBlank()) {
                                client.restApi.getMessageByClientId("eq.$conversationId", "eq.$clientId").firstOrNull()
                                    ?: throw e
                            } else throw e
                        }
                        400, 404 -> {
                            // Schema cache missing rich columns (e.g. client_id / message_type) — fall back seamlessly
                            val legacyRow = client.restApi.sendMessageLegacy(legacyRequest).first()
                            messagesSupportsRichColumns = false
                            legacyRow
                        }
                        else -> throw e
                    }
                } catch (e: Exception) {
                    // Slow-network safeguard: verify whether server already committed this client_id before failing
                    if (clientId.isNotBlank()) {
                        val alreadySaved = runCatching {
                            client.restApi.getMessageByClientId("eq.$conversationId", "eq.$clientId").firstOrNull()
                        }.getOrNull()
                        if (alreadySaved != null) alreadySaved else throw e
                    } else {
                        throw e
                    }
                }
            } else {
                client.restApi.sendMessageLegacy(legacyRequest).first()
            }

            val normalizedInserted = insertedRaw.normalized().let { msg ->
                if (msg.clientId.isBlank() && clientId.isNotBlank()) msg.copy(clientId = clientId) else msg
            }

            val lastPreview = when (messageType) {
                "image" -> if (cleanContent.isNotBlank()) "📷 $cleanContent" else "📷 Photo"
                "voice" -> "🎤 Voice message"
                else -> cleanContent
            }
            runCatching {
                client.restApi.updateConversationLastMessage(
                    "eq.$conversationId",
                    mapOf(
                        "last_message" to lastPreview,
                        "updated_at" to Instant.now().toString()
                    )
                )
            }
            Result.success(normalizedInserted)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun markConversationDelivered(conversationId: String) = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn || !SupabaseConfig.isConfigured) return@withContext
        val rpcRes = runCatching {
            client.restApi.markConversationDelivered(mapOf("p_conversation_id" to conversationId))
        }.getOrNull()
        if (rpcRes == null || !rpcRes.isSuccessful) {
            runCatching {
                client.restApi.updateMessagesStatus(
                    conversationIdFilter = "eq.$conversationId",
                    notSenderFilter = "neq.${session.userId}",
                    statusFilter = "eq.sent",
                    body = mapOf(
                        "status" to "delivered",
                        "delivered_at" to Instant.now().toString()
                    )
                )
            }
        }
    }

    suspend fun markInboxDelivered() = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn || !SupabaseConfig.isConfigured) return@withContext
        runCatching { client.restApi.markInboxDelivered() }
    }

    suspend fun markConversationRead(conversationId: String) = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn || !SupabaseConfig.isConfigured) return@withContext
        val rpcRes = runCatching {
            client.restApi.markConversationRead(mapOf("p_conversation_id" to conversationId))
        }.getOrNull()
        if (rpcRes == null || !rpcRes.isSuccessful) {
            val nowIso = Instant.now().toString()
            val fullPatch = runCatching {
                client.restApi.updateMessagesStatus(
                    conversationIdFilter = "eq.$conversationId",
                    notSenderFilter = "neq.${session.userId}",
                    statusFilter = null,
                    readFilter = "eq.false",
                    body = mapOf(
                        "is_read" to true,
                        "status" to "read",
                        "delivered_at" to nowIso,
                        "read_at" to nowIso
                    )
                )
            }.getOrNull()
            if (fullPatch == null || !fullPatch.isSuccessful) {
                runCatching {
                    client.restApi.updateMessagesStatus(
                        conversationIdFilter = "eq.$conversationId",
                        notSenderFilter = "neq.${session.userId}",
                        readFilter = "eq.false",
                        body = mapOf("is_read" to true)
                    )
                }
            }
        }
    }

    suspend fun observeConversationRealtime(conversationId: String): Flow<RealtimeChatEvent> {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn || !SupabaseConfig.isConfigured) return emptyFlow()
        return client.realtimeClient.observeConversation(conversationId, session.accessToken)
    }

    suspend fun observeInboxRealtime(): Flow<RealtimeChatEvent> {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn || !SupabaseConfig.isConfigured) return emptyFlow()
        return client.realtimeClient.observeUserInbox(session.userId, session.accessToken)
    }

    // =========================================================================
    // SAFETY: REPORT & BLOCK
    // =========================================================================
    suspend fun reportVideo(videoId: String, reportedUserId: String, reason: String): Result<Unit> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in to submit a report"))
        try {
            client.restApi.createReport(
                CreateReportRequest(
                    reporterId = session.userId,
                    videoId = videoId,
                    reportedUserId = reportedUserId,
                    reason = reason
                )
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun blockUser(targetUserId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) return@withContext Result.failure(IllegalStateException("Sign in to block users"))
        try {
            val resp = client.restApi.blockUser(BlockUserRequest(blockerId = session.userId, blockedId = targetUserId))
            if (!resp.isSuccessful) throw HttpException(resp)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    // =========================================================================
    // ADMIN AUTHENTICATION, DASHBOARD & FULL APPLICATION MANAGEMENT
    // =========================================================================
    private suspend fun activeAdminCredentials(): Pair<String, String> =
        preferences.getAdminCredentials(
            defaultId = SupabaseConfig.defaultAdminUserId,
            defaultPass = SupabaseConfig.defaultAdminPassword
        )

    suspend fun verifyAdminGatePassword(password: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (password.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Enter the access password."))
        }
        val (_, expectedPass) = activeAdminCredentials()

        if (SupabaseConfig.isConfigured) {
            try {
                val remoteValid = client.restApi.adminVerifyGatePassword(
                    mapOf("p_password" to password)
                )
                return@withContext if (remoteValid) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException("Incorrect password. Access denied."))
                }
            } catch (e: HttpException) {
                if (e.code() != 404 && e.code() != 400) {
                    return@withContext Result.failure(friendlyException(e))
                }
            } catch (_: Exception) {
                // Fallback below
            }
        }

        return@withContext if (password == expectedPass) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Incorrect password. Access denied."))
        }
    }

    suspend fun authenticateAdmin(adminId: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        val cleanId = adminId.trim()
        if (cleanId.isBlank() || password.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Enter both Admin User ID and Password."))
        }
        val (expectedId, expectedPass) = activeAdminCredentials()

        if (SupabaseConfig.isConfigured) {
            try {
                val remoteValid = client.restApi.adminVerifyCredentials(
                    mapOf("p_admin_id" to cleanId, "p_password" to password)
                )
                return@withContext if (remoteValid) {
                    preferences.saveAdminCredentials(cleanId, password)
                    preferences.saveAdminSession(cleanId)
                    Result.success(cleanId)
                } else {
                    Result.failure(IllegalStateException("Invalid Admin User ID or Password."))
                }
            } catch (e: HttpException) {
                if (e.code() != 404 && e.code() != 400) {
                    return@withContext Result.failure(friendlyException(e))
                }
                // Fallback to configured local/Secrets credentials if RPC not yet deployed
            } catch (_: Exception) {
                // Fallback below
            }
        }

        return@withContext if (cleanId == expectedId && password == expectedPass) {
            preferences.saveAdminSession(cleanId)
            Result.success(cleanId)
        } else {
            Result.failure(IllegalStateException("Invalid Admin User ID or Password."))
        }
    }

    suspend fun signOutAdmin() = withContext(Dispatchers.IO) {
        preferences.clearAdminSession()
    }

    suspend fun fetchAppSettings(): AdminAppSettings = withContext(Dispatchers.IO) {
        val local = preferences.getAppSettingsSnapshot()
        if (!SupabaseConfig.isConfigured) return@withContext local
        val rows = runCatching { client.restApi.getAppSettings() }.getOrNull() ?: return@withContext local
        if (rows.isEmpty()) return@withContext local
        val map = rows.associate { it.key to it.value }
        val merged = AdminAppSettings(
            allowRegistrations = map["allow_registrations"]?.toBooleanStrictOrNull() ?: local.allowRegistrations,
            allowVideoUploads = map["allow_video_uploads"]?.toBooleanStrictOrNull() ?: local.allowVideoUploads,
            allowComments = map["allow_comments"]?.toBooleanStrictOrNull() ?: local.allowComments,
            maintenanceMode = map["maintenance_mode"]?.toBooleanStrictOrNull() ?: local.maintenanceMode,
            announcementBanner = map["announcement_banner"] ?: local.announcementBanner
        )
        preferences.saveAppSettings(merged)
        merged
    }

    suspend fun adminSaveAppSettings(settings: AdminAppSettings): Result<AdminAppSettings> = withContext(Dispatchers.IO) {
        preferences.saveAppSettings(settings)
        if (!SupabaseConfig.isConfigured) return@withContext Result.success(settings)
        val (adminId, adminPass) = activeAdminCredentials()
        val entries = listOf(
            "allow_registrations" to settings.allowRegistrations.toString(),
            "allow_video_uploads" to settings.allowVideoUploads.toString(),
            "allow_comments" to settings.allowComments.toString(),
            "maintenance_mode" to settings.maintenanceMode.toString(),
            "announcement_banner" to settings.announcementBanner.trim()
        )
        try {
            for ((k, v) in entries) {
                try {
                    val resp = client.restApi.adminUpsertAppSetting(
                        mapOf(
                            "p_admin_id" to adminId,
                            "p_admin_password" to adminPass,
                            "p_key" to k,
                            "p_value" to v
                        )
                    )
                    if (!resp.isSuccessful) throw HttpException(resp)
                } catch (e: HttpException) {
                    if (e.code() == 404 || e.code() == 400) {
                        runCatching { client.restApi.upsertAppSetting(AppSettingRecord(key = k, value = v)) }
                    } else throw e
                }
            }
            Result.success(settings)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun adminUpdateCredentials(
        currentPassword: String,
        newAdminId: String,
        newPassword: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val (expectedId, expectedPass) = activeAdminCredentials()
        if (currentPassword != expectedPass) {
            return@withContext Result.failure(IllegalArgumentException("Current admin password is incorrect."))
        }
        val cleanNewId = newAdminId.trim()
        if (cleanNewId.length < 3) {
            return@withContext Result.failure(IllegalArgumentException("Admin User ID must be at least 3 characters."))
        }
        if (newPassword.length < 6) {
            return@withContext Result.failure(IllegalArgumentException("New admin password must be at least 6 characters."))
        }
        if (SupabaseConfig.isConfigured) {
            try {
                client.restApi.adminUpdateCredentials(
                    mapOf(
                        "p_current_id" to expectedId,
                        "p_current_password" to currentPassword,
                        "p_new_admin_id" to cleanNewId,
                        "p_new_password" to newPassword
                    )
                )
            } catch (e: HttpException) {
                if (e.code() != 404 && e.code() != 400) {
                    return@withContext Result.failure(friendlyException(e))
                }
            } catch (e: Exception) {
                return@withContext Result.failure(friendlyException(e))
            }
        }
        preferences.saveAdminCredentials(cleanNewId, newPassword)
        preferences.saveAdminSession(cleanNewId)
        Result.success(cleanNewId)
    }

    suspend fun getAdminDashboardStats(): Result<AdminDashboardStats> = withContext(Dispatchers.IO) {
        val appSettings = fetchAppSettings()
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.success(AdminDashboardStats(appSettings = appSettings))
        }
        try {
            val users = client.restApi.getUsers(order = "created_at.desc", limit = 500)
            val userMap = users.associateBy { it.id }

            val rawVideos = try {
                client.restApi.getVideos(privacy = null, order = "created_at.desc", limit = 500)
            } catch (e: HttpException) {
                if (e.code() == 400 || e.code() == 404) {
                    runCatching { client.restApi.getVideos(select = "*", privacy = null, order = "created_at.desc", limit = 500) }
                        .getOrDefault(emptyList())
                } else throw e
            }
            val videos = rawVideos.map { video ->
                val resolvedCreator = video.creator ?: userMap[video.userId]
                video.copy(creator = resolvedCreator)
            }
            val videoMap = videos.associateBy { it.id }

            val rawComments = try {
                client.restApi.getAllComments(limit = 300)
            } catch (_: Exception) {
                runCatching { client.restApi.getAllComments(select = "*", limit = 300) }.getOrDefault(emptyList())
            }
            val commentEntries = rawComments.map { c ->
                val resolvedAuthor = c.author ?: userMap[c.userId]
                AdminCommentEntry(
                    comment = c.copy(author = resolvedAuthor),
                    video = videoMap[c.videoId]
                )
            }

            val reports = runCatching { client.restApi.getReports(limit = 200) }.getOrDefault(emptyList())
            val ads = runCatching { client.restApi.getActiveAds() }.getOrDefault(emptyList())
            val live = runCatching { client.restApi.getLiveStreams() }.getOrDefault(emptyList())

            Result.success(
                AdminDashboardStats(
                    totalUsers = users.size,
                    totalVideos = videos.size,
                    totalComments = commentEntries.size,
                    totalViews = videos.sumOf { it.viewsCount.toLong() },
                    totalLikes = videos.sumOf { it.likesCount.toLong() },
                    verifiedUsersCount = users.count { it.isVerified },
                    pendingVerificationUsersCount = users.count { it.resolvedVerificationStatus == "pending" },
                    blockedUsersCount = users.count { it.isBlocked },
                    pendingReportsCount = reports.count { it.status == "pending" },
                    pendingReports = reports,
                    recentUsers = users,
                    recentVideos = videos,
                    recentComments = commentEntries,
                    activeAds = ads,
                    liveStreams = live,
                    appSettings = appSettings
                )
            )
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun deleteVideo(videoId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val session = preferences.getSessionSnapshot()
        val adminSession = preferences.getAdminSessionSnapshot()
        if (!session.isLoggedIn && !adminSession.isAuthenticated) {
            return@withContext Result.failure(IllegalStateException("Sign in to delete videos"))
        }
        try {
            val targetVideo = runCatching {
                client.restApi.getVideosByIds("in.($videoId)", select = "*").firstOrNull()
            }.getOrNull()
            val resp = client.restApi.deleteVideo("eq.$videoId")
            if (!resp.isSuccessful) throw HttpException(resp)
            val deleted = resp.body().orEmpty()
            if (deleted.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("Unable to delete video: permission denied or video already removed.")
                )
            }
            targetVideo?.let { v ->
                if (v.videoUrl.isNotBlank()) client.deleteStorageObject(v.videoUrl)
                if (v.thumbnailUrl.isNotBlank()) client.deleteStorageObject(v.thumbnailUrl)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun adminDeleteVideo(videoId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val (adminId, adminPass) = activeAdminCredentials()
        try {
            val targetVideo = runCatching {
                client.restApi.getVideosByIds("in.($videoId)", select = "*").firstOrNull()
            }.getOrNull()

            var rpcHandled = false
            try {
                val resp = client.restApi.adminDeleteVideo(
                    mapOf(
                        "p_admin_id" to adminId,
                        "p_admin_password" to adminPass,
                        "p_video_id" to videoId
                    )
                )
                if (!resp.isSuccessful) throw HttpException(resp)
                rpcHandled = true
            } catch (e: HttpException) {
                if (e.code() != 404 && e.code() != 400) throw e
            }

            if (!rpcHandled) {
                val fallback = client.restApi.deleteVideo("eq.$videoId")
                if (!fallback.isSuccessful) throw HttpException(fallback)
                val deletedRows = fallback.body().orEmpty()
                if (deletedRows.isEmpty()) {
                    return@withContext Result.failure(
                        IllegalStateException("Video delete blocked by database policy. Run supabase/schema.sql in Supabase SQL Editor.")
                    )
                }
            }

            targetVideo?.let { v ->
                if (v.videoUrl.isNotBlank()) client.deleteStorageObject(v.videoUrl)
                if (v.thumbnailUrl.isNotBlank()) client.deleteStorageObject(v.thumbnailUrl)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun toggleUserVerified(userId: String, verify: Boolean): Result<Unit> =
        adminSetUserVerificationStatus(userId, if (verify) "verified" else "rejected")

    suspend fun adminSetUserVerificationStatus(userId: String, status: String): Result<Unit> = withContext(Dispatchers.IO) {
        val (adminId, adminPass) = activeAdminCredentials()
        val normalized = status.lowercase().takeIf { it in setOf("verified", "rejected", "pending") } ?: "pending"
        val isVerified = normalized == "verified"
        try {
            var rpcMissing = false
            try {
                val resp = client.restApi.adminUpdateUser(
                    mapOf(
                        "p_admin_id" to adminId,
                        "p_admin_password" to adminPass,
                        "p_user_id" to userId,
                        "p_is_verified" to isVerified,
                        "p_verification_status" to normalized
                    )
                )
                if (resp.isSuccessful) {
                    return@withContext Result.success(Unit)
                }
                if (resp.code() == 400 || resp.code() == 404) {
                    // Retry without p_verification_status in case older DB schema lacks column
                    val legacyResp = client.restApi.adminUpdateUser(
                        mapOf(
                            "p_admin_id" to adminId,
                            "p_admin_password" to adminPass,
                            "p_user_id" to userId,
                            "p_is_verified" to isVerified
                        )
                    )
                    if (legacyResp.isSuccessful) {
                        return@withContext Result.success(Unit)
                    }
                    if (legacyResp.code() == 400 || legacyResp.code() == 404) {
                        rpcMissing = true
                    } else {
                        throw HttpException(legacyResp)
                    }
                } else {
                    throw HttpException(resp)
                }
            } catch (e: HttpException) {
                if (e.code() == 400 || e.code() == 404) {
                    val retryResp = runCatching {
                        client.restApi.adminUpdateUser(
                            mapOf(
                                "p_admin_id" to adminId,
                                "p_admin_password" to adminPass,
                                "p_user_id" to userId,
                                "p_is_verified" to isVerified
                            )
                        )
                    }.getOrNull()
                    if (retryResp != null && retryResp.isSuccessful) {
                        return@withContext Result.success(Unit)
                    }
                    if (retryResp != null && retryResp.code() != 400 && retryResp.code() != 404) {
                        throw HttpException(retryResp)
                    }
                    rpcMissing = true
                } else {
                    throw e
                }
            }

            if (rpcMissing) {
                val updated = try {
                    client.restApi.updateUserProfile(
                        "eq.$userId",
                        mapOf("is_verified" to isVerified, "verification_status" to normalized)
                    )
                } catch (e: HttpException) {
                    if (e.code() == 400) {
                        client.restApi.updateUserProfile("eq.$userId", mapOf("is_verified" to isVerified))
                    } else throw e
                }
                if (updated.isEmpty()) {
                    return@withContext Result.failure(
                        IllegalStateException("Verification update blocked by database policy. Run supabase/schema.sql in Supabase SQL Editor.")
                    )
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun adminSetUserBlocked(userId: String, blocked: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val (adminId, adminPass) = activeAdminCredentials()
        try {
            try {
                val resp = client.restApi.adminUpdateUser(
                    mapOf(
                        "p_admin_id" to adminId,
                        "p_admin_password" to adminPass,
                        "p_user_id" to userId,
                        "p_is_blocked" to blocked
                    )
                )
                if (!resp.isSuccessful) throw HttpException(resp)
                return@withContext Result.success(Unit)
            } catch (e: HttpException) {
                if (e.code() != 404 && e.code() != 400) throw e
            }
            val updated = client.restApi.updateUserProfile("eq.$userId", mapOf("is_blocked" to blocked))
            if (updated.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("User update blocked by database policy. Run supabase/schema.sql in Supabase SQL Editor.")
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun adminUpdateUserAccount(
        userId: String,
        displayName: String,
        username: String,
        bio: String,
        role: String,
        coinsBalance: Int,
        isVerified: Boolean,
        isBlocked: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val (adminId, adminPass) = activeAdminCredentials()
        val cleanName = displayName.trim()
        val cleanHandle = username.trim().lowercase().replace(Regex("[^a-z0-9_.]"), "")
        val cleanRole = role.lowercase().takeIf { it in setOf("user", "creator", "admin") } ?: "user"
        val verificationStatus = if (isVerified) "verified" else "pending"
        try {
            try {
                val resp = client.restApi.adminUpdateUser(
                    mapOf(
                        "p_admin_id" to adminId,
                        "p_admin_password" to adminPass,
                        "p_user_id" to userId,
                        "p_display_name" to cleanName,
                        "p_username" to cleanHandle.ifBlank { null },
                        "p_bio" to bio.trim(),
                        "p_role" to cleanRole,
                        "p_coins_balance" to coinsBalance.coerceAtLeast(0),
                        "p_is_verified" to isVerified,
                        "p_is_blocked" to isBlocked,
                        "p_verification_status" to verificationStatus
                    )
                )
                if (resp.isSuccessful) return@withContext Result.success(Unit)
                if (resp.code() == 400 || resp.code() == 404) {
                    val legacy = client.restApi.adminUpdateUser(
                        mapOf(
                            "p_admin_id" to adminId,
                            "p_admin_password" to adminPass,
                            "p_user_id" to userId,
                            "p_display_name" to cleanName,
                            "p_username" to cleanHandle.ifBlank { null },
                            "p_bio" to bio.trim(),
                            "p_role" to cleanRole,
                            "p_coins_balance" to coinsBalance.coerceAtLeast(0),
                            "p_is_verified" to isVerified,
                            "p_is_blocked" to isBlocked
                        )
                    )
                    if (legacy.isSuccessful) return@withContext Result.success(Unit)
                    if (legacy.code() != 400 && legacy.code() != 404) throw HttpException(legacy)
                } else {
                    throw HttpException(resp)
                }
            } catch (e: HttpException) {
                if (e.code() != 404 && e.code() != 400) throw e
            }
            val updates = mutableMapOf<String, Any>(
                "display_name" to cleanName,
                "bio" to bio.trim(),
                "role" to cleanRole,
                "coins_balance" to coinsBalance.coerceAtLeast(0),
                "is_verified" to isVerified,
                "is_blocked" to isBlocked
            )
            if (cleanHandle.isNotBlank()) updates["username"] = cleanHandle
            val updated = client.restApi.updateUserProfile("eq.$userId", updates)
            if (updated.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("User update blocked by database policy. Run supabase/schema.sql in Supabase SQL Editor.")
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun adminDeleteUser(userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val (adminId, adminPass) = activeAdminCredentials()
        try {
            try {
                val resp = client.restApi.adminDeleteUser(
                    mapOf(
                        "p_admin_id" to adminId,
                        "p_admin_password" to adminPass,
                        "p_user_id" to userId
                    )
                )
                if (!resp.isSuccessful) throw HttpException(resp)
                return@withContext Result.success(Unit)
            } catch (e: HttpException) {
                if (e.code() != 404 && e.code() != 400) throw e
            }
            val fallback = client.restApi.deleteUserProfile("eq.$userId")
            if (!fallback.isSuccessful) throw HttpException(fallback)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun adminPublishVideoForUser(
        contentResolver: ContentResolver,
        targetUserId: String,
        videoUri: Uri?,
        remoteVideoUrl: String,
        title: String,
        caption: String,
        category: String,
        location: String,
        privacy: String,
        onProgress: (Float) -> Unit
    ): Result<VideoItem> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Supabase is not configured in Secrets."))
        }
        val (adminId, adminPass) = activeAdminCredentials()
        val videoPublicUrl: String
        var thumbPublicUrl = ""
        var durationMs = 0L

        if (videoUri != null) {
            onProgress(0.02f)
            durationMs = extractVideoDurationMs(contentResolver, videoUri)
            val moderation = VideoContentModerator.moderateVideo(
                contentResolver = contentResolver,
                videoUri = videoUri,
                title = title,
                caption = caption,
                location = location,
                durationMs = durationMs
            )
            if (moderation is ModerationResult.Blocked) {
                return@withContext Result.failure(IllegalStateException(moderation.reason))
            }

            onProgress(0.06f)
            val detectedMime = contentResolver.getType(videoUri)
                ?.takeIf { it.startsWith("video/", ignoreCase = true) }
                ?: "video/mp4"
            val ext = when {
                detectedMime.contains("quicktime", ignoreCase = true) -> "mov"
                detectedMime.contains("webm", ignoreCase = true) -> "webm"
                detectedMime.contains("x-matroska", ignoreCase = true) -> "mkv"
                else -> "mp4"
            }
            val fileName = "$targetUserId/admin_${UUID.randomUUID()}.$ext"
            val uploadRes = client.uploadToStorage(
                contentResolver = contentResolver,
                fileUri = videoUri,
                bucket = "videos",
                objectPath = fileName,
                mimeType = detectedMime,
                onProgress = { rawProgress -> onProgress((0.06f + rawProgress * 0.79f).coerceIn(0.06f, 0.85f)) },
                allowAnonFallback = true
            )
            if (uploadRes.isFailure) {
                return@withContext Result.failure(
                    uploadRes.exceptionOrNull() ?: IllegalStateException("Video upload failed")
                )
            }
            videoPublicUrl = uploadRes.getOrThrow()
            onProgress(0.88f)
            thumbPublicUrl = extractAndUploadThumbnail(
                contentResolver = contentResolver,
                videoUri = videoUri,
                userId = targetUserId,
                allowAnonFallback = true,
                frameTimeMs = 500L
            )
            onProgress(0.94f)
        } else {
            val cleanUrl = remoteVideoUrl.trim()
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                return@withContext Result.failure(
                    IllegalArgumentException("Select a video file from gallery or enter a valid http(s) video URL.")
                )
            }
            onProgress(0.02f)
            val moderation = VideoContentModerator.moderateRemoteVideo(
                remoteUrl = cleanUrl,
                title = title,
                caption = caption,
                location = location
            )
            if (moderation is ModerationResult.Blocked) {
                return@withContext Result.failure(IllegalStateException(moderation.reason))
            }
            videoPublicUrl = cleanUrl
            onProgress(0.90f)
        }

        val cleanTitle = title.trim()
        val cleanCaption = caption.trim()
        val cleanCategory = category.trim().ifBlank { "All" }
        val cleanLocation = location.trim()
        val cleanPrivacy = privacy.trim().lowercase().takeIf { it in setOf("public", "friends", "private") } ?: "public"
        val extractedHashtags = Regex("#([A-Za-z0-9_\\u0980-\\u09FF]+)")
            .findAll("$cleanTitle $cleanCaption")
            .map { it.groupValues[1].lowercase() }
            .distinct()
            .toList()

        try {
            val targetCountry = LocationCatalog.canonicalizeCountry(
                runCatching { client.restApi.getUserById("eq.$targetUserId").firstOrNull()?.country.orEmpty() }
                    .getOrDefault("")
            )
            val createdList = try {
                client.restApi.adminCreateVideo(
                    mapOf(
                        "p_admin_id" to adminId,
                        "p_admin_password" to adminPass,
                        "p_user_id" to targetUserId,
                        "p_video_url" to videoPublicUrl,
                        "p_thumbnail_url" to thumbPublicUrl,
                        "p_title" to cleanTitle,
                        "p_caption" to cleanCaption,
                        "p_hashtags" to extractedHashtags,
                        "p_category" to cleanCategory,
                        "p_location" to cleanLocation,
                        "p_privacy" to cleanPrivacy,
                        "p_sound_id" to null,
                        "p_sound_title" to "Original Sound",
                        "p_duration_ms" to durationMs
                    )
                )
            } catch (e: HttpException) {
                if (e.code() != 404 && e.code() != 400) throw e
                try {
                    client.restApi.createVideo(
                        CreateVideoRequest(
                            userId = targetUserId,
                            videoUrl = videoPublicUrl,
                            thumbnailUrl = thumbPublicUrl,
                            title = cleanTitle,
                            caption = cleanCaption,
                            hashtags = extractedHashtags,
                            category = cleanCategory,
                            location = cleanLocation,
                            country = targetCountry,
                            privacy = cleanPrivacy,
                            durationMs = durationMs
                        )
                    )
                } catch (inner: HttpException) {
                    if (inner.code() == 401 || inner.code() == 403) {
                        throw IllegalStateException(
                            "Database security policy requires supabase/schema.sql to post videos under another user's account.",
                            inner
                        )
                    }
                    throw inner
                }
            }
            extractedHashtags.forEach { tag ->
                runCatching {
                    client.restApi.upsertHashtag(
                        mapOf("tag" to tag, "videos_count" to 1, "views_count" to 1, "is_trending" to true)
                    )
                }
            }
            val created = createdList.firstOrNull()
                ?: return@withContext Result.failure(IllegalStateException("Video record was not returned by Supabase."))
            val hydrated = if (created.creator != null) {
                created
            } else {
                val owner = runCatching { client.restApi.getUserById("eq.$targetUserId").firstOrNull() }.getOrNull()
                created.copy(creator = owner)
            }
            onProgress(1f)
            Result.success(hydrated)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun adminUpdateVideoData(
        videoId: String,
        title: String,
        caption: String,
        category: String,
        privacy: String,
        viewsCount: Int,
        likesCount: Int,
        sharesCount: Int,
        savesCount: Int,
        isSponsored: Boolean,
        sponsorLabel: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val (adminId, adminPass) = activeAdminCredentials()
        val cleanPrivacy = privacy.trim().lowercase().takeIf { it in setOf("public", "friends", "private") } ?: "public"
        try {
            try {
                val resp = client.restApi.adminUpdateVideo(
                    mapOf(
                        "p_admin_id" to adminId,
                        "p_admin_password" to adminPass,
                        "p_video_id" to videoId,
                        "p_title" to title.trim(),
                        "p_caption" to caption.trim(),
                        "p_category" to category.trim().ifBlank { "All" },
                        "p_privacy" to cleanPrivacy,
                        "p_views_count" to viewsCount.coerceAtLeast(0),
                        "p_likes_count" to likesCount.coerceAtLeast(0),
                        "p_shares_count" to sharesCount.coerceAtLeast(0),
                        "p_saves_count" to savesCount.coerceAtLeast(0),
                        "p_is_sponsored" to isSponsored,
                        "p_sponsor_label" to sponsorLabel.trim()
                    )
                )
                if (!resp.isSuccessful) throw HttpException(resp)
                return@withContext Result.success(Unit)
            } catch (e: HttpException) {
                if (e.code() != 404 && e.code() != 400) throw e
            }
            val resp = client.restApi.updateVideo(
                "eq.$videoId",
                mapOf(
                    "title" to title.trim(),
                    "caption" to caption.trim(),
                    "category" to category.trim().ifBlank { "All" },
                    "privacy" to cleanPrivacy,
                    "views_count" to viewsCount.coerceAtLeast(0),
                    "likes_count" to likesCount.coerceAtLeast(0),
                    "shares_count" to sharesCount.coerceAtLeast(0),
                    "saves_count" to savesCount.coerceAtLeast(0),
                    "is_sponsored" to isSponsored,
                    "sponsor_label" to sponsorLabel.trim()
                )
            )
            if (!resp.isSuccessful) throw HttpException(resp)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun adminDeleteComment(commentId: String, videoId: String, currentVideoCommentsCount: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val (adminId, adminPass) = activeAdminCredentials()
        try {
            try {
                val resp = client.restApi.adminDeleteComment(
                    mapOf(
                        "p_admin_id" to adminId,
                        "p_admin_password" to adminPass,
                        "p_comment_id" to commentId
                    )
                )
                if (!resp.isSuccessful) throw HttpException(resp)
                return@withContext Result.success(Unit)
            } catch (e: HttpException) {
                if (e.code() != 404 && e.code() != 400) throw e
            }
            val resp = client.restApi.deleteComment("eq.$commentId")
            if (!resp.isSuccessful) throw HttpException(resp)
            runCatching {
                client.restApi.updateVideoCounts(
                    "eq.$videoId",
                    mapOf("comments_count" to (currentVideoCommentsCount - 1).coerceAtLeast(0))
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun resolveReport(reportId: String, status: String): Result<Unit> = withContext(Dispatchers.IO) {
        val (adminId, adminPass) = activeAdminCredentials()
        try {
            try {
                val resp = client.restApi.adminResolveReport(
                    mapOf(
                        "p_admin_id" to adminId,
                        "p_admin_password" to adminPass,
                        "p_report_id" to reportId,
                        "p_status" to status
                    )
                )
                if (!resp.isSuccessful) throw HttpException(resp)
                return@withContext Result.success(Unit)
            } catch (e: HttpException) {
                if (e.code() != 404 && e.code() != 400) throw e
            }
            val resp = client.restApi.updateReportStatus("eq.$reportId", mapOf("status" to status))
            if (!resp.isSuccessful) throw HttpException(resp)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun adminDeleteReport(reportId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val (adminId, adminPass) = activeAdminCredentials()
        try {
            try {
                val resp = client.restApi.adminDeleteReport(
                    mapOf(
                        "p_admin_id" to adminId,
                        "p_admin_password" to adminPass,
                        "p_report_id" to reportId
                    )
                )
                if (!resp.isSuccessful) throw HttpException(resp)
                return@withContext Result.success(Unit)
            } catch (e: HttpException) {
                if (e.code() != 404 && e.code() != 400) throw e
            }
            val resp = client.restApi.deleteReport("eq.$reportId")
            if (!resp.isSuccessful) throw HttpException(resp)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(friendlyException(e))
        }
    }

    suspend fun getLiveStreams(): List<LiveStreamItem> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext emptyList()
        runCatching { client.restApi.getLiveStreams() }.getOrDefault(emptyList())
    }

    suspend fun getFavoriteFilterIds(defaults: Set<String>): Set<String> =
        preferences.getFavoriteFilterIds(defaults)

    suspend fun saveFavoriteFilterIds(ids: Set<String>) =
        preferences.saveFavoriteFilterIds(ids)

    suspend fun getDownloadedFilterIds(defaults: Set<String>): Set<String> =
        preferences.getDownloadedFilterIds(defaults)

    suspend fun saveDownloadedFilterIds(ids: Set<String>) =
        preferences.saveDownloadedFilterIds(ids)

    suspend fun getSavedStudioDraftJson(): String? =
        preferences.getSavedStudioDraftJson()

    suspend fun saveStudioDraftJson(json: String) =
        preferences.saveStudioDraftJson(json)

    suspend fun clearSavedStudioDraft() =
        preferences.clearSavedStudioDraft()
}
