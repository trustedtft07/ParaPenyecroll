import os
import sys
import subprocess
import sqlite3
import time

DATA_DIR = os.getenv("DATA_DIR", "./data")
TEMP_DIR = os.path.join(DATA_DIR, "media", "temp")
VIDEOS_DIR = os.path.join(DATA_DIR, "media", "videos")
HLS_DIR = os.path.join(DATA_DIR, "media", "hls")
THUMBS_DIR = os.path.join(DATA_DIR, "media", "thumbs")
DB_PATH = os.path.join(DATA_DIR, "db", "videofeed.db")

subprocess.run(["docker", "exec", "videofeed-backend", "chmod", "-R", "777", "/data"], check=False)

os.makedirs(TEMP_DIR, exist_ok=True)
os.makedirs(VIDEOS_DIR, exist_ok=True)
os.makedirs(HLS_DIR, exist_ok=True)
os.makedirs(THUMBS_DIR, exist_ok=True)

# v6 and v7 are short YouTube Shorts (fast download & encode)
new_clips = [
    {
        "id": "v6",
        "yt_id": "f-2_KYwqcy8",
        "title": "Motor Baru Pakcik Rusdi Ngawi 🏍️ #rezaauditore #shorts #jomok",
        "music": "Suara Knalpot • @aldeanHaters",
        "author_id": "u_aldean",
        "author_name": "Aldean Haters Clip",
        "author_handle": "aldean_haters",
        "author_avatar": "/media/thumbs/avatar1.jpg",
        "likes": 18750,
        "shares": 5120
    },
    {
        "id": "v7",
        "yt_id": "08BiykO1eTw",
        "title": "Minta Peluk Loh Ya.. Reza Salting Ketemu Doksli 😳 #rezaauditore #clips",
        "music": "Audio Asli • Meddytore",
        "author_id": "u_meddytore",
        "author_name": "Meddytore Clip",
        "author_handle": "meddytore",
        "author_avatar": "/media/thumbs/avatar2.jpg",
        "likes": 14200,
        "shares": 3980
    }
]

for clip in new_clips:
    vid_id = clip["id"]
    yt_id = clip["yt_id"]
    raw_v = os.path.join(TEMP_DIR, f"{vid_id}_v.mp4")
    raw_a = os.path.join(TEMP_DIR, f"{vid_id}_a.m4a")
    out_mp4 = os.path.join(VIDEOS_DIR, f"{vid_id}.mp4")
    out_hls_dir = os.path.join(HLS_DIR, vid_id)
    out_thumb = os.path.join(THUMBS_DIR, f"{vid_id}.jpg")

    os.makedirs(out_hls_dir, exist_ok=True)

    print(f"\n[{vid_id}] 1. Downloading Video stream for {yt_id}...")
    subprocess.run([
        "python3", "-m", "yt_dlp",
        "--js-runtimes", "node:/usr/bin/node",
        "-f", "bestvideo[height<=1280]/bestvideo",
        "--no-playlist",
        "-o", raw_v,
        f"https://www.youtube.com/watch?v={yt_id}"
    ], check=False)

    print(f"[{vid_id}] 2. Downloading Audio stream for {yt_id}...")
    subprocess.run([
        "python3", "-m", "yt_dlp",
        "--js-runtimes", "node:/usr/bin/node",
        "-f", "140/bestaudio",
        "--no-playlist",
        "-o", raw_a,
        f"https://www.youtube.com/watch?v={yt_id}"
    ], check=False)

    if not os.path.exists(raw_v):
        print(f"Skipping {vid_id} video stream failed")
        continue

    print(f"[{vid_id}] 3. Merging & Transcoding to vertical 9:16 (720x1280)...")
    cmd = [
        "docker", "exec", "videofeed-backend", "ffmpeg", "-y",
        "-i", f"/data/media/temp/{vid_id}_v.mp4"
    ]
    if os.path.exists(raw_a):
        cmd.extend(["-i", f"/data/media/temp/{vid_id}_a.m4a"])
    cmd.extend([
        "-vf", "scale=720:1280:force_original_aspect_ratio=decrease,pad=720:1280:(ow-iw)/2:(oh-ih)/2:black,setsar=1",
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
        "-c:a", "aac", "-b:a", "128k",
        "-movflags", "+faststart",
        f"/data/media/videos/{vid_id}.mp4"
    ])
    subprocess.run(cmd, check=True)

    print(f"[{vid_id}] 4. Generating HLS segments...")
    subprocess.run([
        "docker", "exec", "videofeed-backend", "ffmpeg", "-y",
        "-i", f"/data/media/videos/{vid_id}.mp4",
        "-c:v", "copy", "-c:a", "copy",
        "-f", "hls", "-hls_time", "2",
        "-hls_playlist_type", "vod",
        "-hls_flags", "independent_segments",
        "-hls_segment_filename", f"/data/media/hls/{vid_id}/seg_%03d.ts",
        f"/data/media/hls/{vid_id}/index.m3u8"
    ], check=True)

    print(f"[{vid_id}] 5. Extracting thumbnail...")
    subprocess.run([
        "docker", "exec", "videofeed-backend", "ffmpeg", "-y",
        "-ss", "00:00:01.000",
        "-i", f"/data/media/videos/{vid_id}.mp4",
        "-frames:v", "1", "-q:v", "4",
        f"/data/media/thumbs/{vid_id}.jpg"
    ], check=True)

    if os.path.exists(raw_v):
        os.remove(raw_v)
    if os.path.exists(raw_a):
        os.remove(raw_a)
    print(f"[{vid_id}] SUCCESS!")

all_clips = [
    {
        "id": "v1",
        "title": "Awal Mula Kenal Doksli Jomok 😱 #rezaauditore #clips #doksli",
        "music": "Audio Asli • @sungut_lele",
        "author_id": "u_sungut",
        "author_name": "Sungut Lele Clip",
        "author_handle": "sungut_lele",
        "author_avatar": "/media/thumbs/avatar1.jpg",
        "likes": 5420,
        "shares": 1280
    },
    {
        "id": "v2",
        "title": "Reza Imut Doksli Lolos Mediashare ✨ #rezaauditore #shorts",
        "music": "Suara Asli • Mipa Clipper",
        "author_id": "u_mipa",
        "author_name": "Mipa Clipper",
        "author_handle": "mipa_clipper",
        "author_avatar": "/media/thumbs/avatar2.jpg",
        "likes": 8940,
        "shares": 2410
    },
    {
        "id": "v3",
        "title": "Apakah Ini Doksli A Day In My Life.. 😭 #rezaauditore #adayinmylife",
        "music": "A Day In My Life • Sound",
        "author_id": "u_lifeupdate",
        "author_name": "Life Update",
        "author_handle": "life_update",
        "author_avatar": "/media/thumbs/avatar3.jpg",
        "likes": 12300,
        "shares": 3890
    },
    {
        "id": "v4",
        "title": "REZA AUDITORE, META JOMOK MENGGILA 💀 #memes #shorts #lucu",
        "music": "Original Sound • Doksilindo",
        "author_id": "u_doksilindo",
        "author_name": "Doksilindo",
        "author_handle": "doksilindo",
        "author_avatar": "/media/thumbs/avatar4.jpg",
        "likes": 16400,
        "shares": 4520
    },
    {
        "id": "v5",
        "title": "Gudang Doksli Lolos Lagi Brok 😂 Momen Reza Auditore #adngeclip",
        "music": "Original Sound • ADngeclip",
        "author_id": "u_adngeclip",
        "author_name": "ADngeclip",
        "author_handle": "adngeclip",
        "author_avatar": "/media/thumbs/avatar5.jpg",
        "likes": 22100,
        "shares": 6730
    },
    {
        "id": "v6",
        "title": "Motor Baru Pakcik Rusdi Ngawi 🏍️ #rezaauditore #shorts #jomok",
        "music": "Suara Knalpot • @aldeanHaters",
        "author_id": "u_aldean",
        "author_name": "Aldean Haters Clip",
        "author_handle": "aldean_haters",
        "author_avatar": "/media/thumbs/avatar1.jpg",
        "likes": 18750,
        "shares": 5120
    },
    {
        "id": "v7",
        "title": "Minta Peluk Loh Ya.. Reza Salting Ketemu Doksli 😳 #rezaauditore #clips",
        "music": "Audio Asli • Meddytore",
        "author_id": "u_meddytore",
        "author_name": "Meddytore Clip",
        "author_handle": "meddytore",
        "author_avatar": "/media/thumbs/avatar2.jpg",
        "likes": 14200,
        "shares": 3980
    }
]

comments_pool = [
    "minimal sensor brok, layar gw diliatin emak gw wkwkwk 😭",
    "filter mediashare kick jebol lagi loh ya 😂",
    "lah kocak mukanya langsung pucet panik nyari tombol skip",
    "AMBATUKAM momen paling ditunggu se-Indonesia raya 💀",
    "info donasi mas rusdi ngawi 5 ribuan merusak mental",
    "ciee salting mas reza liat doksli mas iron wkwk",
    "gua laporkan ke kominfo lu ya brok wkwkwk",
    "capek banget ketawa tengah malem gegara jomok 🤣",
    "streamer paling apes kalau udah buka mediashare",
    "mas rusdi tersenyum melihat keapesan ini",
    "detik detik reza auditore kehilangan kewarasan diri",
    "ambatunat menangis di pojokan melihat keganasan penonton",
    "anjir kaget gw kira jumpscare serem taunya doksli mas ambasing",
    "reza amba mode on 🔥 gacor kang",
    "pria cikini datang lagi membawa kehancuran mental",
    "sensornya telat 3 detik brok udah keburu keliatan wkwk",
    "mata saya ternodai tapi nagih nontonnya 😭",
    "reaksi bang reza emang ga pernah gagal bikin ngakak",
    "siapa yang ngirim woy ngaku lu di live chat wkwk",
    "keringat dingin langsung keluar dari dahi reza auditore 😭",
    "doksli lolos sensor = rezeki clipper YouTube Shorts 💸",
    "auto subscribe clipper reza, hiburan pengantar tidur"
]

print("\n=== UPDATING SQLITE DATABASE ===")
subprocess.run(["docker", "exec", "videofeed-backend", "chmod", "-R", "777", "/data"], check=False)

conn = sqlite3.connect(DB_PATH)
cursor = conn.cursor()

cursor.execute("DELETE FROM comments WHERE video_id LIKE 'v%'")
cursor.execute("DELETE FROM likes WHERE video_id LIKE 'v%'")
cursor.execute("DELETE FROM bookmarks WHERE video_id LIKE 'v%'")
cursor.execute("DELETE FROM videos WHERE id LIKE 'v%'")

now = int(time.time())

for clip in all_clips:
    cursor.execute("""
        INSERT OR REPLACE INTO users (id, username, display_name, avatar_url, bio, password_hash, followers_count, created_at)
        VALUES (?, ?, ?, ?, ?, 'google_oauth_no_password', ?, ?)
    """, (
        clip["author_id"],
        clip["author_handle"],
        clip["author_name"],
        clip["author_avatar"],
        f"Official Clipper & Highlight {clip['author_name']} 🎬",
        clip["likes"] * 3,
        now - 86400 * 30
    ))

for idx, clip in enumerate(all_clips):
    vid_id = clip["id"]
    cursor.execute("""
        INSERT OR REPLACE INTO videos (
            id, user_id, description, music_title, video_path, hls_path, thumb_path,
            duration_sec, like_count, comment_count, share_count, status, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, 25, ?, ?, ?, 'ready', ?)
    """, (
        vid_id,
        clip["author_id"],
        clip["title"],
        clip["music"],
        f"/media/videos/{vid_id}.mp4",
        f"/media/hls/{vid_id}/index.m3u8",
        f"/media/thumbs/{vid_id}.jpg",
        clip["likes"],
        6,
        clip["shares"],
        now - (idx * 3600 * 2)
    ))

comment_idx = 1
for clip in all_clips:
    vid_id = clip["id"]
    for c_i in range(6):
        c_text = comments_pool[(comment_idx - 1) % len(comments_pool)]
        c_id = f"c_doksli_{comment_idx}"
        u_id = f"u_fan_{((comment_idx * 7) % 15) + 1}"
        u_name = f"Penonton #{((comment_idx * 3) % 20) + 1}"
        u_handle = f"rusdi_enjoyer_{((comment_idx * 3) % 20) + 1}"

        cursor.execute("""
            INSERT OR IGNORE INTO users (id, username, display_name, avatar_url, bio, password_hash, created_at)
            VALUES (?, ?, ?, '/media/thumbs/avatar2.jpg', 'Penonton setia live Reza Auditore', 'guest', ?)
        """, (u_id, u_handle, u_name, now - 86400 * 10))

        cursor.execute("""
            INSERT OR REPLACE INTO comments (id, video_id, user_id, content, like_count, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
        """, (
            c_id,
            vid_id,
            u_id,
            c_text,
            ((comment_idx * 17) % 150) + 5,
            now - (c_i * 600 + 120)
        ))
        comment_idx += 1

conn.commit()
conn.close()

subprocess.run(["docker", "exec", "videofeed-backend", "chmod", "-R", "777", "/data"], check=False)
print("\n=== ALL 7 VIDEOS AND MEME COMMENTS ARE FULLY SEEDED IN DATABASE! ===")
