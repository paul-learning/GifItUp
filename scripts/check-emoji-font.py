#!/usr/bin/env python3
"""Checks the web app's bundled emoji font (ADR-0014 *Web emoji font*, ADR-0006 B10a): it is exactly the pinned file, it is a
color bitmap font (CBDT/CBLC), its character map holds exactly the first code points of the reaction table and nothing
else, its variation-selector table maps U+FE0F for every table entry that needs it, every fingerprint emoji is in it, and
the license text ships next to it. Standard library only (it reads the font's tables itself).

    python3 -I scripts/check-emoji-font.py <emoji-v2.ttf> <emoji-reactions-v1.tsv> <fingerprint-emojis-v1.tsv> <emoji-v2-OFL.txt>
"""
import hashlib
import struct
import sys

# SHA-256 of composeApp/src/wasmJsMain/resources/emoji-v2.ttf. Changing the font means changing this line, the notes in
# ADR-0014 and the license file's source line in the same PR.
PINNED_SHA256 = "7399eb4fa3a589aa50b7dc2305532dbbfb20af812197322c3daef3a6e14f8dc3"
MAX_BYTES = 3_500_000
VS16 = 0xFE0F


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


def uint24(data, at):
    return int.from_bytes(data[at : at + 3], "big")


def variation_bases(data, start):
    """The base characters a format 14 subtable maps for U+FE0F (default and non-default records)."""
    found = set()
    records = struct.unpack(">I", data[start + 6 : start + 10])[0]
    for i in range(records):
        at = start + 10 + 11 * i
        selector = uint24(data, at)
        default_offset, non_default_offset = struct.unpack(">II", data[at + 3 : at + 11])
        if selector != VS16:
            continue
        if default_offset:
            base = start + default_offset
            for r in range(struct.unpack(">I", data[base : base + 4])[0]):
                first = uint24(data, base + 4 + 4 * r)
                extra = data[base + 4 + 4 * r + 3]
                found.update(range(first, first + extra + 1))
        if non_default_offset:
            base = start + non_default_offset
            for m in range(struct.unpack(">I", data[base : base + 4])[0]):
                found.add(uint24(data, base + 4 + 5 * m))
    return found


def cmap_sets(data, cmap):
    """(code points of the format 4 and 12 subtables, bases with a U+FE0F variation) of the character map."""
    offset, _length = cmap
    count = struct.unpack(">H", data[offset + 2 : offset + 4])[0]
    plain, variations = set(), set()
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
                    plain.update(range(lo, hi + 1))
        elif fmt == 12:
            groups = struct.unpack(">I", data[start + 12 : start + 16])[0]
            for g in range(groups):
                lo, hi, _gid = struct.unpack(">III", data[start + 16 + 12 * g : start + 28 + 12 * g])
                plain.update(range(lo, hi + 1))
        elif fmt == 14:
            variations |= variation_bases(data, start)
        else:
            raise ValueError("unexpected cmap format %d" % fmt)
    return plain, variations


def read_table(path, column):
    rows = []
    for line in open(path, encoding="utf-8"):
        if line.strip() and not line.startswith("#"):
            rows.append(line.rstrip("\n").split("\t")[column])
    return rows


def main(font_path, reactions_path, fingerprint_path, license_path):
    problems = []
    data = open(font_path, "rb").read()
    if hashlib.sha256(data).hexdigest() != PINNED_SHA256:
        problems.append("the font's SHA-256 is not the pinned one (update PINNED_SHA256 and ADR-0014 together)")
    if len(data) > MAX_BYTES:
        problems.append("the font is %d bytes, more than %d" % (len(data), MAX_BYTES))
    if problems:  # a different file: don't parse it (a crafted one could trip the reader)
        for p in problems:
            print("::error::" + p)
        return 1
    tables = table_directory(data)
    for needed in ("CBDT", "CBLC", "cmap"):
        if needed not in tables:
            problems.append("the font has no %s table (it must be a color bitmap font)" % needed)
    if "cmap" in tables:
        entries = [[int(h, 16) for h in row.split()] for row in read_table(reactions_path, 1)]
        want = {e[0] for e in entries}
        needs_variation = {e[0] for e in entries if len(e) == 2 and e[1] == VS16}
        fingerprint = {int(c.replace("U+", ""), 16) for c in read_table(fingerprint_path, 1)}
        plain, variations = cmap_sets(data, tables["cmap"])
        if len(entries) != 1156:
            problems.append("the reaction table lists %d entries, expected 1156" % len(entries))
        if plain != want:
            problems.append(
                "character map differs from the table: missing %s, extra %s"
                % (sorted(hex(c) for c in want - plain), sorted(hex(c) for c in plain - want))
            )
        if not needs_variation <= variations:
            problems.append("no U+FE0F mapping for %s" % sorted(hex(c) for c in needs_variation - variations))
        if not fingerprint <= plain:
            problems.append("fingerprint emojis missing from the font: %s" % sorted(hex(c) for c in fingerprint - plain))
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
    sys.exit(main(*sys.argv[1:5]))
