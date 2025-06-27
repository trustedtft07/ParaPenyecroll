package com.project.videofeed.model

import java.util.Locale

data class VideoModel(
    val id: String,
    val videoUrl: String,
    val hlsUrl: String = videoUrl,
    val thumbUrl: String = "",
    val postType: String = "video",
    val authorId: String = "u1",
    val authorName: String,
    val authorHandle: String,
    val authorAvatarUrl: String,
    val description: String,
    val musicTitle: String,
    val likeCount: Int,
    val commentCount: Int,
    val shareCount: Int,
    val isLiked: Boolean = false,
    val isBookmarked: Boolean = false,
    val isFollowing: Boolean = false
) {
    val formattedLikes: String get() = formatCount(likeCount)
    val formattedComments: String get() = formatCount(commentCount)
    val formattedShares: String get() = formatCount(shareCount)

    companion object {
        fun formatCount(count: Int): String {
            return when {
                count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000f)
                count >= 1_000 -> String.format(Locale.US, "%.1fK", count / 1_000f)
                else -> count.toString()
            }
        }
    }
}

data class CommentModel(
    val id: String,
    val videoId: String,
    val userId: String = "",
    val authorName: String,
    val authorHandle: String,
    val authorAvatarUrl: String,
    val content: String,
    val likeCount: Int,
    val timeAgo: String,
    val parentId: String? = null,
    val isMine: Boolean = false
)

object DummyVideoData {
    private const val BASE = "http://10.0.2.2:5050"

    val sampleVideos = listOf(
        VideoModel(
            id = "v1",
            videoUrl = "$BASE/media/videos/v1.mp4",
            hlsUrl = "$BASE/media/hls/v1/index.m3u8",
            thumbUrl = "$BASE/media/thumbs/v1.jpg",
            authorId = "u_sungut",
            authorName = "Sungut Lele Clip",
            authorHandle = "@sungut_lele",
            authorAvatarUrl = "$BASE/media/thumbs/avatar1.jpg",
            description = "Awal Mula Kenal Doksli Jomok 😱 #rezaauditore #clips #doksli",
            musicTitle = "Audio Asli • @sungut_lele",
            likeCount = 5420,
            commentCount = 6,
            shareCount = 1280,
            isLiked = false
        ),
        VideoModel(
            id = "v2",
            videoUrl = "$BASE/media/videos/v2.mp4",
            hlsUrl = "$BASE/media/hls/v2/index.m3u8",
            thumbUrl = "$BASE/media/thumbs/v2.jpg",
            authorId = "u_mipa",
            authorName = "Mipa Clipper",
            authorHandle = "@mipa_clipper",
            authorAvatarUrl = "$BASE/media/thumbs/avatar2.jpg",
            description = "Reza Imut Doksli Lolos Mediashare ✨ #rezaauditore #shorts",
            musicTitle = "Suara Asli • Mipa Clipper",
            likeCount = 8940,
            commentCount = 6,
            shareCount = 2410,
            isLiked = false
        ),
        VideoModel(
            id = "v3",
            videoUrl = "$BASE/media/videos/v3.mp4",
            hlsUrl = "$BASE/media/hls/v3/index.m3u8",
            thumbUrl = "$BASE/media/thumbs/v3.jpg",
            authorId = "u_lifeupdate",
            authorName = "Life Update",
            authorHandle = "@life_update",
            authorAvatarUrl = "$BASE/media/thumbs/avatar3.jpg",
            description = "Apakah Ini Doksli A Day In My Life.. 😭 #rezaauditore #adayinmylife",
            musicTitle = "A Day In My Life • Sound",
            likeCount = 12300,
            commentCount = 6,
            shareCount = 3890,
            isLiked = false
        ),
        VideoModel(
            id = "v4",
            videoUrl = "$BASE/media/videos/v4.mp4",
            hlsUrl = "$BASE/media/hls/v4/index.m3u8",
            thumbUrl = "$BASE/media/thumbs/v4.jpg",
            authorId = "u_doksilindo",
            authorName = "Doksilindo",
            authorHandle = "@doksilindo",
            authorAvatarUrl = "$BASE/media/thumbs/avatar4.jpg",
            description = "REZA AUDITORE, META JOMOK MENGGILA 💀 #memes #shorts #lucu",
            musicTitle = "Original Sound • Doksilindo",
            likeCount = 16400,
            commentCount = 6,
            shareCount = 4520,
            isLiked = false
        ),
        VideoModel(
            id = "v5",
            videoUrl = "$BASE/media/videos/v5.mp4",
            hlsUrl = "$BASE/media/hls/v5/index.m3u8",
            thumbUrl = "$BASE/media/thumbs/v5.jpg",
            authorId = "u_adngeclip",
            authorName = "ADngeclip",
            authorHandle = "@adngeclip",
            authorAvatarUrl = "$BASE/media/thumbs/avatar5.jpg",
            description = "Gudang Doksli Lolos Lagi Brok 😂 Momen Reza Auditore #adngeclip",
            musicTitle = "Original Sound • ADngeclip",
            likeCount = 22100,
            commentCount = 6,
            shareCount = 6730,
            isLiked = false
        ),
        VideoModel(
            id = "v6",
            videoUrl = "$BASE/media/videos/v6.mp4",
            hlsUrl = "$BASE/media/hls/v6/index.m3u8",
            thumbUrl = "$BASE/media/thumbs/v6.jpg",
            authorId = "u_aldean",
            authorName = "Aldean Haters Clip",
            authorHandle = "@aldean_haters",
            authorAvatarUrl = "$BASE/media/thumbs/avatar1.jpg",
            description = "Motor Baru Pakcik Rusdi Ngawi 🏍️ #rezaauditore #shorts #jomok",
            musicTitle = "Suara Knalpot • @aldeanHaters",
            likeCount = 18750,
            commentCount = 6,
            shareCount = 5120,
            isLiked = false
        ),
        VideoModel(
            id = "v7",
            videoUrl = "$BASE/media/videos/v7.mp4",
            hlsUrl = "$BASE/media/hls/v7/index.m3u8",
            thumbUrl = "$BASE/media/thumbs/v7.jpg",
            authorId = "u_meddytore",
            authorName = "Meddytore Clip",
            authorHandle = "@meddytore",
            authorAvatarUrl = "$BASE/media/thumbs/avatar2.jpg",
            description = "Minta Peluk Loh Ya.. Reza Salting Ketemu Doksli 😳 #rezaauditore #clips",
            musicTitle = "Audio Asli • Meddytore",
            likeCount = 14200,
            commentCount = 6,
            shareCount = 3980,
            isLiked = false
        )
    )
}
