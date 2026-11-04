#!/usr/bin/env bash
# Deploys one GifItUp server release on the VM (docs/ops/deploy.md). It is the forced command of the CI deploy key:
#   restrict,command="/opt/gifitup/deploy/deploy.sh" ssh-ed25519 AAAA... gifitup-ci
# Input via SSH_ORIGINAL_COMMAND: "<40-hex commit> sha256:<64-hex digest>". It checks out exactly that commit
# (compose + this script) and runs that image. Output ends up in public Actions logs, so it prints only fixed
# status lines; command output goes to the journal (journalctl -t gifitup-deploy).
set -euo pipefail

home=${GIFITUP_HOME:-/opt/gifitup}
repo=$home/repo
compose_dir=$home/deploy
state_file=$home/deploy-state   # "<commit> <digest>" of the last successful deploy
# Overridable for local tests only; an SSH client can't set it (sshd passes no client env to forced commands
# except LANG/LC_*).
image_repo=${GIFITUP_IMAGE_REPO:-ghcr.io/paul-learning/gifitup-server}
web_repo=${GIFITUP_WEB_IMAGE_REPO:-ghcr.io/paul-learning/gifitup-web}
health_timeout=${GIFITUP_HEALTH_TIMEOUT:-90}

say() { echo "gifitup-deploy: $*"; }
log() { logger -t gifitup-deploy -- "$*" 2>/dev/null || true; }
# Runs a command with its output in the journal only.
quiet() {
  local out rc=0
  out=$("$@" 2>&1) || rc=$?
  log "$* -> exit $rc"
  [ -z "$out" ] || log "$out"
  return "$rc"
}
reject() { say "rejected: $1"; log "rejected: $1"; exit 2; }

valid_pair() { [[ "$1" =~ ^[0-9a-f]{40}\ sha256:[0-9a-f]{64}$ ]]; }

# Stage 1 (forced command, no arguments): validate before anything else, lock, check out the approved commit, then
# re-exec that commit's copy of this script. sshd never passes client arguments to a forced command, so stage 2
# can only be reached from here.
if [ "$#" -eq 0 ]; then
  input=${SSH_ORIGINAL_COMMAND:-}
  valid_pair "$input" || reject "expected '<commit> sha256:<digest>'"
  commit=${input%% *}
  digest=${input#* }

  exec 9>"$home/deploy.lock"
  flock 9

  quiet git -C "$repo" fetch --quiet origin main || reject "git fetch failed"
  quiet git -C "$repo" merge-base --is-ancestor "$commit" origin/main || reject "$commit is not on main"
  # Commits from before #7 have no deploy.sh; checking one out would delete this forced command.
  quiet git -C "$repo" cat-file -e "$commit:deploy/deploy.sh" || reject "$commit has no deploy/deploy.sh"
  quiet git -C "$repo" checkout --quiet --detach "$commit" || reject "checkout of $commit failed"
  exec "$repo/deploy/deploy.sh" --stage2 "$commit" "$digest"   # fd 9 (the lock) stays open
fi

# Stage 2: running from the checked-out commit. Stage 1 always runs from the *previously* deployed commit, so this
# hand-off (--stage2 <commit> <digest>) is a compatibility contract: keep it stable across all deployable commits.
if [ "$#" -ne 3 ] || [ "$1" != --stage2 ] || ! valid_pair "$2 $3"; then
  reject "bad stage-2 arguments"
fi
commit=$2
digest=$3
[ "$(git -C "$repo" rev-parse HEAD)" = "$commit" ] || reject "checked-out commit does not match"

env_file=$compose_dir/.env
[ -f "$env_file" ] || reject ".env missing in $compose_dir"
# Reads one key from .env without sourcing it.
env_value() { sed -n "s/^$1=//p" "$env_file" | head -n 1; }
# Replaces only the line of one image variable, atomically, keeping the file private.
set_env() {
  local tmp
  tmp=$(mktemp "$env_file.XXXXXX")
  grep -v "^$1=" "$env_file" > "$tmp" || true
  printf '%s=%s\n' "$1" "$2" >> "$tmp"
  chmod 600 "$tmp"
  mv "$tmp" "$env_file"
}
set_image() { set_env GIFITUP_SERVER_IMAGE "$1"; }

public_url=$(env_value GIFITUP_PUBLIC_URL)
[[ "$public_url" =~ ^https://[A-Za-z0-9.-]+(:[0-9]+)?$ ]] || reject "GIFITUP_PUBLIC_URL in .env is missing or invalid"

compose() { docker compose --project-directory "$compose_dir" "$@"; }

# Healthy = the server container runs exactly this image and /health answers 200 through Caddy, and the web container
# (when the release has one) runs its image and answers.
healthy() {
  local want=$1 want_web=${2:-} deadline=$((SECONDS + health_timeout)) cid image code
  while [ "$SECONDS" -lt "$deadline" ]; do
    cid=$(compose ps -q server 2>/dev/null || true)
    image=$([ -n "$cid" ] && docker inspect --format '{{.Config.Image}}' "$cid" 2>/dev/null || true)
    if [ "$image" = "$want" ]; then
      code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 --proto =https "$public_url/health" 2>/dev/null || true)
      [ "$code" = 200 ] && web_healthy "$want_web" && return 0
    fi
    sleep 3
  done
  return 1
}

# The web image that belongs to a server image (#27): the release workflow puts "<repo>@sha256:<digest>" into the server
# image's label before attesting it, so the one verified digest names both. Prints nothing unless the label is exactly a
# digest of our own web repository (a release without a web image, or any other value, means no web).
web_for() {
  local ref
  ref=$(docker image inspect --format '{{ index .Config.Labels "app.gifitup.web-image" }}' "$1" 2>/dev/null || true)
  if [ "${ref%@*}" = "$web_repo" ] && [[ "${ref#*@}" =~ ^sha256:[0-9a-f]{64}$ ]]; then printf '%s' "$ref"; fi
}

# Starts the server image $1 and, when it names one, its web image; otherwise the web service is stopped.
start() {
  local web
  quiet compose pull server || return 1
  web=$(web_for "$1")
  if [ -n "$web" ]; then
    set_env GIFITUP_WEB_IMAGE "$web"
    quiet compose --profile web pull web && quiet compose --profile web up -d server web
  else
    quiet compose up -d server && { quiet compose --profile web stop web || true; }
  fi
}

# The web container runs exactly this image and answers on its own port. Nothing is said about the public site: until
# the VM's Caddy has the new site block, "/" is not served by this container.
web_healthy() {
  local want=$1 cid image
  [ -n "$want" ] || return 0
  cid=$(compose --profile web ps -q web 2>/dev/null || true)
  image=$([ -n "$cid" ] && docker inspect --format '{{.Config.Image}}' "$cid" 2>/dev/null || true)
  [ "$image" = "$want" ] && compose --profile web exec -T web wget -q -O /dev/null http://127.0.0.1:8080/index.html >/dev/null 2>&1
}

# The containers read their secrets as UID 10001 through a bind mount, so the files must exist, be non-empty and
# world-readable (they sit in the 0700 secrets/ directory; docs/ops/vm-setup.md). Names only, never contents.
# The directory is what keeps them private on the host: mode 0700, owned by the user running this script (#69 SEC-17).
secrets_dir=$compose_dir/secrets
if [ ! -d "$secrets_dir" ] || [ -L "$secrets_dir" ]; then reject "secrets/ missing (docs/ops/vm-setup.md)"; fi
[ "$(stat -c '%a %u' "$secrets_dir")" = "700 $(id -u)" ] || reject "secrets/ must be mode 0700 and owned by $(id -un)"
for secret in postgres_password postgres_app_password klipy_api_key; do
  file=$secrets_dir/$secret
  if [ ! -f "$file" ] || [ -L "$file" ]; then reject "secrets/$secret missing or a symlink (docs/ops/vm-setup.md)"; fi
  [ -s "$file" ] || reject "secrets/$secret is empty (docs/ops/vm-setup.md)"
  [ -n "$(find "$file" -perm -o=r)" ] || reject "secrets/$secret must be mode 0444 (docs/ops/vm-setup.md)"
done
# The key's shape (ADR-0003, #40): one line of 16 to 128 of [A-Za-z0-9_-]. The content is only tested, never printed; a key
# outside it would fail the server's startup and roll the deploy back, so it is rejected up front.
key_file=$secrets_dir/klipy_api_key
[ "$(grep -c '' "$key_file")" = 1 ] || reject "secrets/klipy_api_key must be one line (docs/ops/vm-setup.md)"
LC_ALL=C grep -Eqx '[A-Za-z0-9_-]{16,128}' "$key_file" || reject "secrets/klipy_api_key is not a valid key (docs/ops/vm-setup.md)"

# Schema migration with the NEW image, before the server switches over (ADR-0008). The running server is untouched
# if it fails. Migrations are expand-only, so the old server keeps working on the migrated schema (rollback below).
# The shell variable overrides .env for this one command only; `run` pulls the digest-pinned image if it's missing and
# starts no dependency that isn't already up (postgres is).
migrate() { GIFITUP_SERVER_IMAGE=$1 quiet compose --profile migrate run --rm migrate; }

image=$image_repo@$digest
say "deploying $commit ($digest)"
migrate "$image" || reject "migration failed; server unchanged (journalctl -t gifitup-deploy)"
set_image "$image"
if start "$image" && healthy "$image" "$(web_for "$image")"; then
  printf '%s %s\n' "$commit" "$digest" > "$state_file.tmp" && mv "$state_file.tmp" "$state_file"
  say "deployed"
  exit 0
fi

# Roll back to the last successful deploy: its commit (compose + script) and its image.
if [ -s "$state_file" ] && valid_pair "$(cat "$state_file")"; then
  read -r prev_commit prev_digest < "$state_file"
  quiet git -C "$repo" checkout --quiet --detach "$prev_commit" || true
  set_image "$image_repo@$prev_digest"
  if start "$image_repo@$prev_digest" && healthy "$image_repo@$prev_digest" "$(web_for "$image_repo@$prev_digest")"; then
    say "health check failed; rolled back to $prev_commit"
  else
    say "health check failed; rollback to $prev_commit is unhealthy too, check the VM"
  fi
else
  quiet compose stop server || true
  say "health check failed; no earlier deploy to roll back to, server stopped"
fi
exit 1
