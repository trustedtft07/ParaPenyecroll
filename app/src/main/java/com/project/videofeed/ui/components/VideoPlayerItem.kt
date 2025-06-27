package com.project.videofeed.ui.components

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.project.videofeed.model.VideoModel
import com.project.videofeed.network.VideoApiClient
import com.project.videofeed.player.VideoCache
import com.project.videofeed.ui.theme.CoralHeart
import com.project.videofeed.ui.theme.GlassBorder
import com.project.videofeed.ui.theme.GlassDark
import com.project.videofeed.ui.theme.VioletPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerItem(
    video: VideoModel,
    isCurrentPage: Boolean,
    isMuted: Boolean,
    onOpenComments: (VideoModel, (Int) -> Unit) -> Unit,
    onCreatorClicked: (String) -> Unit,
    onVideoDeleted: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val isImagePost = remember(video.id) { video.postType == "image" }

    var isBuffering by remember(video.id) { mutableStateOf(!isImagePost) }
    var isFirstFrameRendered by remember(video.id) { mutableStateOf(isImagePost) }
    var userPaused by remember(video.id) { mutableStateOf(false) }
    var isAppInForeground by remember { mutableStateOf(true) }
    var showDoubleTapHeart by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var progress by remember(video.id) { mutableFloatStateOf(if (isImagePost) 1f else 0f) }

    var isLiked by remember(video.id) { mutableStateOf(video.isLiked) }
    var likeCount by remember(video.id) { mutableIntStateOf(video.likeCount) }
    var commentCount by remember(video.id) { mutableIntStateOf(video.commentCount) }
    var shareCount by remember(video.id) { mutableIntStateOf(video.shareCount) }
    var isBookmarked by remember(video.id) { mutableStateOf(video.isBookmarked) }
    var isFollowing by remember(video.id) { mutableStateOf(video.isFollowing) }

    val exoPlayer = remember(video.id, video.videoUrl) {
        if (isImagePost) {
            null
        } else {
            val cacheDataSourceFactory = VideoCache.buildCacheDataSourceFactory(context)
            val mediaSourceFactory = DefaultMediaSourceFactory(cacheDataSourceFactory)

            ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
                .apply {
                    repeatMode = Player.REPEAT_MODE_ALL
                    val streamUrl = video.videoUrl.ifEmpty { video.hlsUrl }
                    setMediaItem(MediaItem.fromUri(Uri.parse(streamUrl)))
                    prepare()
                }
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    isAppInForeground = false
                    exoPlayer?.pause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    isAppInForeground = true
                    if (isCurrentPage && !userPaused) {
                        exoPlayer?.play()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = (playbackState == Player.STATE_BUFFERING)
            }

            override fun onRenderedFirstFrame() {
                isFirstFrameRendered = true
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                android.util.Log.e("VideoPlayerItem", "Playback error for ${video.videoUrl}: ${error.message}")
            }
        }
        exoPlayer?.addListener(listener)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer?.removeListener(listener)
            exoPlayer?.release()
        }
    }

    LaunchedEffect(isCurrentPage, userPaused, isAppInForeground, isImagePost) {
        if (!isImagePost) {
            if (isCurrentPage && !userPaused && isAppInForeground) {
                exoPlayer?.play()
            } else {
                exoPlayer?.pause()
            }
        }
    }

    LaunchedEffect(isMuted, isImagePost) {
        if (!isImagePost) {
            exoPlayer?.volume = if (isMuted) 0f else 1f
        }
    }

    LaunchedEffect(isCurrentPage, userPaused, isAppInForeground, isImagePost) {
        if (!isImagePost && exoPlayer != null) {
            while (isActive && isCurrentPage && !userPaused && isAppInForeground) {
                val dur = exoPlayer.duration
                val pos = exoPlayer.currentPosition
                if (dur > 0) {
                    progress = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                }
                delay(100)
            }
        }
    }

    fun triggerLike(forceLikeOnly: Boolean = false) {
        if (forceLikeOnly && isLiked) return
        val newLiked = if (forceLikeOnly) true else !isLiked
        isLiked = newLiked
        likeCount = if (newLiked) likeCount + 1 else (likeCount - 1).coerceAtLeast(0)

        scope.launch {
            val res = VideoApiClient.toggleLike(context, video.id)
            if (res != null) {
                isLiked = res.isLiked
                likeCount = res.likeCount
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(video.id) {
                detectTapGestures(
                    onDoubleTap = {
                        triggerLike(forceLikeOnly = true)
                        scope.launch {
                            showDoubleTapHeart = true
                            delay(650)
                            showDoubleTapHeart = false
                        }
                    },
                    onTap = {
                        if (!isImagePost) {
                            userPaused = !userPaused
                        }
                    }
                )
            }
    ) {
        if (isImagePost) {
            AsyncImage(
                model = video.videoUrl.ifEmpty { video.thumbUrl },
                contentDescription = video.description,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center)
            )
        } else if (exoPlayer != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                update = { view ->
                    if (view.player != exoPlayer) {
                        view.player = exoPlayer
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (!isFirstFrameRendered && video.thumbUrl.isNotEmpty()) {
                AsyncImage(
                    model = video.thumbUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (isBuffering) {
            CircularProgressIndicator(
                color = VioletPrimary,
                strokeWidth = 3.dp,
                modifier = Modifier
                    .size(44.dp)
                    .align(Alignment.Center)
            )
        }

        AnimatedVisibility(
            visible = userPaused,
            enter = fadeIn() + scaleIn(initialScale = 0.7f),
            exit = fadeOut() + scaleOut(targetScale = 0.7f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .background(GlassDark, CircleShape)
                    .border(1.dp, GlassBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Paused",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = showDoubleTapHeart,
            enter = fadeIn() + scaleIn(
                initialScale = 0.4f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
            ),
            exit = fadeOut() + scaleOut(targetScale = 1.4f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = "Liked",
                tint = CoralHeart,
                modifier = Modifier.size(110.dp)
            )
        }

        VideoOverlay(
            video = video,
            progress = progress,
            isLiked = isLiked,
            likeCount = likeCount,
            commentCount = commentCount,
            shareCount = shareCount,
            isBookmarked = isBookmarked,
            isFollowing = isFollowing,
            onLikeToggle = { triggerLike(forceLikeOnly = false) },
            onCommentClick = {
                onOpenComments(video) { updatedTotal ->
                    commentCount = updatedTotal
                }
            },
            onBookmarkToggle = {
                isBookmarked = !isBookmarked
                scope.launch {
                    val serverState = VideoApiClient.toggleBookmark(context, video.id)
                    if (serverState != null) {
                        isBookmarked = serverState
                    }
                }
            },
            onFollowToggle = {
                isFollowing = !isFollowing
                scope.launch {
                    val serverFollow = VideoApiClient.toggleFollow(context, video.authorId)
                    if (serverFollow != null) {
                        isFollowing = serverFollow
                    }
                }
            },
            onShareClick = {
                shareCount += 1
                scope.launch {
                    val serverShares = VideoApiClient.shareVideo(video.id)
                    if (serverShares != null) {
                        shareCount = serverShares
                    }
                }
            },
            onMoreOptionsClick = {
                showMoreSheet = true
            },
            onSeekProgress = { ratio ->
                val dur = exoPlayer?.duration ?: 0L
                if (dur > 0 && exoPlayer != null) {
                    exoPlayer.seekTo((ratio * dur).toLong())
                    progress = ratio
                }
            },
            onCreatorClick = {
                onCreatorClicked(video.authorId)
            },
            modifier = Modifier.fillMaxSize()
        )

        if (showMoreSheet) {
            ModalBottomSheet(
                onDismissRequest = { showMoreSheet = false },
                containerColor = Color(0xFF16161C)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Opsi Video",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF22222A))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                showMoreSheet = false
                                scope.launch {
                                    VideoApiClient.reportVideo(context, video.id, "Dilaporkan oleh pengguna")
                                    Toast.makeText(
                                        context,
                                        "Terima kasih. Laporan telah dikirim ke sistem moderasi.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                            .padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Flag,
                            contentDescription = null,
                            tint = Color(0xFFFFB703)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Laporkan Video", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Laporkan konten spam atau tidak pantas",
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 12.sp
                            )
                        }
                    }

                    if (video.id.startsWith("v_")) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CoralHeart.copy(alpha = 0.15f))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    showMoreSheet = false
                                    scope.launch {
                                        val ok = VideoApiClient.deleteVideo(context, video.id)
                                        if (ok) {
                                            Toast.makeText(context, "Video berhasil dihapus", Toast.LENGTH_SHORT).show()
                                            onVideoDeleted()
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "Hanya pemilik yang dapat menghapus video ini",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                                .padding(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = null,
                                tint = CoralHeart
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Hapus Reel", color = CoralHeart, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Hapus video ini secara permanen dari profil Anda",
                                    color = Color.White.copy(alpha = 0.55f),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}
