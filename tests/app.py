"""Loads the omarchy-music script as a module for the tests, with every file it keeps
(state, cache, music) moved into a temporary folder, so a test never touches yours.

Run the tests with the app's Python, from the repository:
    ~/.local/share/omarchy-music/venv/bin/python -m unittest discover -s tests
"""
import importlib.machinery
import importlib.util
import os
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TMP = Path(tempfile.mkdtemp(prefix="omarchy-music-tests-"))
os.environ.setdefault("OMARCHY_MUSIC_PROFILE", "test")
os.environ["OMARCHY_MUSIC_DIR"] = str(TMP / "music")


def load():
    if "omarchy_music" in sys.modules:
        return sys.modules["omarchy_music"]
    argv, sys.argv = sys.argv, ["omarchy-music"]
    loader = importlib.machinery.SourceFileLoader("omarchy_music", str(ROOT / "omarchy-music"))
    spec = importlib.util.spec_from_loader("omarchy_music", loader)
    m = importlib.util.module_from_spec(spec)
    sys.modules["omarchy_music"] = m
    loader.exec_module(m)
    sys.argv = argv
    # Every path the app keeps something at, in the temporary folder instead.
    for name, value in list(vars(m).items()):
        if isinstance(value, Path) and name.isupper() and name not in ("HOME", "ROOT"):
            try:
                rel = value.relative_to(m.HOME)
            except ValueError:
                continue
            setattr(m, name, TMP / "home" / rel)
    return m
