#!/usr/bin/env bash
# Builds dist/OmarchyMusic/: Omarchy Music for Windows with everything it needs
# (Python, PyGObject, GTK 4, libadwaita, mpv, ffmpeg), copied from the MSYS2 UCRT64
# prefix it runs in and laid out the same way (bin/, lib/, share/, etc/), so GLib,
# GTK and Python find their files relative to themselves. Run it in an MSYS2 UCRT64
# shell with the packages the GitHub workflow installs; installer.iss packs the result.
set -euo pipefail
cd "$(dirname "$0")/../.."
PREFIX=${MINGW_PREFIX:-/ucrt64}
OUT=dist/OmarchyMusic
PY=$(python -c 'import sys; print(f"python{sys.version_info.major}.{sys.version_info.minor}")')

SKIP_CYTHON=1 python -m pip install --break-system-packages --no-warn-script-location -r requirements-windows.txt

rm -rf "$OUT"
mkdir -p "$OUT"/{bin,lib,share/glib-2.0,share/icons,etc}

# Programs: Python (pythonw runs the app without a console), mpv plays, ffmpeg takes
# the FLAC out of Tidal's hi-res MP4, gdbus/gspawn are GLib's helpers.
for exe in python.exe pythonw.exe mpv.exe ffmpeg.exe gdbus.exe gspawn-win64-helper.exe gspawn-win64-helper-console.exe; do
    [ -f "$PREFIX/bin/$exe" ] && cp "$PREFIX/bin/$exe" "$OUT/bin/"
done

# Python's library (without its tests and Tk), with PyGObject and the pip modules.
cp -r "$PREFIX/lib/$PY" "$OUT/lib/"
rm -rf "$OUT/lib/$PY"/{test,idlelib,tkinter,turtledemo,ensurepip,lib2to3} "$OUT/lib/$PY"/site-packages/pip
find "$OUT/lib/$PY" -name __pycache__ -type d -prune -exec rm -rf {} +
# Python modules other MSYS2 packages brought along that the app never imports.
rm -rf "$OUT/lib/$PY"/site-packages/{imath*,Imath*,OpenEXR*,numpy*,setuptools*,_distutils_hack,wheel*,pip*,distlib*,distutils-precedence.pth} \
    "$OUT/lib/$PY"/site-packages/{caca*,libxml2*,drv_libxml2.py,VapourSynth*,vapoursynth*,README.txt}
echo "Python packages: $(ls "$OUT/lib/$PY/site-packages" | sed 's/-[0-9].*//' | sort -u | tr '\n' ' ')"

# GObject introspection, image loaders, settings schemas, icons, fonts and TLS roots.
cp -r "$PREFIX/lib/girepository-1.0" "$OUT/lib/"
[ -d "$PREFIX/lib/gdk-pixbuf-2.0" ] && cp -r "$PREFIX/lib/gdk-pixbuf-2.0" "$OUT/lib/"
cp -r "$PREFIX/share/glib-2.0/schemas" "$OUT/share/glib-2.0/"
cp -r "$PREFIX/share/icons/Adwaita" "$PREFIX/share/icons/hicolor" "$OUT/share/icons/"
[ -d "$PREFIX/etc/fonts" ] && cp -r "$PREFIX/etc/fonts" "$OUT/etc/"
[ -d "$PREFIX/etc/ssl" ] && cp -r "$PREFIX/etc/ssl" "$OUT/etc/"

# The DLLs all that loads, and theirs, and no others: ntldd follows the imports of the
# programs, Python's compiled modules and the image loaders; the libraries GObject
# introspection opens by name (Gtk, Adw, Pango…) are listed in the typelibs.
{
    ls "$OUT"/bin/*.exe
    find "$OUT/lib" \( -name '*.pyd' -o -name '*.dll' \)
    for lib in $(cat "$OUT"/lib/girepository-1.0/*.typelib | tr -c 'A-Za-z0-9_.+-' '\n' | grep -E '\.dll$' | sort -u); do
        [ -f "$PREFIX/bin/$lib" ] && echo "$PREFIX/bin/$lib"
    done
} > dist/seeds.txt
for _ in 1 2 3; do   # until nothing new turns up (a library found may need more)
    while read -r f; do ntldd -R "$f" 2>/dev/null; done < dist/seeds.txt \
        | sed -n 's/.*=> \(.*\.dll\) (0x.*/\1/p' | tr '\\' '/' | sort -u > dist/needed.txt
    while read -r dll; do
        name=$(basename "$dll")
        if [ -f "$PREFIX/bin/$name" ] && [ ! -f "$OUT/bin/$name" ]; then
            cp "$PREFIX/bin/$name" "$OUT/bin/"
            echo "$OUT/bin/$name" >> dist/seeds.txt
        fi
    done < dist/needed.txt
done
for lib in $(cat "$OUT"/lib/girepository-1.0/*.typelib | tr -c 'A-Za-z0-9_.+-' '\n' | grep -E '\.dll$' | sort -u); do
    [ -f "$PREFIX/bin/$lib" ] && [ ! -f "$OUT/bin/$lib" ] && cp "$PREFIX/bin/$lib" "$OUT/bin/"
done

# What Windows has no use for: cursor themes (it draws its own), headers and static libraries.
rm -rf "$OUT"/share/icons/Adwaita/cursors
find "$OUT" \( -name '*.a' -o -name '*.h' -o -name '*.pyi' \) -delete
rm -rf "$OUT/lib/$PY"/config-* "$OUT/lib/$PY"/site-packages/*/tests "$OUT/lib/$PY"/site-packages/*/test

# The app, its icon (the window's and the taskbar's), license and readme.
cp omarchy-music LICENSE README.md "$OUT/"
cp packaging/windows/omarchy-music.ico "$OUT/"
mkdir -p "$OUT/share/icons/hicolor/256x256/apps"
cp docs/icon.png "$OUT/share/icons/hicolor/256x256/apps/org.omarchy.music.png"

du -sh "$OUT"
echo "$(ls "$OUT"/bin/*.dll | wc -l) DLLs. The biggest files:"
find "$OUT" -type f -printf '%s\t%P\n' | sort -rn | awk -F'\t' 'NR <= 25 { printf "%8.1f MB  %s\n", $1 / 1048576, $2 }'
