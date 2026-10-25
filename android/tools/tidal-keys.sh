#!/usr/bin/env bash
# Write android/tidal.properties (gitignored) with the Tidal app keys that the
# tidalapi package ships, so the APK logs in the way the desktop app does.
set -euo pipefail
here=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
py=${OMARCHY_MUSIC_PYTHON:-$HOME/.local/share/omarchy-music/venv/bin/python}
"$py" - "$here/tidal.properties" <<'PY'
import sys, tidalapi
c = tidalapi.Config()
open(sys.argv[1], "w").write(f"client_id={c.client_id_pkce}\nclient_secret={c.client_secret_pkce}\n")
print("wrote", sys.argv[1])
PY
