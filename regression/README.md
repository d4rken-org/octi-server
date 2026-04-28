# Cross-version regression scaffolding (local-only)

Side-by-side legacy + new server containers for verifying compatibility before a production cutover.

This directory exists for **manual local testing**. CI uses synthetic fixtures
(see `CrossVersionFlowTest` in `src/test/...`); nothing here runs in CI and no
production data is ever pushed.

## What it does

`docker-compose.yml` brings up two containers, both bound to localhost:

| Service | Port | Image | Data dir |
|---|---|---|---|
| `legacy-server` | 18080 | `ghcr.io/d4rken-org/octi-server:0.8.1` | `data-legacy/` (gitignored) |
| `new-server` | 18081 | built from this repo's `Dockerfile` | `data-prod-replay/` (gitignored) |

`replay-prod.local.sh` populates `data-prod-replay/` from a local `zdatapath-prod.zip`
(the developer's own production export — gitignored at the repo root).

## Pre-cutover runbook

Build the legacy client APK in a worktree first so you can install it on an emulator:

    git -C ../../app-main worktree add /tmp/octi-v0.8.1 v0.8.1-rc0
    (cd /tmp/octi-v0.8.1 && ./gradlew :app:assembleFossDebug)

Then bring the new server up against the production replay:

    cd sync-server/regression
    ./replay-prod.local.sh           # one-time; expects ../zdatapath-prod.zip
    docker compose up new-server     # tail logs for ~60 s, watch for recovery.* errors

Confirm:

  * No `recovery.session_malformed` cascades.
  * `ls -1 data-prod-replay/accounts | wc -l` matches the account count printed at startup.

Point an emulator at `http://10.0.2.2:18081` and run the verification matrix:

1. **Legacy client (v0.8.1) on the new server.** Pair with a fresh test account; push
   modules (battery / wifi / clipboard); reboot the new server; verify modules survive.
2. **New client on the new server.** Already covered end-to-end on `file-sharing-polish`;
   re-verify file-sharing now hits the prod-replay backend cleanly.
3. **Mixed pair: legacy client + new client on the new server.** Each side writes their
   own modules; the other side reads them. New client shares a file via the share-sheet;
   legacy client receives the module update (without the file payload — old client doesn't
   speak blob endpoints; module wire form is unchanged).
4. **New client on the legacy server** (`docker compose up legacy-server`). Pair on port
   18080. Capability probe demotes the connector to LEGACY; file-share FAB is hidden;
   regular module sync still works. With the Phase A fix, racing a share before the probe
   completes returns "no eligible connectors" instead of a raw HTTP error.

## Cleanup

    docker compose down
    rm -rf data-legacy data-prod-replay
