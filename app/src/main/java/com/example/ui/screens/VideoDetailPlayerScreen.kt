package com.example.ui.screens

import android.content.Intent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.ModeComment
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.i18n.bladeStrings
import com.example.data.model.UiState
import com.example.data.model.UserProfile
import com.example.data.model.VideoItem
import com.example.ui.components.BladeAvatar
import com.example.ui.components.CommentsBottomSheet
import com.example.ui.components.VerifiedBadge
import com.example.ui.components.VideoLikersBottomSheet
import com.example.ui.components.VideoOptionsSheet
import com.example.ui.components.VideoPlayerBox
import com.example.ui.components.VideoRailActionButton
import com.example.ui.components.formatCompactCount
import com.example.ui.theme.BladeCountCrisp
import com.example.ui.theme.BladeCountLiked
import com.example.ui.theme.BladeCountSaved
import com.example.ui.theme.BladeCrimson
import com.example.ui.theme.BladeCyan
import com.example.ui.theme.BladeGold
import com.example.ui.theme.TextWhite
import com.example.ui.viewmodel.FeedViewModel

@Composable
fun VideoDetailPlayerScreen(
    videoUrl: String,
    title: String,
    caption: String,
    username: String,
    filterName: String,
    trimStartMs: Long,
    trimEndMs: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(Color.Black)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
        ) {
            VideoPlayerBox(
                videoUri = videoUrl,
                isActive = true,
                filterName = filterName,
                trimStartMs = trimStartMs,
                trimEndMs = trimEndMs
            )

            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .padding(16.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
            }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "@$username",
                    color = TextWhite,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(Modifier.width(8.dp))
            }
            if (title.isNotBlank()) {
                Text(
                    text = title,
                    color = TextWhite,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
            if (caption.isNotBlank()) {
                Text(
                    text = caption,
                    color = TextWhite,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        }
    }
}

@Composable
fun NotificationVideoScreen(
    videoId: String,
    currentUser: UserProfile?,
    feedViewModel: FeedViewModel,
    onBack: () -> Unit,
    onNavigateToCreator: (String) -> Unit,
    onHashtagClick: (String) -> Unit,
    onSoundClick: (VideoItem) -> Unit,
    onRequireAuth: () -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = bladeStrings()
    val context = LocalContext.current
    val uiState by feedViewModel.uiState.collectAsStateWithLifecycle()
    var optionsVideo by remember { mutableStateOf<VideoItem?>(null) }

    LaunchedEffect(videoId) {
        feedViewModel.loadTargetVideo(videoId)
    }

    LaunchedEffect(uiState.feedbackMessage) {
        uiState.feedbackMessage?.let { msg ->
            onShowMessage(msg)
            if (uiState.requireAuthPrompt) {
                onRequireAuth()
            }
            feedViewModel.clearFeedback()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(Color.Black)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
        ) {
        when (val targetState = uiState.targetVideoState) {
            is UiState.Loading -> {
                CircularProgressIndicator(
                    color = BladeCrimson,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            is UiState.Error -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = targetState.message,
                        color = TextWhite,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { feedViewModel.loadTargetVideo(videoId) },
                            colors = ButtonDefaults.buttonColors(containerColor = BladeCrimson)
                        ) {
                            Text("Retry")
                        }
                        OutlinedButton(onClick = onBack) {
                            Text("Back", color = TextWhite)
                        }
                    }
                }
            }
            is UiState.Success -> {
                val video = targetState.data
                val creatorName = video.creator?.username?.ifBlank { "creator" } ?: "creator"
                val textShadow = remember {
                    Shadow(
                        color = Color.Black.copy(alpha = 0.7f),
                        offset = Offset(0f, 1.5f),
                        blurRadius = 4f
                    )
                }

                VideoPlayerBox(
                    videoUri = video.videoUrl,
                    isActive = true,
                    filterName = video.filterName,
                    trimStartMs = video.trimStartMs,
                    trimEndMs = video.trimEndMs,
                    durationMs = video.durationMs,
                    onDoubleTapLike = { feedViewModel.toggleLike(video, forceLike = true) },
                    onStartedPlayback = { feedViewModel.onVideoViewed(video) }
                )

                // Right Action Rail
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 10.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .clip(CircleShape)
                            .clickable { onNavigateToCreator(video.userId) }
                            .testTag("notif_player_avatar_${video.id}"),
                        contentAlignment = Alignment.Center
                    ) {
                        BladeAvatar(
                            avatarUrl = video.creator?.avatarUrl ?: "",
                            username = creatorName,
                            size = 44.dp
                        )
                    }

                    val likeScale by animateFloatAsState(
                        targetValue = if (video.isLiked) 1.15f else 1f,
                        animationSpec = spring(stiffness = Spring.StiffnessMedium),
                        label = "notifLikeScale"
                    )

                    VideoRailActionButton(
                        icon = if (video.isLiked) Icons.Rounded.ThumbUp else Icons.Outlined.ThumbUp,
                        tint = if (video.isLiked) BladeCrimson else TextWhite,
                        label = formatCompactCount(video.likesCount),
                        labelColor = if (video.isLiked) BladeCountLiked else BladeCountCrisp,
                        onClick = { feedViewModel.toggleLike(video) },
                        onLabelClick = { feedViewModel.openVideoLikers(video.id) },
                        labelTestTag = "notif_player_like_count_${video.id}",
                        iconModifier = Modifier.scale(likeScale),
                        modifier = Modifier.testTag("notif_player_like_${video.id}")
                    )

                    VideoRailActionButton(
                        icon = Icons.Rounded.ModeComment,
                        tint = TextWhite,
                        label = formatCompactCount(video.commentsCount),
                        labelColor = BladeCountCrisp,
                        onClick = { feedViewModel.openComments(video.id) },
                        modifier = Modifier.testTag("notif_player_comment_${video.id}")
                    )

                    VideoRailActionButton(
                        icon = if (video.isSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        tint = if (video.isSaved) BladeGold else TextWhite,
                        label = formatCompactCount(video.savesCount),
                        labelColor = if (video.isSaved) BladeCountSaved else BladeCountCrisp,
                        onClick = { feedViewModel.toggleSave(video) },
                        modifier = Modifier.testTag("notif_player_save_${video.id}")
                    )

                    VideoRailActionButton(
                        icon = NotificationShareIcon,
                        tint = TextWhite,
                        label = formatCompactCount(video.sharesCount),
                        labelColor = BladeCountCrisp,
                        onClick = {
                            feedViewModel.onVideoShared(video)
                            val shareLabel = video.title.ifBlank { video.caption }
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "Watch @${video.creator?.username ?: "creator"} on Blade: $shareLabel\n${video.videoUrl}"
                                )
                            }
                            context.startActivity(Intent.createChooser(shareIntent, strings.share))
                        },
                        modifier = Modifier.testTag("notif_player_share_${video.id}")
                    )

                    IconButton(onClick = { optionsVideo = video }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.MoreHoriz, contentDescription = "Options", tint = TextWhite)
                    }
                }

                // Bottom Left Metadata
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .fillMaxWidth(0.76f)
                        .padding(start = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .clickable { onNavigateToCreator(video.userId) }
                        ) {
                            Text(
                                text = "@$creatorName",
                                color = TextWhite,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    shadow = textShadow
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (video.creator?.isVerified == true) {
                                Spacer(Modifier.width(5.dp))
                                VerifiedBadge(size = 15.dp)
                            }
                        }
                        if (currentUser?.id != video.userId) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (video.isFollowingCreator) Color.White.copy(alpha = 0.18f) else BladeCrimson,
                                border = if (video.isFollowingCreator) {
                                    BorderStroke(1.dp, TextWhite.copy(alpha = 0.55f))
                                } else null,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { feedViewModel.toggleFollowCreator(video.userId) }
                            ) {
                                Text(
                                    text = if (video.isFollowingCreator) strings.following else strings.follow,
                                    color = TextWhite,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    if (video.location.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.LocationOn,
                                contentDescription = null,
                                tint = TextWhite.copy(alpha = 0.85f),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = video.location,
                                color = TextWhite.copy(alpha = 0.9f),
                                style = MaterialTheme.typography.labelSmall.copy(shadow = textShadow),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (video.title.isNotBlank()) {
                        Text(
                            text = video.title,
                            color = TextWhite,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                shadow = textShadow
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (video.caption.isNotBlank()) {
                        val annotatedCaption = buildAnnotatedString {
                            val words = video.caption.split(" ")
                            words.forEachIndexed { idx, word ->
                                if (word.startsWith("#") && word.length > 1) {
                                    pushStringAnnotation(tag = "HASHTAG", annotation = word)
                                    pushStyle(SpanStyle(color = BladeCyan, fontWeight = FontWeight.SemiBold, shadow = textShadow))
                                    append(word)
                                    pop()
                                    pop()
                                } else {
                                    pushStyle(SpanStyle(color = TextWhite, shadow = textShadow))
                                    append(word)
                                    pop()
                                }
                                if (idx < words.lastIndex) append(" ")
                            }
                        }
                        @Suppress("DEPRECATION")
                        ClickableText(
                            text = annotatedCaption,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            onClick = { offset ->
                                annotatedCaption.getStringAnnotations(tag = "HASHTAG", start = offset, end = offset)
                                    .firstOrNull()?.let { onHashtagClick(it.item) }
                            }
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.35f))
                            .clickable { onSoundClick(video) }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = TextWhite,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = video.soundTitle.ifBlank { "Original Sound - @$creatorName" },
                            color = TextWhite,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(14.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .testTag("notification_video_back_button")
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = TextWhite
            )
        }
        }
    }

    uiState.commentsVideoId?.let { cid ->
        CommentsBottomSheet(
            commentsState = uiState.commentsState,
            onDismiss = { feedViewModel.closeComments() },
            onPostComment = { text, parentId -> feedViewModel.submitComment(cid, text, parentId) },
            onCreatorClick = { uid ->
                feedViewModel.closeComments()
                onNavigateToCreator(uid)
            }
        )
    }

    uiState.likersVideoId?.let {
        VideoLikersBottomSheet(
            likersState = uiState.likersState,
            onDismiss = { feedViewModel.closeVideoLikers() },
            onCreatorClick = { uid ->
                feedViewModel.closeVideoLikers()
                onNavigateToCreator(uid)
            }
        )
    }

    optionsVideo?.let { target ->
        VideoOptionsSheet(
            video = target,
            onReport = { reason -> feedViewModel.reportVideo(target, reason) },
            onBlock = { feedViewModel.blockCreator(target.userId) },
            onDismiss = { optionsVideo = null }
        )
    }
}

private val NotificationShareIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "NotificationTikTokShare",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(13.8f, 3.8f)
            curveTo(13.8f, 3.1f, 14.6f, 2.7f, 15.1f, 3.2f)
            lineTo(22.1f, 9.7f)
            curveTo(22.5f, 10.1f, 22.5f, 10.7f, 22.1f, 11.1f)
            lineTo(15.1f, 17.6f)
            curveTo(14.6f, 18.1f, 13.8f, 17.7f, 13.8f, 17.0f)
            lineTo(13.8f, 13.6f)
            curveTo(8.9f, 13.6f, 5.4f, 15.2f, 3.0f, 19.3f)
            curveTo(2.6f, 20.0f, 1.6f, 19.6f, 1.7f, 18.8f)
            curveTo(2.7f, 12.1f, 6.7f, 7.6f, 13.8f, 7.2f)
            close()
        }
    }.build()
}

