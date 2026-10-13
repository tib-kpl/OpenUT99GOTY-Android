#!/bin/sh
set -eu
cd /game/System
for map in /game/Maps/*.unr.uz; do
    [ -f "$map" ] || continue
    name=$(basename "$map" .uz)
    [ -f "/game/Maps/$name" ] && continue
    cached="/map-cache/$name"
    stamp="$cached.source"
    source=$(stat -L -c '%s:%Y' "$map")
    if [ ! -f "$cached" ] || [ ! -f "$stamp" ] || [ "$(cat "$stamp")" != "$source" ]; then
        echo "Decompression : $name"
        ./ucc-bin-arm64 decompress "$map" -nohomedir
        [ -f "$name" ] || { echo "Carte manquante : $name"; exit 1; }
        mv -f "$name" "$cached"
        echo "$source" > "$stamp"
    fi
    ln -s "$cached" "/game/Maps/$name"
done
if [ -f '/game/Maps/DM-Cybrosis][.unr' ] && [ ! -e '/game/Maps/DOM-Cybrosis][.unr' ]; then
    ln -s 'DM-Cybrosis][.unr' '/game/Maps/DOM-Cybrosis][.unr'
fi
