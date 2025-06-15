package main

import (
	"bytes"
	"crypto/rand"
	"crypto/subtle"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"mime/multipart"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"golang.org/x/crypto/argon2"
	_ "modernc.org/sqlite"
)

var (
	DataDir        = getEnv("DATA_DIR", "/data")
	Port           = getEnv("PORT", "5050")
	JWTSecret      = []byte("init-placeholder")
	PublicURL      = getEnv("PUBLIC_URL", "")
	MaxUploadBytes int64 = 35 * 1024 * 1024
)

func initJWTSecret() {
	if envSecret := os.Getenv("JWT_SECRET"); envSecret != "" {
		JWTSecret = []byte(envSecret)
		return
	}
	secretFile := filepath.Join(DataDir, "db", "jwt_secret.key")
	if data, err := os.ReadFile(secretFile); err == nil && len(data) >= 32 {
		JWTSecret = data
		return
	}
	randomKey := make([]byte, 32)
	if _, err := rand.Read(randomKey); err == nil {
		encodedKey := []byte(base64.RawURLEncoding.EncodeToString(randomKey))
		_ = os.WriteFile(secretFile, encodedKey, 0600)
		JWTSecret = encodedKey
		log.Println("Generated new persistent cryptographically secure JWT secret key.")
		return
	}
	JWTSecret = []byte("dev-jwt-secret-replace-in-production")
}

type Server struct {
	db          *sql.DB
	uploadQueue chan UploadJob
	rateLimiter *RateLimiter
}

type UploadJob struct {
	VideoID     string
	TempPath    string
	UserID      string
	Description string
	MusicTitle  string
}

type User struct {
	ID          string `json:"id"`
	Username    string `json:"username"`
	DisplayName string `json:"display_name"`
	AvatarURL   string `json:"avatar_url"`
	Bio         string `json:"bio"`
}

type VideoResponse struct {
	ID              string `json:"id"`
	VideoURL        string `json:"video_url"`
	HlsURL          string `json:"hls_url"`
	ThumbURL        string `json:"thumb_url"`
	PostType        string `json:"post_type"`
	AuthorID        string `json:"author_id"`
	AuthorName      string `json:"author_name"`
	AuthorHandle    string `json:"author_handle"`
	AuthorAvatarURL string `json:"author_avatar_url"`
	Description     string `json:"description"`
	MusicTitle      string `json:"music_title"`
	LikeCount       int    `json:"like_count"`
	CommentCount    int    `json:"comment_count"`
	ShareCount      int    `json:"share_count"`
	IsLiked         bool   `json:"is_liked"`
	IsBookmarked    bool   `json:"is_bookmarked"`
	IsFollowing     bool   `json:"is_following"`
	CreatedAt       int64  `json:"created_at"`
}

type CommentResponse struct {
	ID              string  `json:"id"`
	VideoID         string  `json:"video_id"`
	UserID          string  `json:"user_id"`
	AuthorName      string  `json:"author_name"`
	AuthorHandle    string  `json:"author_handle"`
	AuthorAvatarURL string  `json:"author_avatar_url"`
	Content         string  `json:"content"`
	LikeCount       int     `json:"like_count"`
	ParentID        *string `json:"parent_id,omitempty"`
	IsMine          bool    `json:"is_mine"`
	CreatedAt       int64   `json:"created_at"`
	TimeAgo         string  `json:"time_ago"`
}

type RateLimiter struct {
	mu      sync.Mutex
	clients map[string]*clientBucket
}

type clientBucket struct {
	tokens   float64
	lastSeen time.Time
}

func NewRateLimiter() *RateLimiter {
	rl := &RateLimiter{clients: make(map[string]*clientBucket)}
	go func() {
		for {
			time.Sleep(2 * time.Minute)
			rl.mu.Lock()
			now := time.Now()
			for ip, b := range rl.clients {
				if now.Sub(b.lastSeen) > 5*time.Minute {
					delete(rl.clients, ip)
				}
			}
			rl.mu.Unlock()
		}
	}()
	return rl
}

func (rl *RateLimiter) Allow(ip string, ratePerSec float64, burst float64) bool {
	rl.mu.Lock()
	defer rl.mu.Unlock()
	now := time.Now()
	b, exists := rl.clients[ip]
	if !exists {
		rl.clients[ip] = &clientBucket{tokens: burst - 1, lastSeen: now}
		return true
	}
	elapsed := now.Sub(b.lastSeen).Seconds()
	b.lastSeen = now
	b.tokens += elapsed * ratePerSec
	if b.tokens > burst {
		b.tokens = burst
	}
	if b.tokens < 1 {
		return false
	}
	b.tokens -= 1
	return true
}

func main() {
	log.SetFlags(log.LstdFlags | log.Lshortfile)
	log.Printf("Starting VideoFeed High-Performance Go Backend on :%s...", Port)

	for _, sub := range []string{"db", "media/videos", "media/hls", "media/thumbs", "media/temp"} {
		if err := os.MkdirAll(filepath.Join(DataDir, sub), 0755); err != nil {
			log.Fatalf("Failed to create dir %s: %v", sub, err)
		}
	}

	initJWTSecret()

	dbPath := filepath.Join(DataDir, "db", "videofeed.db")
	db, err := sql.Open("sqlite", dbPath)
	if err != nil {
		log.Fatalf("Failed to open SQLite DB: %v", err)
	}
	defer db.Close()

	db.SetMaxOpenConns(16)
	db.SetMaxIdleConns(8)
	db.SetConnMaxLifetime(time.Hour)

	pragmas := []string{
		"PRAGMA journal_mode = WAL;",
		"PRAGMA synchronous = NORMAL;",
		"PRAGMA mmap_size = 536870912;",
		"PRAGMA cache_size = -64000;",
		"PRAGMA temp_store = MEMORY;",
		"PRAGMA foreign_keys = ON;",
		"PRAGMA busy_timeout = 5000;",
	}
	for _, p := range pragmas {
		if _, err := db.Exec(p); err != nil {
			log.Printf("Warning executing %s: %v", p, err)
		}
	}

	srv := &Server{
		db:          db,
		uploadQueue: make(chan UploadJob, 25),
		rateLimiter: NewRateLimiter(),
	}

	if err := srv.initSchema(); err != nil {
		log.Fatalf("Failed to initialize schema: %v", err)
	}

	srv.prepareMockMediaAndSeed()

	go srv.transcodeWorker()
	go srv.autoBackupWorker()

	mux := http.NewServeMux()

	mux.HandleFunc("/api/v1/health", srv.handleHealth)
	mux.HandleFunc("/api/v1/auth/google", srv.handleGoogleAuth)
	mux.HandleFunc("/api/v1/auth/register", srv.handleRegister)
	mux.HandleFunc("/api/v1/auth/login", srv.handleLogin)
	mux.HandleFunc("/api/v1/feed", srv.handleFeed)
	mux.HandleFunc("/api/v1/videos/upload", srv.handleUpload)
	mux.HandleFunc("/api/v1/videos/", srv.handleVideoSubroutes)
	mux.HandleFunc("/api/v1/comments/", srv.handleCommentSubroutes)
	mux.HandleFunc("/api/v1/users/", srv.handleUserSubroutes)
	mux.HandleFunc("/media/", srv.handleMedia)

	handler := srv.securityMiddleware(mux)

	httpServer := &http.Server{
		Addr:              ":" + Port,
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
		WriteTimeout:      120 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	log.Printf("Server ready and listening on :%s (DataDir: %s)", Port, DataDir)
	if err := httpServer.ListenAndServe(); err != nil {
		log.Fatalf("Server exited: %v", err)
	}
}

func (s *Server) autoBackupWorker() {
	for {
		time.Sleep(6 * time.Hour)
		backupPath := filepath.Join(DataDir, "db", "videofeed_backup.db")
		_ = os.Remove(backupPath)
		if _, err := s.db.Exec(fmt.Sprintf("VACUUM INTO '%s'", backupPath)); err != nil {
			log.Printf("Auto-backup warning: %v", err)
		} else {
			log.Printf("SQLite online backup completed: %s", backupPath)
		}
	}
}

func (s *Server) initSchema() error {
	schema := `
	CREATE TABLE IF NOT EXISTS users (
		id TEXT PRIMARY KEY,
		username TEXT UNIQUE NOT NULL,
		display_name TEXT NOT NULL,
		avatar_url TEXT NOT NULL,
		bio TEXT DEFAULT '',
		password_hash TEXT NOT NULL,
		followers_count INTEGER DEFAULT 0,
		following_count INTEGER DEFAULT 0,
		created_at INTEGER NOT NULL
	);

	CREATE TABLE IF NOT EXISTS videos (
		id TEXT PRIMARY KEY,
		user_id TEXT NOT NULL,
		description TEXT NOT NULL,
		music_title TEXT NOT NULL,
		video_path TEXT NOT NULL,
		hls_path TEXT NOT NULL,
		thumb_path TEXT NOT NULL,
		duration_sec INTEGER DEFAULT 15,
		like_count INTEGER DEFAULT 0,
		comment_count INTEGER DEFAULT 0,
		share_count INTEGER DEFAULT 0,
		status TEXT DEFAULT 'ready',
		post_type TEXT DEFAULT 'video',
		created_at INTEGER NOT NULL,
		FOREIGN KEY(user_id) REFERENCES users(id)
	);

	CREATE INDEX IF NOT EXISTS idx_videos_created_at ON videos(created_at DESC);
	CREATE INDEX IF NOT EXISTS idx_videos_status ON videos(status);

	CREATE TABLE IF NOT EXISTS likes (
		user_id TEXT NOT NULL,
		video_id TEXT NOT NULL,
		created_at INTEGER NOT NULL,
		PRIMARY KEY(user_id, video_id)
	);

	CREATE TABLE IF NOT EXISTS bookmarks (
		user_id TEXT NOT NULL,
		video_id TEXT NOT NULL,
		created_at INTEGER NOT NULL,
		PRIMARY KEY(user_id, video_id)
	);

	CREATE TABLE IF NOT EXISTS follows (
		follower_id TEXT NOT NULL,
		creator_id TEXT NOT NULL,
		created_at INTEGER NOT NULL,
		PRIMARY KEY(follower_id, creator_id)
	);

	CREATE TABLE IF NOT EXISTS reports (
		id TEXT PRIMARY KEY,
		video_id TEXT NOT NULL,
		user_id TEXT NOT NULL,
		reason TEXT NOT NULL,
		created_at INTEGER NOT NULL
	);

	CREATE TABLE IF NOT EXISTS comments (
		id TEXT PRIMARY KEY,
		video_id TEXT NOT NULL,
		user_id TEXT NOT NULL,
		content TEXT NOT NULL,
		like_count INTEGER DEFAULT 0,
		parent_id TEXT DEFAULT NULL,
		created_at INTEGER NOT NULL,
		FOREIGN KEY(video_id) REFERENCES videos(id),
		FOREIGN KEY(user_id) REFERENCES users(id)
	);

	CREATE INDEX IF NOT EXISTS idx_comments_video_id ON comments(video_id, created_at DESC);
	`
	if _, err := s.db.Exec(schema); err != nil {
		return err
	}
	_, _ = s.db.Exec("ALTER TABLE videos ADD COLUMN post_type TEXT DEFAULT 'video';")
	_, _ = s.db.Exec("ALTER TABLE comments ADD COLUMN parent_id TEXT DEFAULT NULL;")
	return nil
}

func (s *Server) prepareMockMediaAndSeed() {
	for i := 1; i <= 5; i++ {
		vidKey := fmt.Sprintf("v%d", i)
		mp4Path := filepath.Join(DataDir, "media", "videos", vidKey+".mp4")
		if _, err := os.Stat(mp4Path); err != nil {
			continue
		}

		hlsDir := filepath.Join(DataDir, "media", "hls", vidKey)
		m3u8Path := filepath.Join(hlsDir, "index.m3u8")
		thumbPath := filepath.Join(DataDir, "media", "thumbs", vidKey+".jpg")

		if _, err := os.Stat(m3u8Path); os.IsNotExist(err) {
			_ = os.MkdirAll(hlsDir, 0755)
			log.Printf("Generating HLS segments for %s...", vidKey)
			cmd := exec.Command("ffmpeg", "-y",
				"-i", mp4Path,
				"-c:v", "copy",
				"-c:a", "aac", "-b:a", "128k",
				"-f", "hls",
				"-hls_time", "2",
				"-hls_playlist_type", "vod",
				"-hls_flags", "independent_segments",
				"-hls_segment_filename", filepath.Join(hlsDir, "seg_%03d.ts"),
				m3u8Path,
			)
			if out, err := cmd.CombinedOutput(); err != nil {
				log.Printf("HLS remux warning for %s: %v (%s)", vidKey, err, string(out))
			}
		}

		if _, err := os.Stat(thumbPath); os.IsNotExist(err) {
			log.Printf("Extracting thumbnail for %s...", vidKey)
			cmd := exec.Command("ffmpeg", "-y",
				"-ss", "00:00:00.500",
				"-i", mp4Path,
				"-frames:v", "1",
				"-q:v", "4",
				thumbPath,
			)
			_ = cmd.Run()
		}
	}

	var count int
	_ = s.db.QueryRow("SELECT COUNT(*) FROM videos").Scan(&count)
	if count > 0 {
		log.Printf("Database already contains %d videos, skipping initial seed.", count)
		return
	}

	log.Println("Seeding mockup creators, vertical videos, and sample comments...")
	now := time.Now().Unix()
	defaultHash := hashPassword("password123")

	users := []struct {
		ID, Username, DisplayName, Avatar, Bio string
		Followers                              int
	}{
		{"u1", "alex_wander", "Alex Wanderer", "/media/thumbs/avatar1.jpg", "Visual Storyteller & Night Photographer", 124500},
		{"u2", "sarah_creates", "Sarah Studio", "/media/thumbs/avatar2.jpg", "Mobile Dev & Nature Enthusiast", 89200},
		{"u3", "techpulse_id", "TechPulse ID", "/media/thumbs/avatar3.jpg", "Catatan software engineering dan infrastruktur", 310800},
		{"u4", "urban_explorer", "Urban Explorer", "/media/thumbs/avatar4.jpg", "Dokumentasi visual sudut kota", 56400},
		{"u5", "kuliner_malam", "Kuliner Malam ID", "/media/thumbs/avatar5.jpg", "Eksplorasi kuliner lokal", 198000},
	}

	for _, u := range users {
		_, _ = s.db.Exec(`
			INSERT OR IGNORE INTO users (id, username, display_name, avatar_url, bio, password_hash, followers_count, created_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
			u.ID, u.Username, u.DisplayName, u.Avatar, u.Bio, defaultHash, u.Followers, now-86400*10)
	}

	videos := []struct {
		ID, UserID, Desc, Music, VidPath, HlsPath, ThumbPath string
		Likes, Comments, Shares                              int
		OffsetSec                                            int64
	}{
		{
			"v1", "u1",
			"Suasana malam di pusat kota. Streaming format HLS dengan Go dan SQLite. #cinematic #nightvibes",
			"Midnight Echoes • Lofi Synth",
			"/media/videos/v1.mp4", "/media/hls/v1/index.m3u8", "/media/thumbs/v1.jpg",
			1245, 6, 312, 3600 * 5,
		},
		{
			"v2", "u2",
			"Implementasi feed video vertikal menggunakan Jetpack Compose dan ExoPlayer. #coding #androiddev #golang",
			"Original Audio • Sarah Studio",
			"/media/videos/v2.mp4", "/media/hls/v2/index.m3u8", "/media/thumbs/v2.jpg",
			2890, 5, 640, 3600 * 4,
		},
		{
			"v3", "u3",
			"Benchmark SQLite WAL mode dengan akses IO berbasis mmap untuk beban baca tinggi. #homelab #selfhosted #techpulse",
			"Cyberpunk Drive • Synthwave",
			"/media/videos/v3.mp4", "/media/hls/v3/index.m3u8", "/media/thumbs/v3.jpg",
			5420, 6, 1280, 3600 * 3,
		},
		{
			"v4", "u4",
			"Uji transisi dan buffering playback video HLS. #adventure #reels #smooth",
			"Bass Motion • Cinematic Sound",
			"/media/videos/v4.mp4", "/media/hls/v4/index.m3u8", "/media/thumbs/v4.jpg",
			980, 4, 145, 3600 * 2,
		},
		{
			"v5", "u5",
			"Pengujian sinkronisasi komentar, like, dan upload langsung dari klien Android. #indiedev #mobile #explore",
			"Golden Hour Acoustic • Chill",
			"/media/videos/v5.mp4", "/media/hls/v5/index.m3u8", "/media/thumbs/v5.jpg",
			1760, 4, 290, 3600 * 1,
		},
	}

	for _, v := range videos {
		_, _ = s.db.Exec(`
			INSERT OR IGNORE INTO videos (id, user_id, description, music_title, video_path, hls_path, thumb_path, like_count, comment_count, share_count, status, created_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ready', ?)`,
			v.ID, v.UserID, v.Desc, v.Music, v.VidPath, v.HlsPath, v.ThumbPath, v.Likes, v.Comments, v.Shares, now-v.OffsetSec)
	}

	comments := []struct {
		VideoID, UserID, Content string
		Likes                    int
		AgoSec                   int64
	}{
		{"v1", "u3", "Latensi streaming HLS 2 detik terasa sangat responsif saat diuji.", 84, 7200},
		{"v1", "u2", "Kualitas kompresi video tetap terjaga baik.", 42, 5400},
		{"v1", "u4", "Format segmen HLS sangat membantu efisiensi caching.", 19, 3600},
		{"v1", "u5", "Swipe vertikal berjalan lancar tanpa frame drop.", 11, 1800},
		{"v1", "u2", "Interaksi gesture like bekerja sesuai harapan.", 27, 900},
		{"v1", "u3", "Konsumsi memory backend tetap stabil di bawah 25 MB.", 53, 300},
		{"v2", "u1", "Integrasi VerticalPager dengan ExoPlayer berjalan mulus.", 67, 6800},
		{"v2", "u3", "Mode WAL di SQLite membuat operasi baca-tulis konkuren tidak saling lock.", 39, 4200},
		{"v2", "u5", "Struktur arsitektur clean dan mudah dipahami.", 22, 2400},
		{"v2", "u4", "Setup pipeline transcoding via container berjalan rapi.", 15, 1200},
		{"v2", "u1", "Komentar real-time langsung ter-update di UI.", 31, 400},
		{"v3", "u2", "Akselerasi hardware VAAPI sangat memangkas beban CPU saat encode.", 112, 8100},
		{"v3", "u1", "Konfigurasi mmap SQLite sangat membantu throughput.", 76, 5100},
		{"v3", "u4", "Header cache immutable sangat efisien untuk distribusi file segmen.", 48, 3300},
		{"v3", "u5", "Resource footprint yang efisien untuk layanan self-hosted.", 64, 2100},
		{"v3", "u2", "Bagus untuk referensi desain sistem backend streaming.", 19, 800},
		{"v3", "u4", "Batas upload 35 MB sudah cukup untuk format video pendek.", 29, 200},
		{"v4", "u5", "Transisi antar item video tidak terasa lag.", 34, 4000},
		{"v4", "u1", "Pre-buffering satu item berikutnya menjaga kestabilan scroll.", 21, 2500},
		{"v4", "u3", "Cache lokal LRU di client berfungsi baik saat offline.", 45, 1500},
		{"v4", "u2", "Layout responsif di berbagai resolusi layar.", 12, 600},
		{"v5", "u3", "Siklus hidup auth guest dan pengguna terdaftar bekerja konsisten.", 58, 3000},
		{"v5", "u1", "Tampilan navigasi bersih dan mudah dinavigasi.", 24, 1900},
		{"v5", "u4", "Pengujian validasi magic bytes file media berjalan tepat.", 33, 1100},
		{"v5", "u2", "Pengembangan fitur yang terstruktur dengan baik.", 18, 300},
	}

	for idx, c := range comments {
		cID := fmt.Sprintf("c_%d", idx+1)
		_, _ = s.db.Exec(`
			INSERT OR IGNORE INTO comments (id, video_id, user_id, content, like_count, created_at)
			VALUES (?, ?, ?, ?, ?, ?)`,
			cID, c.VideoID, c.UserID, c.Content, c.Likes, now-c.AgoSec)
	}
	log.Println("Seeding completed successfully!")
}

func (s *Server) securityMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-User-ID")

		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}

		if strings.HasPrefix(r.URL.Path, "/api/") {
			ip := getClientIP(r)
			rate := 4.0
			burst := 50.0
			if strings.HasSuffix(r.URL.Path, "/upload") {
				rate = 0.2
				burst = 5.0
			}
			if !s.rateLimiter.Allow(ip, rate, burst) {
				writeJSON(w, http.StatusTooManyRequests, map[string]string{
					"error": "Terlalu banyak request, silakan tunggu beberapa detik.",
				})
				return
			}
		}

		next.ServeHTTP(w, r)
	})
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	var m runtime.MemStats
	runtime.ReadMemStats(&m)

	var videoCount, commentCount, userCount int
	_ = s.db.QueryRow("SELECT COUNT(*) FROM videos WHERE status = 'ready'").Scan(&videoCount)
	_ = s.db.QueryRow("SELECT COUNT(*) FROM comments").Scan(&commentCount)
	_ = s.db.QueryRow("SELECT COUNT(*) FROM users").Scan(&userCount)

	_, vaapiErr := os.Stat("/dev/dri/renderD128")

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"status":         "ok",
		"engine":         "Go + SQLite (WAL + mmap) + FFmpeg",
		"ram_alloc_mb":   fmt.Sprintf("%.2f MB", float64(m.Alloc)/1024/1024),
		"ram_sys_mb":     fmt.Sprintf("%.2f MB", float64(m.Sys)/1024/1024),
		"vaapi_hw_accel": vaapiErr == nil,
		"stats": map[string]int{
			"videos":   videoCount,
			"comments": commentCount,
			"users":    userCount,
		},
	})
}

func (s *Server) handleFeed(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "Method not allowed"})
		return
	}

	userID := s.resolveUserID(r)
	baseURL := resolveBaseURL(r)

	limit := 20
	if lStr := r.URL.Query().Get("limit"); lStr != "" {
		if l, err := strconv.Atoi(lStr); err == nil && l > 0 && l <= 50 {
			limit = l
		}
	}

	filter := r.URL.Query().Get("filter")     // "following" | "saved"
	searchQ := strings.TrimSpace(r.URL.Query().Get("q"))
	authorID := strings.TrimSpace(r.URL.Query().Get("author_id"))

	query := `
		SELECT 
			v.id, v.video_path, v.hls_path, v.thumb_path,
			u.id, u.display_name, u.username, u.avatar_url,
			v.description, v.music_title,
			v.like_count, v.comment_count, v.share_count,
			COALESCE(v.post_type, 'video') AS post_type,
			v.created_at,
			EXISTS(SELECT 1 FROM likes l WHERE l.video_id = v.id AND l.user_id = ?) AS is_liked,
			EXISTS(SELECT 1 FROM bookmarks b WHERE b.video_id = v.id AND b.user_id = ?) AS is_bookmarked,
			EXISTS(SELECT 1 FROM follows f WHERE f.creator_id = u.id AND f.follower_id = ?) AS is_following
		FROM videos v
		JOIN users u ON v.user_id = u.id
		WHERE v.status = 'ready'
	`
	args := []interface{}{userID, userID, userID}

	if filter == "following" {
		query += ` AND EXISTS(SELECT 1 FROM follows f2 WHERE f2.creator_id = v.user_id AND f2.follower_id = ?)`
		args = append(args, userID)
	} else if filter == "saved" {
		query += ` AND EXISTS(SELECT 1 FROM bookmarks b2 WHERE b2.video_id = v.id AND b2.user_id = ?)`
		args = append(args, userID)
	}

	if authorID != "" {
		query += ` AND v.user_id = ?`
		args = append(args, authorID)
	}

	if searchQ != "" {
		query += ` AND (LOWER(v.description) LIKE ? OR LOWER(u.display_name) LIKE ? OR LOWER(u.username) LIKE ? OR LOWER(v.music_title) LIKE ?)`
		likePattern := "%" + strings.ToLower(searchQ) + "%"
		args = append(args, likePattern, likePattern, likePattern, likePattern)
	}

	query += ` ORDER BY v.created_at DESC LIMIT ?`
	args = append(args, limit)

	rows, err := s.db.Query(query, args...)
	if err != nil {
		log.Printf("Feed query error: %v", err)
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "Database error"})
		return
	}
	defer rows.Close()

	var feed []VideoResponse
	var nextCursor int64 = 0

	for rows.Next() {
		var item VideoResponse
		var vidPath, hlsPath, thumbPath, username, avatarPath string
		if err := rows.Scan(
			&item.ID, &vidPath, &hlsPath, &thumbPath,
			&item.AuthorID, &item.AuthorName, &username, &avatarPath,
			&item.Description, &item.MusicTitle,
			&item.LikeCount, &item.CommentCount, &item.ShareCount,
			&item.PostType,
			&item.CreatedAt,
			&item.IsLiked, &item.IsBookmarked, &item.IsFollowing,
		); err != nil {
			continue
		}
		item.AuthorHandle = "@" + strings.TrimPrefix(username, "@")
		item.VideoURL = fullURL(baseURL, vidPath)
		if hlsPath != "" && fileExists(filepath.Join(DataDir, strings.TrimPrefix(hlsPath, "/"))) {
			item.HlsURL = fullURL(baseURL, hlsPath)
		} else {
			item.HlsURL = item.VideoURL
		}
		item.ThumbURL = fullURL(baseURL, thumbPath)
		item.AuthorAvatarURL = fullURL(baseURL, avatarPath)

		feed = append(feed, item)
		nextCursor = item.CreatedAt
	}

	if feed == nil {
		feed = []VideoResponse{}
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"videos":      feed,
		"next_cursor": nextCursor,
	})
}

func (s *Server) handleUserSubroutes(w http.ResponseWriter, r *http.Request) {
	trimmed := strings.TrimPrefix(r.URL.Path, "/api/v1/users/")
	parts := strings.Split(strings.Trim(trimmed, "/"), "/")
	if len(parts) < 2 {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "Endpoint not found"})
		return
	}
	targetUserID := parts[0]
	action := parts[1]

	if action == "follow" && r.Method == http.MethodPost {
		followerID := s.resolveUserID(r)
		s.ensureGuestUser(followerID)

		var exists int
		_ = s.db.QueryRow("SELECT 1 FROM follows WHERE follower_id = ? AND creator_id = ?", followerID, targetUserID).Scan(&exists)
		isFollowing := false
		if exists == 1 {
			_, _ = s.db.Exec("DELETE FROM follows WHERE follower_id = ? AND creator_id = ?", followerID, targetUserID)
			_, _ = s.db.Exec("UPDATE users SET followers_count = MAX(0, followers_count - 1) WHERE id = ?", targetUserID)
			isFollowing = false
		} else {
			_, _ = s.db.Exec("INSERT INTO follows (follower_id, creator_id, created_at) VALUES (?, ?, ?)", followerID, targetUserID, time.Now().Unix())
			_, _ = s.db.Exec("UPDATE users SET followers_count = followers_count + 1 WHERE id = ?", targetUserID)
			isFollowing = true
		}

		var followersCount int
		_ = s.db.QueryRow("SELECT followers_count FROM users WHERE id = ?", targetUserID).Scan(&followersCount)
		writeJSON(w, http.StatusOK, map[string]interface{}{
			"creator_id":      targetUserID,
			"is_following":    isFollowing,
			"followers_count": followersCount,
		})
		return
	}

	writeJSON(w, http.StatusNotFound, map[string]string{"error": "Unknown user action"})
}

func (s *Server) handleVideoSubroutes(w http.ResponseWriter, r *http.Request) {
	trimmed := strings.TrimPrefix(r.URL.Path, "/api/v1/videos/")
	parts := strings.Split(strings.Trim(trimmed, "/"), "/")
	videoID := parts[0]

	if len(parts) == 1 && r.Method == http.MethodDelete {
		s.handleDeleteVideo(w, r, videoID)
		return
	}

	if len(parts) < 2 {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "Endpoint not found"})
		return
	}
	action := parts[1]

	switch action {
	case "like":
		s.handleToggleLike(w, r, videoID)
	case "bookmark":
		s.handleToggleBookmark(w, r, videoID)
	case "share":
		s.handleShare(w, r, videoID)
	case "comments":
		s.handleComments(w, r, videoID)
	case "report":
		s.handleReportVideo(w, r, videoID)
	default:
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "Unknown video action"})
	}
}

func (s *Server) handleDeleteVideo(w http.ResponseWriter, r *http.Request, videoID string) {
	userID := s.resolveUserID(r)
	var ownerID string
	err := s.db.QueryRow("SELECT user_id FROM videos WHERE id = ?", videoID).Scan(&ownerID)
	if err != nil {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "Video tidak ditemukan"})
		return
	}
	// Protect initial seeded videos from accidental deletion, allow user's own uploaded videos
	if (strings.HasPrefix(videoID, "v_") || strings.HasPrefix(videoID, "usr_")) && ownerID == userID {
		_, _ = s.db.Exec("DELETE FROM comments WHERE video_id = ?", videoID)
		_, _ = s.db.Exec("DELETE FROM likes WHERE video_id = ?", videoID)
		_, _ = s.db.Exec("DELETE FROM bookmarks WHERE video_id = ?", videoID)
		_, _ = s.db.Exec("DELETE FROM videos WHERE id = ?", videoID)
		_ = os.Remove(filepath.Join(DataDir, "media", "videos", videoID+".mp4"))
		_ = os.Remove(filepath.Join(DataDir, "media", "videos", videoID+".jpg"))
		_ = os.Remove(filepath.Join(DataDir, "media", "videos", videoID+".png"))
		_ = os.Remove(filepath.Join(DataDir, "media", "videos", videoID+".webp"))
		_ = os.RemoveAll(filepath.Join(DataDir, "media", "hls", videoID))
		_ = os.Remove(filepath.Join(DataDir, "media", "thumbs", videoID+".jpg"))
		_ = os.Remove(filepath.Join(DataDir, "media", "thumbs", videoID+".png"))
		_ = os.Remove(filepath.Join(DataDir, "media", "thumbs", videoID+".webp"))
		writeJSON(w, http.StatusOK, map[string]string{"status": "deleted"})
		return
	}
	writeJSON(w, http.StatusForbidden, map[string]string{"error": "Hanya pemilik konten yang dapat menghapus postingan ini"})
}

func (s *Server) handleReportVideo(w http.ResponseWriter, r *http.Request, videoID string) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "POST required"})
		return
	}
	var body struct {
		Reason string `json:"reason"`
	}
	_ = json.NewDecoder(http.MaxBytesReader(w, r.Body, 2048)).Decode(&body)
	reason := strings.TrimSpace(body.Reason)
	if reason == "" {
		reason = "Konten tidak pantas / spam"
	}
	userID := s.resolveUserID(r)
	repID := "rep_" + strings.ReplaceAll(uuid.New().String()[:10], "-", "")
	_, _ = s.db.Exec("INSERT INTO reports (id, video_id, user_id, reason, created_at) VALUES (?, ?, ?, ?, ?)",
		repID, videoID, userID, reason, time.Now().Unix())
	writeJSON(w, http.StatusOK, map[string]string{
		"status":  "reported",
		"message": "Laporan berhasil dikirim dan akan ditinjau.",
	})
}

func (s *Server) handleToggleLike(w http.ResponseWriter, r *http.Request, videoID string) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "POST required"})
		return
	}
	userID := s.resolveUserID(r)
	s.ensureGuestUser(userID)

	tx, err := s.db.Begin()
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "Transaction error"})
		return
	}
	defer tx.Rollback()

	var exists int
	_ = tx.QueryRow("SELECT 1 FROM likes WHERE user_id = ? AND video_id = ?", userID, videoID).Scan(&exists)

	isLiked := false
	if exists == 1 {
		_, _ = tx.Exec("DELETE FROM likes WHERE user_id = ? AND video_id = ?", userID, videoID)
		_, _ = tx.Exec("UPDATE videos SET like_count = MAX(0, like_count - 1) WHERE id = ?", videoID)
		isLiked = false
	} else {
		_, _ = tx.Exec("INSERT INTO likes (user_id, video_id, created_at) VALUES (?, ?, ?)", userID, videoID, time.Now().Unix())
		_, _ = tx.Exec("UPDATE videos SET like_count = like_count + 1 WHERE id = ?", videoID)
		isLiked = true
	}

	var likeCount int
	_ = tx.QueryRow("SELECT like_count FROM videos WHERE id = ?", videoID).Scan(&likeCount)
	_ = tx.Commit()

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"video_id":   videoID,
		"is_liked":   isLiked,
		"like_count": likeCount,
	})
}

func (s *Server) handleToggleBookmark(w http.ResponseWriter, r *http.Request, videoID string) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "POST required"})
		return
	}
	userID := s.resolveUserID(r)
	s.ensureGuestUser(userID)

	var exists int
	_ = s.db.QueryRow("SELECT 1 FROM bookmarks WHERE user_id = ? AND video_id = ?", userID, videoID).Scan(&exists)

	isBookmarked := false
	if exists == 1 {
		_, _ = s.db.Exec("DELETE FROM bookmarks WHERE user_id = ? AND video_id = ?", userID, videoID)
		isBookmarked = false
	} else {
		_, _ = s.db.Exec("INSERT INTO bookmarks (user_id, video_id, created_at) VALUES (?, ?, ?)", userID, videoID, time.Now().Unix())
		isBookmarked = true
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"video_id":      videoID,
		"is_bookmarked": isBookmarked,
	})
}

func (s *Server) handleShare(w http.ResponseWriter, r *http.Request, videoID string) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "POST required"})
		return
	}
	_, _ = s.db.Exec("UPDATE videos SET share_count = share_count + 1 WHERE id = ?", videoID)
	var shareCount int
	_ = s.db.QueryRow("SELECT share_count FROM videos WHERE id = ?", videoID).Scan(&shareCount)
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"video_id":    videoID,
		"share_count": shareCount,
	})
}

func (s *Server) handleComments(w http.ResponseWriter, r *http.Request, videoID string) {
	baseURL := resolveBaseURL(r)
	callerID := s.resolveUserID(r)

	if r.Method == http.MethodGet {
		rows, err := s.db.Query(`
			SELECT c.id, c.video_id, c.user_id, u.display_name, u.username, u.avatar_url, c.content, c.like_count, c.parent_id, c.created_at
			FROM comments c
			JOIN users u ON c.user_id = u.id
			WHERE c.video_id = ?
			ORDER BY c.created_at ASC
			LIMIT 200
		`, videoID)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "Failed to fetch comments"})
			return
		}
		defer rows.Close()

		var list []CommentResponse
		now := time.Now().Unix()
		for rows.Next() {
			var item CommentResponse
			var username, avatar string
			var parentID sql.NullString
			if err := rows.Scan(&item.ID, &item.VideoID, &item.UserID, &item.AuthorName, &username, &avatar, &item.Content, &item.LikeCount, &parentID, &item.CreatedAt); err != nil {
				continue
			}
			if parentID.Valid && parentID.String != "" {
				p := parentID.String
				item.ParentID = &p
			}
			item.IsMine = (callerID != "" && item.UserID == callerID)
			item.AuthorHandle = "@" + strings.TrimPrefix(username, "@")
			item.AuthorAvatarURL = fullURL(baseURL, avatar)
			item.TimeAgo = formatTimeAgo(now - item.CreatedAt)
			list = append(list, item)
		}
		if list == nil {
			list = []CommentResponse{}
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{
			"comments": list,
		})
		return
	}

	if r.Method == http.MethodPost {
		var body struct {
			Content    string  `json:"content"`
			AuthorName string  `json:"author_name"`
			ParentID   *string `json:"parent_id"`
		}
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8192)).Decode(&body); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Invalid JSON body"})
			return
		}
		content := strings.TrimSpace(body.Content)
		if content == "" || len(content) > 500 {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Komentar harus berisi 1-500 karakter"})
			return
		}

		userID := s.resolveUserID(r)
		s.ensureGuestUserWithName(userID, body.AuthorName)

		var validParentID *string
		if body.ParentID != nil && strings.TrimSpace(*body.ParentID) != "" {
			pID := strings.TrimSpace(*body.ParentID)
			var parentVidID string
			if err := s.db.QueryRow("SELECT video_id FROM comments WHERE id = ?", pID).Scan(&parentVidID); err == nil && parentVidID == videoID {
				validParentID = &pID
			}
		}

		commentID := "c_" + strings.ReplaceAll(uuid.New().String()[:12], "-", "")
		now := time.Now().Unix()

		_, err := s.db.Exec(`INSERT INTO comments (id, video_id, user_id, content, like_count, parent_id, created_at) VALUES (?, ?, ?, ?, 0, ?, ?)`,
			commentID, videoID, userID, content, validParentID, now)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "Gagal menyimpan komentar"})
			return
		}
		_, _ = s.db.Exec(`UPDATE videos SET comment_count = comment_count + 1 WHERE id = ?`, videoID)

		var uName, uHandle, uAvatar string
		_ = s.db.QueryRow(`SELECT display_name, username, avatar_url FROM users WHERE id = ?`, userID).Scan(&uName, &uHandle, &uAvatar)

		var totalComments int
		_ = s.db.QueryRow(`SELECT comment_count FROM videos WHERE id = ?`, videoID).Scan(&totalComments)

		writeJSON(w, http.StatusCreated, map[string]interface{}{
			"comment": CommentResponse{
				ID:              commentID,
				VideoID:         videoID,
				UserID:          userID,
				AuthorName:      uName,
				AuthorHandle:    "@" + strings.TrimPrefix(uHandle, "@"),
				AuthorAvatarURL: fullURL(baseURL, uAvatar),
				Content:         content,
				LikeCount:       0,
				ParentID:        validParentID,
				IsMine:          true,
				CreatedAt:       now,
				TimeAgo:         "Baru saja",
			},
			"comment_count": totalComments,
		})
		return
	}

	writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "Method not allowed"})
}

func (s *Server) handleCommentSubroutes(w http.ResponseWriter, r *http.Request) {
	trimmed := strings.TrimPrefix(r.URL.Path, "/api/v1/comments/")
	commentID := strings.Trim(trimmed, "/")
	if commentID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "ID komentar diperlukan"})
		return
	}

	if r.Method == http.MethodDelete {
		s.handleDeleteComment(w, r, commentID)
		return
	}

	writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "Method not allowed"})
}

func (s *Server) handleDeleteComment(w http.ResponseWriter, r *http.Request, commentID string) {
	callerID := s.resolveUserID(r)
	if callerID == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "Silakan login terlebih dahulu"})
		return
	}

	var authorID, videoID string
	err := s.db.QueryRow("SELECT user_id, video_id FROM comments WHERE id = ?", commentID).Scan(&authorID, &videoID)
	if err != nil {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "Komentar tidak ditemukan"})
		return
	}

	var videoOwnerID string
	_ = s.db.QueryRow("SELECT user_id FROM videos WHERE id = ?", videoID).Scan(&videoOwnerID)

	if callerID != authorID && callerID != videoOwnerID {
		writeJSON(w, http.StatusForbidden, map[string]string{"error": "Anda tidak memiliki izin untuk menghapus komentar ini"})
		return
	}

	_, _ = s.db.Exec("DELETE FROM comments WHERE id = ? OR parent_id = ?", commentID, commentID)

	var updatedCount int
	_ = s.db.QueryRow("SELECT COUNT(*) FROM comments WHERE video_id = ?", videoID).Scan(&updatedCount)
	_, _ = s.db.Exec("UPDATE videos SET comment_count = ? WHERE id = ?", updatedCount, videoID)

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"status":        "deleted",
		"comment_id":    commentID,
		"comment_count": updatedCount,
	})
}

func (s *Server) handleUpload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "POST required"})
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, MaxUploadBytes)
	if err := r.ParseMultipartForm(10 << 20); err != nil {
		writeJSON(w, http.StatusRequestEntityTooLarge, map[string]string{
			"error": "Ukuran file terlalu besar (Maksimal 35 MB)",
		})
		return
	}

	var file multipart.File

	for _, field := range []string{"video", "image", "media", "file"} {
		f, _, e := r.FormFile(field)
		if e == nil {
			file = f
			break
		}
	}
	if file == nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "File media (video atau foto) wajib diunggah"})
		return
	}
	defer file.Close()

	headerBuf := make([]byte, 512)
	n, err := file.Read(headerBuf)
	if err != nil || n < 8 {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "File media tidak valid atau kosong"})
		return
	}

	isVideo := isValidVideoMagicBytes(headerBuf[:n])
	isImage := isValidImageMagicBytes(headerBuf[:n])

	if !isVideo && !isImage {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Format file ditolak: Hanya mendukung video (MP4/MOV/WebM) atau foto (JPEG/PNG/WebP)"})
		return
	}
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "Gagal membaca stream file"})
		return
	}

	description := strings.TrimSpace(r.FormValue("description"))
	if description == "" {
		if isVideo {
			description = "Video baru di-upload dari Android 🚀 #explore #parapenyecroll"
		} else {
			description = "Foto baru dibagikan dari Android 📸 #photography #parapenyecroll"
		}
	}
	musicTitle := strings.TrimSpace(r.FormValue("music_title"))
	if musicTitle == "" {
		musicTitle = "Original Audio • Creator"
	}
	authorName := strings.TrimSpace(r.FormValue("author_name"))

	userID := s.resolveUserID(r)
	s.ensureGuestUserWithName(userID, authorName)

	mediaID := "usr_" + strings.ReplaceAll(uuid.New().String()[:10], "-", "")

	if isImage {
		ext := ".jpg"
		if bytes.Equal(headerBuf[:8], []byte{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}) {
			ext = ".png"
		} else if len(headerBuf) >= 12 && bytes.Equal(headerBuf[0:4], []byte("RIFF")) && bytes.Equal(headerBuf[8:12], []byte("WEBP")) {
			ext = ".webp"
		}

		finalPath := filepath.Join(DataDir, "media", "videos", mediaID+ext)
		thumbPath := filepath.Join(DataDir, "media", "thumbs", mediaID+ext)

		out, err := os.Create(finalPath)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "Gagal menyimpan foto"})
			return
		}
		if _, err := io.Copy(out, file); err != nil {
			out.Close()
			_ = os.Remove(finalPath)
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "Gagal menulis file foto"})
			return
		}
		out.Close()

		_ = copyFile(finalPath, thumbPath)

		now := time.Now().Unix()
		_, err = s.db.Exec(`
			INSERT INTO videos (id, user_id, description, music_title, video_path, hls_path, thumb_path, duration_sec, like_count, comment_count, share_count, status, post_type, created_at)
			VALUES (?, ?, ?, ?, ?, '', ?, 0, 1, 0, 0, 'ready', 'image', ?)`,
			mediaID, userID, description, musicTitle,
			"/media/videos/"+mediaID+ext,
			"/media/thumbs/"+mediaID+ext,
			now,
		)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "Gagal menyimpan metadata postingan"})
			return
		}

		baseURL := resolveBaseURL(r)
		writeJSON(w, http.StatusCreated, map[string]interface{}{
			"status":    "ready",
			"post_type": "image",
			"video_id":  mediaID,
			"video_url": fullURL(baseURL, "/media/videos/"+mediaID+ext),
			"hls_url":   "",
			"thumb_url": fullURL(baseURL, "/media/thumbs/"+mediaID+ext),
		})
		return
	}

	tempPath := filepath.Join(DataDir, "media", "temp", mediaID+"_raw.mp4")

	out, err := os.Create(tempPath)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "Gagal membuat file sementara"})
		return
	}
	if _, err := io.Copy(out, file); err != nil {
		out.Close()
		os.Remove(tempPath)
		writeJSON(w, http.StatusRequestEntityTooLarge, map[string]string{"error": "Upload terputus atau melebihi batas ukuran"})
		return
	}
	out.Close()

	if err := s.processUploadedVideo(UploadJob{
		VideoID:     mediaID,
		TempPath:    tempPath,
		UserID:      userID,
		Description: description,
		MusicTitle:  musicTitle,
	}); err != nil {
		log.Printf("Upload processing error for %s: %v", mediaID, err)
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Gagal memproses video: " + err.Error()})
		return
	}

	baseURL := resolveBaseURL(r)
	writeJSON(w, http.StatusCreated, map[string]interface{}{
		"status":    "ready",
		"post_type": "video",
		"video_id":  mediaID,
		"video_url": fullURL(baseURL, "/media/videos/"+mediaID+".mp4"),
		"hls_url":   fullURL(baseURL, "/media/hls/"+mediaID+"/index.m3u8"),
		"thumb_url": fullURL(baseURL, "/media/thumbs/"+mediaID+".jpg"),
	})
}

func (s *Server) transcodeWorker() {
	for job := range s.uploadQueue {
		if err := s.processUploadedVideo(job); err != nil {
			log.Printf("Worker failed job %s: %v", job.VideoID, err)
		}
	}
}

func (s *Server) processUploadedVideo(job UploadJob) error {
	defer os.Remove(job.TempPath)

	finalMP4 := filepath.Join(DataDir, "media", "videos", job.VideoID+".mp4")
	hlsDir := filepath.Join(DataDir, "media", "hls", job.VideoID)
	_ = os.MkdirAll(hlsDir, 0755)
	m3u8Path := filepath.Join(hlsDir, "index.m3u8")
	thumbPath := filepath.Join(DataDir, "media", "thumbs", job.VideoID+".jpg")

	vaapiAvailable := false
	if _, err := os.Stat("/dev/dri/renderD128"); err == nil {
		vaapiAvailable = true
	}

	encoded := false
	if vaapiAvailable {
		log.Printf("[%s] Encoding video using Intel VAAPI (/dev/dri/renderD128)...", job.VideoID)
		cmd := exec.Command("ffmpeg", "-y",
			"-vaapi_device", "/dev/dri/renderD128",
			"-i", job.TempPath,
			"-t", "60",
			"-map_metadata", "-1",
			"-vf", "format=nv12,hwupload,scale_vaapi=w=-2:h=1280",
			"-c:v", "h264_vaapi", "-b:v", "2000k", "-maxrate", "2500k",
			"-c:a", "aac", "-b:a", "128k",
			"-movflags", "+faststart",
			finalMP4,
		)
		if out, err := cmd.CombinedOutput(); err == nil {
			encoded = true
		} else {
			log.Printf("[%s] VAAPI fallback to software encoder: %v (%s)", job.VideoID, err, string(out))
		}
	}

	if !encoded {
		log.Printf("[%s] Encoding video with fast software H.264...", job.VideoID)
		cmd := exec.Command("ffmpeg", "-y",
			"-i", job.TempPath,
			"-t", "60",
			"-map_metadata", "-1",
			"-vf", "scale=-2:'min(1280,ih)'",
			"-c:v", "libx264", "-preset", "veryfast", "-crf", "24", "-maxrate", "2500k", "-bufsize", "5000k",
			"-c:a", "aac", "-b:a", "128k",
			"-movflags", "+faststart",
			finalMP4,
		)
		if out, err := cmd.CombinedOutput(); err != nil {
			return fmt.Errorf("ffmpeg failed: %v (%s)", err, string(out))
		}
	}

	hlsCmd := exec.Command("ffmpeg", "-y",
		"-i", finalMP4,
		"-c:v", "copy",
		"-c:a", "copy",
		"-f", "hls",
		"-hls_time", "2",
		"-hls_playlist_type", "vod",
		"-hls_flags", "independent_segments",
		"-hls_segment_filename", filepath.Join(hlsDir, "seg_%03d.ts"),
		m3u8Path,
	)
	_ = hlsCmd.Run()

	thumbCmd := exec.Command("ffmpeg", "-y",
		"-ss", "00:00:00.500",
		"-i", finalMP4,
		"-frames:v", "1",
		"-q:v", "4",
		thumbPath,
	)
	_ = thumbCmd.Run()

	now := time.Now().Unix()
	_, err := s.db.Exec(`
		INSERT INTO videos (id, user_id, description, music_title, video_path, hls_path, thumb_path, duration_sec, like_count, comment_count, share_count, status, post_type, created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, 15, 1, 0, 0, 'ready', 'video', ?)`,
		job.VideoID, job.UserID, job.Description, job.MusicTitle,
		"/media/videos/"+job.VideoID+".mp4",
		"/media/hls/"+job.VideoID+"/index.m3u8",
		"/media/thumbs/"+job.VideoID+".jpg",
		now,
	)
	return err
}

func (s *Server) handleGoogleAuth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "POST required"})
		return
	}
	var req struct {
		Email       string `json:"email"`
		DisplayName string `json:"display_name"`
		AvatarURL   string `json:"avatar_url"`
		IDToken     string `json:"id_token"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8192)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Invalid JSON"})
		return
	}

	if strings.TrimSpace(req.IDToken) != "" {
		client := &http.Client{Timeout: 5 * time.Second}
		resp, err := client.Get("https://oauth2.googleapis.com/tokeninfo?id_token=" + url.QueryEscape(req.IDToken))
		if err == nil {
			if resp.StatusCode == http.StatusOK {
				var tokenInfo struct {
					Email   string `json:"email"`
					Name    string `json:"name"`
					Picture string `json:"picture"`
				}
				if err := json.NewDecoder(resp.Body).Decode(&tokenInfo); err == nil && tokenInfo.Email != "" {
					req.Email = tokenInfo.Email
					if req.DisplayName == "" && tokenInfo.Name != "" {
						req.DisplayName = tokenInfo.Name
					}
					if req.AvatarURL == "" && tokenInfo.Picture != "" {
						req.AvatarURL = tokenInfo.Picture
					}
				}
			}
			resp.Body.Close()
		}
	}

	email := strings.ToLower(strings.TrimSpace(req.Email))
	if email == "" || !strings.Contains(email, "@") {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Akun Google tidak valid"})
		return
	}

	localPart := strings.Split(email, "@")[0]
	var cleanChars []rune
	for _, ch := range localPart {
		if (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '.' {
			cleanChars = append(cleanChars, ch)
		}
	}
	username := string(cleanChars)
	if len(username) < 3 {
		username = "user_" + username
	}

	displayName := strings.TrimSpace(req.DisplayName)
	if displayName == "" {
		parts := strings.FieldsFunc(localPart, func(r rune) bool {
			return r == '.' || r == '_' || r == '-'
		})
		for i, p := range parts {
			if len(p) > 0 {
				parts[i] = strings.ToUpper(p[:1]) + p[1:]
			}
		}
		displayName = strings.Join(parts, " ")
		if displayName == "" {
			displayName = username
		}
	}

	var u User
	err := s.db.QueryRow(`SELECT id, username, display_name, avatar_url, bio FROM users WHERE username = ?`, username).
		Scan(&u.ID, &u.Username, &u.DisplayName, &u.AvatarURL, &u.Bio)

	if err != nil {
		u.ID = "g_" + username
		u.Username = username
		u.DisplayName = displayName
		u.AvatarURL = req.AvatarURL
		if u.AvatarURL == "" {
			u.AvatarURL = "/media/thumbs/avatar1.jpg"
		}
		u.Bio = "Kreator Reels"
		_, _ = s.db.Exec(`
			INSERT OR IGNORE INTO users (id, username, display_name, avatar_url, bio, password_hash, created_at)
			VALUES (?, ?, ?, ?, ?, 'google_oauth_no_password', ?)`,
			u.ID, u.Username, u.DisplayName, u.AvatarURL, u.Bio, time.Now().Unix())
	}

	u.AvatarURL = fullURL(resolveBaseURL(r), u.AvatarURL)
	token := generateJWT(u.ID, u.Username)

	var postsCount, followersCount, followingCount int
	_ = s.db.QueryRow("SELECT COUNT(*) FROM videos WHERE user_id = ? AND status = 'ready'", u.ID).Scan(&postsCount)
	_ = s.db.QueryRow("SELECT followers_count FROM users WHERE id = ?", u.ID).Scan(&followersCount)
	_ = s.db.QueryRow("SELECT COUNT(*) FROM follows WHERE follower_id = ?", u.ID).Scan(&followingCount)

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"token":           token,
		"user":            u,
		"posts_count":     postsCount,
		"followers_count": followersCount,
		"following_count": followingCount,
	})
}

func (s *Server) handleRegister(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "POST required"})
		return
	}
	var req struct {
		Username    string `json:"username"`
		DisplayName string `json:"display_name"`
		Password    string `json:"password"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Invalid JSON"})
		return
	}
	username := strings.ToLower(strings.TrimSpace(req.Username))
	if len(username) < 3 || len(req.Password) < 6 {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Username min 3 karakter & password min 6 karakter"})
		return
	}
	displayName := strings.TrimSpace(req.DisplayName)
	if displayName == "" {
		displayName = username
	}

	userID := "u_" + strings.ReplaceAll(uuid.New().String()[:10], "-", "")
	passHash := hashPassword(req.Password)
	avatar := "/media/thumbs/avatar1.jpg"

	_, err := s.db.Exec(`INSERT INTO users (id, username, display_name, avatar_url, password_hash, created_at) VALUES (?, ?, ?, ?, ?, ?)`,
		userID, username, displayName, avatar, passHash, time.Now().Unix())
	if err != nil {
		writeJSON(w, http.StatusConflict, map[string]string{"error": "Username sudah digunakan"})
		return
	}

	token := generateJWT(userID, username)
	writeJSON(w, http.StatusCreated, map[string]interface{}{
		"token": token,
		"user": User{
			ID:          userID,
			Username:    username,
			DisplayName: displayName,
			AvatarURL:   fullURL(resolveBaseURL(r), avatar),
		},
	})
}

func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "POST required"})
		return
	}
	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Invalid JSON"})
		return
	}

	var u User
	var storedHash string
	err := s.db.QueryRow(`SELECT id, username, display_name, avatar_url, bio, password_hash FROM users WHERE username = ?`,
		strings.ToLower(strings.TrimSpace(req.Username))).Scan(&u.ID, &u.Username, &u.DisplayName, &u.AvatarURL, &u.Bio, &storedHash)
	if err != nil || !verifyPassword(req.Password, storedHash) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "Username atau password salah"})
		return
	}

	u.AvatarURL = fullURL(resolveBaseURL(r), u.AvatarURL)
	token := generateJWT(u.ID, u.Username)
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"token": token,
		"user":  u,
	})
}

func (s *Server) handleMedia(w http.ResponseWriter, r *http.Request) {
	relPath := strings.TrimPrefix(r.URL.Path, "/media/")
	cleanRel := filepath.Clean(relPath)
	if strings.Contains(cleanRel, "..") || strings.HasPrefix(cleanRel, "/") || strings.HasPrefix(cleanRel, "\\") {
		http.Error(w, "Forbidden", http.StatusForbidden)
		return
	}

	fullPath := filepath.Join(DataDir, "media", cleanRel)
	info, err := os.Stat(fullPath)
	if err != nil || info.IsDir() {
		http.NotFound(w, r)
		return
	}

	ext := strings.ToLower(filepath.Ext(fullPath))
	switch ext {
	case ".m3u8":
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
		w.Header().Set("Cache-Control", "public, max-age=3600")
	case ".ts":
		w.Header().Set("Content-Type", "video/mp2t")
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	case ".mp4":
		w.Header().Set("Content-Type", "video/mp4")
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	case ".jpg", ".jpeg":
		w.Header().Set("Content-Type", "image/jpeg")
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	case ".apk":
		w.Header().Set("Content-Type", "application/vnd.android.package-archive")
		w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
	}

	f, err := os.Open(fullPath)
	if err != nil {
		http.Error(w, "Internal Error", http.StatusInternalServerError)
		return
	}
	defer f.Close()

	http.ServeContent(w, r, info.Name(), info.ModTime(), f)
}

func (s *Server) resolveUserID(r *http.Request) string {
	authHeader := r.Header.Get("Authorization")
	if strings.HasPrefix(authHeader, "Bearer ") {
		tokenStr := strings.TrimPrefix(authHeader, "Bearer ")
		token, err := jwt.Parse(tokenStr, func(t *jwt.Token) (interface{}, error) {
			if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
				return nil, fmt.Errorf("unexpected signing algorithm: %v", t.Header["alg"])
			}
			return JWTSecret, nil
		})
		if err == nil && token.Valid {
			if claims, ok := token.Claims.(jwt.MapClaims); ok {
				if sub, ok := claims["sub"].(string); ok && sub != "" {
					return sub
				}
			}
		}
	}
	// For unauthenticated/guest callers, only allow client identifiers prefixed with "guest_"
	// Authenticated accounts (Google accounts "g_" or creators "u_") REQUIRE a valid signed JWT token!
	if xUser := strings.TrimSpace(r.Header.Get("X-User-ID")); xUser != "" && len(xUser) <= 64 {
		if strings.HasPrefix(xUser, "guest_") {
			return xUser
		}
	}
	return "guest_" + strings.ReplaceAll(getClientIP(r), ":", "_")
}

func (s *Server) ensureGuestUser(userID string) {
	s.ensureGuestUserWithName(userID, "")
}

func (s *Server) ensureGuestUserWithName(userID, customName string) {
	var exists int
	_ = s.db.QueryRow("SELECT 1 FROM users WHERE id = ?", userID).Scan(&exists)
	if exists == 1 {
		if customName != "" {
			_, _ = s.db.Exec("UPDATE users SET display_name = ? WHERE id = ?", customName, userID)
		}
		return
	}
	suffix := userID
	if len(suffix) > 6 {
		suffix = suffix[len(suffix)-6:]
	}
	username := "user_" + suffix
	displayName := customName
	if displayName == "" {
		displayName = "Kreator #" + suffix
	}
	_, _ = s.db.Exec(`
		INSERT OR IGNORE INTO users (id, username, display_name, avatar_url, password_hash, created_at)
		VALUES (?, ?, ?, '/media/thumbs/avatar3.jpg', 'guest', ?)`,
		userID, username, displayName, time.Now().Unix())
}

func resolveBaseURL(r *http.Request) string {
	if PublicURL != "" {
		return strings.TrimSuffix(PublicURL, "/")
	}
	proto := r.Header.Get("X-Forwarded-Proto")
	if proto == "" {
		if r.TLS != nil || r.Header.Get("X-Forwarded-Ssl") == "on" || strings.Contains(r.Header.Get("CF-Visitor"), "https") {
			proto = "https"
		} else {
			proto = "http"
		}
	}
	return proto + "://" + r.Host
}

func fullURL(base, path string) string {
	if strings.HasPrefix(path, "http://") || strings.HasPrefix(path, "https://") {
		return path
	}
	return strings.TrimSuffix(base, "/") + "/" + strings.TrimPrefix(path, "/")
}

func isValidVideoMagicBytes(buf []byte) bool {
	if len(buf) < 12 {
		return false
	}
	if bytes.Equal(buf[4:8], []byte("ftyp")) || bytes.Equal(buf[4:8], []byte("moov")) || bytes.Equal(buf[4:8], []byte("wide")) || bytes.Equal(buf[4:8], []byte("mdat")) {
		return true
	}
	if buf[0] == 0x1A && buf[1] == 0x45 && buf[2] == 0xDF && buf[3] == 0xA3 {
		return true
	}
	return false
}

func isValidImageMagicBytes(buf []byte) bool {
	if len(buf) < 8 {
		return false
	}
	// JPEG: FF D8 FF
	if buf[0] == 0xFF && buf[1] == 0xD8 && buf[2] == 0xFF {
		return true
	}
	// PNG: 89 50 4E 47 0D 0A 1A 0A
	if bytes.Equal(buf[:8], []byte{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}) {
		return true
	}
	// WebP: RIFF....WEBP
	if len(buf) >= 12 && bytes.Equal(buf[0:4], []byte("RIFF")) && bytes.Equal(buf[8:12], []byte("WEBP")) {
		return true
	}
	return false
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer out.Close()
	_, err = io.Copy(out, in)
	return err
}

func hashPassword(password string) string {
	salt := make([]byte, 16)
	_, _ = rand.Read(salt)
	hash := argon2.IDKey([]byte(password), salt, 2, 19*1024, 1, 32)
	return fmt.Sprintf("%s$%s", base64.RawStdEncoding.EncodeToString(salt), base64.RawStdEncoding.EncodeToString(hash))
}

func verifyPassword(password, encoded string) bool {
	parts := strings.Split(encoded, "$")
	if len(parts) != 2 {
		return false
	}
	salt, err1 := base64.RawStdEncoding.DecodeString(parts[0])
	expectedHash, err2 := base64.RawStdEncoding.DecodeString(parts[1])
	if err1 != nil || err2 != nil {
		return false
	}
	hash := argon2.IDKey([]byte(password), salt, 2, 19*1024, 1, 32)
	return subtle.ConstantTimeCompare(hash, expectedHash) == 1
}

func generateJWT(userID, username string) string {
	claims := jwt.MapClaims{
		"sub":      userID,
		"username": username,
		"exp":      time.Now().Add(30 * 24 * time.Hour).Unix(),
		"iat":      time.Now().Unix(),
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	s, _ := token.SignedString(JWTSecret)
	return s
}

func getClientIP(r *http.Request) string {
	if cfIP := r.Header.Get("CF-Connecting-IP"); cfIP != "" {
		return cfIP
	}
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		return strings.TrimSpace(strings.Split(xff, ",")[0])
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err == nil {
		return host
	}
	return r.RemoteAddr
}

func formatTimeAgo(diffSec int64) string {
	if diffSec < 60 {
		return "Baru saja"
	}
	if diffSec < 3600 {
		return fmt.Sprintf("%dm lalu", diffSec/60)
	}
	if diffSec < 86400 {
		return fmt.Sprintf("%dj lalu", diffSec/3600)
	}
	return fmt.Sprintf("%dh lalu", diffSec/86400)
}

func fileExists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}

func writeJSON(w http.ResponseWriter, status int, payload interface{}) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
