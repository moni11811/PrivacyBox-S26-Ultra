#!/usr/bin/env python3
"""Verify that THIRD_PARTY_NOTICES.md matches locked release runtime coordinates."""

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
LOCKFILE = ROOT / "app" / "gradle.lockfile"
NOTICES = ROOT / "THIRD_PARTY_NOTICES.md"


def locked_release_coordinates() -> set[str]:
    coordinates: set[str] = set()
    for line in LOCKFILE.read_text(encoding="utf-8").splitlines():
        if "releaseRuntimeClasspath" in line and "=" in line:
            coordinates.add(line.split("=", 1)[0])
    return coordinates


def noticed_coordinates() -> set[str]:
    return set(re.findall(r"^- `([^`]+)`$", NOTICES.read_text(encoding="utf-8"), re.MULTILINE))


locked = locked_release_coordinates()
noticed = noticed_coordinates()
missing = sorted(locked - noticed)
stale = sorted(noticed - locked)
if missing or stale:
    if missing:
        print("Missing notices: " + ", ".join(missing), file=sys.stderr)
    if stale:
        print("Stale notices: " + ", ".join(stale), file=sys.stderr)
    raise SystemExit(1)

print(f"Verified {len(locked)} locked release runtime notices.")
