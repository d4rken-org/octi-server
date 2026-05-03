# Octi Server

[![Code tests & eval](https://img.shields.io/github/actions/workflow/status/d4rken-org/octi-server/code-checks.yml?logo=githubactions&label=Code%20tests)](https://github.com/d4rken-org/octi-server/actions/workflows/code-checks.yml)
[![Gradle wrapper](https://img.shields.io/github/actions/workflow/status/d4rken-org/octi-server/gradle-wrapper-validation.yml?logo=gradle&label=Gradle%20wrapper)](https://github.com/d4rken-org/octi-server/actions/workflows/gradle-wrapper-validation.yml)

[![GitHub release](https://img.shields.io/github/v/release/d4rken-org/octi-server?logo=github&label=Release)](https://github.com/d4rken-org/octi-server/releases/latest)
[![Docker image version](https://ghcr-badge.egpl.dev/d4rken-org/octi-server/latest_tag?trim=major&label=ghcr.io)](https://github.com/d4rken-org/octi-server/pkgs/container/octi-server)
[![Docker image size](https://ghcr-badge.egpl.dev/d4rken-org/octi-server/size?color=%2344cc11&tag=latest&label=image%20size)](https://github.com/d4rken-org/octi-server/pkgs/container/octi-server)

This is a synchronization server for [Octi](https://github.com/d4rken-org/octi)

## Setup

### Build server

```bash
./gradlew clean installDist
```

The binaries you can copy to a server will be placed under `./build/install/octi-server`.
More details [here](https://ktor.io/docs/server-packaging.html).

### Run server

```bash
./build/install/octi-server/bin/octi-server --datapath=./octi-data
```

The following flags are available:

* `--datapath` (required) where the server should store its data
* `--debug` to enable additional log output
* `--port` to change the default port (8080)
* `--trusted-proxy-ips` comma-separated proxy IPs whose `X-Real-IP`/`X-Forwarded-For` headers are trusted (default: loopback)
* `--account-quota-mb`, `--max-blob-mb`, `--max-module-document-kb`, `--max-blob-patch-kb`, `--min-free-disk-mb`
* `--max-upload-sessions-per-device`, `--max-upload-sessions-per-account`
* `--idle-session-ttl-seconds`, `--complete-idle-session-ttl-seconds`, `--absolute-session-ttl-seconds`
* `--max-devices-per-account`, `--max-modules-per-device`, `--max-blob-refs-per-module`
* `--rate-limit`, `--rate-limit-window-seconds`, `--account-rate-limit`, `--account-rate-limit-window-seconds`, `--disable-rate-limits`

### Docker

The default data volume is `/etc/octi-server`.

Stable releases are published as `:latest` and `:X.Y.Z`. Pre-release tags such as `-beta` or `-rc` are published only as their explicit version tag.

```bash
docker run -v octi-data:/etc/octi-server -p 8080:8080 ghcr.io/d4rken-org/octi-server:latest
```

Environment variables:

* `OCTI_PORT` — server port (default: 8080)
* `OCTI_DEBUG` — enable debug mode (default: false)
* `OCTI_DATA_DIR` — override data directory path
* `OCTI_TRUSTED_PROXY_IPS` — comma-separated trusted proxy IPs
* `OCTI_ACCOUNT_QUOTA_MB`, `OCTI_MAX_BLOB_MB`, `OCTI_MAX_MODULE_DOCUMENT_KB`, `OCTI_MAX_BLOB_PATCH_KB`, `OCTI_MIN_FREE_DISK_MB`
* `OCTI_MAX_UPLOAD_SESSIONS_PER_DEVICE`, `OCTI_MAX_UPLOAD_SESSIONS_PER_ACCOUNT`
* `OCTI_IDLE_SESSION_TTL_SECONDS`, `OCTI_COMPLETE_IDLE_SESSION_TTL_SECONDS`, `OCTI_ABSOLUTE_SESSION_TTL_SECONDS`
* `OCTI_MAX_DEVICES_PER_ACCOUNT`, `OCTI_MAX_MODULES_PER_DEVICE`, `OCTI_MAX_BLOB_REFS_PER_MODULE`
* `OCTI_RATE_LIMIT`, `OCTI_RATE_LIMIT_WINDOW_SECONDS`, `OCTI_ACCOUNT_RATE_LIMIT`, `OCTI_ACCOUNT_RATE_LIMIT_WINDOW_SECONDS`, `OCTI_DISABLE_RATE_LIMITS`

`GET /v1/metrics` exposes coarse aggregate counters only. It does not include account, device, blob, session, or IP identifiers.

**Migrating from `/etc/octi-sync-server`**: The old volume path is still detected automatically. Update your mount to `/etc/octi-server` when convenient, or set `OCTI_DATA_DIR` explicitly.
