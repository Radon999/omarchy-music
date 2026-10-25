# Omarchy Music

A small music popup for [Omarchy](https://omarchy.org/) (Hyprland), themed live from the
Omarchy theme. You browse YouTube Music, Tidal, Apple Music or Spotify, plus the music
in your own folders. Everything plays in lossless FLAC from Tidal where Tidal has it.

It is a single Python/GTK 4 file: `omarchy-music`. It also runs on **Windows**, and there is
an **Android** app.

**Downloads** (Windows installer, Android APK) are on the
[download page](https://varixxl.github.io/omarchy-music/) and the
[releases page](https://github.com/Varixxl/omarchy-music/releases).

## Features

- **Four services, one player.** Switch between them at the top. Each song is matched on
  Tidal (by ISRC when the service has one, otherwise by title, artist and length) and
  **streamed in FLAC**, hi-res (up to 24-bit/192 kHz) where Tidal has it.
- **Lossy only as a fallback.** A song Tidal has no FLAC of streams in what there is: Tidal's
  AAC (320 kbps), else YouTube Music's own audio (Opus, about 130–160 kbps). The mark by the
  controls then shows **AAC** or **Opus** instead of **⤓ FLAC**. Without Tidal, everything
  streams from YouTube Music. Downloads are always FLAC.
- **Search Tidal, YouTube Music or your own songs.** Pick where with the menu next to the
  search bar. Results show matching artists and albums above the songs. A pasted link always
  opens on its own service. Without Tidal connected, a Tidal search goes to YouTube Music.
- **Download when you choose.** Click the **⤓ FLAC** mark next to the controls, or pick
  **Download** in a song's menu (or for a selection), to save the FLAC and play it from disk.
- **Nothing downloads on its own.** **Keep** (in the accounts menu) saves every song you
  play. Each service's **Sync** switch saves all of its playlists. Both are off to start.
- **Apple Music.** Browse and search your Apple Music library and playlists. Apple's own audio
  is locked with DRM (FairPlay), so Apple songs stream and download from the same recording
  on Tidal, matched by ISRC.
- **Local files.** Songs in your folders (`~/Music` to start; add more in the accounts menu)
  appear in the Music tab beside your downloads, with the covers they carry.
- **Music tab:**
  - **All:** every song in one list, sorted by **title**, **artist** or **album** (grouped
    under headings; click an artist's for their page), or newest first (**Added**).
  - **Playlists:** your playlists.
  - **Recent:** recently played.
  - **Most played:** with Last.fm connected, your most scrobbled songs from anywhere. Without
    it, the songs played here (a song counts after 30 seconds, or half of a shorter song).
  - **DJ:** like Spotify's DJ, in text. Sets of six songs, each begun with a line: what you've
    had on repeat, new songs from Tidal's and YouTube Music's radios, throwbacks, deep cuts of
    artists you play, your top genre and more like what's playing (from your Last.fm history
    when it's connected, else what you played here). **Switch it up** starts another kind of
    set, and the box under it asks the DJ for a mood, a decade, a genre or an artist.
    **From** (or **DJ** on a playlist's or album's page) has it play only some of your songs:
    all your music, one of your folders or a folder in one, a playlist, an album, or any folder
    you choose. It then makes sets of just those: your favourites, ones you haven't played, a
    few by one artist, ones you haven't heard in a while, and a mix.
- **Playlists.** Create, rename and delete them, and add or remove songs. They are stored in
  `playlists.json`.
- **Import .m3u8 playlists.** Pick **Import a playlist (.m3u8)** under Playlists, for example
  a playlist exported from Poweramp or foobar2000. Each song is found as the file the playlist
  names (or a file of the same name), else among your songs, else on Tidal (YouTube Music
  without Tidal). The playlist is named after the file. Songs not found are counted in a message.
- **Multi-select.** Ctrl/Shift+click, or right-click and pick **Select…**, then queue, add to a
  playlist, remove or download many songs at once. Ctrl+A selects all and Esc stops selecting.
- **Artist pages.** Click any artist name to see their songs and albums.
- **Album-art colours.** The window takes its tint from the cover of what's playing. You can
  switch this off in the accounts menu.
- **EQ:**
  - A 10-band equalizer with presets.
  - Headphone correction curves.
  - **Bass** and **Treble** shelves.
- **Lyrics.** Genius lyrics, or lines timed to the music (saved as `.lrc` files).
- **Background tagging.** Saved songs get genre, composer, label, MusicBrainz IDs and a big
  cover.
- **Last.fm.** Plays are scrobbled, and **Wrapped** looks back on any week, month or year of your history (full screen with F11).
- **Desktop controls.** Media keys work through MPRIS (playerctl) on Linux, and directly on
  Windows. The `omarchy-music`
  command has a CLI (`omarchy-music help`).
- **Phone remote.** The Android app can play on the desktop instead of the phone (see below).
- **Updates.** Each app checks GitHub for a new release at start and every 12 hours. When there
  is one, **Update** appears at the top of the accounts menu; nothing installs until you pick
  it. On Linux it moves your git clone to the release (if you haven't changed it) and runs
  `install.sh` again; on Windows it runs the new installer, which reopens the app; on Android
  the system installer asks you to confirm.

## Android app

`android/` holds the Android version (Kotlin, Jetpack Compose, Media3). It has the same
services, Tidal FLAC streaming and downloads, Music tab, playlists, EQ with the headphone
curves, lyrics, Last.fm and Wrapped, the search menu, .m3u8 import and the DJ. It is a separate app, package `org.omarchy.music`.
What's playing shows in the notification shade and on the lock screen, the desktop's song
too while the phone is its remote.

Build it with JDK 17+ and the Android SDK (platform 35):

```sh
cd android
tools/tidal-keys.sh          # writes tidal.properties (gitignored) from the tidalapi package
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`./gradlew assembleRelease` signs with the key named in
`~/.local/share/omarchy-music/keystore.properties` (`storeFile`, `storePassword`, `keyAlias`,
`keyPassword`). That file and the key stay outside the repository. Without them, the
release build is signed with the debug key. A phone with a debug build installed has to
uninstall it once before it takes a release build, because the signatures differ.

On the phone, YouTube Music and Apple Music sign in on their own pages inside the app.
Tidal, Spotify and Last.fm connect the same way as on the desktop. Downloads go to
`Music/Omarchy Music/` on the phone. The Music tab reads `Music/` to start; **Choose
folders** there (or **Folders** in the accounts menu) picks which folders it reads.

**History** in the accounts menu adds your Spotify listening to Wrapped: import the zip
from Spotify's data download (<https://www.spotify.com/account/privacy/>, the extended
streaming history or the past year's), and with Spotify connected your last plays come in
each time the app starts. A play Last.fm already has counts once. Nothing is sent to
Last.fm, which takes nothing older than two weeks.

### Controlling the desktop from the phone

1. On the desktop, switch on **Phone** in the accounts menu. It shows a six-digit code and
   the computer's address.
2. On the phone, open the accounts menu (☰). Under **Play on**, pick the desktop (it is found
   on your network through Avahi) or type its address, then enter the code.

While **Play on** points at the desktop, the phone's now-playing bar, Up next and Lyrics
follow the desktop, and every play, queue and button goes there. Songs stored on the phone
itself play on the desktop too: the desktop streams each one from the phone (port 8899 on
the phone, while Play on points at the desktop). Each song's address carries a code only
the phone can make, and the desktop takes such songs only from the phone that sent them.
Keep the phone app running and on the same network while they play.

The remote listens on TCP port 8897 and answers only paired phones. It is plain HTTP, so use
it on a network you trust. Omarchy's firewall blocks the port until you allow it, for
example:

```sh
sudo ufw allow from 192.168.1.0/24 to any port 8897 proto tcp
sudo ufw allow from 192.168.1.0/24 to any port 5353 proto udp   # finding it without typing the address
```

**Forget the paired phones** in the accounts menu signs every phone out.

## Install

```sh
sudo pacman -S --needed mpv ffmpeg python-gobject gtk4 libadwaita
git clone https://github.com/Varixxl/omarchy-music.git ~/Projects/omarchy-music
~/Projects/omarchy-music/install.sh
```

`install.sh` does three things:

- creates a venv in `~/.local/share/omarchy-music/venv` with the modules in
  `requirements.txt`;
- writes `~/.local/bin/omarchy-music`;
- adds a launcher entry.

Run it again to update the modules.

For the dropdown look, add a Hyprland rule for the class `org.omarchy.music`, for example:

```lua
o.window({ class = "org.omarchy.music" }, {
  float = true,
  size = { 420, 680 },
  move = { "(monitor_w*0.5-window_w*0.5)", "(36)" },
  animation = "slide top",
})
```

Then bind a key to `~/.local/bin/omarchy-music`. Running the command again toggles the
popup.

## Windows

Get `OmarchyMusic-Setup.exe` from the releases page. It installs for your account only
(no administrator rights needed) and adds Omarchy Music to the Start menu. It's the same app
in a normal window, with Python, GTK 4, mpv and ffmpeg bundled. Media keys control it
directly.

The installer is built by GitHub Actions (`.github/workflows/windows.yml`) in MSYS2:
`packaging/windows/build.sh` gathers the app with everything it runs on, and
`packaging/windows/installer.iss` (Inno Setup) packs it. Pushing a `v*` tag builds it and
attaches it to that release. Running the workflow by hand gives the installer as a build
artifact.

On Windows, sign in to YouTube Music and Apple Music in **Firefox**. Chrome and Edge encrypt
their cookies so that only they can read them.

## Accounts

Open the accounts menu with the ☰ button, or run `omarchy-music accounts`.

- **YouTube Music / Apple Music.** Sign in at music.youtube.com and music.apple.com in
  Chromium (Firefox on Windows). The app reads those sign-ins from the browser's cookies.
- **Tidal** (for FLAC; without it songs stream from YouTube Music). Click **Connect**, or run `omarchy-music --tidal-login`.
  Then sign in on Tidal's page and paste back the address it ends on. Lossless needs a Tidal
  subscription.
- **Spotify** (optional). Spotify only lets each person use their own developer app:
  1. Create an app at <https://developer.spotify.com/dashboard>.
  2. Tick **Web API** and add the redirect URI `http://127.0.0.1:8898/callback`.
  3. Paste the app's Client ID into the accounts menu and click **Connect**, or run
     `omarchy-music --spotify-login`.

  Since February 2026 the app's owner needs Spotify Premium. Spotify's API also no longer
  gives artist top tracks or radio, and only shows the items of playlists you own or
  collaborate on. Audio still comes from Tidal.
- **Last.fm** (optional). Make a free API account, then paste its key and secret.

## Your data

No logins or keys are stored in this repository. Everything personal lives in your home
folder:

| What | Where |
| --- | --- |
| Downloaded songs, covers, library, your playlists | `~/Music/Omarchy Music/` (set `OMARCHY_MUSIC_DIR` to move it) |
| Queue, settings, EQ, folders, paired phones | `~/.local/state/omarchy-music.json` |
| Play counts (Recent / Most played) | `~/.local/state/omarchy-music-plays.json` |
| Tidal, Spotify and Last.fm logins (mode 600) | `~/.local/state/omarchy-music-{tidal,spotify,lastfm}.json` |
| Cover and tag cache | `~/.cache/omarchy-music/` |

On Windows, everything under `~/.local/state` and `~/.cache` is in
`%LOCALAPPDATA%\omarchy-music` instead, and downloads go to `Music\Omarchy Music` in your
user folder.

To start over, quit the app (`omarchy-music quit`) and delete those paths.

## Development

A second copy can run beside your own. Its own app id and state come from
`OMARCHY_MUSIC_PROFILE=dev`. The other development variables:

- `OMARCHY_MUSIC_DIR`: a test library.
- `OMARCHY_MUSIC_MPRIS=0`: no media keys.
- `OMARCHY_MUSIC_NO_FOCUS=1`.
- `OMARCHY_MUSIC_MPV="--ao=null"`: silent.
- `OMARCHY_MUSIC_SELFTEST=1`: opens the window, checks that mpv and the modules work, prints
  the result and exits (the Windows build runs this).

## License

MIT. See `LICENSE`.
