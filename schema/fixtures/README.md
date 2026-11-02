# Shared fixtures (schema v1)

Every app's validator, and the MCP server's schema check, must agree with these.
Each `<name>.json` payload has a `<name>.expect.json`:

- `ok` — whether the app accepts it;
- `error` / `path` — for rejected payloads, the error code and the field it names;
- `warnings` — `CODE@path` for each warning an accepted payload must produce (and no others);
- `schema_valid` — whether it passes the JSON Schema files in `../v1` alone. Some
  payloads are well-formed but musically wrong (an unknown chord symbol, an
  unknown scale name, a newer schema version), which only the app can catch.

Run them: `./gradlew testDebugUnitTest` (Android/shared code), `./gradlew :desktop:test`
(desktop build of the same code), `mcp/.venv/bin/python mcp/test_fixtures.py` (JSON Schema).
