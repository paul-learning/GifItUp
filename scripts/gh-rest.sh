#!/usr/bin/env bash
# The one sanctioned GitHub REST write path for agent sessions without GraphQL (cloud sessions, #96).
# Three fixed calls on this repo only: open a PR, edit a marked PR's title and body, comment on an issue or PR.
# Nothing from the caller becomes a path, an HTTP method or a gh flag; there is no passthrough. It never merges,
# approves, closes or retargets. The `gh api` write denies in .claude/settings.json stay in place: this script
# routes around them on purpose, and it has no allow rule, so every run goes through the session's normal permission
# handling (#96 plan, round-0 SEC-1). Limits and known gaps: docs/security/threat-model.md.
#
#   bash scripts/gh-rest.sh pr-create <head> <base> <title> <body.md> [--draft]
#   bash scripts/gh-rest.sh pr-edit <number> <title> <body.md>
#   bash scripts/gh-rest.sh comment <number> <body.md>
#
# Bodies come only from a .md file under /tmp (the session scratchpad) or the gitignored .gh-rest/ in the repo.
# Every body gets a fixed marker as its first and last line: text with it is agent text, never owner consent
# (CLAUDE.md rule 7). Exit 2 on any refusal, before any write (pr-edit reads the PR first). Run from anywhere; Git Bash on Windows works.
set -euo pipefail

readonly REPO=paul-learning/GifItUp
readonly MARKER='_Posted by Claude Code via scripts/gh-rest.sh._'
readonly MAX_BODY_BYTES=65536
readonly MAX_TITLE_CHARS=256
readonly BRANCH_RE='^(feat|fix|chore|docs|spike)/[0-9]+-[a-z0-9-]+(/[a-z0-9-]+)?$'
readonly NUMBER_RE='^[1-9][0-9]{0,5}$'
# Values, not mentions: a review that quotes these patterns must still post (round-0 SEC-2).
readonly SECRET_RE='gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|-----BEGIN [A-Z ]*PRIVATE KEY-----'
# Matched case-insensitively: DB_PASSWORD=…, storePassword=…, "password": "…" (#98 CR-2).
readonly PASSWORD_RE='password["'"'"']?[[:space:]]*[:=][[:space:]]*["'"'"']?[^[:space:]`'"'"'"]{6,}'

# No environment redirects the call or logs it (round-0 SEC-7). GH_TOKEN/GH_CONFIG_DIR stay: they carry the auth.
unset GH_REPO GH_DEBUG GH_PAGER GH_FORCE_TTY
export GH_PROMPT_DISABLED=1 GH_HOST=github.com

refuse() {
  echo "gh-rest: refused: $1" >&2
  exit 2
}

# Absolute path in one spelling, so prefixes compare on Windows (C:/…) and Linux alike.
norm() {
  if command -v cygpath > /dev/null; then cygpath -m -- "$1"; else printf '%s\n' "$1"; fi
}

root=$(norm "$(cd "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)")
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT

check_number() {
  [[ $1 =~ $NUMBER_RE ]] || refuse "issue/PR number must be a plain number"
}

check_branch() {
  [[ $1 =~ $BRANCH_RE ]] || refuse "$2 must look like feat|fix|chore|docs|spike/<issue>-<name>"
}

check_title() {
  local title=$1
  [ -n "$title" ] || refuse "title is empty"
  [ "${#title}" -le "$MAX_TITLE_CHARS" ] || refuse "title is longer than $MAX_TITLE_CHARS characters"
  [[ $title != *$'\n'* && $title != *$'\r'* ]] || refuse "title contains a line break"
  [[ $title != [@-]* ]] || refuse "title starts with @ or -"
}

# Prints the gh-readable path of a temp copy of the body plus the marker (round-0 SEC-2, SEC-3).
prepare_body() {
  local file=$1 base resolved literal allowed=0 dir
  [[ $file != -* ]] || refuse "body file path starts with -"
  [[ $file == *.md ]] || refuse "body file must be a .md file"
  if [ ! -f "$file" ] || [ -L "$file" ]; then refuse "body file is missing or not a regular file"; fi
  resolved=$(norm "$(realpath -e -- "$file")")
  literal=$(norm "$(realpath -s -- "$file")")
  [ "$resolved" = "$literal" ] || refuse "body file path goes through a symlink"
  [ "$(stat -c %h -- "$file")" = 1 ] || refuse "body file has more than one hard link"
  for dir in "$(norm "$(realpath -e /tmp)")" "$root/.gh-rest"; do
    [[ $resolved == "$dir"/* ]] && allowed=1
  done
  [ "$allowed" = 1 ] || refuse "body file must be under /tmp or .gh-rest/"
  [[ $resolved != */.git/* ]] || refuse "body file is under .git/"
  base=${resolved##*/}
  case $base in
    local.properties* | .env* | *.jks* | *.keystore*) refuse "body file name matches a secret-file name" ;;
  esac
  # Every later check runs on the copy, which is exactly what gets sent (#98 CR-3).
  local copy="$work/source.md" sent="$work/body.md"
  cat -- "$resolved" > "$copy" || refuse "body file can't be read"
  [ -s "$copy" ] || refuse "body file is empty"
  scan "$copy" -E "$SECRET_RE"
  scan "$copy" -Ei "$PASSWORD_RE"
  # The marker opens the body too, so an unclosed <!-- or <details> at the end can't hide it in the web view (#98 SEC-11).
  { printf '%s\n\n' "$MARKER"; cat -- "$copy"; printf '\n\n%s\n' "$MARKER"; } > "$sent"
  [ "$(wc -c < "$sent")" -le "$MAX_BODY_BYTES" ] || refuse "body is larger than $MAX_BODY_BYTES bytes"
  norm "$sent"
}

# scan <file> <grep flags> <pattern>: refuses on a match, and on a grep error too (status > 1).
scan() {
  local status=0
  grep -q "$2" -- "$3" "$1" || status=$?
  # Fails closed on prose like "Password: required" too (#98 CR-6): reword such a line, e.g. "the password field".
  [ "$status" = 1 ] || refuse "body contains something that looks like a token, key or password, or can't be scanned (reword a 'password: …' line if it's prose)"
}

pr_create() {
  [ $# -eq 4 ] || [ $# -eq 5 ] || refuse "usage: pr-create <head> <base> <title> <body.md> [--draft]"
  local head=$1 base=$2 title=$3 body draft=()
  check_branch "$head" head
  [ "$base" = main ] || check_branch "$base" base
  [ "$head" != "$base" ] || refuse "head and base are the same branch"
  if [ $# -eq 5 ]; then
    [ "$5" = --draft ] || refuse "the only option is a final --draft"
    draft=(-F draft=true)
  fi
  check_title "$title"
  body=$(prepare_body "$4")
  gh api --method POST "repos/$REPO/pulls" -f "head=$head" -f "base=$base" -f "title=$title" \
    -F "body=@$body" "${draft[@]}" --jq .html_url
}

# Only open PRs from a branch of this repo whose body already ends with the marker: never an owner-written body.
pr_edit() {
  [ $# -eq 3 ] || refuse "usage: pr-edit <number> <title> <body.md>"
  local number=$1 title=$2 body state repo ref marked
  check_number "$number"
  check_title "$title"
  body=$(prepare_body "$3")
  # A failed read leaves the fields empty, so the checks below refuse.
  IFS=$'\t' read -r state repo ref marked < <(gh api "repos/$REPO/pulls/$number" --jq \
    '[.state, (.head.repo.full_name // ""), .head.ref, ((.body // "") | sub("\\s+$"; "") | endswith("'"$MARKER"'"))] | @tsv') || true
  [ "$state" = open ] || refuse "PR #$number is not open"
  [ "$repo" = "$REPO" ] || refuse "PR #$number comes from another repository"
  [[ $ref =~ $BRANCH_RE ]] || refuse "PR #$number has a head branch outside the naming scheme"
  [ "$marked" = true ] || refuse "PR #$number has a body without the marker (owner-written); edit it by hand"
  gh api --method PATCH "repos/$REPO/pulls/$number" -f "title=$title" -F "body=@$body" --jq .html_url
}

comment() {
  [ $# -eq 2 ] || refuse "usage: comment <number> <body.md>"
  local number=$1 body
  check_number "$number"
  body=$(prepare_body "$2")
  gh api --method POST "repos/$REPO/issues/$number/comments" -F "body=@$body" --jq .html_url
}

[ $# -ge 1 ] || refuse "usage: gh-rest.sh pr-create|pr-edit|comment …"
cmd=$1
shift
case $cmd in
  pr-create) pr_create "$@" ;;
  pr-edit) pr_edit "$@" ;;
  comment) comment "$@" ;;
  *) refuse "unknown subcommand (only pr-create, pr-edit, comment)" ;;
esac
