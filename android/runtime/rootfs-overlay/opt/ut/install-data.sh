#!/bin/sh
# Extract official OldUnreal game media into the chosen destination.
set -eu
ISO=${1:?ISO}; DEST=${2:?destination}; WORK=${3:?work directory}
[ -f "$ISO" ] || exit 2
mkdir -p "$DEST" "$WORK"
: > "$DEST/.nomedia"
echo "STEP 1 Extraction des donnees"
7z x -y -bso0 -bsp0 -o"$WORK" "$ISO" "Help/*"
if [ -d "$WORK/Help" ]; then mkdir -p "$DEST/Help"; cp -rf "$WORK/Help/." "$DEST/Help/"; fi
7z x -y -bso0 -bsp0 -o"$WORK" "$ISO" "Maps/*"
if [ -d "$WORK/Maps" ]; then mkdir -p "$DEST/Maps"; cp -rf "$WORK/Maps/." "$DEST/Maps/"; fi
7z x -y -bso0 -bsp0 -o"$WORK" "$ISO" "Music/*"
if [ -d "$WORK/Music" ]; then mkdir -p "$DEST/Music"; cp -rf "$WORK/Music/." "$DEST/Music/"; fi
7z x -y -bso0 -bsp0 -o"$WORK" "$ISO" "Sounds/*"
if [ -d "$WORK/Sounds" ]; then mkdir -p "$DEST/Sounds"; cp -rf "$WORK/Sounds/." "$DEST/Sounds/"; fi
7z x -y -bso0 -bsp0 -o"$WORK" "$ISO" "System/*"
if [ -d "$WORK/System" ]; then mkdir -p "$DEST/System"; cp -rf "$WORK/System/." "$DEST/System/"; fi
7z x -y -bso0 -bsp0 -o"$WORK" "$ISO" "Textures/*"
if [ -d "$WORK/Textures" ]; then mkdir -p "$DEST/Textures"; cp -rf "$WORK/Textures/." "$DEST/Textures/"; fi
7z x -y -bso0 -bsp0 -o"$WORK" "$ISO" "Web/*"
if [ -d "$WORK/Web" ]; then mkdir -p "$DEST/Web"; cp -rf "$WORK/Web/." "$DEST/Web/"; fi
echo "STEP 2 Bonus Pack 4"
7z x -y -bso0 -bsp0 -o"$DEST" "${4:?Bonus Pack 4}"
rm -f "$DEST/System/"*.exe "$DEST/System/"*.dll "$DEST/System/"*.bat
sync
echo "STEP 4 Termine"
