"""Validate the bundled Nostr relay directory against its pinned provenance.

The CSV ships inside the app and decides which relays every client reveals its
IP address and geohash subscriptions to, so it must only contain public
hostnames reached over TLS (``wss://``) with sane coordinates.
"""

from __future__ import annotations

import argparse
import hashlib
import ipaddress
import pathlib
import re
import sys

HEADER = ("Relay URL", "Latitude", "Longitude")
MIN_ROWS = 50
MAX_ROWS = 5000
MAX_LINE_LENGTH = 300
_LABEL = re.compile(r"^(?!-)[a-z0-9-]{1,63}(?<!-)$")
_COMMIT = re.compile(r"^[0-9a-f]{40}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_BLOCKED_SUFFIXES = (
    ".local",
    ".localhost",
    ".internal",
    ".lan",
    ".home",
    ".corp",
    ".intranet",
    ".arpa",
)


def relay_host_error(raw: str) -> str | None:
    """Return why a relay entry is unacceptable, or None when it is valid."""
    value = raw.strip()
    if value.startswith("wss://"):
        value = value[len("wss://"):]
    elif "://" in value:
        return "only wss:// or bare hostnames are allowed"
    if not value:
        return "empty relay host"
    if any(ch in value for ch in "/?#@[] \t\\"):
        return "relay must be a bare host with an optional port"
    host, sep, port = value.partition(":")
    if sep:
        if not port.isdigit() or not 1 <= int(port) <= 65535:
            return "invalid port"
    host = host.lower()
    try:
        ipaddress.ip_address(host)
        return "IP literals are not allowed"
    except ValueError:
        pass
    if len(host) > 253 or "." not in host:
        return "relay must be a fully qualified hostname"
    labels = host.split(".")
    if not all(_LABEL.match(label) for label in labels):
        return "invalid hostname"
    if labels[-1].isdigit():
        return "numeric top-level domain"
    if host == "localhost" or host.endswith(_BLOCKED_SUFFIXES):
        return "private or local hostname"
    return None


def _coordinate(raw: str, limit: float) -> float | None:
    try:
        value = float(raw)
    except ValueError:
        return None
    if value != value or not -limit <= value <= limit:
        return None
    return value


def validate_csv(data: bytes) -> list[str]:
    """Return a list of problems with the relay CSV; empty when valid."""
    try:
        text = data.decode("ascii")
    except UnicodeDecodeError:
        return ["file must be ASCII"]
    lines = text.splitlines()
    if not lines or tuple(c.strip() for c in lines[0].split(",")) != HEADER:
        return [f"header must be {','.join(HEADER)}"]
    errors: list[str] = []
    seen: set[str] = set()
    rows = 0
    for number, line in enumerate(lines[1:], start=2):
        if not line.strip():
            continue
        if len(line) > MAX_LINE_LENGTH:
            errors.append(f"line {number}: too long")
            continue
        parts = [p.strip() for p in line.split(",")]
        if len(parts) != 3:
            errors.append(f"line {number}: expected 3 columns")
            continue
        host, lat, lon = parts
        problem = relay_host_error(host)
        if problem:
            errors.append(f"line {number}: {host!r}: {problem}")
        if _coordinate(lat, 90.0) is None:
            errors.append(f"line {number}: invalid latitude")
        if _coordinate(lon, 180.0) is None:
            errors.append(f"line {number}: invalid longitude")
        key = host.lower().removeprefix("wss://")
        if key in seen:
            errors.append(f"line {number}: duplicate relay {host!r}")
        seen.add(key)
        rows += 1
    if not MIN_ROWS <= rows <= MAX_ROWS:
        errors.append(f"expected {MIN_ROWS}-{MAX_ROWS} relays, found {rows}")
    return errors


def read_lock(path: pathlib.Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            key, _, value = line.partition("=")
            values[key.strip()] = value.strip()
    return values


def validate_lock(lock: dict[str, str], data: bytes) -> list[str]:
    errors: list[str] = []
    if not _COMMIT.match(lock.get("commit", "")):
        errors.append("lock commit must be a full 40-character commit SHA")
    expected = lock.get("sha256", "")
    if not _SHA256.match(expected):
        errors.append("lock sha256 must be a 64-character hex digest")
    elif hashlib.sha256(data).hexdigest() != expected:
        errors.append("relay CSV does not match the sha256 pinned in the lock file")
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", type=pathlib.Path)
    parser.add_argument("--lock", type=pathlib.Path, help="verify against this lock file")
    args = parser.parse_args(argv)
    data = args.csv.read_bytes()
    errors = validate_csv(data)
    if args.lock:
        errors += validate_lock(read_lock(args.lock), data)
    for error in errors:
        print(f"{args.csv}: {error}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
