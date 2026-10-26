"""What's playing, on Discord: the Rich Presence activity, and talking to Discord's socket."""
import json
import os
import socket
import struct
import threading
import types
import unittest

from app import TMP, load

om = load()


class ActivityTest(unittest.TestCase):
    def test_a_streamed_song(self):
        t = {"id": "abc123", "source": "ytm", "title": "Karma Police", "artist": "Radiohead", "album": "OK Computer",
             "thumb": "https://lh3.googleusercontent.com/x=w544-h544"}
        a = om.discord_activity(t, 30, 264, now=1000)
        self.assertEqual(a["type"], 2)   # "Listening to"
        self.assertEqual((a["details"], a["state"], a["assets"]["large_text"]), ("Karma Police", "Radiohead", "OK Computer"))
        self.assertEqual(a["timestamps"], {"start": 970_000, "end": 1_234_000})
        self.assertEqual(a["assets"]["large_image"], t["thumb"])
        self.assertEqual(a["buttons"], [{"label": "Play on YouTube Music", "url": "https://music.youtube.com/watch?v=abc123"},
                                        {"label": "Get VarMusic", "url": om.DOWNLOAD_PAGE}])

    def test_your_own_file_and_a_faster_song(self):
        t = {"id": "f1", "source": "file", "file": "/m/a.flac", "title": "X", "artist": "", "thumb": ""}
        a = om.discord_activity(t, 10, 100, speed=2.0, now=1000)
        self.assertEqual(len(a["details"]), 2)   # Discord wants two characters at least
        self.assertEqual(a["timestamps"], {"start": 995_000, "end": 1_045_000})
        self.assertTrue(a["assets"]["large_image"].endswith("icon.png"))
        self.assertEqual([b["label"] for b in a["buttons"]], ["Get VarMusic"])
        self.assertNotIn("large_text", a["assets"])

    def test_a_little_drift_is_the_same(self):
        t = {"id": "td1", "source": "tidal", "tidal": 1, "title": "Song", "artist": "A"}
        a, b = om.discord_activity(t, 10, 100, now=1000), om.discord_activity(t, 11.5, 100, now=1000)
        self.assertTrue(om.same_activity(a, b))
        self.assertFalse(om.same_activity(a, om.discord_activity(t, 40, 100, now=1000)))   # a seek
        self.assertFalse(om.same_activity(a, None))
        self.assertTrue(om.same_activity(None, None))
        self.assertEqual(om.song_page(t), ("Tidal", "https://tidal.com/browse/track/1"))


class FakeDiscord:
    """A Discord app's Rich Presence socket that keeps what it's sent."""

    def __init__(self, path, refuse=False):
        self.got, self.refuse = [], refuse
        self.server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.server.bind(path)
        self.server.listen(1)
        threading.Thread(target=self.serve, daemon=True).start()

    def read(self, c):
        op, n = struct.unpack("<II", c.recv(8))
        return op, json.loads(c.recv(n))

    def write(self, c, op, data):
        body = json.dumps(data).encode()
        c.sendall(struct.pack("<II", op, len(body)) + body)

    def serve(self):
        c, _ = self.server.accept()
        while True:
            try:
                op, data = self.read(c)
            except (OSError, struct.error):
                return
            self.got.append((op, data))
            if op == 0 and self.refuse:
                self.write(c, 2, {"code": 4000, "message": "Invalid Client ID"})
            elif op == 0:
                self.write(c, 1, {"cmd": "DISPATCH", "evt": "READY", "data": {"v": 1}})
            else:
                self.write(c, 1, {"cmd": data["cmd"], "evt": None, "data": data["args"].get("activity"), "nonce": data["nonce"]})


class SocketTest(unittest.TestCase):
    def presence(self, sub, refuse=False):
        run = TMP / sub
        run.mkdir(exist_ok=True)
        for f in run.glob("discord-ipc-*"):
            f.unlink()
        self.fake = FakeDiscord(str(run / "discord-ipc-0"), refuse)
        os.environ["XDG_RUNTIME_DIR"] = str(run)
        p = om.DiscordPresence.__new__(om.DiscordPresence)   # (without its thread)
        p.app, p.conn = types.SimpleNamespace(api_keys={"discord_app_id": "123"}), None
        return p

    def test_handshake_then_activity(self):
        p = self.presence("run1")
        p.connect()
        p.send(1, {"cmd": "SET_ACTIVITY", "args": {"pid": 1, "activity": {"details": "Song"}}, "nonce": "n"})
        self.assertEqual(p.reply()["data"], {"details": "Song"})
        self.assertEqual(self.fake.got[0], (0, {"v": 1, "client_id": "123"}))
        self.assertEqual(self.fake.got[1][1]["cmd"], "SET_ACTIVITY")
        p.close()

    def test_says_why_discord_refused(self):
        p = self.presence("run2", refuse=True)
        with self.assertRaisesRegex(ValueError, "Invalid Client ID"):
            p.connect()


if __name__ == "__main__":
    unittest.main()
