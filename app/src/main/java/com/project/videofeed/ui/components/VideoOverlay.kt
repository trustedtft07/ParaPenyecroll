package com.project.videofeed.ui.components

import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.project.videofeed.model.VideoModel

private val IgRed = Color(0xFFFF3040)

@Composable
fun VideoOverlay(
    video: VideoModel,
    progress: Float,
    isLiked: Boolean,
    likeCount: Int,
    commentCount: Int,
    shareCount: Int,
    isBookmarked: Boolean,
    isFollowing: Boolean,
    onLikeToggle: () -> Unit,
    onCommentClick: () -> Unit,
    onBookmarkToggle: () -> Unit,
    onFollowToggle: () -> Unit,
    onShareClick: () -> Unit,
    onMoreOptionsClick: () -> Unit,
    onSeekProgress: (Float) -> Unit,
    onCreatorClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isCaptionExpanded by remember(video.id) { mutableStateOf(false) }
    val cleanUsername = remember(video.authorHandle) { video.authorHandle.removePrefix("@") }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color.Black.copy(alpha = 0.25f),
                        0.18f to Color.Transparent,
                        0.62f to Color.Transparent,
                        1.0f to Color.Black.copy(alpha = 0.78f)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.82f)
                .padding(start = 16.dp, end = 12.dp, bottom = 18.dp)
                .animateContentSize()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = video.authorAvatarUrl,
                    contentDescription = video.authorName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF262626))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onCreatorClick
                        )
                )

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = cleanUsername,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onCreatorClick
                    )
                )

                Spacer(modifier = Modifier.width(10.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = if (isFollowing) 0.4f else 0.75f),
                            shape = RoundedCornerShape(7.dp)
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onFollowToggle
                        )
                        .padding(horizontal = 11.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isFollowing) "Mengikuti" else "Ikuti",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val styledCaption = remember(video.description) {
                buildAnnotatedString {
                    val words = video.description.split(" ")
                    words.forEachIndexed { idx, word ->
                        if (word.startsWith("#")) {
                            withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) {
                                append(word)
                            }
                        } else {
                            withStyle(SpanStyle(color = Color.White.copy(alpha = 0.95f))) {
                                append(word)
                            }
                        }
                        if (idx < words.lastIndex) append(" ")
                    }
                }
            }

            Text(
                text = styledCaption,
                fontSize = 13.5.sp,
                maxLines = if (isCaptionExpanded) 10 else 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 18.sp,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { isCaptionExpanded = !isCaptionExpanded }
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = video.musicTitle,
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            ReelsActionIcon(
                icon = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                label = VideoModel.formatCount(likeCount),
                tint = if (isLiked) IgRed else Color.White,
                isActive = isLiked,
                onClick = onLikeToggle
            )

            ReelsActionIcon(
                icon = Icons.Outlined.ChatBubbleOutline,
                label = VideoModel.formatCount(commentCount),
                onClick = onCommentClick
            )

            ReelsActionIcon(
                icon = Icons.AutoMirrored.Outlined.Send,
                label = VideoModel.formatCount(shareCount),
                rotationDegrees = -26f,
                onClick = {
                    onShareClick()
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "${video.description}\n${video.videoUrl}"
                        )
                    }
                    context.startActivity(Intent.createChooser(sendIntent, "Bagikan Reels"))
                }
            )

            ReelsActionIcon(
                icon = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                label = "",
                isActive = isBookmarked,
                onClick = onBookmarkToggle
            )

            ReelsActionIcon(
                icon = Icons.Default.MoreVert,
                label = "",
                iconSize = 22,
                onClick = onMoreOptionsClick
            )

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.5.dp, Color.White, RoundedCornerShape(6.dp))
                    .background(Color(0xFF262626))
            ) {
                AsyncImage(
                    model = video.authorAvatarUrl,
                    contentDescription = "Audio Cover",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (video.postType != "image") {
            var isDragging by remember { mutableStateOf(false) }
            var dragProgress by remember { mutableFloatStateOf(0f) }
            val currentDisplayProgress = if (isDragging) dragProgress else progress.coerceIn(0f, 1f)

            val barHeight by animateDpAsState(
                targetValue = if (isDragging) 6.dp else 2.dp,
                label = "scrubberHeight"
            )

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(28.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val ratio = (offset.x / size.width).coerceIn(0f, 1f)
                            onSeekProgress(ratio)
                        }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                isDragging = true
                                val ratio = (offset.x / size.width).coerceIn(0f, 1f)
                                dragProgress = ratio
                                onSeekProgress(ratio)
                            },
                            onDragEnd = {
                                isDragging = false
                            },
                            onDragCancel = {
                                isDragging = false
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                val ratio = (change.position.x / size.width).coerceIn(0f, 1f)
                                dragProgress = ratio
                                onSeekProgress(ratio)
                            }
                        )
                    },
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barHeight)
                        .background(Color.White.copy(alpha = 0.28f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(currentDisplayProgress)
                            .fillMaxHeight()
                            .background(Color.White)
                    )
                }
            }
        }
    }
}

@Composable
private fun ReelsActionIcon(
    icon: ImageVector,
    label: String,
    tint: Color = Color.White,
    isActive: Boolean = false,
    iconSize: Int = 27,
    rotationDegrees: Float = 0f,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.80f
            isActive -> 1.08f
            else -> 1.0f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "reels_icon_scale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(iconSize.dp)
                .rotate(rotationDegrees)
        )
        if (label.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
