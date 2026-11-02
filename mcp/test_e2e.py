#!/usr/bin/env python3
"""End to end: an MCP client (as Claude Code would be) → neckscape_mcp.py → a
running NeckScape-Navigator app. Start the app first; set NECKSCAPE_LIBRARY to
demo/library to try find_tab.

    NECKSCAPE_LIBRARY=demo/library mcp/.venv/bin/python mcp/test_e2e.py
"""
import asyncio
import json
import os
import sys
from pathlib import Path

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

HERE = Path(__file__).resolve().parent
RISING = json.loads((HERE.parent / "schema/fixtures/good-house-of-the-rising-sun.json").read_text())


async def main():
    # The SDK starts servers with a minimal environment; pass the library setting through.
    env = {**os.environ}
    if "NECKSCAPE_LIBRARY" in env:
        env["NECKSCAPE_LIBRARY"] = str(Path(env["NECKSCAPE_LIBRARY"]).resolve())
    params = StdioServerParameters(command=sys.executable, args=[str(HERE / "neckscape_mcp.py")], env=env)
    async with stdio_client(params) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()
            tools = sorted(t.name for t in (await session.list_tools()).tools)
            print(f"{len(tools)} tools:", ", ".join(tools))

            async def call(name, **args):
                result = await session.call_tool(name, args)
                data = json.loads(result.content[0].text)
                status = "ok " if data.get("ok") else "ERR"
                detail = data.get("rendered_summary") or data.get("error", {}).get("detail")
                print(f"  {status} {name}: {detail}" + (f"  warnings={[w['code'] for w in data.get('warnings', [])]}" if data.get("warnings") else ""))
                return data

            assert (await call("list_targets"))["targets"], "no app running"
            info = await call("get_app_info")
            assert "minor_pentatonic" in info["scales"]
            r = await call("load_progression", progression=RISING)
            assert r["ok"] and not r["warnings"]
            wrong = json.loads(json.dumps(RISING))
            wrong["chords"]["F"] = {"frets": [1, 3, 3, 2, 1, 0]}
            r = await call("load_progression", progression=wrong)
            assert r["warnings"][0]["code"] == "VOICING_MISMATCH", r
            r = await call("get_state")
            assert r["type"] == "progression" and r["current_chord"] == "Am"
            r = await call("show_scale", scale={"schema_version": "1.0", "type": "scale", "root": "A", "scale": "minor_pentatonic",
                                                 "fret_range": [5, 8], "highlight": {"roots": True}}, overlay=True)
            assert r["ok"]
            r = await call("find_tab", song="greensleeves", part="refrain")
            assert r["ok"], r
            r = await call("find_tab", song="Free Bird", artist="Lynyrd Skynyrd", part="solo")
            assert r["error"]["code"] == "NOT_FOUND"
            r = await call("highlight_notes", notes=["A"], label="every A")
            assert r["ok"]
            r = await call("playback", action="play", section="refrain", tempo_pct=120)
            assert r["ok"]
            await asyncio.sleep(1)
            assert (await call("get_state"))["playback"]["state"] == "playing"
            await call("playback", action="stop")
            r = await call("undo")
            assert r["ok"]
            print("end to end: all good")


if __name__ == "__main__":
    asyncio.run(main())
