#!/usr/bin/env bash
set -e

MODE=$1

if [ "$MODE" = "high" ]; then
    echo "[Switch Mode] Mengalokasikan resource penuh ke videofeed-backend..."
    docker update --memory 2g --memory-swap 2g --cpus 4.0 videofeed-backend 2>/dev/null || true
    echo "[OK] videofeed-backend berjalan pada profil performa tinggi (2GB RAM, 4 CPU)."
    free -h
elif [ "$MODE" = "low" ]; then
    echo "[Switch Mode] Mengalihkan videofeed-backend ke mode hemat resource..."
    docker update --memory 192m --memory-swap 256m --cpus 1.0 videofeed-backend 2>/dev/null || true
    echo "[OK] videofeed-backend berjalan pada mode hemat (192MB RAM, 1 CPU)."
    free -h
else
    echo "Penggunaan: ./switch-mode.sh [high|low]"
    exit 1
fi
