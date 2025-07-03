package com.project.videofeed.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.project.videofeed.model.CommentModel
import com.project.videofeed.model.VideoModel
import com.project.videofeed.network.VideoApiClient
import com.project.videofeed.ui.theme.RedPink
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsBottomSheet(
    video: VideoModel,
    onDismiss: () -> Unit,
    onCommentAdded: (Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val comments = remember { mutableStateListOf<CommentModel>() }
    var isLoading by remember { mutableStateOf(true) }
    var commentText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var replyingTo by remember { mutableStateOf<CommentModel?>(null) }

    LaunchedEffect(video.id) {
        isLoading = true
        val fetched = VideoApiClient.fetchComments(context, video.id)
        comments.clear()
        comments.addAll(fetched)
        isLoading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF161616),
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.68f)
                .navigationBarsPadding()
                .imePadding()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Text(
                    text = "${comments.size.coerceAtLeast(video.commentCount)} Komentar",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Tutup",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier
                        .size(22.dp)
                        .align(Alignment.CenterEnd)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onDismiss() }
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = RedPink,
                        modifier = Modifier
                            .size(32.dp)
                            .align(Alignment.Center)
                    )
                } else if (comments.isEmpty()) {
                    Text(
                        text = "Belum ada komentar. Jadilah yang pertama berkomentar!",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 14.sp,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    val rootComments = comments.filter { it.parentId.isNullOrBlank() }
                    val repliesByParent = comments.filter { !it.parentId.isNullOrBlank() }.groupBy { it.parentId }
                    val rootIds = rootComments.map { it.id }.toSet()
                    val orphanReplies = comments.filter { !it.parentId.isNullOrBlank() && it.parentId !in rootIds }

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item { Spacer(modifier = Modifier.height(4.dp)) }

                        rootComments.forEach { root ->
                            item(key = root.id) {
                                CommentRow(
                                    comment = root,
                                    isReply = false,
                                    onReplyClick = { replyingTo = root },
                                    onDeleteClick = { toDelete ->
                                        scope.launch {
                                            val ok = VideoApiClient.deleteComment(context, toDelete.id)
                                            if (ok) {
                                                comments.remove(toDelete)
                                                comments.removeAll { it.parentId == toDelete.id }
                                                onCommentAdded(comments.size)
                                            }
                                        }
                                    }
                                )
                            }

                            val children = repliesByParent[root.id].orEmpty()
                            items(children, key = { it.id }) { reply ->
                                CommentRow(
                                    comment = reply,
                                    isReply = true,
                                    onReplyClick = { replyingTo = root },
                                    onDeleteClick = { toDelete ->
                                        scope.launch {
                                            val ok = VideoApiClient.deleteComment(context, toDelete.id)
                                            if (ok) {
                                                comments.remove(toDelete)
                                                onCommentAdded(comments.size)
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        if (orphanReplies.isNotEmpty()) {
                            items(orphanReplies, key = { it.id }) { orphan ->
                                CommentRow(
                                    comment = orphan,
                                    isReply = true,
                                    onReplyClick = { replyingTo = orphan },
                                    onDeleteClick = { toDelete ->
                                        scope.launch {
                                            val ok = VideoApiClient.deleteComment(context, toDelete.id)
                                            if (ok) {
                                                comments.remove(toDelete)
                                                onCommentAdded(comments.size)
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        item { Spacer(modifier = Modifier.height(8.dp)) }
                    }
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

            if (replyingTo != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF24242A))
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Membalas @${replyingTo?.authorName ?: "pengguna"}",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Batal",
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier
                            .size(16.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { replyingTo = null }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = commentText,
                    onValueChange = { commentText = it },
                    placeholder = {
                        Text(
                            text = if (replyingTo != null) "Tulis balasan..." else "Tambahkan komentar...",
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 14.sp
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color(0xFF2A2A2A),
                        unfocusedContainerColor = Color(0xFF2A2A2A),
                        focusedBorderColor = RedPink,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    enabled = commentText.isNotBlank() && !isSending,
                    onClick = {
                        val textToSend = commentText.trim()
                        if (textToSend.isEmpty()) return@IconButton
                        isSending = true
                        val googleUser = VideoApiClient.getGoogleSession(context)
                        val senderName = googleUser?.displayName ?: "Pengguna"
                        val senderHandle = googleUser?.username ?: "@pengguna"
                        val senderAvatar = googleUser?.avatarUrl ?: video.authorAvatarUrl
                        val parentIdToSend = replyingTo?.id

                        scope.launch {
                            val result = VideoApiClient.postComment(
                                context = context,
                                videoId = video.id,
                                content = textToSend,
                                authorName = senderName,
                                parentId = parentIdToSend
                            )
                            if (result != null) {
                                comments.add(0, result.comment)
                                onCommentAdded(result.totalComments)
                            } else {
                                val fallback = CommentModel(
                                    id = "local_${System.currentTimeMillis()}",
                                    videoId = video.id,
                                    userId = VideoApiClient.getActiveUserId(context),
                                    authorName = senderName,
                                    authorHandle = senderHandle,
                                    authorAvatarUrl = senderAvatar,
                                    content = textToSend,
                                    likeCount = 0,
                                    timeAgo = "Baru saja",
                                    parentId = parentIdToSend,
                                    isMine = true
                                )
                                comments.add(0, fallback)
                                onCommentAdded(comments.size)
                            }
                            commentText = ""
                            replyingTo = null
                            isSending = false
                        }
                    },
                    modifier = Modifier
                        .size(42.dp)
                        .background(
                            if (commentText.isNotBlank()) RedPink else Color(0xFF333333),
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Kirim",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CommentRow(
    comment: CommentModel,
    isReply: Boolean = false,
    onReplyClick: (CommentModel) -> Unit = {},
    onDeleteClick: (CommentModel) -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (isReply) 46.dp else 16.dp,
                end = 16.dp
            ),
        verticalAlignment = Alignment.Top
    ) {
        AsyncImage(
            model = comment.authorAvatarUrl,
            contentDescription = comment.authorName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(if (isReply) 28.dp else 36.dp)
                .clip(CircleShape)
                .background(Color(0xFF333333))
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = comment.authorName,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = if (isReply) 12.sp else 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = comment.timeAgo,
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 11.sp
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = comment.content,
                color = Color.White,
                fontSize = if (isReply) 13.sp else 14.sp,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Balas",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onReplyClick(comment) }
                )
                if (comment.isMine) {
                    Text(
                        text = "Hapus",
                        color = RedPink.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onDeleteClick(comment) }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.FavoriteBorder,
                contentDescription = "Like Comment",
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp)
            )
            if (comment.likeCount > 0) {
                Text(
                    text = comment.likeCount.toString(),
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
            }
        }
    }
}
