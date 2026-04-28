#!/usr/bin/env bash
# replay-prod.local.sh — populate ./data-prod-replay from a local zdatapath-prod.zip.
#
# LOCAL USE ONLY. The expected zip is the developer's own production data export, which is
# gitignored (see ../.gitignore: zdatapath*). Never run this on a CI runner or commit the
# data dir.
#
# Usage:
#   cd sync-server/regression
#   ./replay-prod.local.sh                       # uses ../zdatapath-prod.zip by default
#   ./replay-prod.local.sh /path/to/dump.zip     # explicit path
set -euo pipefail

zip_path="${1:-../zdatapath-prod.zip}"

if [[ ! -f "$zip_path" ]]; then
  echo "error: $zip_path not found" >&2
  echo "expected the production data dump (zip) at that path; pass an explicit path as the first arg" >&2
  exit 1
fi

# Refuse to clobber an existing populated dir without an explicit re-run.
if [[ -d data-prod-replay && -n "$(ls -A data-prod-replay 2>/dev/null)" ]]; then
  echo "data-prod-replay/ already populated; remove it first if you want a fresh import" >&2
  exit 1
fi

mkdir -p data-prod-replay
unzip -q "$zip_path" -d data-prod-replay

# The zip ships its content under a top-level zdatapath-prod/ folder; flatten so the
# server sees accounts/ at the mount root.
if [[ -d data-prod-replay/zdatapath-prod ]]; then
  mv data-prod-replay/zdatapath-prod/* data-prod-replay/
  rmdir data-prod-replay/zdatapath-prod
fi

account_count=$(ls -1 data-prod-replay/accounts 2>/dev/null | wc -l | tr -d ' ')
echo "imported $account_count accounts into data-prod-replay/"
echo "next: docker compose up new-server"
