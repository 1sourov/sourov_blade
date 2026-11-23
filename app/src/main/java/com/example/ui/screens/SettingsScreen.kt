package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DataSaverOff
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.i18n.bladeStrings
import com.example.data.local.UserSettingsPrefs
import com.example.data.model.LocationPreferences
import com.example.data.model.UserProfile
import com.example.ui.theme.BladeCrimson
import com.example.ui.theme.BladeEmerald

@Composable
fun SettingsScreen(
    currentUser: UserProfile?,
    isDarkMode: Boolean,
    languageCode: String,
    isSupabaseConfigured: Boolean,
    userSettings: UserSettingsPrefs = UserSettingsPrefs(),
    locationPreferences: LocationPreferences = LocationPreferences(),
    onToggleDarkMode: (Boolean) -> Unit,
    onSelectLanguage: (String) -> Unit,
    onUpdateUserSettings: ((UserSettingsPrefs) -> UserSettingsPrefs) -> Unit = {},
    onUpdateLocationPreferences: (LocationPreferences) -> Unit = {},
    onToggleAccountPrivacy: (Boolean) -> Unit = {},
    onEditProfile: () -> Unit = {},
    onClearWatchHistory: () -> Unit = {},
    onShowMessage: (String) -> Unit = {},
    onSignOut: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = bladeStrings()
    val context = LocalContext.current
    var activeSheet by remember { mutableStateOf<SettingsSheetSection?>(null) }
    var cacheSizeBytes by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        cacheSizeBytes = calculateCacheSizeBytes(context)
    }

    val languageLabel = if (languageCode == "bn") "বাংলা" else "English (UK)"
    val screenTimeLabel = if (userSettings.dailyScreenTimeMinutes > 0) {
        "${userSettings.dailyScreenTimeMinutes}m"
    } else {
        null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
        ) {
            // Top bar matching "Settings and privacy"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("settings_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Text(
                    text = strings.settingsAndPrivacy,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.size(48.dp))
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp)
            ) {
                // ACCOUNT
                SettingsSectionHeader("ACCOUNT")
                SettingsMenuItem(
                    icon = Icons.Outlined.PersonOutline,
                    title = "Manage account",
                    testTag = "settings_manage_account",
                    onClick = { activeSheet = SettingsSheetSection.MANAGE_ACCOUNT }
                )
                SettingsMenuItem(
                    icon = Icons.Outlined.Lock,
                    title = "Privacy",
                    trailingText = if (currentUser?.isPrivate == true) "Private" else null,
                    testTag = "settings_privacy",
                    onClick = { activeSheet = SettingsSheetSection.PRIVACY }
                )

                SettingsSectionDivider()

                // CONTENT & ACTIVITY
                SettingsSectionHeader("CONTENT & ACTIVITY")
                SettingsMenuItem(
                    icon = Icons.Outlined.Videocam,
                    title = "Content preferences",
                    trailingText = if (userSettings.restrictedMode) "Restricted" else null,
                    testTag = "settings_content_preferences",
                    onClick = { activeSheet = SettingsSheetSection.CONTENT_PREFERENCES }
                )
                SettingsMenuItem(
                    icon = Icons.Outlined.Public,
                    title = strings.countryAndRegion,
                    trailingText = when {
                        locationPreferences.isIndia && locationPreferences.hasActiveIndiaFilters -> {
                            listOf(
                                locationPreferences.preferredState,
                                locationPreferences.preferredLanguage
                            ).firstOrNull { it.isNotBlank() }?.let { "India • $it" } ?: "India"
                        }
                        locationPreferences.isIndia -> "India (${strings.allOfIndia})"
                        locationPreferences.country.isNotBlank() -> locationPreferences.country
                        else -> strings.selectCountry
                    },
                    testTag = "settings_country_region",
                    onClick = { activeSheet = SettingsSheetSection.COUNTRY_REGION }
                )
                SettingsMenuItem(
                    icon = Icons.Outlined.Notifications,
                    title = "Notifications",
                    testTag = "settings_notifications",
                    onClick = { activeSheet = SettingsSheetSection.NOTIFICATIONS }
                )
                SettingsMenuItem(
                    icon = Icons.Outlined.Schedule,
                    title = "Activity centre",
                    testTag = "settings_activity_centre",
                    onClick = { activeSheet = SettingsSheetSection.ACTIVITY_CENTRE }
                )
                SettingsMenuItem(
                    icon = Icons.Outlined.Campaign,
                    title = "Ads",
                    testTag = "settings_ads",
                    onClick = { activeSheet = SettingsSheetSection.ADS }
                )
                SettingsMenuItem(
                    icon = Icons.Outlined.FontDownload,
                    title = "App language",
                    trailingText = languageLabel,
                    testTag = "settings_app_language",
                    onClick = { activeSheet = SettingsSheetSection.APP_LANGUAGE }
                )
                SettingsMenuItem(
                    icon = Icons.Outlined.HourglassEmpty,
                    title = "Screen time",
                    trailingText = screenTimeLabel,
                    testTag = "settings_screen_time",
                    onClick = { activeSheet = SettingsSheetSection.SCREEN_TIME }
                )
                SettingsMenuItem(
                    icon = Icons.Outlined.Home,
                    title = "Family Pairing",
                    testTag = "settings_family_pairing",
                    onClick = { activeSheet = SettingsSheetSection.FAMILY_PAIRING }
                )
                SettingsMenuItem(
                    icon = Icons.Outlined.Group,
                    title = "Audience control",
                    trailingText = userSettings.defaultPostPrivacy.takeIf { it != "Public" },
                    testTag = "settings_audience_control",
                    onClick = { activeSheet = SettingsSheetSection.AUDIENCE_CONTROL }
                )
                SettingsMenuItem(
                    icon = Icons.Outlined.AccessibilityNew,
                    title = "Display & accessibility",
                    trailingText = if (isDarkMode) "Dark" else "Light",
                    testTag = "settings_display_accessibility",
                    onClick = { activeSheet = SettingsSheetSection.DISPLAY_ACCESSIBILITY }
                )

                SettingsSectionDivider()

                // CACHE & MOBILE DATA
                SettingsSectionHeader("CACHE & MOBILE DATA")
                SettingsMenuItem(
                    icon = Icons.Outlined.DeleteOutline,
                    title = "Free up space",
                    trailingText = formatCacheSizeMb(cacheSizeBytes),
                    testTag = "settings_free_up_space",
                    onClick = {
                        cacheSizeBytes = calculateCacheSizeBytes(context)
                        activeSheet = SettingsSheetSection.FREE_UP_SPACE
                    }
                )
                SettingsMenuItem(
                    icon = Icons.Outlined.DataSaverOff,
                    title = "Data Saver",
                    trailingText = if (userSettings.dataSaverEnabled) "On" else null,
                    testTag = "settings_data_saver",
                    onClick = { activeSheet = SettingsSheetSection.DATA_SAVER }
                )

                SettingsSectionDivider()

                // ABOUT
                SettingsSectionHeader("ABOUT")
                SettingsMenuItem(
                    icon = Icons.Outlined.Code,
                    title = stringResource(R.string.settings_developer),
                    trailingText = stringResource(R.string.developer_name_value),
                    testTag = "settings_developer",
                    onClick = { activeSheet = SettingsSheetSection.DEVELOPER }
                )

                SettingsSectionDivider()

                // LOGIN & STATUS
                SettingsSectionHeader("LOGIN")
                SettingsMenuItem(
                    icon = if (isSupabaseConfigured) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff,
                    iconTint = if (isSupabaseConfigured) BladeEmerald else BladeCrimson,
                    title = "Cloud Backend",
                    trailingText = if (isSupabaseConfigured) "Connected" else "Offline",
                    testTag = "settings_cloud_status",
                    onClick = {
                        onShowMessage(
                            if (isSupabaseConfigured) "Cloud backend connected" else "Offline mode"
                        )
                    }
                )
                if (currentUser != null) {
                    SettingsMenuItem(
                        icon = Icons.AutoMirrored.Filled.Logout,
                        iconTint = MaterialTheme.colorScheme.error,
                        title = strings.signOut,
                        titleColor = MaterialTheme.colorScheme.error,
                        testTag = "settings_sign_out",
                        onClick = onSignOut
                    )
                }
            }
        }
    }

    activeSheet?.let { section ->
        SettingsDetailSheet(
            section = section,
            currentUser = currentUser,
            isDarkMode = isDarkMode,
            languageCode = languageCode,
            userSettings = userSettings,
            locationPreferences = locationPreferences,
            cacheSizeBytes = cacheSizeBytes,
            strings = strings,
            onDismiss = { activeSheet = null },
            onEditProfile = onEditProfile,
            onToggleAccountPrivacy = onToggleAccountPrivacy,
            onUpdateSettings = onUpdateUserSettings,
            onUpdateLocationPreferences = onUpdateLocationPreferences,
            onToggleDarkMode = onToggleDarkMode,
            onSelectLanguage = onSelectLanguage,
            onClearWatchHistory = onClearWatchHistory,
            onClearAppCache = {
                cacheSizeBytes = clearAppCacheFiles(context)
                onShowMessage("Cache cleared")
            },
            onSignOut = onSignOut,
            onShowMessage = onShowMessage
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 6.dp)
    )
}

@Composable
private fun SettingsSectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
        thickness = 0.6.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    )
}

@Composable
private fun SettingsMenuItem(
    icon: ImageVector,
    title: String,
    trailingText: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleColor: Color = MaterialTheme.colorScheme.onBackground,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal
            ),
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!trailingText.isNullOrBlank()) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = trailingText,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
