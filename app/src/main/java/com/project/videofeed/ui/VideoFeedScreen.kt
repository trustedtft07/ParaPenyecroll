package com.project.videofeed.ui

import android.accounts.AccountManager
import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.project.videofeed.model.DummyVideoData
import com.project.videofeed.model.VideoModel
import com.project.videofeed.network.GoogleUserSession
import com.project.videofeed.network.VideoApiClient
import com.project.videofeed.ui.components.CommentsBottomSheet
import com.project.videofeed.ui.components.UploadVideoSheet
import com.project.videofeed.ui.components.VideoPlayerItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoFeedScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val videos = remember {
        mutableStateListOf<VideoModel>().apply {
            addAll(DummyVideoData.sampleVideos)
        }
    }

    // Bottom Navigation: 0 = Reels, 1 = Explore, 2 = Profile
    var currentBottomNav by remember { mutableIntStateOf(0) }

    // Feed Mode: 0 = Reels (All), 1 = Mengikuti (Following)
    var selectedFeedFilter by remember { mutableIntStateOf(0) }
    var showFeedFilterMenu by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }

    var activeCommentVideo by remember { mutableStateOf<VideoModel?>(null) }
    var onCommentCountUpdated by remember { mutableStateOf<((Int) -> Unit)?>(null) }
    var showUploadSheet by remember { mutableStateOf(false) }
    var googleSession by remember { mutableStateOf(VideoApiClient.getGoogleSession(context)) }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { videos.size }
    )

    fun reloadFeed(scrollToTop: Boolean = false, filterMode: Int = selectedFeedFilter) {
        scope.launch {
            val filterParam = if (filterMode == 1) "following" else ""
            val remoteVideos = VideoApiClient.fetchFeed(context, filter = filterParam)
            if (remoteVideos.isNotEmpty()) {
                videos.clear()
                videos.addAll(remoteVideos)
                if (scrollToTop) {
                    pagerState.scrollToPage(0)
                }
            } else if (filterMode == 1) {
                Toast.makeText(
                    context,
                    "Belum ada kreator yang diikuti",
                    Toast.LENGTH_SHORT
                ).show()
                selectedFeedFilter = 0
            }
        }
    }

    LaunchedEffect(Unit) {
        reloadFeed(scrollToTop = false)
    }

    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            InstagramBottomBar(
                selectedTab = currentBottomNav,
                userAvatarUrl = googleSession?.avatarUrl,
                onTabSelected = { currentBottomNav = it },
                onUploadClick = { showUploadSheet = true }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
                .background(Color.Black)
        ) {
            when (currentBottomNav) {
                0 -> {
                    if (videos.isNotEmpty()) {
                        VerticalPager(
                            state = pagerState,
                            beyondBoundsPageCount = 1,
                            key = { index -> videos[index].id },
                            modifier = Modifier.fillMaxSize()
                        ) { index ->
                            val isCurrentPage =
                                (pagerState.currentPage == index && !pagerState.isScrollInProgress && currentBottomNav == 0)
                            VideoPlayerItem(
                                video = videos[index],
                                isCurrentPage = isCurrentPage,
                                isMuted = isMuted,
                                onOpenComments = { vid, callback ->
                                    activeCommentVideo = vid
                                    onCommentCountUpdated = callback
                                },
                                onCreatorClicked = {
                                    currentBottomNav = 1
                                },
                                onVideoDeleted = {
                                    reloadFeed(scrollToTop = true)
                                }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { showFeedFilterMenu = true }
                            ) {
                                Text(
                                    text = if (selectedFeedFilter == 0) "Reels" else "Mengikuti",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 22.sp
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = showFeedFilterMenu,
                                onDismissRequest = { showFeedFilterMenu = false },
                                modifier = Modifier.background(Color(0xFF262626))
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Untuk Anda", color = Color.White, fontSize = 14.sp) },
                                    onClick = {
                                        showFeedFilterMenu = false
                                        selectedFeedFilter = 0
                                        reloadFeed(scrollToTop = true, filterMode = 0)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Mengikuti", color = Color.White, fontSize = 14.sp) },
                                    onClick = {
                                        showFeedFilterMenu = false
                                        selectedFeedFilter = 1
                                        reloadFeed(scrollToTop = true, filterMode = 1)
                                    }
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            Icon(
                                imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Audio",
                                tint = Color.White,
                                modifier = Modifier
                                    .size(25.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { isMuted = !isMuted }
                            )

                            Icon(
                                imageVector = Icons.Outlined.CameraAlt,
                                contentDescription = "Buat Reels",
                                tint = Color.White,
                                modifier = Modifier
                                    .size(25.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { showUploadSheet = true }
                            )
                        }
                    }
                }

                1 -> {
                    ExploreScreen(
                        allVideos = videos,
                        onVideoSelected = { selectedVideo ->
                            val idx = videos.indexOfFirst { it.id == selectedVideo.id }
                            currentBottomNav = 0
                            if (idx >= 0) {
                                scope.launch { pagerState.scrollToPage(idx) }
                            }
                        }
                    )
                }

                2 -> {
                    ProfileAndAuthScreen(
                        session = googleSession,
                        onSessionUpdated = { newSession ->
                            googleSession = newSession
                            reloadFeed(scrollToTop = false)
                        },
                        onVideoSelected = { selectedVideo ->
                            val idx = videos.indexOfFirst { it.id == selectedVideo.id }
                            if (idx < 0) {
                                videos.add(0, selectedVideo)
                            }
                            currentBottomNav = 0
                            scope.launch {
                                val targetIdx = videos.indexOfFirst { it.id == selectedVideo.id }.coerceAtLeast(0)
                                pagerState.scrollToPage(targetIdx)
                            }
                        }
                    )
                }
            }

            val commentTarget = activeCommentVideo
            if (commentTarget != null) {
                CommentsBottomSheet(
                    video = commentTarget,
                    onDismiss = { activeCommentVideo = null },
                    onCommentAdded = { newTotal ->
                        onCommentCountUpdated?.invoke(newTotal)
                    }
                )
            }

            if (showUploadSheet) {
                UploadVideoSheet(
                    onDismiss = { showUploadSheet = false },
                    onRequireLogin = {
                        showUploadSheet = false
                        currentBottomNav = 2
                    },
                    onUploadSuccess = {
                        showUploadSheet = false
                        selectedFeedFilter = 0
                        currentBottomNav = 0
                        reloadFeed(scrollToTop = true, filterMode = 0)
                    }
                )
            }
        }
    }
}

@Composable
private fun InstagramBottomBar(
    selectedTab: Int,
    userAvatarUrl: String?,
    onTabSelected: (Int) -> Unit,
    onUploadClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .navigationBarsPadding()
    ) {
        HorizontalDivider(thickness = 0.5.dp, color = Color.White.copy(alpha = 0.14f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (selectedTab == 0) Icons.Filled.SmartDisplay else Icons.Outlined.SmartDisplay,
                contentDescription = "Reels",
                tint = Color.White,
                modifier = Modifier
                    .size(26.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onTabSelected(0) }
            )

            Icon(
                imageVector = if (selectedTab == 1) Icons.Filled.Search else Icons.Outlined.Search,
                contentDescription = "Cari",
                tint = Color.White,
                modifier = Modifier
                    .size(26.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onTabSelected(1) }
            )

            Icon(
                imageVector = Icons.Outlined.AddBox,
                contentDescription = "Buat",
                tint = Color.White,
                modifier = Modifier
                    .size(26.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onUploadClick() }
            )

            if (!userAvatarUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = userAvatarUrl,
                    contentDescription = "Profil",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .border(
                            width = if (selectedTab == 2) 1.5.dp else 0.dp,
                            color = if (selectedTab == 2) Color.White else Color.Transparent,
                            shape = CircleShape
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onTabSelected(2) }
                )
            } else {
                Icon(
                    imageVector = if (selectedTab == 2) Icons.Filled.Person else Icons.Outlined.Person,
                    contentDescription = "Profil",
                    tint = Color.White,
                    modifier = Modifier
                        .size(26.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onTabSelected(2) }
                )
            }
        }
    }
}

@Composable
private fun ExploreScreen(
    allVideos: List<VideoModel>,
    onVideoSelected: (VideoModel) -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedTag by remember { mutableStateOf("") }
    val filteredVideos = remember { mutableStateListOf<VideoModel>() }

    val categories = listOf("Untuk Anda", "#cinematic", "#nightvibes", "#coding", "#adventure", "#explore")

    LaunchedEffect(searchQuery, selectedTag, allVideos.size) {
        val q = when {
            searchQuery.isNotBlank() -> searchQuery.trim()
            selectedTag.isNotEmpty() && selectedTag != "Untuk Anda" -> selectedTag
            else -> ""
        }
        val res = if (q.isEmpty()) allVideos else VideoApiClient.fetchFeed(context, query = q)
        filteredVideos.clear()
        filteredVideos.addAll(res)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text("Cari", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color(0xFF262626),
                    unfocusedContainerColor = Color(0xFF262626),
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 10.dp)
        ) {
            items(categories) { tag ->
                val isSelected = (selectedTag == tag) || (selectedTag.isEmpty() && tag == "Untuk Anda")
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Color.White else Color(0xFF262626))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            selectedTag = if (tag == "Untuk Anda") "" else tag
                            searchQuery = ""
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = tag,
                        color = if (isSelected) Color.Black else Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
            verticalArrangement = Arrangement.spacedBy(1.5.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(filteredVideos) { _, vid ->
                ReelsGridTile(video = vid, onClick = { onVideoSelected(vid) })
            }
        }
    }
}

@Composable
private fun ProfileAndAuthScreen(
    session: GoogleUserSession?,
    onSessionUpdated: (GoogleUserSession?) -> Unit,
    onVideoSelected: (VideoModel) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isAuthenticating by remember { mutableStateOf(false) }
    var showFallbackGoogleDialog by remember { mutableStateOf(false) }
    var fallbackEmail by remember { mutableStateOf("") }

    val googleAccountPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val email = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            if (!email.isNullOrBlank()) {
                isAuthenticating = true
                scope.launch {
                    val newSession = VideoApiClient.signInWithGoogle(context, email)
                    isAuthenticating = false
                    onSessionUpdated(newSession)
                }
            }
        }
    }

    if (session == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .padding(horizontal = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .border(1.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
                        .background(Color(0xFF161616)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.SmartDisplay,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Masuk ke Reels",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Bagikan video pendek, simpan koleksi favorit, dan berinteraksi dengan kreator lainnya.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(36.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = !isAuthenticating
                        ) {
                            try {
                                @Suppress("DEPRECATION")
                                val intent = AccountManager.newChooseAccountIntent(
                                    null,
                                    null,
                                    arrayOf("com.google"),
                                    false,
                                    null,
                                    null,
                                    null,
                                    null
                                )
                                googleAccountPickerLauncher.launch(intent)
                            } catch (_: Exception) {
                                showFallbackGoogleDialog = true
                            }
                        }
                        .padding(horizontal = 16.dp)
                ) {
                    GoogleLogoIcon(modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (isAuthenticating) "Menghubungkan ke Google..." else "Lanjutkan dengan Google",
                        color = Color(0xFF1F1F1F),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Autentikasi dikelola secara aman oleh Google.\nKami tidak pernah meminta atau menyimpan kata sandi Anda.",
                    color = Color.White.copy(alpha = 0.42f),
                    fontSize = 11.5.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp
                )
            }

            if (showFallbackGoogleDialog) {
                AlertDialog(
                    onDismissRequest = { showFallbackGoogleDialog = false },
                    containerColor = Color(0xFF1E1E1E),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GoogleLogoIcon(modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Masuk dengan Google", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        OutlinedTextField(
                            value = fallbackEmail,
                            onValueChange = { fallbackEmail = it },
                            placeholder = { Text("nama@gmail.com", color = Color.White.copy(alpha = 0.4f)) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color.White,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val emailInput = fallbackEmail.trim()
                                if (emailInput.contains("@")) {
                                    showFallbackGoogleDialog = false
                                    scope.launch {
                                        val s = VideoApiClient.signInWithGoogle(context, emailInput)
                                        onSessionUpdated(s)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0095F6))
                        ) {
                            Text("Lanjutkan", color = Color.White)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showFallbackGoogleDialog = false }) {
                            Text("Batal", color = Color.White.copy(alpha = 0.7f))
                        }
                    }
                )
            }
        }
    } else {
        var selectedTab by remember { mutableIntStateOf(0) }
        val gridVideos = remember { mutableStateListOf<VideoModel>() }

        LaunchedEffect(selectedTab, session.userId) {
            val list = if (selectedTab == 0) {
                VideoApiClient.fetchFeed(context, authorId = session.userId)
            } else {
                VideoApiClient.fetchFeed(context, filter = "saved")
            }
            gridVideos.clear()
            gridVideos.addAll(list)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = session.username,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Logout,
                    contentDescription = "Keluar",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .size(22.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            VideoApiClient.signOutGoogle(context)
                            onSessionUpdated(null)
                        }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = session.avatarUrl,
                    contentDescription = session.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(78.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF262626))
                )

                Spacer(modifier = Modifier.width(24.dp))

                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ProfileStatItem(count = session.postsCount.toString(), label = "postingan")
                    ProfileStatItem(count = VideoModel.formatCount(session.followersCount), label = "pengikut")
                    ProfileStatItem(count = VideoModel.formatCount(session.followingCount), label = "mengikuti")
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = session.displayName,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = session.email,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.5.sp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                val tabs = listOf(Icons.Default.GridOn, Icons.Default.BookmarkBorder)
                tabs.forEachIndexed { idx, icon ->
                    val active = selectedTab == idx
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { selectedTab = idx }
                    ) {
                        Icon(
                            imageVector = if (idx == 1 && active) Icons.Default.Bookmark else icon,
                            contentDescription = null,
                            tint = if (active) Color.White else Color.White.copy(alpha = 0.45f),
                            modifier = Modifier
                                .padding(vertical = 10.dp)
                                .size(24.dp)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.5.dp)
                                .background(if (active) Color.White else Color.White.copy(alpha = 0.1f))
                        )
                    }
                }
            }

            if (gridVideos.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (selectedTab == 0) "Belum ada Reels yang dibagikan" else "Belum ada Reels yang disimpan",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                    verticalArrangement = Arrangement.spacedBy(1.5.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(gridVideos) { _, vid ->
                        ReelsGridTile(video = vid, onClick = { onVideoSelected(vid) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileStatItem(count: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 13.sp
        )
    }
}

@Composable
private fun ReelsGridTile(
    video: VideoModel,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f)
            .background(Color(0xFF1A1A1A))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        AsyncImage(
            model = video.thumbUrl.ifEmpty { video.authorAvatarUrl },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.65f to Color.Transparent,
                            1.0f to Color.Black.copy(alpha = 0.65f)
                        )
                    )
                )
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = video.formattedLikes,
                color = Color.White,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun GoogleLogoIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.width * 0.20f
        val diameter = size.width - strokeWidth
        val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
        val arcSize = Size(diameter, diameter)

        drawArc(
            color = Color(0xFFEA4335),
            startAngle = -145f,
            sweepAngle = 95f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
        )
        drawArc(
            color = Color(0xFFFBBC05),
            startAngle = 145f,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
        )
        drawArc(
            color = Color(0xFF34A853),
            startAngle = 45f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
        )
        drawArc(
            color = Color(0xFF4285F4),
            startAngle = -15f,
            sweepAngle = 65f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
        )
        drawLine(
            color = Color(0xFF4285F4),
            start = Offset(size.width * 0.50f, size.height * 0.50f),
            end = Offset(size.width * 0.94f, size.height * 0.50f),
            strokeWidth = strokeWidth
        )
    }
}
