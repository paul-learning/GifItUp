#!/usr/bin/env bash
# Turns a release tag into the app's version (#27 PR 2). Input is untrusted text (a pushed tag name): it is matched
# against a strict pattern before anything else, and the result is two numbers and a dotted name, never the tag itself.
#   bash scripts/release-version.sh <tag> [<other existing tag>...]
# Prints "<versionCode> <versionName>". Fails unless the tag is exactly vMAJOR.MINOR.PATCH (digits only, no leading
# zeros, MINOR and PATCH at most 99, MAJOR at most 200), and unless its versionCode (MAJOR*10000 + MINOR*100 + PATCH) is
# greater than that of every other well-formed tag given (a release must be newer than all earlier ones; a pushed
# tag that was already released can't be reused with a lower or equal number).
set -euo pipefail

tag=${1:?usage: release-version.sh <tag> [<other tag>...]}
shift
pattern='^v(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$'

code_of() { # prints the versionCode of a well-formed tag, nothing otherwise
  if [[ "$1" =~ $pattern ]]; then
    local major=${BASH_REMATCH[1]} minor=${BASH_REMATCH[2]} patch=${BASH_REMATCH[3]}
    if [ "$major" -le 200 ]; then echo $((major * 10000 + minor * 100 + patch)); fi
  fi
}

code=$(code_of "$tag")
[ -n "$code" ] || { echo "not a release tag: it must be vMAJOR.MINOR.PATCH (minor and patch up to 99, major up to 200)" >&2; exit 1; }
[ "$code" -ge 1 ] || { echo "v0.0.0 is not a release" >&2; exit 1; }
for other in "$@"; do
  [ "$other" != "$tag" ] || continue
  oc=$(code_of "$other")
  if [ -n "$oc" ] && [ "$oc" -ge "$code" ]; then
    echo "version $code is not newer than the existing release tag $other ($oc)" >&2
    exit 1
  fi
done
echo "$code ${tag#v}"
