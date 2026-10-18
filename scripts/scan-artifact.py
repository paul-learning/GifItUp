#!/usr/bin/env python3
"""Scans shipped files for strings that must not be in them (CI, "What ships"; #79, #102 SEC-13).

usage: scan-artifact.py --control <text> --forbid <text> [--forbid <text> ...] <file or dir>...

Directories are scanned recursively. Each text is searched as UTF-8 and as UTF-16LE, since Kotlin/Wasm may keep
string literals in either form. The control is a string the app is known to ship, in the same form as the forbidden ones (the app's own package
prefix); if no file holds it, the scan can't see strings in these files
at all and fails rather than passing on nothing. Exit 0: control found and nothing forbidden; 1: a forbidden string
or no control; 2: usage or read error.
"""

import os
import sys


def walk_error(error):
    raise error  # an unreadable subdirectory fails the scan instead of being skipped


def forms(text):
    return [text.encode("utf-8"), text.encode("utf-16-le")]


def main(argv):
    control, forbidden, files = None, [], []
    args = iter(argv)
    for arg in args:
        if arg == "--control":
            control = next(args, None)
        elif arg == "--forbid":
            forbidden.append(next(args, None))
        elif os.path.isdir(arg):
            try:
                files += sorted(os.path.join(root, f) for root, _, names in os.walk(arg, onerror=walk_error) for f in names)
            except OSError as error:
                print(f"::error::can't read {arg}: {error.strerror}")
                return 2
        else:
            files.append(arg)
    if not control or not forbidden or None in forbidden or not files:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    control_seen, status = False, 0
    for name in files:
        try:
            with open(name, "rb") as f:
                data = f.read()
        except OSError as error:
            print(f"::error::can't read {name}: {error.strerror}")
            return 2
        control_seen = control_seen or any(form in data for form in forms(control))
        for text in forbidden:
            if any(form in data for form in forms(text)):
                print(f"::error::{name} contains '{text}'")
                status = 1
    if not control_seen:
        print(f"::error::none of the files holds the control string '{control}': the scan sees nothing")
        status = 1
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
