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
  quiet git -C "$repo" checkout --quiet --detach "$commit" || reject "checkout of $commit failed"
  exec "$repo/deploy/deploy.sh" --stage2 "$commit" "$digest"   # fd 9 (the lock) stays open
fi

# Stage 2: running from the checked-out commit.
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
# Replaces only the GIFITUP_SERVER_IMAGE line, atomically, keeping the file private.
set_image() {
  local tmp
  tmp=$(mktemp "$env_file.XXXXXX")
  grep -v '^GIFITUP_SERVER_IMAGE=' "$env_file" > "$tmp" || true
  printf 'GIFITUP_SERVER_IMAGE=%s\n' "$1" >> "$tmp"
  chmod 600 "$tmp"
  mv "$tmp" "$env_file"
}

public_url=$(env_value GIFITUP_PUBLIC_URL)
[[ "$public_url" =~ ^https://[A-Za-z0-9.-]+(:[0-9]+)?$ ]] || reject "GIFITUP_PUBLIC_URL in .env is missing or invalid"

compose() { docker compose --project-directory "$compose_dir" "$@"; }

# Healthy = the server container runs exactly this image and /health answers 200 through Caddy.
healthy() {
  local want=$1 deadline=$((SECONDS + health_timeout)) cid image code
  while [ "$SECONDS" -lt "$deadline" ]; do
    cid=$(compose ps -q server 2>/dev/null || true)
    image=$([ -n "$cid" ] && docker inspect --format '{{.Config.Image}}' "$cid" 2>/dev/null || true)
    if [ "$image" = "$want" ]; then
      code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 --proto =https "$public_url/health" 2>/dev/null || true)
      [ "$code" = 200 ] && return 0
    fi
    sleep 3
  done
  return 1
}

start() { quiet compose pull server && quiet compose up -d server; }

image=$image_repo@$digest
say "deploying $commit ($digest)"
set_image "$image"
if start && healthy "$image"; then
  printf '%s %s\n' "$commit" "$digest" > "$state_file.tmp" && mv "$state_file.tmp" "$state_file"
  say "deployed"
  exit 0
fi

# Roll back to the last successful deploy: its commit (compose + script) and its image.
if [ -s "$state_file" ] && valid_pair "$(cat "$state_file")"; then
  read -r prev_commit prev_digest < "$state_file"
  quiet git -C "$repo" checkout --quiet --detach "$prev_commit" || true
  set_image "$image_repo@$prev_digest"
  if start && healthy "$image_repo@$prev_digest"; then
    say "health check failed; rolled back to $prev_commit"
  else
    say "health check failed; rollback to $prev_commit is unhealthy too, check the VM"
  fi
else
  quiet compose stop server || true
  say "health check failed; no earlier deploy to roll back to, server stopped"
fi
exit 1
