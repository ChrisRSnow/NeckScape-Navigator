# neckscape-mcp

An MCP server that lets an AI terminal session — Claude Code, Codex or anything
else that speaks MCP — push chords, progressions, scales and tabs into a running
NeckScape-Navigator on Windows, macOS, Linux or an Android phone.

```
AI session ──MCP (stdio)──▶ neckscape_mcp.py ──HTTP 127.0.0.1 + token──▶ desktop app
                                    └──adb forward (USB / wireless debugging)──▶ phone app
```

## Set up

```sh
tools/install_mcp.sh                       # venv in mcp/.venv + requirements
claude mcp add --scope user neckscape -- "$PWD/mcp/.venv/bin/python" "$PWD/mcp/neckscape_mcp.py"
```

Then start the app and ask, for example:

- *"Show me the chords for House of the Rising Sun in Neckscape."*
- *"Put A minor pentatonic, position 5, over it."*
- *"Tab me the refrain of Greensleeves."* (from your tab library)
- *"Mark every C on the neck."* · *"Capo 2, DADGAD."* · *"Loop the chorus at 80%."*

`python mcp/neckscape_mcp.py --check` lists the apps it can reach.

## Finding apps and tokens

- **Desktop**: each running app writes `instances/<port>.json` (port, token;
  readable only by you) in `~/.config/neckscape` (Linux),
  `~/Library/Application Support/NeckScape` (macOS) or `%APPDATA%\NeckScape`
  (Windows). The bridge listens on 127.0.0.1:47621, or the next free port up to
  47630 if another instance has it.
- **Android**: phones listed by `adb devices` get `adb forward tcp:4763x tcp:47621`
  automatically. The token comes from `NECKSCAPE_ANDROID_TOKEN`, the config file,
  or — for debug builds — straight from the app over adb.
- `NECKSCAPE_TOKEN` overrides everything; the token is in the app under
  Settings → AI bridge (Copy / Regenerate).

## Tools

`list_targets`, `get_app_info`, `get_state`, `set_tuning`, `show_chord`,
`load_progression`, `load_tab`, `find_tab`, `import_url`, `show_scale`,
`highlight_notes`, `import_file`, `playback`, `clear`, plus `undo`. All but
`list_targets` take an optional `target`. Every tool answers
`{ok, warnings, rendered_summary}` or `{ok: false, error: {code, path, detail}}`
— see `docs/ai-integration-spec.md` and the JSON Schemas in `schema/v1`.

## Tab library and online sites

`find_tab` searches the folder in `library_dir` (default `~/Music/Tabs`; or set
`NECKSCAPE_LIBRARY`) — Guitar Pro (.gp3/.gp4/.gp5/.gp), MusicXML (.musicxml/.xml/.mxl),
MIDI, plain-text tab and ChordPro — by title and artist from the files themselves
(or `Artist - Title` file names), and opens the best match at the part asked for
when the file marks sections. `demo/library` has a public-domain example.

Online tab sites plug in as adapters (`adapters_base.py`); **none are enabled**.
Most tab sites restrict automated access in their terms, and user-submitted tabs
of recorded songs aren't necessarily licensed, so check a site's terms before
writing or enabling one. Anything fetched is saved to `library_dir/online` with
its source, author and rating, and is labelled "Online" in the app.

Config file: `~/.config/neckscape-mcp/config.json`

```json
{ "library_dir": "~/Music/Tabs", "tokens": { "android-Pixel_8": "…" }, "adapters": [] }
```

## Tests

```sh
mcp/.venv/bin/python mcp/test_fixtures.py                          # JSON Schemas vs the shared fixtures
NECKSCAPE_LIBRARY=demo/library mcp/.venv/bin/python mcp/test_e2e.py  # needs the app running
```
