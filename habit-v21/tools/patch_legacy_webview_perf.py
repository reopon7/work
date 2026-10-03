#!/usr/bin/env python3
"""
Surgical legacy classes.dex performance patch for Habit Browser 1.1.77.

Why this exists:
- BrowserActivity.onCreate() explicitly calls WebView.enableSlowWholeDocumentDraw()
  on modern Android before WebViews are created.
- HabitWebView.initSettings() calls it again.
- HabitWebView.initSettings() also enables the obsolete View drawing cache.

Android's own WebView documentation states that slow-whole-document drawing has
"a significant performance cost" and exists for old full-document drawing /
capturePicture compatibility. Habit still contains capturePicture-era code, but
normal browsing/video should use the modern viewport-optimized draw path.

This script changes only three 3-code-unit invoke instructions to NOPs.
It guards the original byte sequences and recomputes DEX SHA-1/adler32.

Expected legacy classes.dex SHA256:
a22af3a25924f13502ec62afda729c8d0f38ede0a4136d45169f17e93556690f
"""

import argparse
import hashlib
import struct
import zlib
from pathlib import Path

EXPECTED_SHA256 = "a22af3a25924f13502ec62afda729c8d0f38ede0a4136d45169f17e93556690f"

PATCHES = [
    (
        0x282CA6,
        bytes.fromhex("71000d180000"),
        b"\x00\x00\x00\x00\x00\x00",
        "BrowserActivity.onCreate WebView.enableSlowWholeDocumentDraw",
    ),
    (
        0x291880,
        bytes.fromhex("6e2075472300"),
        b"\x00\x00\x00\x00\x00\x00",
        "HabitWebView.initSettings setDrawingCacheEnabled(true)",
    ),
    (
        0x291892,
        bytes.fromhex("710031470000"),
        b"\x00\x00\x00\x00\x00\x00",
        "HabitWebView.initSettings enableSlowWholeDocumentDraw",
    ),
]

def patch_dex(src: Path, dst: Path) -> None:
    data = bytearray(src.read_bytes())
    sha = hashlib.sha256(data).hexdigest()
    if sha != EXPECTED_SHA256:
        raise SystemExit(
            f"Refusing to patch unexpected classes.dex.\n"
            f"expected={EXPECTED_SHA256}\nactual={sha}"
        )

    for off, expected, replacement, label in PATCHES:
        actual = bytes(data[off:off + len(expected)])
        if actual != expected:
            raise SystemExit(
                f"Guard failed for {label} at 0x{off:x}: "
                f"expected {expected.hex()} got {actual.hex()}"
            )
        data[off:off + len(replacement)] = replacement

    # DEX signature is SHA-1 over bytes [32:].
    data[12:32] = hashlib.sha1(data[32:]).digest()
    # DEX checksum is adler32 over bytes [12:].
    struct.pack_into("<I", data, 8, zlib.adler32(data[12:]) & 0xFFFFFFFF)

    dst.write_bytes(data)

    # Verify post-write integrity.
    out = dst.read_bytes()
    assert out[12:32] == hashlib.sha1(out[32:]).digest()
    assert struct.unpack_from("<I", out, 8)[0] == (zlib.adler32(out[12:]) & 0xFFFFFFFF)

    print("patched:", dst)
    print("sha256:", hashlib.sha256(out).hexdigest())
    for off, _, replacement, label in PATCHES:
        print(f"0x{off:x}: {label} -> {replacement.hex()}")

def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    args = ap.parse_args()
    patch_dex(Path(args.src), Path(args.dst))

if __name__ == "__main__":
    main()
