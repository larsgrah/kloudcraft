#!/usr/bin/env python3
"""Rebuild the pack for the Minecraft version the server runs.

Usage: update.py [--rebuild] [minecraft-version]
Without a version it comes from a status ping of SERVER.
A version change or --rebuild re-adds every mod from mods.txt at its latest build.
Otherwise only the mods listed in missing.txt are retried.

A line slug@<modrinth version id> in mods.txt pins that mod, fabric@<version> pins the loader.
A pin for another Minecraft version is ignored, the mod gets its latest build then.
"""
import json
import re
import socket
import struct
import subprocess
import sys
import urllib.request
from pathlib import Path

SERVER = ("mc.kloud.software", 25565)
ROOT = Path(__file__).resolve().parent
MODS = ROOT / "mods"


def varint(n):
    out = b""
    while True:
        b = n & 0x7F
        n >>= 7
        out += bytes([b | (0x80 if n else 0)])
        if not n:
            return out


def read_varint(sock):
    n = 0
    for i in range(5):
        b = sock.recv(1)[0]
        n |= (b & 0x7F) << (7 * i)
        if not b & 0x80:
            break
    return n


def server_version():
    host, port = SERVER
    with socket.create_connection(SERVER, 10) as s:
        name = host.encode()
        handshake = varint(0) + varint(0xFFFFFFFF) + varint(len(name)) + name + struct.pack(">H", port) + varint(1)
        s.sendall(varint(len(handshake)) + handshake + varint(1) + varint(0))
        read_varint(s)
        read_varint(s)
        size = read_varint(s)
        data = b""
        while len(data) < size:
            data += s.recv(size - len(data))
    return json.loads(data)["version"]["name"]


def pack_version():
    pack = ROOT / "pack.toml"
    if not pack.exists():
        return None
    m = re.search(r'^minecraft = "(.+)"$', pack.read_text(), re.M)
    return m.group(1) if m else None


def pin_fits(version_id, mc):
    with urllib.request.urlopen(f"https://api.modrinth.com/v2/version/{version_id}", timeout=30) as r:
        return mc in json.load(r)["game_versions"]


def packwiz(*args):
    return subprocess.run(["packwiz", *args], cwd=ROOT, capture_output=True, text=True)


def main():
    args = sys.argv[1:]
    rebuild = "--rebuild" in args
    args = [a for a in args if a != "--rebuild"]
    current = pack_version()
    if args:
        target = args[0]
    else:
        try:
            target = server_version()
        except (OSError, ValueError, KeyError, IndexError) as e:
            # server down or paused, keep what we have and only retry missing mods
            print(f"server ping failed ({e}), staying on {current}")
            target = current
    if not target or not re.fullmatch(r"\d+(\.\d+)+", target):
        sys.exit(f"no usable minecraft version: {target!r}")

    lines = [l.split("#")[0].strip() for l in (ROOT / "mods.txt").read_text().splitlines()]
    entries = [l.partition("@")[::2] for l in lines if l]
    fabric = next((v for s, v in entries if s == "fabric" and v), None)
    mods = [(s, v) for s, v in entries if s != "fabric"]
    # pinned first, or another mod pulls them in as a dependency at their latest build
    mods.sort(key=lambda e: not e[1])

    if target != current or rebuild:
        print(f"minecraft {current} -> {target}")
        # only the packwiz metadata, the jars committed here (kloudcraft-updater) stay
        for meta in MODS.glob("*.pw.toml"):
            meta.unlink()
        r = packwiz("init", "-r", "--name", "kloudcraft", "--author", "kloud", "--version", target,
                    "--mc-version", target, "--modloader", "fabric",
                    *(["--fabric-version", fabric] if fabric else ["--fabric-latest"]))
        if r.returncode != 0:
            sys.exit(r.stdout + r.stderr)

    slugs = [s for s, _ in mods]
    missing = []
    for slug, pin in mods:
        meta = MODS / f"{slug}.pw.toml"
        if not meta.exists():
            if pin and not pin_fits(pin, target):
                print(f"  stale pin {slug}@{pin}, not built for {target}, using the latest build")
                pin = ""
            packwiz("modrinth", "add", "-y", f"https://modrinth.com/mod/{slug}/version/{pin}" if pin else slug)
        if meta.exists():
            print(f"  ok       {slug}{'@' + pin if pin else ''}")
        else:
            print(f"  missing  {slug}")
            missing.append(slug)

    (ROOT / "missing.txt").write_text("".join(s + "\n" for s in missing))
    r = packwiz("refresh")
    if r.returncode != 0:
        sys.exit(r.stdout + r.stderr)
    print(f"pack is on minecraft {target}, {len(slugs) - len(missing)}/{len(slugs)} mods, missing: {' '.join(missing) or 'none'}")


if __name__ == "__main__":
    main()
