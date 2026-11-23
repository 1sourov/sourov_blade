package com.example.ui.screens

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.i18n.BladeStrings
import com.example.data.local.UserSettingsPrefs
import com.example.data.model.LocationCatalog
import com.example.data.model.LocationPreferences
import com.example.data.model.UserProfile
import com.example.ui.theme.BladeCrimson
import com.example.ui.theme.BladeCyan
import com.example.ui.theme.BladeEmerald
import com.example.ui.theme.TextWhite
import java.io.File
import java.util.Locale

enum class SettingsSheetSection {
    MANAGE_ACCOUNT,
    PRIVACY,
    CONTENT_PREFERENCES,
    COUNTRY_REGION,
    NOTIFICATIONS,
    ACTIVITY_CENTRE,
    ADS,
    APP_LANGUAGE,
    SCREEN_TIME,
    FAMILY_PAIRING,
    AUDIENCE_CONTROL,
    DISPLAY_ACCESSIBILITY,
    FREE_UP_SPACE,
    DATA_SAVER,
    DEVELOPER
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsDetailSheet(
    section: SettingsSheetSection,
    currentUser: UserProfile?,
    isDarkMode: Boolean,
    languageCode: String,
    userSettings: UserSettingsPrefs,
    locationPreferences: LocationPreferences = LocationPreferences(),
    cacheSizeBytes: Long,
    strings: BladeStrings,
    onDismiss: () -> Unit,
    onEditProfile: () -> Unit,
    onToggleAccountPrivacy: (Boolean) -> Unit,
    onUpdateSettings: ((UserSettingsPrefs) -> UserSettingsPrefs) -> Unit,
    onUpdateLocationPreferences: (LocationPreferences) -> Unit = {},
    onToggleDarkMode: (Boolean) -> Unit,
    onSelectLanguage: (String) -> Unit,
    onClearWatchHistory: () -> Unit,
    onClearAppCache: () -> Unit,
    onSignOut: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (section) {
                SettingsSheetSection.MANAGE_ACCOUNT -> {
                    SheetHeader(
                        title = "Manage account",
                        subtitle = "Account details, profile visibility, and session controls"
                    )
                    if (currentUser != null) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = currentUser.displayName.ifBlank { currentUser.username },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (currentUser.isVerified) {
                                        Spacer(Modifier.width(6.dp))
                                        Icon(
                                            Icons.Filled.CheckCircle,
                                            contentDescription = "Verified",
                                            tint = BladeEmerald,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "@${currentUser.username}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = if (currentUser.isPrivate) "Private account" else "Public account",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Button(
                            onClick = {
                                onDismiss()
                                onEditProfile()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("settings_edit_profile_button")
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(strings.editProfile)
                        }
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onSignOut()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("settings_manage_signout_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(strings.signOut)
                        }
                    }
                }

                SettingsSheetSection.PRIVACY -> {
                    SheetHeader(
                        title = "Privacy",
                        subtitle = "Control who can view your profile and interact with your content"
                    )
                    SettingSwitchRow(
                        title = "Private account",
                        subtitle = "Only approved followers can see your private videos",
                        checked = currentUser?.isPrivate == true,
                        testTag = "privacy_private_account_switch",
                        onCheckedChange = { enabled ->
                            onToggleAccountPrivacy(enabled)
                        }
                    )
                    SettingSwitchRow(
                        title = "Allow comments",
                        subtitle = "Allow viewers to leave comments on your videos",
                        checked = userSettings.allowComments,
                        testTag = "privacy_allow_comments_switch",
                        onCheckedChange = { enabled ->
                            onUpdateSettings { it.copy(allowComments = enabled) }
                        }
                    )
                    SettingSwitchRow(
                        title = "Suggest account to others",
                        subtitle = "Let friends and contacts discover your creator profile",
                        checked = userSettings.suggestAccountToOthers,
                        testTag = "privacy_suggest_account_switch",
                        onCheckedChange = { enabled ->
                            onUpdateSettings { it.copy(suggestAccountToOthers = enabled) }
                        }
                    )
                }

                SettingsSheetSection.CONTENT_PREFERENCES -> {
                    SheetHeader(
                        title = "Content preferences",
                        subtitle = "Customize what appears in your For You feed"
                    )
                    SettingSwitchRow(
                        title = "Restricted Mode",
                        subtitle = "Limit videos that may contain sensitive themes",
                        checked = userSettings.restrictedMode,
                        testTag = "content_restricted_mode_switch",
                        onCheckedChange = { enabled ->
                            onUpdateSettings { it.copy(restrictedMode = enabled) }
                        }
                    )
                    OutlinedButton(
                        onClick = {
                            onClearWatchHistory()
                            onShowMessage("For You feed preferences refreshed")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("content_refresh_feed_button")
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Refresh your For You feed")
                    }
                }

                SettingsSheetSection.NOTIFICATIONS -> {
                    SheetHeader(
                        title = "Notifications",
                        subtitle = "Choose which activity updates you receive"
                    )
                    SettingSwitchRow(
                        title = "Likes",
                        subtitle = "Notify when someone likes your video",
                        checked = userSettings.notifyLikes,
                        testTag = "notify_likes_switch",
                        onCheckedChange = { v -> onUpdateSettings { it.copy(notifyLikes = v) } }
                    )
                    SettingSwitchRow(
                        title = "Comments",
                        subtitle = "Notify when someone comments on your video",
                        checked = userSettings.notifyComments,
                        testTag = "notify_comments_switch",
                        onCheckedChange = { v -> onUpdateSettings { it.copy(notifyComments = v) } }
                    )
                    SettingSwitchRow(
                        title = "New followers",
                        subtitle = "Notify when someone starts following you",
                        checked = userSettings.notifyFollowers,
                        testTag = "notify_followers_switch",
                        onCheckedChange = { v -> onUpdateSettings { it.copy(notifyFollowers = v) } }
                    )
                    SettingSwitchRow(
                        title = "Direct messages",
                        subtitle = "Notify when you receive a new chat message",
                        checked = userSettings.notifyMessages,
                        testTag = "notify_messages_switch",
                        onCheckedChange = { v -> onUpdateSettings { it.copy(notifyMessages = v) } }
                    )
                }

                SettingsSheetSection.ACTIVITY_CENTRE -> {
                    SheetHeader(
                        title = "Activity centre",
                        subtitle = "Review or clear your local watch and feed history"
                    )
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Notifications, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "Watch history personalizes your For You feed order. Clearing it resets feed recommendations on this device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Button(
                        onClick = {
                            onClearWatchHistory()
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("activity_clear_history_button")
                    ) {
                        Text("Clear watch & feed history")
                    }
                }

                SettingsSheetSection.ADS -> {
                    SheetHeader(
                        title = "Ads",
                        subtitle = "Manage how sponsored videos are selected"
                    )
                    SettingSwitchRow(
                        title = "Personalized ads",
                        subtitle = "Show relevant sponsored blades based on your interests",
                        checked = userSettings.personalizedAds,
                        testTag = "ads_personalized_switch",
                        onCheckedChange = { v -> onUpdateSettings { it.copy(personalizedAds = v) } }
                    )
                }

                SettingsSheetSection.APP_LANGUAGE -> {
                    SheetHeader(
                        title = "App language",
                        subtitle = "Select your preferred display language"
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilterChip(
                            selected = languageCode == "en",
                            onClick = {
                                onSelectLanguage("en")
                                onShowMessage("Language changed to English (UK)")
                            },
                            label = { Text("English (UK)") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BladeCrimson,
                                selectedLabelColor = TextWhite
                            ),
                            modifier = Modifier.testTag("lang_en_chip")
                        )
                        FilterChip(
                            selected = languageCode == "bn",
                            onClick = {
                                onSelectLanguage("bn")
                                onShowMessage("ভাষা বাংলা করা হয়েছে")
                            },
                            label = { Text("বাংলা (Bengali)") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BladeCrimson,
                                selectedLabelColor = TextWhite
                            ),
                            modifier = Modifier.testTag("lang_bn_chip")
                        )
                    }
                }

                SettingsSheetSection.SCREEN_TIME -> {
                    SheetHeader(
                        title = "Screen time",
                        subtitle = "Set daily watch limits and sleep reminders"
                    )
                    Text(
                        text = "Daily screen time limit",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val options = listOf(0, 30, 60, 90, 120)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        options.forEach { mins ->
                            FilterChip(
                                selected = userSettings.dailyScreenTimeMinutes == mins,
                                onClick = {
                                    onUpdateSettings { it.copy(dailyScreenTimeMinutes = mins) }
                                },
                                label = { Text(if (mins == 0) "Off" else "${mins}m") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BladeCrimson,
                                    selectedLabelColor = TextWhite
                                ),
                                modifier = Modifier.testTag("screen_time_${mins}_chip")
                            )
                        }
                    }
                    SettingSwitchRow(
                        title = "Sleep reminders",
                        subtitle = "Remind you to take a break late at night",
                        checked = userSettings.sleepRemindersEnabled,
                        testTag = "screen_time_sleep_switch",
                        onCheckedChange = { v -> onUpdateSettings { it.copy(sleepRemindersEnabled = v) } }
                    )
                }

                SettingsSheetSection.FAMILY_PAIRING -> {
                    SheetHeader(
                        title = "Family Pairing",
                        subtitle = "Manage safety guardrails for younger viewers"
                    )
                    SettingSwitchRow(
                        title = "Safe viewing guardrails",
                        subtitle = "Apply stricter filtering on search and feeds",
                        checked = userSettings.familyRestrictedMode,
                        testTag = "family_restricted_switch",
                        onCheckedChange = { v -> onUpdateSettings { it.copy(familyRestrictedMode = v) } }
                    )
                    SettingSwitchRow(
                        title = "Restrict direct messages",
                        subtitle = "Limit incoming messages to mutual friends only",
                        checked = userSettings.familyRestrictDms,
                        testTag = "family_restrict_dms_switch",
                        onCheckedChange = { v -> onUpdateSettings { it.copy(familyRestrictDms = v) } }
                    )
                }

                SettingsSheetSection.AUDIENCE_CONTROL -> {
                    SheetHeader(
                        title = "Audience control",
                        subtitle = "Default audience and sharing permissions for your videos"
                    )
                    Text(
                        text = "Default video visibility",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        listOf("Public", "Friends", "Private").forEach { option ->
                            FilterChip(
                                selected = userSettings.defaultPostPrivacy.equals(option, ignoreCase = true),
                                onClick = {
                                    onUpdateSettings { it.copy(defaultPostPrivacy = option) }
                                },
                                label = { Text(option) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BladeCrimson,
                                    selectedLabelColor = TextWhite
                                ),
                                modifier = Modifier.testTag("audience_privacy_${option.lowercase()}_chip")
                            )
                        }
                    }
                    SettingSwitchRow(
                        title = "Allow downloads",
                        subtitle = "Allow viewers to save or download your public videos",
                        checked = userSettings.allowDownloads,
                        testTag = "audience_downloads_switch",
                        onCheckedChange = { v -> onUpdateSettings { it.copy(allowDownloads = v) } }
                    )
                    SettingSwitchRow(
                        title = "Allow reactions & duets",
                        subtitle = "Allow creators to respond to your videos",
                        checked = userSettings.allowReactions,
                        testTag = "audience_reactions_switch",
                        onCheckedChange = { v -> onUpdateSettings { it.copy(allowReactions = v) } }
                    )
                }

                SettingsSheetSection.DISPLAY_ACCESSIBILITY -> {
                    SheetHeader(
                        title = "Display & accessibility",
                        subtitle = "Theme appearance, captions, and motion preferences"
                    )
                    SettingSwitchRow(
                        title = strings.darkMode,
                        subtitle = "Use dark obsidian theme across the app",
                        checked = isDarkMode,
                        testTag = "settings_dark_mode_switch",
                        onCheckedChange = onToggleDarkMode
                    )
                    SettingSwitchRow(
                        title = "Auto-generated captions",
                        subtitle = "Show captions automatically when available",
                        checked = userSettings.autoCaptions,
                        testTag = "display_auto_captions_switch",
                        onCheckedChange = { v -> onUpdateSettings { it.copy(autoCaptions = v) } }
                    )
                    SettingSwitchRow(
                        title = "Reduce motion",
                        subtitle = "Use gentler animations across feeds and transitions",
                        checked = userSettings.reduceMotion,
                        testTag = "display_reduce_motion_switch",
                        onCheckedChange = { v -> onUpdateSettings { it.copy(reduceMotion = v) } }
                    )
                }

                SettingsSheetSection.FREE_UP_SPACE -> {
                    SheetHeader(
                        title = "Free up space",
                        subtitle = "Clear temporary video exports and image cache"
                    )
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Cached media & temp files", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = formatCacheSizeMb(cacheSizeBytes),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Button(
                        onClick = onClearAppCache,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("settings_clear_cache_button")
                    ) {
                        Icon(Icons.Filled.CleaningServices, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Clear cache")
                    }
                }

                SettingsSheetSection.DATA_SAVER -> {
                    SheetHeader(
                        title = "Data Saver",
                        subtitle = "Reduce cellular data consumption while watching videos"
                    )
                    SettingSwitchRow(
                        title = "Enable Data Saver",
                        subtitle = "Limit background video preloading on mobile networks",
                        checked = userSettings.dataSaverEnabled,
                        testTag = "data_saver_switch",
                        onCheckedChange = { v -> onUpdateSettings { it.copy(dataSaverEnabled = v) } }
                    )
                }

                SettingsSheetSection.COUNTRY_REGION -> {
                    var draftPrefs by remember(locationPreferences) {
                        mutableStateOf(
                            locationPreferences.copy(
                                country = locationPreferences.country.ifBlank {
                                    currentUser?.country.orEmpty()
                                }
                            )
                        )
                    }
                    var countrySearch by remember { mutableStateOf("") }
                    val filteredCountries = remember(countrySearch) {
                        val q = countrySearch.trim()
                        if (q.isBlank()) LocationCatalog.countries
                        else LocationCatalog.countries.filter { it.contains(q, ignoreCase = true) }
                    }

                    SheetHeader(
                        title = strings.countryAndRegion,
                        subtitle = "Your selected country controls your default video feed"
                    )

                    OutlinedTextField(
                        value = countrySearch,
                        onValueChange = { countrySearch = it },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        label = { Text(strings.countryLabel) },
                        placeholder = { Text(strings.selectCountry) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_country_search")
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        filteredCountries.forEach { countryOption ->
                            val selected = draftPrefs.country.equals(countryOption, ignoreCase = true)
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    draftPrefs = if (countryOption.equals("India", ignoreCase = true)) {
                                        draftPrefs.copy(country = "India")
                                    } else {
                                        LocationPreferences(country = countryOption)
                                    }
                                },
                                label = { Text(countryOption) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BladeCrimson,
                                    selectedLabelColor = TextWhite
                                ),
                                modifier = Modifier.testTag("country_chip_${countryOption.lowercase().replace(" ", "_")}")
                            )
                        }
                    }

                    if (draftPrefs.isIndia) {
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = strings.indiaPreferencesTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (draftPrefs.hasActiveIndiaFilters) {
                                        "Feed filtered to your selected India preferences below."
                                    } else {
                                        "No filters selected — showing videos from ${strings.allOfIndia}."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        OptionalIndiaChipSelector(
                            label = strings.languageFilterLabel,
                            options = LocationCatalog.indiaLanguages,
                            selectedValue = draftPrefs.preferredLanguage,
                            allLabel = strings.allOfIndia,
                            onSelect = { chosen -> draftPrefs = draftPrefs.copy(preferredLanguage = chosen) }
                        )

                        OptionalIndiaChipSelector(
                            label = strings.regionLabel,
                            options = LocationCatalog.indiaRegions,
                            selectedValue = draftPrefs.preferredRegion,
                            allLabel = strings.allOfIndia,
                            onSelect = { chosen -> draftPrefs = draftPrefs.copy(preferredRegion = chosen) }
                        )

                        OptionalIndiaChipSelector(
                            label = strings.stateLabel,
                            options = LocationCatalog.indiaStates,
                            selectedValue = draftPrefs.preferredState,
                            allLabel = strings.allOfIndia,
                            onSelect = { chosen ->
                                val validDivisions = LocationCatalog.divisionsForState(chosen)
                                val keptDivision = if (draftPrefs.preferredDivision in validDivisions) {
                                    draftPrefs.preferredDivision
                                } else {
                                    ""
                                }
                                draftPrefs = draftPrefs.copy(
                                    preferredState = chosen,
                                    preferredDivision = keptDivision
                                )
                            }
                        )

                        OptionalIndiaChipSelector(
                            label = strings.divisionLabel,
                            options = LocationCatalog.divisionsForState(draftPrefs.preferredState),
                            selectedValue = draftPrefs.preferredDivision,
                            allLabel = strings.allOfIndia,
                            onSelect = { chosen -> draftPrefs = draftPrefs.copy(preferredDivision = chosen) }
                        )

                        if (draftPrefs.hasActiveIndiaFilters) {
                            OutlinedButton(
                                onClick = { draftPrefs = draftPrefs.clearedIndiaFilters() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .testTag("settings_clear_india_filters")
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(strings.clearIndiaFilters)
                            }
                        }
                    }

                    Button(
                        onClick = {
                            val normalized = draftPrefs.normalized()
                            if (normalized.country.isBlank()) {
                                onShowMessage(strings.selectCountry)
                            } else {
                                onUpdateLocationPreferences(normalized)
                                onDismiss()
                            }
                        },
                        enabled = draftPrefs.country.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BladeCrimson,
                            contentColor = TextWhite
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("settings_save_country_region")
                    ) {
                        Text(strings.save)
                    }
                }

                SettingsSheetSection.DEVELOPER -> {
                    DeveloperDetailsSheetContent(
                        onClose = onDismiss
                    )
                }
            }
        }
    }
}

@Composable
private fun DeveloperDetailsSheetContent(
    onClose: () -> Unit
) {
    val devName = stringResource(R.string.developer_name_value)
    val devAge = stringResource(R.string.developer_age_value)
    val devLocation = stringResource(R.string.developer_location_value)

    SheetHeader(
        title = stringResource(R.string.settings_developer),
        subtitle = "Creator & engineering profile"
    )

    Surface(
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        BladeCrimson.copy(alpha = 0.42f),
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                        BladeCyan.copy(alpha = 0.36f)
                    )
                ),
                shape = RoundedCornerShape(24.dp)
            )
            .testTag("developer_info_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            BladeCrimson.copy(alpha = 0.12f),
                            BladeCyan.copy(alpha = 0.05f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.0f)
                        )
                    )
                )
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = 156.dp, height = 192.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        width = 2.dp,
                        brush = Brush.linearGradient(listOf(BladeCrimson, BladeCyan)),
                        shape = RoundedCornerShape(22.dp)
                    )
            ) {
                Image(
                    painter = painterResource(id = R.drawable.developer_sourov_photo),
                    contentDescription = "Developer portrait of $devName",
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("developer_photo")
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = devName,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.2.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Surface(
                    shape = CircleShape,
                    color = BladeCrimson.copy(alpha = 0.14f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Code,
                            contentDescription = null,
                            tint = BladeCrimson,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Lead App Developer",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = BladeCrimson
                        )
                    }
                }
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 0.75.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                shape = RoundedCornerShape(18.dp)
            )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            DeveloperDetailRow(
                icon = Icons.Filled.Person,
                label = stringResource(R.string.developer_name_label),
                value = devName
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
            )
            DeveloperDetailRow(
                icon = Icons.Filled.Cake,
                label = stringResource(R.string.developer_age_label),
                value = devAge
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
            )
            DeveloperDetailRow(
                icon = Icons.Filled.LocationOn,
                label = stringResource(R.string.developer_location_label),
                value = devLocation
            )
        }
    }

    OutlinedButton(
        onClick = onClose,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("developer_sheet_close")
    ) {
        Text("Done")
    }
}

@Composable
private fun DeveloperDetailRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(BladeCrimson.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = BladeCrimson,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun OptionalIndiaChipSelector(
    label: String,
    options: List<String>,
    selectedValue: String,
    allLabel: String,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = selectedValue.isBlank(),
                    onClick = { onSelect("") },
                    label = { Text(allLabel) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BladeCrimson,
                        selectedLabelColor = TextWhite
                    )
                )
            }
            items(options) { option ->
                val isSelected = selectedValue.equals(option, ignoreCase = true)
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(if (isSelected) "" else option) },
                    label = { Text(option) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BladeCrimson,
                        selectedLabelColor = TextWhite
                    )
                )
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

fun calculateCacheSizeBytes(context: Context): Long {
    fun dirSize(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { dirSize(it) } ?: 0L
    }
    return dirSize(context.cacheDir) + dirSize(context.externalCacheDir)
}

fun clearAppCacheFiles(context: Context): Long {
    fun deleteChildren(dir: File?) {
        if (dir == null || !dir.exists() || !dir.isDirectory) return
        dir.listFiles()?.forEach { child ->
            runCatching { child.deleteRecursively() }
        }
    }
    deleteChildren(context.cacheDir)
    deleteChildren(context.externalCacheDir)
    return calculateCacheSizeBytes(context)
}

fun formatCacheSizeMb(bytes: Long): String {
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MB", mb.coerceAtLeast(0.0))
}
