#!/usr/bin/env bash
# Sets up the neckscape MCP server (mcp/neckscape_mcp.py) in mcp/.venv and
# prints the command that registers it with Claude Code. Python 3.10+.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 -m venv "$ROOT/mcp/.venv"
"$ROOT/mcp/.venv/bin/pip" -q install -r "$ROOT/mcp/requirements.txt"
mkdir -p "$HOME/Music/Tabs"
echo "Installed. Check it can see a running app:"
echo "  $ROOT/mcp/.venv/bin/python $ROOT/mcp/neckscape_mcp.py --check"
echo
echo "Register it with Claude Code (once):"
echo "  claude mcp add --scope user neckscape -- $ROOT/mcp/.venv/bin/python $ROOT/mcp/neckscape_mcp.py"
echo "Codex and other MCP clients: run the same command line as a stdio server."
