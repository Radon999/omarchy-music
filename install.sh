#!/usr/bin/env bash
# Install VarMusic for this user: a venv for the Python modules, the
# `omarchy-music` command in ~/.local/bin and a launcher entry. Run it again
# to update the modules. Nothing here touches your music or your logins.
set -euo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
venv=${XDG_DATA_HOME:-$HOME/.local/share}/omarchy-music/venv
bin=$HOME/.local/bin/omarchy-music
desktop=${XDG_DATA_HOME:-$HOME/.local/share}/applications/org.omarchy.music.desktop
icon=${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor/scalable/apps/org.omarchy.music.svg

# GTK 4, libadwaita and PyGObject come from the system; mpv plays, ffmpeg repackages downloads.
missing=()
for cmd in mpv ffmpeg python3; do
  command -v "$cmd" >/dev/null || missing+=("$cmd")
done
python3 -c 'import gi; gi.require_version("Gtk", "4.0"); gi.require_version("Adw", "1")' 2>/dev/null \
  || missing+=("python-gobject gtk4 libadwaita")
if ((${#missing[@]})); then
  echo "Missing: ${missing[*]}"
  echo "On Arch/Omarchy: sudo pacman -S --needed mpv ffmpeg python-gobject gtk4 libadwaita"
  exit 1
fi

# --system-site-packages so the venv sees the system's PyGObject.
[[ -x $venv/bin/python ]] || python3 -m venv --system-site-packages "$venv"
"$venv/bin/pip" install --quiet --upgrade pip
"$venv/bin/pip" install --quiet --upgrade -r "$repo/requirements.txt"

# A wrapper rather than a symlink, so the app runs on the venv's Python.
mkdir -p "$(dirname "$bin")"
[[ -L $bin ]] && rm "$bin"
cat >"$bin" <<EOF
#!/bin/sh
exec "$venv/bin/python" "$repo/omarchy-music" "\$@"
EOF
chmod +x "$bin"

mkdir -p "$(dirname "$icon")"
cp "$repo/packaging/icon.svg" "$icon"
gtk-update-icon-cache -qtf "${icon%/scalable/*}" 2>/dev/null || true

mkdir -p "$(dirname "$desktop")"
cat >"$desktop" <<EOF
[Desktop Entry]
Type=Application
Name=VarMusic
GenericName=Music Player
Comment=Lossless music popup for YouTube Music, Tidal, Apple Music, Spotify and your own files
Exec=$bin
Icon=org.omarchy.music
Categories=AudioVideo;Audio;Player;
StartupWMClass=org.omarchy.music
EOF

echo "Installed: $bin"
case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *) echo "Add ~/.local/bin to your PATH." ;; esac
