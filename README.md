# Para Penyecroll

Aplikasi vertical short-form video feed full-stack yang terdiri dari klien Android native dan backend streaming video berbasis Go.

## Arsitektur

### 1. Android Client (`app/`)
- **UI & Navigation**: Jetpack Compose dengan Material 3, Edge-to-Edge display, dan Bottom Navigation.
- **Playback Engine**: AndroidX Media3 (ExoPlayer) dengan orientasi vertikal (`VerticalPager`) dan pre-buffering halaman (`beyondBoundsPageCount = 1`).
- **Media Cache**: LRU Disk Cache berbasis `SimpleCache` dan `LeastRecentlyUsedCacheEvictor` (100 MB) untuk meminimalkan beban jaringan saat video diputar ulang.
- **Interaksi**: Double-tap gesture like, navigasi komentar bertingkat (threaded comments), toggle bookmark, dan follow kreator.
- **Media Upload**: Picker media galeri untuk video (MP4) dan foto dengan chunked streaming multipart upload.
- **Autentikasi**: Integrasi akun Google via Android AccountManager dengan fallback sign-in lokal.

### 2. Backend Service (`backend/`)
- **Runtime**: Go binary dengan HTTP router bawaan `net/http`.
- **Database**: SQLite embedded menggunakan driver pure-Go (`modernc.org/sqlite`) dengan konfigurasi `PRAGMA journal_mode = WAL`, `synchronous = NORMAL`, dan memory-mapped IO (`mmap_size`).
- **Transcoding Pipeline**: FFmpeg untuk konversi video ke format MP4 faststart dan HTTP Live Streaming (HLS `.m3u8` + segmen `.ts`), dengan dukungan akselerasi hardware Intel VAAPI (`/dev/dri/renderD128`) jika perangkat keras tersedia.
- **Validasi Media**: Pengecekan magic bytes (header binary) sebelum file diproses ke disk untuk mencegah upload file berbahaya.
- **Keamanan & Rate Limiter**: Autentikasi berbasis token JWT, sanitasi path traversal pada static media server, dan token-bucket rate limiter per alamat IP.

## Menjalankan Backend

### Prasyarat
- Docker dan Docker Compose, atau
- Go 1.22+ dan FFmpeg terpasang di sistem host.

### Menjalankan dengan Docker Compose
```bash
cd backend
docker compose up -d --build
```

Backend akan berjalan secara default pada port `5050`.

### Menjalankan Langsung (Host)
```bash
cd backend
go run main.go
```

### Environment Variables
| Variabel | Default | Keterangan |
|---|---|---|
| `PORT` | `5050` | Port listen HTTP server |
| `DATA_DIR` | `/data` (Docker) atau `./data` | Direktori penyimpanan database SQLite dan file media |
| `PUBLIC_URL` | Kosong | URL publik dasar (contoh: reverse proxy HTTPS) untuk resolusi tautan absolut |
| `JWT_SECRET` | Otomatis | Secret key untuk penandatanganan token JWT |

## Konfigurasi Klien Android

Endpoint backend pada klien Android dikonfigurasi melalui `VideoApiClient.kt`:
- **Emulator Android**: Gunakan `http://10.0.2.2:5050` untuk mengakses server di mesin host.
- **Perangkat Fisik**: Gunakan IP jaringan lokal mesin host (contoh: `http://192.168.x.x:5050`) atau reverse proxy HTTPS yang telah dikonfigurasi.

Kompilasi aplikasi dapat dijalankan melalui Gradle:
```bash
./gradlew assembleDebug
```

File APK hasil build berada di direktori `app/build/outputs/apk/debug/`.

## Struktur Direktori
```
ParaPenyecroll/
├── app/                  # Sumber kode modul Android (Jetpack Compose)
│   └── src/main/java/com/project/videofeed/
│       ├── model/        # Data model (VideoModel, CommentModel)
│       ├── network/      # HTTP client dan session manager
│       ├── player/       # ExoPlayer cache configuration
│       └── ui/           # Composable screen dan reusable components
├── backend/              # Sumber kode server Go dan Docker setup
│   ├── main.go           # HTTP handler, database, dan transcoding worker
│   ├── Dockerfile        # Container build definition
│   └── docker-compose.yml
└── README.md
```
