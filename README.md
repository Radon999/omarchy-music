# Omarchy Music

A small music popup for [Omarchy](https://omarchy.org/) (Hyprland), themed live from the
Omarchy theme. You browse YouTube Music, Tidal, Apple Music or Spotify, plus the music
in your own folders. Everything plays in lossless FLAC from Tidal.

It is a single Python/GTK 4 file: `omarchy-music`.

## Features

- **Four services, one player.** Switch between them at the top. Each song is matched on
  Tidal (by ISRC when the service has one, otherwise by title, artist and length) and
  downloaded as FLAC the first time you play it, or when you pick **Download**.
- **Nothing downloads on its own.** Each service has a **Sync** switch in the accounts menu
  that saves all of its playlists. Every switch is off to start.
- **Local files.** Songs in your folders (`~/Music` to start; add more in the accounts menu)
  appear in the Music tab beside your downloads, with the covers they carry.
- **Music tab:**
  - **All:** every song in one list.
  - **Playlists:** your playlists.
  - **Recent:** recently played.
  - **Added:** recently added.
  - **Most played:** a song counts after 30 seconds, or half of a shorter song.
- **Playlists.** Create, rename and delete them, and add or remove songs. They are stored in
  `playlists.json`.
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
- **Last.fm.** Plays are scrobbled, and **Wrapped** looks back on any year of your history.
- **Desktop controls.** Media keys work through MPRIS (playerctl). The `omarchy-music`
  command has a CLI (`omarchy-music help`).

## Install

```sh
sudo pacman -S --needed mpv ffmpeg python-gobject gtk4 libadwaita
git clone https://github.com/<you>/omarchy-music.git ~/Projects/omarchy-music
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

## Accounts

Open the accounts menu with the ☰ button, or run `omarchy-music accounts`.

- **YouTube Music / Apple Music.** Sign in at music.youtube.com and music.apple.com in
  Chromium. The app reads those sign-ins from Chromium's cookies.
- **Tidal** (required for playback). Click **Connect**, or run `omarchy-music --tidal-login`.
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
| Queue, settings, EQ, folders | `~/.local/state/omarchy-music.json` |
| Play counts (Recent / Most played) | `~/.local/state/omarchy-music-plays.json` |
| Tidal, Spotify and Last.fm logins (mode 600) | `~/.local/state/omarchy-music-{tidal,spotify,lastfm}.json` |
| Cover and tag cache | `~/.cache/omarchy-music/` |

To start over, quit the app (`omarchy-music quit`) and delete those paths.

## Development

A second copy can run beside your own. Its own app id and state come from
`OMARCHY_MUSIC_PROFILE=dev`. The other development variables:

- `OMARCHY_MUSIC_DIR`: a test library.
- `OMARCHY_MUSIC_MPRIS=0`: no media keys.
- `OMARCHY_MUSIC_NO_FOCUS=1`.
- `OMARCHY_MUSIC_MPV="--ao=null"`: silent.
