#!/usr/bin/env python3
"""Checks the web app's bundled emoji font (ADR-0014): it is exactly the pinned file, it is a color bitmap font
(CBDT/CBLC), its character map holds exactly the 256 code points of the key-comparison table and nothing else, and its
license text ships next to it. Standard library only (it reads the font's tables itself).

    python3 -I scripts/check-emoji-font.py <emoji-v1.ttf> <fingerprint-emojis-v1.tsv> <emoji-v1-OFL.txt>
"""
import hashlib
import struct
import sys

# SHA-256 of composeApp/src/wasmJsMain/resources/emoji-v1.ttf. Changing the font means changing this line, the notes
# in ADR-0014 and the license file's source line in the same PR.
PINNED_SHA256 = "aadbb28c5e59dae628831c4fd20f4f8035f2033553ac293f06d5394e0f71e9c4"
MAX_BYTES = 1_000_000


def table_directory(data):
    if data[:4] not in (b"\x00\x01\x00\x00", b"true"):
        raise ValueError("not a TrueType font file")
    count = struct.unpack(">H", data[4:6])[0]
    tables = {}
    for i in range(count):
        tag, _checksum, offset, length = struct.unpack(">4sIII", data[12 + 16 * i : 28 + 16 * i])
        if offset + length > len(data):
            raise ValueError("table %r runs past the end of the file" % tag)
        tables[tag.decode("latin-1")] = (offset, length)
    return tables


def code_points(data, cmap):
    offset, length = cmap
    count = struct.unpack(">H", data[offset + 2 : offset + 4])[0]
    found = set()
    for i in range(count):
        _platform, _encoding, sub = struct.unpack(">HHI", data[offset + 4 + 8 * i : offset + 12 + 8 * i])
        start = offset + sub
        fmt = struct.unpack(">H", data[start : start + 2])[0]
        if fmt == 4:
            seg2 = struct.unpack(">H", data[start + 6 : start + 8])[0]
            segs = seg2 // 2
            ends = struct.unpack(">%dH" % segs, data[start + 14 : start + 14 + seg2])
            starts = struct.unpack(">%dH" % segs, data[start + 16 + seg2 : start + 16 + 2 * seg2])
            for lo, hi in zip(starts, ends):
                if lo != 0xFFFF:
                    found.update(range(lo, hi + 1))
        elif fmt == 12:
            groups = struct.unpack(">I", data[start + 12 : start + 16])[0]
            for g in range(groups):
                lo, hi, _gid = struct.unpack(">III", data[start + 16 + 12 * g : start + 28 + 12 * g])
                found.update(range(lo, hi + 1))
        else:
            raise ValueError("unexpected cmap format %d" % fmt)
    return found


def main(font_path, tsv_path, license_path):
    problems = []
    data = open(font_path, "rb").read()
    if hashlib.sha256(data).hexdigest() != PINNED_SHA256:
        problems.append("the font's SHA-256 is not the pinned one (update PINNED_SHA256 and ADR-0014 together)")
    if len(data) > MAX_BYTES:
        problems.append("the font is %d bytes, more than %d" % (len(data), MAX_BYTES))
    tables = table_directory(data)
    for needed in ("CBDT", "CBLC", "cmap"):
        if needed not in tables:
            problems.append("the font has no %s table (it must be a color bitmap font)" % needed)
    if "cmap" in tables:
        want = set()
        for line in open(tsv_path, encoding="utf-8"):
            if line.strip() and not line.startswith("#"):
                want.add(int(line.split("\t")[1].replace("U+", ""), 16))
        have = code_points(data, tables["cmap"])
        if len(want) != 256:
            problems.append("the table lists %d code points, expected 256" % len(want))
        if have != want:
            problems.append(
                "character map differs from the table: missing %s, extra %s"
                % (sorted(hex(c) for c in want - have), sorted(hex(c) for c in have - want))
            )
    text = open(license_path, encoding="utf-8").read()
    if "SIL OPEN FONT LICENSE Version 1.1" not in text or "Copyright 2013-2017 Google Inc." not in text:
        problems.append("the license file does not hold the SIL Open Font License 1.1 and the copyright line")
    for p in problems:
        print("::error::" + p)
    if problems:
        return 1
    print("Emoji font checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:4]))
