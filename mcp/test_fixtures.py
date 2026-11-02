#!/usr/bin/env python3
"""Checks the JSON Schema files in schema/v1 against the shared fixtures'
`schema_valid` expectations (the apps check the rest)."""
import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parent.parent / "schema"
schemas = {p.name: json.loads(p.read_text()) for p in (ROOT / "v1").glob("*.schema.json")}
registry = Registry().with_resources(
    [(s["$id"], Resource.from_contents(s)) for s in schemas.values()]
    + [(name, Resource.from_contents(s)) for name, s in schemas.items()]
)
failures = 0
for payload_file in sorted((ROOT / "fixtures").glob("*.json")):
    if payload_file.name.endswith(".expect.json"):
        continue
    payload = json.loads(payload_file.read_text())
    expect = json.loads(payload_file.with_name(payload_file.stem + ".expect.json").read_text())
    schema = schemas.get(f"{payload.get('type')}.schema.json")
    valid = schema is not None and Draft202012Validator(schema, registry=registry).is_valid(payload)
    ok = valid == expect["schema_valid"]
    failures += not ok
    print(f"{'ok  ' if ok else 'FAIL'} {payload_file.name}: schema says {'valid' if valid else 'invalid'}")
sys.exit(1 if failures else 0)
