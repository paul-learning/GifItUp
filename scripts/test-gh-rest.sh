#!/usr/bin/env bash
# Tests for scripts/gh-rest.sh (#96) against a fake gh. Runs in CI (job shell-and-workflow-lint) and locally:
# `bash scripts/test-gh-rest.sh` (Git Bash on Windows). It never reaches GitHub: the fake gh comes first on a PATH of
# only the system dirs (a real gh can still sit in /usr/bin on CI runners, #98 SEC-16), the token is invalid and the gh
# config dir is empty, and every case checks that the fake was the one called (round-0 SEC-6).
set -euo pipefail

script="$(cd "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/gh-rest.sh"
repo_root=$(dirname -- "$(dirname -- "$script")")
work=$(mktemp -d /tmp/gh-rest-test.XXXXXX)
local_dir="$repo_root/.gh-rest"
created_local_dir=0
[ -d "$local_dir" ] || { mkdir "$local_dir"; created_local_dir=1; }
cleanup() {
  rm -rf -- "$work" "$local_dir/test-$$.md"
  if [ "$created_local_dir" = 1 ]; then rmdir -- "$local_dir" 2> /dev/null || true; fi
}
trap cleanup EXIT

mkdir "$work/bin" "$work/config" "$work/log"
# The fake writes one file per call (one argument per line) and keeps a copy of any body=@file it was given.
# A GET on pulls/<n> answers with $FAKE_PR (state, head repo, head ref, the body's first line), like the script's --jq
# asks for. With $FAKE_PR_JSON set instead, it runs the script's own --jq filter through jq on that PR JSON, so the filter
# itself is tested (#98 smoke test: a check that passed the canned answer failed on GitHub).
cat > "$work/bin/gh" << 'FAKE'
#!/usr/bin/env bash
n=$(( $(find "$FAKE_LOG" -name 'call.*' -not -name '*.body' | wc -l) + 1 ))
printf '%s\n' "$@" > "$FAKE_LOG/call.$n"
for arg in "$@"; do
  case $arg in body=@*) cat -- "${arg#body=@}" > "$FAKE_LOG/call.$n.body" ;; esac
done
case " $* " in
  *" --method "*) echo "https://github.com/paul-learning/GifItUp/fake/$n" ;;
  *)
    if [ -n "${FAKE_PR_JSON:-}" ]; then
      filter=
      while [ $# -gt 0 ]; do [ "$1" = --jq ] && filter=$2; shift; done
      printf '%s' "$FAKE_PR_JSON" | jq -r "$filter"
    else
      printf '%s\n' "$FAKE_PR"
    fi
    ;;
esac
FAKE
chmod +x "$work/bin/gh"

export PATH="$work/bin:/usr/bin:/bin" GH_TOKEN=invalid GH_CONFIG_DIR="$work/config" FAKE_LOG="$work/log"
unset GH_HOST GH_REPO
marker='_Posted by Claude Code via scripts/gh-rest.sh._'
open_pr=$'open\tpaul-learning/GifItUp\tfeat/96-x\t'"$marker"
failures=0
fail() { echo "FAIL: $1"; failures=$((failures + 1)); }

body="$work/body.md"
printf 'Hello\n' > "$body"

reset_log() { rm -f -- "$work"/log/*; }
calls() { find "$work/log" -name 'call.*' -not -name '*.body' | wc -l | tr -d ' '; }

# expect_ok <name> <expected argv of the last call, one per line> -- <args…>
expect_ok() {
  local name=$1 expected=$2
  shift 3
  reset_log
  local out status=0
  out=$(FAKE_PR=${FAKE_PR:-$open_pr} bash "$script" "$@" 2>&1) || status=$?
  [ "$status" = 0 ] || { fail "$name: exit $status ($out)"; return; }
  local n
  n=$(calls)
  [ "$n" -ge 1 ] || { fail "$name: the fake gh was never called"; return; }
  local actual
  actual=$(sed -E 's#^body=@.*#body=@<file>#' "$work/log/call.$n")
  [ "$actual" = "$expected" ] || fail "$name: argv was"$'\n'"$actual"
  [ "$(head -n 1 "$work/log/call.$n.body")" = "$marker" ] || fail "$name: body doesn't start with the marker"
  [ "$(tail -n 1 "$work/log/call.$n.body")" = "$marker" ] || fail "$name: body doesn't end with the marker"
  sed -n 3p "$work/log/call.$n.body" | grep -qx Hello || fail "$name: body content lost"
}

# expect_refused <name> <args…>: exit 2 and gh never called.
expect_refused() {
  local name=$1
  shift
  reset_log
  local status=0
  FAKE_PR=${FAKE_PR:-$open_pr} bash "$script" "$@" > /dev/null 2>&1 || status=$?
  [ "$status" = 2 ] || fail "$name: expected exit 2, got $status"
  [ "$(calls)" = 0 ] || fail "$name: gh was called"
}

# expect_refused_after_read <name> <args…>: exit 2 after only the read of the PR, never a write.
expect_refused_after_read() {
  local name=$1
  shift
  reset_log
  local status=0
  FAKE_PR=${FAKE_PR:-} bash "$script" "$@" > /dev/null 2>&1 || status=$?
  [ "$status" = 2 ] || fail "$name: expected exit 2, got $status"
  [ "$(calls)" = 1 ] || { fail "$name: expected exactly the read, got $(calls) calls"; return; }
  grep -qx -- --method "$work/log/call.1" && fail "$name: the one call was a write"
  return 0
}

nl=$'\n'
api_create="api${nl}--method${nl}POST${nl}repos/paul-learning/GifItUp/pulls"

# Allowed calls.
expect_ok "pr-create" "$api_create${nl}-f${nl}head=feat/96-x${nl}-f${nl}base=main${nl}-f${nl}title=T${nl}-F${nl}body=@<file>${nl}--jq${nl}.html_url" \
  -- pr-create feat/96-x main T "$body"
expect_ok "pr-create stacked, draft" "$api_create${nl}-f${nl}head=feat/79-b/part-2${nl}-f${nl}base=feat/79-a${nl}-f${nl}title=T${nl}-F${nl}body=@<file>${nl}-F${nl}draft=true${nl}--jq${nl}.html_url" \
  -- pr-create feat/79-b/part-2 feat/79-a T "$body" --draft
expect_ok "pr-edit" "api${nl}--method${nl}PATCH${nl}repos/paul-learning/GifItUp/pulls/94${nl}-f${nl}title=New title${nl}-F${nl}body=@<file>${nl}--jq${nl}.html_url" \
  -- pr-edit 94 "New title" "$body"
grep -qx 'repos/paul-learning/GifItUp/pulls/94' "$work/log/call.1" || fail "pr-edit: no read of the PR first"
expect_ok "comment" "api${nl}--method${nl}POST${nl}repos/paul-learning/GifItUp/issues/96/comments${nl}-F${nl}body=@<file>${nl}--jq${nl}.html_url" \
  -- comment 96 "$body"
printf 'Hello\n' > "$local_dir/test-$$.md"
expect_ok "comment, body in .gh-rest/" "api${nl}--method${nl}POST${nl}repos/paul-learning/GifItUp/issues/1/comments${nl}-F${nl}body=@<file>${nl}--jq${nl}.html_url" \
  -- comment 1 "$local_dir/test-$$.md"
# shellcheck disable=SC2016 # the backticks are literal Markdown, not a command substitution
printf 'Hello\nQuoting the rules is fine: `password=`, -----BEGIN [A-Z ]*PRIVATE KEY, gh[pousr]_\n' > "$work/mentions.md"
expect_ok "comment that mentions secret patterns" "api${nl}--method${nl}POST${nl}repos/paul-learning/GifItUp/issues/2/comments${nl}-F${nl}body=@<file>${nl}--jq${nl}.html_url" \
  -- comment 2 "$work/mentions.md"

printf 'Hello\n<!-- hides everything after it in the web view\n' > "$work/comment-open.md"
expect_ok "comment ending in an unclosed <!--" "api${nl}--method${nl}POST${nl}repos/paul-learning/GifItUp/issues/3/comments${nl}-F${nl}body=@<file>${nl}--jq${nl}.html_url" \
  -- comment 3 "$work/comment-open.md"

# Subcommands and arguments.
expect_refused "no arguments"
expect_refused "merge" merge 94
expect_refused "review" review 94 --approve
expect_refused "unknown" close 94
expect_refused "extra argument" comment 96 "$body" extra
expect_refused "-X DELETE" comment 96 "$body" -X DELETE
expect_refused "--method" comment --method PUT "$body"
expect_refused "missing argument" comment 96

# Numbers and branches.
expect_refused "path as number" comment 1/merge "$body"
expect_refused "dot path as number" comment ../x "$body"
expect_refused "zero" comment 0 "$body"
expect_refused "too long a number" comment 1234567 "$body"
expect_refused "head main" pr-create main main T "$body"
expect_refused "head refspec" pr-create HEAD:main main T "$body"
expect_refused "head +main" pr-create +main main T "$body"
expect_refused "head .lock" pr-create feat/x.lock main T "$body"
expect_refused "head double slash" pr-create feat//x main T "$body"
expect_refused "head trailing slash" pr-create feat/96-x/ main T "$body"
expect_refused "head without issue number" pr-create feat/x main T "$body"
expect_refused "base not matching" pr-create feat/96-x develop T "$body"
expect_refused "head equals base" pr-create feat/96-x feat/96-x T "$body"
expect_refused "draft misspelt" pr-create feat/96-x main T "$body" --draft=false

# pr-edit only on open, same-repo, marked PRs.
FAKE_PR=$'closed\tpaul-learning/GifItUp\tfeat/96-x\t'"$marker" expect_refused_after_read "closed PR" pr-edit 94 T "$body"
FAKE_PR=$'open\tsomeone/GifItUp\tfeat/96-x\t'"$marker" expect_refused_after_read "fork PR" pr-edit 94 T "$body"
FAKE_PR=$'open\tpaul-learning/GifItUp\tmain\t'"$marker" expect_refused_after_read "head main" pr-edit 94 T "$body"
FAKE_PR=$'open\tpaul-learning/GifItUp\tfeat/96-x\tOwner text' expect_refused_after_read "owner-written body" pr-edit 94 T "$body"
FAKE_PR='' expect_refused_after_read "read failed" pr-edit 94 T "$body"

# The script's real --jq filter on real PR bodies (#98 smoke test). jq ships with CI's runners; Git Bash may lack it.
pr_json() { jq -cn --arg body "$1" '{state: "open", head: {repo: {full_name: "paul-learning/GifItUp"}, ref: "feat/96-x"}, body: $body}'; }
if command -v jq > /dev/null; then
  footer=$'\n\n---\n_Generated by [Claude Code](https://claude.ai/code/session_01x)_\n'
  ours="$marker"$'\n\nHello\n\n'"$marker"
  edit_ok="api${nl}--method${nl}PATCH${nl}repos/paul-learning/GifItUp/pulls/94${nl}-f${nl}title=T${nl}-F${nl}body=@<file>${nl}--jq${nl}.html_url"
  FAKE_PR_JSON=$(pr_json "$ours") expect_ok "pr-edit, our body" "$edit_ok" -- pr-edit 94 T "$body"
  FAKE_PR_JSON=$(pr_json "$ours$footer") expect_ok "pr-edit, cloud footer appended" "$edit_ok" -- pr-edit 94 T "$body"
  FAKE_PR_JSON=$(pr_json "${ours//$'\n'/$'\r\n'}") expect_ok "pr-edit, CRLF body" "$edit_ok" -- pr-edit 94 T "$body"
  FAKE_PR_JSON=$(pr_json $'Owner text\n\n'"$marker") expect_refused_after_read "owner body ending with the marker" pr-edit 94 T "$body"
  FAKE_PR_JSON=$(pr_json " $marker") expect_refused_after_read "marker not at the very start" pr-edit 94 T "$body"
  FAKE_PR_JSON=$(pr_json "$marker more on the same line") expect_refused_after_read "marker plus text on line 1" pr-edit 94 T "$body"
  FAKE_PR_JSON=$(jq -cn '{state: "open", head: {repo: {full_name: "paul-learning/GifItUp"}, ref: "feat/96-x"}, body: null}') \
    expect_refused_after_read "no body" pr-edit 94 T "$body"
else
  echo "SKIP: jq not installed, so the script's --jq filter isn't tested here (CI runs it)"
fi

# Body files.
expect_refused "missing body" comment 96 "$work/nope.md"
: > "$work/empty.md"
expect_refused "empty body" comment 96 "$work/empty.md"
head -c 65530 /dev/zero | tr '\0' a > "$work/big.md"
expect_refused "body over the limit with the marker" comment 96 "$work/big.md"
printf 'Hello\n' > "$work/body.txt"
expect_refused "not .md" comment 96 "$work/body.txt"
expect_refused "dash path" comment 96 -
# Git Bash on Windows copies instead of linking by default, so these two run only where symlinks exist; CI sets
# GH_REST_TEST_REQUIRE_SYMLINKS=1 so they can't be skipped there.
mkdir "$work/real"
printf 'Hello\n' > "$work/real/b.md"
ln -s "$body" "$work/link.md" 2> /dev/null || true
ln -s "$work/real" "$work/linkdir" 2> /dev/null || true
if [ -L "$work/link.md" ] && [ -L "$work/linkdir" ]; then
  expect_refused "symlinked file" comment 96 "$work/link.md"
  expect_refused "symlinked parent" comment 96 "$work/linkdir/b.md"
elif [ "${GH_REST_TEST_REQUIRE_SYMLINKS:-0}" = 1 ]; then
  fail "symlink cases: this system created no symlinks"
else
  echo "skipped: symlink cases (no symlinks on this system)"
fi
printf 'Hello\n' > "$work/hard-src.md"
ln "$work/hard-src.md" "$work/hard.md" 2> /dev/null && expect_refused "hard link" comment 96 "$work/hard.md"
printf 'Hello\n' > "$repo_root/gh-rest-test-$$.md"
expect_refused "outside the allowed dirs" comment 96 "$repo_root/gh-rest-test-$$.md"
rm -f -- "$repo_root/gh-rest-test-$$.md"
mkdir -p "$work/x/.git"
printf 'Hello\n' > "$work/x/.git/b.md"
expect_refused "under .git/" comment 96 "$work/x/.git/b.md"
printf 'Hello\n' > "$work/.env.md"
expect_refused "secret-file name" comment 96 "$work/.env.md"
printf 'Hello\n' > "$work/local.properties.md"
expect_refused "local.properties name" comment 96 "$work/local.properties.md"
for secret in "ghp_$(printf 'a%.0s' {1..36})" "github_pat_$(printf 'b%.0s' {1..30})" \
  '-----BEGIN OPENSSH PRIVATE KEY-----' 'storePassword=hunter2hunter2' 'password = s3cr3tvalue' \
  'DB_PASSWORD=hunter2hunter2' '"password": "s3cr3tvalue"' "password: 's3cr3tvalue'"; do
  printf 'Hello\n%s\n' "$secret" > "$work/secret.md"
  expect_refused "secret in body (${secret:0:12}…)" comment 96 "$work/secret.md"
done

# Titles.
expect_refused "empty title" pr-create feat/96-x main "" "$body"
expect_refused "title with newline" pr-create feat/96-x main "a${nl}b" "$body"
expect_refused "title starting with @" pr-create feat/96-x main "@file" "$body"
expect_refused "title starting with -" pr-create feat/96-x main "-X" "$body"
expect_refused "title too long" pr-create feat/96-x main "$(printf 't%.0s' {1..257})" "$body"

if [ "$failures" -gt 0 ]; then
  echo "$failures test(s) failed"
  exit 1
fi
echo "gh-rest.sh: all tests passed"
