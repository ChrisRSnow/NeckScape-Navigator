#!/usr/bin/env python3
"""neckscape-mcp — lets an AI terminal session (Claude Code, Codex …) drive
NeckScape-Navigator on the desktop or on an Android phone.

It forwards each MCP tool call to the app's local bridge (HTTP on 127.0.0.1,
bearer token), finds instances (desktop discovery files, phones over adb with
a port forward), reads files on this computer for import_file, and searches
the user's tab library for find_tab. It is the only part that would go online,
and only through site adapters the user enables (none by default).

Run:  python3 neckscape_mcp.py            (stdio, as an MCP server)
      python3 neckscape_mcp.py --check    (list targets and exit)
See mcp/README.md.
"""
from __future__ import annotations

import argparse
import base64
import difflib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import urllib.error
import urllib.request
import zipfile
from pathlib import Path
from typing import Any

APP_PORT = 47621          # the bridge's port on the app (desktop tries the next nine if busy)
ANDROID_LOCAL_BASE = 47631  # local ports used for adb forwards, one per phone
MAX_FILE = 8 * 1024 * 1024
TIMEOUT = 20

FORMATS = {
    ".gp3": "guitar_pro", ".gp4": "guitar_pro", ".gp5": "guitar_pro", ".gp": "guitar_pro", ".gpx": "guitar_pro",
    ".xml": "musicxml", ".musicxml": "musicxml", ".mxl": "musicxml",
    ".mid": "midi", ".midi": "midi", ".kar": "midi",
    ".txt": "ascii_tab", ".tab": "ascii_tab",
    ".cho": "chordpro", ".chopro": "chordpro", ".chordpro": "chordpro", ".crd": "chordpro", ".pro": "chordpro",
}
# Guitar Pro first (it carries rhythm), then MusicXML and MIDI, then text.
FORMAT_RANK = {"guitar_pro": 0, "musicxml": 1, "midi": 2, "ascii_tab": 3, "chordpro": 4}


# ---------------------------------------------------------------- configuration

def config_path() -> Path:
    if os.environ.get("NECKSCAPE_CONFIG"):
        return Path(os.environ["NECKSCAPE_CONFIG"]).expanduser()
    base = os.environ.get("XDG_CONFIG_HOME") or ("~/AppData/Roaming" if os.name == "nt" else "~/.config")
    return Path(base).expanduser() / "neckscape-mcp" / "config.json"


def load_config() -> dict:
    try:
        return json.loads(config_path().read_text())
    except (OSError, ValueError):
        return {}


CONFIG = load_config()


def library_dir() -> Path:
    return Path(os.environ.get("NECKSCAPE_LIBRARY") or CONFIG.get("library_dir") or "~/Music/Tabs").expanduser()


def app_instances_dir() -> Path:
    """Where desktop apps write discovery files (see desktop/DesktopBridge.kt)."""
    home = Path.home()
    if sys.platform == "darwin":
        return home / "Library/Application Support/NeckScape/instances"
    if os.name == "nt":
        return Path(os.environ.get("APPDATA", home)) / "NeckScape" / "instances"
    return Path(os.environ.get("XDG_CONFIG_HOME") or home / ".config") / "neckscape" / "instances"


# ---------------------------------------------------------------- talking to apps

def error(code: str, detail: str, **extra) -> dict:
    return {"ok": False, "error": {"code": code, "detail": detail, **extra}, "warnings": []}


def http(port: int, method: str, path: str, token: str | None = None, body: Any = None) -> dict:
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode())
        except ValueError:
            return error("NO_TARGET", f"HTTP {e.code} from the app")
    except (OSError, ValueError) as e:
        return error("NO_TARGET", f"Couldn't reach the app on port {port}: {e}")


def adb_path() -> str | None:
    found = shutil.which("adb")
    if found:
        return found
    for root in [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"), "~/Android/Sdk", "~/Library/Android/sdk", "~/AppData/Local/Android/Sdk"]:
        if root:
            p = Path(root).expanduser() / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
            if p.exists():
                return str(p)
    return None


def adb(*args: str) -> str:
    exe = adb_path()
    if not exe:
        return ""
    try:
        return subprocess.run([exe, *args], capture_output=True, text=True, timeout=10).stdout
    except (OSError, subprocess.TimeoutExpired):
        return ""


def android_token(serial: str, name: str) -> str | None:
    """From config/env; for debug builds, read it from the app's own settings over adb (needs USB debugging)."""
    token = CONFIG.get("tokens", {}).get(name) or os.environ.get("NECKSCAPE_ANDROID_TOKEN")
    if token:
        return token
    for package in ("com.crsnow.neckscape.debug", "com.crsnow.neckscape"):
        xml = adb("-s", serial, "shell", "run-as", package, "cat", "shared_prefs/bridge.xml")
        m = re.search(r'name="bridge\.token">([^<]+)<', xml)
        if m:
            return m.group(1)
    return None


def discover() -> list[dict]:
    """Reachable app instances: desktop apps via discovery files, phones via adb."""
    targets = []
    for f in sorted(app_instances_dir().glob("*.json")) if app_instances_dir().is_dir() else []:
        try:
            info = json.loads(f.read_text())
        except (OSError, ValueError):
            continue
        port = int(info.get("port", 0))
        if http(port, "GET", "/v1/health").get("ok"):
            targets.append({
                "name": f"desktop-{info.get('platform', 'pc')}-{port}", "kind": "desktop", "port": port,
                "token": os.environ.get("NECKSCAPE_TOKEN") or CONFIG.get("tokens", {}).get(f"desktop-{port}") or info.get("token"),
            })
    devices = [line.split()[0] for line in adb("devices").splitlines()[1:] if line.strip().endswith("device")]
    for i, serial in enumerate(devices):
        local = ANDROID_LOCAL_BASE + i
        adb("-s", serial, "forward", f"tcp:{local}", f"tcp:{APP_PORT}")
        if not http(local, "GET", "/v1/health").get("ok"):
            continue
        model = adb("-s", serial, "shell", "getprop", "ro.product.model").strip().replace(" ", "_") or serial
        name = f"android-{model}"
        targets.append({"name": name, "kind": "android", "port": local, "serial": serial, "token": android_token(serial, name)})
    return targets


def pick(target: str | None) -> dict:
    found = discover()
    if not found:
        raise LookupError("No NeckScape-Navigator app is reachable. Start the desktop app, or open the app on a phone connected with USB debugging.")
    if target is None:
        if len(found) > 1:
            raise LookupError("Several apps are running; pass target: " + ", ".join(t["name"] for t in found))
        return found[0]
    for t in found:
        if t["name"] == target or target in t["name"]:
            return t
    raise LookupError(f"No target '{target}'; reachable: " + ", ".join(t["name"] for t in found))


def call(tool: str, args: dict, target: str | None = None) -> dict:
    try:
        t = pick(target)
    except LookupError as e:
        return error("NO_TARGET", str(e))
    if not t.get("token"):
        return error("AUTH_FAILED", f"No token for {t['name']}: copy it from the app's Settings → AI bridge into NECKSCAPE_TOKEN or {config_path()}")
    result = http(t["port"], "POST", f"/v1/tools/{tool}", t["token"], {k: v for k, v in args.items() if v is not None})
    result.setdefault("target", t["name"])
    return result


# ---------------------------------------------------------------- the tab library

def _gp_header(data: bytes) -> dict:
    """Title and artist from a Guitar Pro 3–5 header (version, then int+byte-length strings)."""
    try:
        pos = 31
        out = []
        for _ in range(3):
            count = struct.unpack_from("<i", data, pos)[0]
            length = data[pos + 4]
            out.append(data[pos + 5:pos + 5 + length].decode("latin-1"))
            pos += 4 + count
        return {"title": out[0], "artist": out[2]}
    except (struct.error, IndexError):
        return {}


def _metadata(path: Path) -> dict:
    meta: dict = {}
    sidecar = path.with_suffix(path.suffix + ".json")
    if sidecar.exists():
        try:
            meta.update(json.loads(sidecar.read_text()))
        except ValueError:
            pass
    try:
        if path.suffix.lower() in (".gp3", ".gp4", ".gp5"):
            meta = {**_gp_header(path.read_bytes()[:4096]), **meta}
        elif path.suffix.lower() in (".gp", ".mxl"):
            with zipfile.ZipFile(path) as z:
                for n in z.namelist():
                    if n.endswith("score.gpif") or (n.endswith(".xml") and not n.startswith("META-INF")):
                        meta = {**_xml_meta(z.read(n)[:200_000].decode("utf-8", "replace")), **meta}
                        break
        elif path.suffix.lower() in (".xml", ".musicxml"):
            meta = {**_xml_meta(path.read_text(errors="replace")[:200_000]), **meta}
    except (OSError, zipfile.BadZipFile):
        pass
    if not meta.get("title"):
        stem = path.stem
        if " - " in stem:
            meta.setdefault("artist", stem.split(" - ", 1)[0])
            meta["title"] = stem.split(" - ", 1)[1]
        else:
            meta["title"] = stem.replace("_", " ")
    return meta


def _xml_meta(text: str) -> dict:
    def first(*patterns):
        for p in patterns:
            m = re.search(p, text, re.S)
            if m and m.group(1).strip():
                return re.sub(r"^<!\[CDATA\[|\]\]>$", "", m.group(1).strip()).strip()
        return None
    return {k: v for k, v in {
        "title": first(r"<work-title>(.*?)</work-title>", r"<movement-title>(.*?)</movement-title>", r"<Title>(.*?)</Title>"),
        "artist": first(r'<creator type="composer">(.*?)</creator>', r"<creator[^>]*>(.*?)</creator>", r"<Artist>(.*?)</Artist>"),
    }.items() if v}


def _norm(s: str | None) -> str:
    return re.sub(r"[^a-z0-9 ]+", "", (s or "").lower()).strip()


def search_library(song: str, artist: str | None) -> list[dict]:
    root = library_dir()
    if not root.is_dir():
        return []
    hits = []
    for path in root.rglob("*"):
        fmt = FORMATS.get(path.suffix.lower())
        if not fmt or not path.is_file() or path.stat().st_size > MAX_FILE:
            continue
        meta = _metadata(path)
        score = difflib.SequenceMatcher(None, _norm(song), _norm(meta.get("title"))).ratio()
        if _norm(song) and _norm(song) in _norm(meta.get("title")):
            score = max(score, 0.9)
        if artist:
            a = difflib.SequenceMatcher(None, _norm(artist), _norm(meta.get("artist"))).ratio()
            score = score * 0.75 + a * 0.25
        if score >= 0.6:
            hits.append({"path": str(path), "format": fmt, "score": round(score, 3), **meta})
    hits.sort(key=lambda h: (-h["score"], FORMAT_RANK.get(h["format"], 9), -(h.get("rating") or 0)))
    return hits


def send_file(path: Path, fmt: str | None, part: str | None, track: str | None, target: str | None, meta: dict | None = None) -> dict:
    if not path.is_file():
        return error("NOT_FOUND", f"No file at {path}")
    if path.stat().st_size > MAX_FILE:
        return error("INVALID_SCHEMA", f"{path.name} is over 8 MB")
    fmt = fmt or FORMATS.get(path.suffix.lower())
    if not fmt:
        return error("UNSUPPORTED_FORMAT", f"Can't tell the format of {path.name}; formats: {', '.join(sorted(set(FORMATS.values())))}")
    meta = meta or {}
    args = {
        "content_base64": base64.b64encode(path.read_bytes()).decode(),
        "file_name": path.name, "format": fmt, "part": part, "track": track,
        "source": meta.get("source", "user_file"), "source_url": meta.get("source_url"),
        "author": meta.get("author"), "rating": meta.get("rating"),
    }
    return call("import_file", args, target)


# ---------------------------------------------------------------- online adapters

def adapters() -> list:
    """Tab-site adapters enabled in the config ("adapters": ["module:Class"]); none by default.
    Check each site's terms before enabling one: many forbid automated access."""
    out = []
    sys.path.insert(0, str(Path(__file__).parent))
    for spec in CONFIG.get("adapters", []):
        try:
            module, cls = spec.split(":")
            out.append(getattr(__import__(module), cls)(CONFIG))
        except (ValueError, ImportError, AttributeError) as e:
            print(f"neckscape-mcp: couldn't load adapter {spec}: {e}", file=sys.stderr)
    return out


def cache_online(data: bytes, ext: str, meta: dict) -> Path:
    """Saves what loaded to the library, with where it came from, so it works offline next time."""
    folder = library_dir() / "online"
    folder.mkdir(parents=True, exist_ok=True)
    stem = re.sub(r"[^\w\- ]+", "", f"{meta.get('artist', '')} - {meta.get('title', 'tab')}").strip(" -")[:120] or "tab"
    path = folder / f"{stem}{ext}"
    path.write_bytes(data)
    path.with_suffix(ext + ".json").write_text(json.dumps({**meta, "source": "online"}, indent=2))
    return path


# ---------------------------------------------------------------- MCP tools

def build_server():
    from mcp.server.fastmcp import FastMCP

    mcp = FastMCP(
        "neckscape",
        instructions=(
            "Drives NeckScape-Navigator, a guitar fretboard / score / tab app, on this computer or a USB-connected phone. "
            "Write chord progressions yourself (load_progression), check rendered_summary and fix any VOICING_MISMATCH warnings. "
            "For a solo or riff from a recording, use find_tab (the user's tab library) or import_url/import_file — never "
            "write a recorded part from memory; if find_tab finds nothing, say so and offer the key, scale positions and "
            "original licks instead. No lyrics. Strings in tab payloads: 1 = highest. Fret arrays: lowest string first."
        ),
    )

    @mcp.tool()
    def list_targets() -> dict:
        """Lists reachable NeckScape-Navigator instances (desktop app, phones over adb) by name."""
        found = discover()
        return {"ok": True, "targets": [{"name": t["name"], "kind": t["kind"], "has_token": bool(t.get("token"))} for t in found],
                "rendered_summary": f"{len(found)} reachable" + (": " + ", ".join(t["name"] for t in found) if found else "")}

    @mcp.tool()
    def get_app_info(target: str | None = None) -> dict:
        """App version, platform, schema version, scale names, voicing styles, import formats, tuning presets."""
        return call("get_app_info", {}, target)

    @mcp.tool()
    def get_state(target: str | None = None) -> dict:
        """What's on screen: type, title, key, tuning, capo, current bar, playback. Use it to build on what's showing."""
        return call("get_state", {}, target)

    @mcp.tool()
    def set_tuning(tuning: dict | str | None = None, capo: int | None = None, target: str | None = None) -> dict:
        """Changes tuning ({"name": ..., "strings": ["E2", ...]} lowest first, or a preset name) and/or capo; content re-renders."""
        return call("set_tuning", {"tuning": tuning, "capo": capo}, target)

    @mcp.tool()
    def show_chord(chord: str, voicing_style: str = "open", target: str | None = None) -> dict:
        """Shows one chord (e.g. "F#m7b5"); the app picks a voicing: open, barre, drop2 or position_N."""
        return call("show_chord", {"chord": chord, "voicing_style": voicing_style}, target)

    @mcp.tool()
    def load_progression(progression: dict, target: str | None = None) -> dict:
        """Replaces the content with a progression (schema v1, type "progression"). Mark source "ai_generated"."""
        return call("load_progression", {"progression": progression}, target)

    @mcp.tool()
    def load_tab(tab: dict, target: str | None = None) -> dict:
        """Replaces the content with a tab (schema v1, type "tab"). Only for original licks, exercises, traditional tunes."""
        return call("load_tab", {"tab": tab}, target)

    @mcp.tool()
    def find_tab(song: str, artist: str | None = None, part: str | None = None, track: str | None = None, target: str | None = None) -> dict:
        """Finds a song's tab in the user's tab library (then any enabled online tab sites) and loads it at `part`
        (e.g. "solo", "intro"). Returns candidates instead when the match is ambiguous."""
        hits = search_library(song, artist)
        if hits:
            best = hits[0]
            close = [h for h in hits[1:] if best["score"] - h["score"] < 0.05 and _norm(h.get("title")) != _norm(best.get("title"))]
            if close:
                return {"ok": True, "loaded": False, "warnings": [], "candidates": [best, *close][:8],
                        "rendered_summary": "Several matches in the library; call again with artist, or import_file with one path"}
            result = send_file(Path(best["path"]), best["format"], part, track, target, best)
            result["library_file"] = best["path"]
            return result
        for adapter in adapters():
            try:
                found = adapter.search(song, artist, part)
            except Exception as e:  # an adapter's problem shouldn't break the tool
                print(f"neckscape-mcp: {adapter} search failed: {e}", file=sys.stderr)
                continue
            found.sort(key=lambda c: (-c.get("score", 0), FORMAT_RANK.get(c.get("format"), 9), -(c.get("rating") or 0)))
            for cand in found[:3]:
                try:
                    data, ext, meta = adapter.fetch(cand["url"])
                except Exception as e:
                    print(f"neckscape-mcp: fetch failed: {e}", file=sys.stderr)
                    continue
                path = cache_online(data, ext, meta)
                return send_file(path, FORMATS.get(ext), part, track, target, {**meta, "source": "online"})
        where = f"your tab library ({library_dir()})"
        online = "" if adapters() else "; no online tab sites are enabled"
        return error("NOT_FOUND", f"No tab for '{song}'" + (f" by {artist}" if artist else "") + f" in {where}{online}. "
                     "Offer the key, scale positions and original licks in that style instead.")

    @mcp.tool()
    def import_url(url: str, part: str | None = None, track: str | None = None, target: str | None = None) -> dict:
        """Loads a tab from a web page through the matching (enabled) site adapter."""
        for adapter in adapters():
            if adapter.handles(url):
                data, ext, meta = adapter.fetch(url)
                path = cache_online(data, ext, meta)
                return send_file(path, FORMATS.get(ext), part, track, target, {**meta, "source": "online"})
        return error("UNSUPPORTED_FORMAT", "No enabled tab-site adapter handles that address (see mcp/README.md). "
                     "If the user has the file, use import_file with its path.")

    @mcp.tool()
    def show_scale(scale: dict | str, overlay: bool = False, root: str | None = None, fret_range: list[int] | None = None, target: str | None = None) -> dict:
        """Shows a scale alone, or over the current content (overlay=true). `scale` is a schema v1 scale payload,
        or a name from get_app_info (with `root`)."""
        return call("show_scale", {"scale": scale, "overlay": overlay, "root": root, "fret_range": fret_range}, target)

    @mcp.tool()
    def highlight_notes(positions: list[dict] | None = None, notes: list[str] | None = None, label: str | None = None, target: str | None = None) -> dict:
        """Marks notes: positions [{"string": 1, "fret": 5}] (1 = highest string) or note names (["C"] marks every C)."""
        return call("highlight_notes", {"positions": positions, "notes": notes, "label": label}, target)

    @mcp.tool()
    def import_file(path: str, format: str | None = None, part: str | None = None, track: str | None = None, target: str | None = None) -> dict:
        """Loads a file from this computer: Guitar Pro (.gp3/.gp4/.gp5/.gp), MusicXML, MIDI, ASCII tab or ChordPro
        (lyrics dropped). Works for phone targets too (the file's contents are sent)."""
        p = Path(path).expanduser()
        return send_file(p, format, part, track, target, _metadata(p) if p.is_file() else None)

    @mcp.tool()
    def playback(action: str = "play", section: str | None = None, tempo_pct: float | None = None, target: str | None = None) -> dict:
        """Plays, loops or stops the loaded progression/tab; optionally one section, at tempo_pct of its tempo."""
        return call("playback", {"action": action, "section": section, "tempo_pct": tempo_pct}, target)

    @mcp.tool()
    def clear(target: str | None = None) -> dict:
        """Empties the neck."""
        return call("clear", {}, target)

    @mcp.tool()
    def undo(target: str | None = None) -> dict:
        """Takes back the last change pushed to the app (the app keeps a history)."""
        return call("undo", {}, target)

    return mcp


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--check", action="store_true", help="list reachable apps and exit")
    args = ap.parse_args()
    if args.check:
        for t in discover():
            print(f"{t['name']}: port {t['port']}, token {'found' if t.get('token') else 'MISSING'}")
        print(f"library: {library_dir()} ({'exists' if library_dir().is_dir() else 'missing'})")
        return
    build_server().run()


if __name__ == "__main__":
    main()
