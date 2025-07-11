package com.project.videofeed.network

import android.content.Context
import android.net.Uri
import com.project.videofeed.model.CommentModel
import com.project.videofeed.model.DummyVideoData
import com.project.videofeed.model.VideoModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

data class GoogleUserSession(
    val userId: String,
    val email: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String,
    val token: String,
    val postsCount: Int = 0,
    val followersCount: Int = 0,
    val followingCount: Int = 0
)

object VideoApiClient {
    private val CANDIDATE_URLS = listOf(
        "http://10.0.2.2:5050",
        "http://localhost:5050"
    )

    @Volatile
    var activeBaseUrl: String = CANDIDATE_URLS[0]
        private set

    fun getGoogleSession(context: Context): GoogleUserSession? {
        val prefs = context.getSharedPreferences("videofeed_google_auth", Context.MODE_PRIVATE)
        val email = prefs.getString("email", null) ?: return null
        val userId = prefs.getString("user_id", null) ?: return null
        val username = prefs.getString("username", "") ?: ""
        val displayName = prefs.getString("display_name", "") ?: ""
        val avatarUrl = prefs.getString("avatar_url", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        val posts = prefs.getInt("posts_count", 0)
        val followers = prefs.getInt("followers_count", 0)
        val following = prefs.getInt("following_count", 0)
        return GoogleUserSession(userId, email, username, displayName, avatarUrl, token, posts, followers, following)
    }

    private fun saveGoogleSession(context: Context, session: GoogleUserSession) {
        val prefs = context.getSharedPreferences("videofeed_google_auth", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("user_id", session.userId)
            .putString("email", session.email)
            .putString("username", session.username)
            .putString("display_name", session.displayName)
            .putString("avatar_url", session.avatarUrl)
            .putString("token", session.token)
            .putInt("posts_count", session.postsCount)
            .putInt("followers_count", session.followersCount)
            .putInt("following_count", session.followingCount)
            .apply()
    }

    fun signOutGoogle(context: Context) {
        context.getSharedPreferences("videofeed_google_auth", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    fun getActiveUserId(context: Context): String {
        getGoogleSession(context)?.let { return it.userId }
        val prefs = context.getSharedPreferences("videofeed_prefs", Context.MODE_PRIVATE)
        var uid = prefs.getString("guest_id", null)
        if (uid == null) {
            uid = "guest_" + UUID.randomUUID().toString().replace("-", "").take(8)
            prefs.edit().putString("guest_id", uid).apply()
        }
        return uid
    }

    fun getUserDisplayName(context: Context): String {
        return getGoogleSession(context)?.displayName ?: "Pengguna"
    }

    suspend fun signInWithGoogle(
        context: Context,
        email: String,
        displayName: String = ""
    ): GoogleUserSession = withContext(Dispatchers.IO) {
        val baseUrl = resolveWorkingBaseUrl()
        val cleanEmail = email.trim().lowercase()
        val localPart = cleanEmail.substringBefore("@").replace(Regex("[^a-z0-9._]"), "")
        val derivedName = displayName.ifBlank {
            localPart.split(".", "_", "-").joinToString(" ") { part ->
                part.replaceFirstChar { it.uppercase() }
            }.ifBlank { localPart }
        }

        try {
            val conn = (URL("$baseUrl/api/v1/auth/google").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput = true
                connectTimeout = 6000
                readTimeout = 6000
            }
            val payload = JSONObject().apply {
                put("email", cleanEmail)
                put("display_name", derivedName)
            }.toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode in 200..201) {
                val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val root = JSONObject(body)
                val u = root.getJSONObject("user")
                val session = GoogleUserSession(
                    userId = u.getString("id"),
                    email = cleanEmail,
                    username = u.getString("username"),
                    displayName = u.getString("display_name"),
                    avatarUrl = u.optString("avatar_url", "$baseUrl/media/thumbs/avatar1.jpg"),
                    token = root.optString("token", ""),
                    postsCount = root.optInt("posts_count", 0),
                    followersCount = root.optInt("followers_count", 0),
                    followingCount = root.optInt("following_count", 0)
                )
                saveGoogleSession(context, session)
                return@withContext session
            }
        } catch (_: Exception) {
        }

        // Fallback session if server is temporarily unreachable
        val fallbackSession = GoogleUserSession(
            userId = "g_$localPart",
            email = cleanEmail,
            username = localPart,
            displayName = derivedName,
            avatarUrl = "$activeBaseUrl/media/thumbs/avatar1.jpg",
            token = ""
        )
        saveGoogleSession(context, fallbackSession)
        return@withContext fallbackSession
    }

    private suspend fun resolveWorkingBaseUrl(): String = withContext(Dispatchers.IO) {
        for (candidate in CANDIDATE_URLS) {
            try {
                val conn = (URL("$candidate/api/v1/health").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 3000
                    readTimeout = 3000
                }
                if (conn.responseCode == 200) {
                    activeBaseUrl = candidate
                    conn.disconnect()
                    return@withContext candidate
                }
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
        return@withContext activeBaseUrl
    }

    private fun applyAuth(conn: HttpURLConnection, context: Context) {
        val session = getGoogleSession(context)
        if (session != null && session.token.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer ${session.token}")
        }
        conn.setRequestProperty("X-User-ID", getActiveUserId(context))
    }

    suspend fun fetchFeed(
        context: Context,
        filter: String = "",
        query: String = "",
        authorId: String = ""
    ): List<VideoModel> = withContext(Dispatchers.IO) {
        val baseUrl = resolveWorkingBaseUrl()
        try {
            val params = mutableListOf("limit=30")
            if (filter.isNotEmpty()) params.add("filter=${URLEncoder.encode(filter, "UTF-8")}")
            if (query.isNotEmpty()) params.add("q=${URLEncoder.encode(query, "UTF-8")}")
            if (authorId.isNotEmpty()) params.add("author_id=${URLEncoder.encode(authorId, "UTF-8")}")

            val urlStr = "$baseUrl/api/v1/feed?${params.joinToString("&")}"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                applyAuth(this, context)
                connectTimeout = 6000
                readTimeout = 6000
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val root = JSONObject(body)
                val arr = root.getJSONArray("videos")
                val list = mutableListOf<VideoModel>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val videoUrl = obj.getString("video_url")
                    val hlsUrl = obj.optString("hls_url", videoUrl).ifEmpty { videoUrl }
                    list.add(
                        VideoModel(
                            id = obj.getString("id"),
                            videoUrl = videoUrl,
                            hlsUrl = hlsUrl,
                            thumbUrl = obj.optString("thumb_url", ""),
                            postType = obj.optString("post_type", "video"),
                            authorId = obj.optString("author_id", "u1"),
                            authorName = obj.optString("author_name", "Creator"),
                            authorHandle = obj.optString("author_handle", "@creator"),
                            authorAvatarUrl = obj.optString("author_avatar_url", ""),
                            description = obj.optString("description", ""),
                            musicTitle = obj.optString("music_title", "Audio Asli"),
                            likeCount = obj.optInt("like_count", 0),
                            commentCount = obj.optInt("comment_count", 0),
                            shareCount = obj.optInt("share_count", 0),
                            isLiked = obj.optBoolean("is_liked", false),
                            isBookmarked = obj.optBoolean("is_bookmarked", false),
                            isFollowing = obj.optBoolean("is_following", false)
                        )
                    )
                }
                conn.disconnect()
                if (list.isNotEmpty() || filter.isNotEmpty() || query.isNotEmpty() || authorId.isNotEmpty()) {
                    return@withContext list
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            android.util.Log.w("VideoApiClient", "Fallback to local sample videos: ${e.message}")
        }
        return@withContext if (filter.isEmpty() && query.isEmpty() && authorId.isEmpty()) DummyVideoData.sampleVideos else emptyList()
    }

    data class LikeResult(val isLiked: Boolean, val likeCount: Int)

    suspend fun toggleLike(context: Context, videoId: String): LikeResult? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("$activeBaseUrl/api/v1/videos/$videoId/like").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                applyAuth(this, context)
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val obj = JSONObject(body)
                return@withContext LikeResult(
                    isLiked = obj.getBoolean("is_liked"),
                    likeCount = obj.getInt("like_count")
                )
            }
        } catch (_: Exception) {
        }
        return@withContext null
    }

    suspend fun toggleBookmark(context: Context, videoId: String): Boolean? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("$activeBaseUrl/api/v1/videos/$videoId/bookmark").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                applyAuth(this, context)
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val obj = JSONObject(body)
                return@withContext obj.getBoolean("is_bookmarked")
            }
        } catch (_: Exception) {
        }
        return@withContext null
    }

    suspend fun toggleFollow(context: Context, creatorId: String): Boolean? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("$activeBaseUrl/api/v1/users/$creatorId/follow").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                applyAuth(this, context)
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val obj = JSONObject(body)
                return@withContext obj.getBoolean("is_following")
            }
        } catch (_: Exception) {
        }
        return@withContext null
    }

    suspend fun reportVideo(context: Context, videoId: String, reason: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("$activeBaseUrl/api/v1/videos/$videoId/report").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                applyAuth(this, context)
                doOutput = true
                connectTimeout = 5000
                readTimeout = 5000
            }
            val payload = JSONObject().put("reason", reason).toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            return@withContext conn.responseCode == 200
        } catch (_: Exception) {
        }
        return@withContext false
    }

    suspend fun deleteVideo(context: Context, videoId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("$activeBaseUrl/api/v1/videos/$videoId").openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                applyAuth(this, context)
                connectTimeout = 5000
                readTimeout = 5000
            }
            return@withContext conn.responseCode == 200
        } catch (_: Exception) {
        }
        return@withContext false
    }

    suspend fun shareVideo(videoId: String): Int? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("$activeBaseUrl/api/v1/videos/$videoId/share").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val obj = JSONObject(body)
                return@withContext obj.getInt("share_count")
            }
        } catch (_: Exception) {
        }
        return@withContext null
    }

    suspend fun fetchComments(context: Context, videoId: String): List<CommentModel> = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("$activeBaseUrl/api/v1/videos/$videoId/comments").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                applyAuth(this, context)
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val root = JSONObject(body)
                val arr = root.getJSONArray("comments")
                val list = mutableListOf<CommentModel>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val rawParentId = obj.optString("parent_id", "")
                    list.add(
                        CommentModel(
                            id = obj.getString("id"),
                            videoId = obj.getString("video_id"),
                            userId = obj.optString("user_id", ""),
                            authorName = obj.optString("author_name", "User"),
                            authorHandle = obj.optString("author_handle", "@user"),
                            authorAvatarUrl = obj.optString("author_avatar_url", ""),
                            content = obj.getString("content"),
                            likeCount = obj.optInt("like_count", 0),
                            timeAgo = obj.optString("time_ago", "Baru saja"),
                            parentId = if (rawParentId.isBlank()) null else rawParentId,
                            isMine = obj.optBoolean("is_mine", false)
                        )
                    )
                }
                return@withContext list
            }
        } catch (_: Exception) {
        }
        return@withContext emptyList()
    }

    data class PostCommentResult(val comment: CommentModel, val totalComments: Int)

    suspend fun postComment(
        context: Context,
        videoId: String,
        content: String,
        authorName: String = getUserDisplayName(context),
        parentId: String? = null
    ): PostCommentResult? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("$activeBaseUrl/api/v1/videos/$videoId/comments").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                applyAuth(this, context)
                doOutput = true
                connectTimeout = 6000
                readTimeout = 6000
            }
            val payload = JSONObject().apply {
                put("content", content)
                put("author_name", authorName)
                if (parentId != null && parentId.isNotBlank()) {
                    put("parent_id", parentId)
                }
            }.toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode in 200..201) {
                val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val root = JSONObject(body)
                val c = root.getJSONObject("comment")
                val total = root.optInt("comment_count", 1)
                val rawParent = c.optString("parent_id", "")
                return@withContext PostCommentResult(
                    comment = CommentModel(
                        id = c.getString("id"),
                        videoId = c.getString("video_id"),
                        userId = c.optString("user_id", getActiveUserId(context)),
                        authorName = c.optString("author_name", authorName),
                        authorHandle = c.optString("author_handle", "@user"),
                        authorAvatarUrl = c.optString("author_avatar_url", ""),
                        content = c.getString("content"),
                        likeCount = c.optInt("like_count", 0),
                        timeAgo = c.optString("time_ago", "Baru saja"),
                        parentId = if (rawParent.isBlank()) null else rawParent,
                        isMine = true
                    ),
                    totalComments = total
                )
            }
        } catch (_: Exception) {
        }
        return@withContext null
    }

    suspend fun deleteComment(context: Context, commentId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("$activeBaseUrl/api/v1/comments/$commentId").openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                applyAuth(this, context)
                connectTimeout = 5000
                readTimeout = 5000
            }
            return@withContext conn.responseCode == 200
        } catch (_: Exception) {
        }
        return@withContext false
    }

    suspend fun uploadVideo(
        context: Context,
        videoUri: Uri,
        description: String,
        musicTitle: String,
        authorName: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val baseUrl = resolveWorkingBaseUrl()
        val boundary = "----VideoFeedBoundary" + System.currentTimeMillis()
        val lineEnd = "\r\n"
        val twoHyphens = "--"

        try {
            val mimeType = context.contentResolver.getType(videoUri) ?: "video/mp4"
            val isImage = mimeType.startsWith("image/")
            val filename = if (isImage) "upload.jpg" else "upload.mp4"
            val fieldContentType = if (isImage) mimeType else "video/mp4"

            val inputStream = context.contentResolver.openInputStream(videoUri)
                ?: return@withContext Result.failure(Exception("Gagal membuka file media"))

            val conn = (URL("$baseUrl/api/v1/videos/upload").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doInput = true
                doOutput = true
                useCaches = false
                setChunkedStreamingMode(64 * 1024)
                setRequestProperty("Connection", "Keep-Alive")
                applyAuth(this, context)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                connectTimeout = 15000
                readTimeout = 90000
            }

            DataOutputStream(conn.outputStream).use { dos ->
                fun writeFormField(name: String, value: String) {
                    dos.writeBytes(twoHyphens + boundary + lineEnd)
                    dos.writeBytes("Content-Disposition: form-data; name=\"$name\"$lineEnd$lineEnd")
                    dos.write(value.toByteArray(Charsets.UTF_8))
                    dos.writeBytes(lineEnd)
                }

                writeFormField("description", description)
                writeFormField("music_title", musicTitle)
                writeFormField("author_name", authorName)

                dos.writeBytes(twoHyphens + boundary + lineEnd)
                dos.writeBytes("Content-Disposition: form-data; name=\"media\"; filename=\"$filename\"$lineEnd")
                dos.writeBytes("Content-Type: $fieldContentType$lineEnd$lineEnd")

                inputStream.use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        dos.write(buffer, 0, bytesRead)
                    }
                }

                dos.writeBytes(lineEnd)
                dos.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd)
                dos.flush()
            }

            val code = conn.responseCode
            if (code in 200..201) {
                val resp = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val obj = JSONObject(resp)
                return@withContext Result.success(obj.optString("video_id", "ok"))
            } else {
                val errBody = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: "HTTP $code"
                val msg = try {
                    JSONObject(errBody).optString("error", errBody)
                } catch (_: Exception) {
                    errBody
                }
                return@withContext Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }
}
