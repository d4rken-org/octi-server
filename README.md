# Octi Server

[![Code tests & eval](https://img.shields.io/github/actions/workflow/status/d4rken-org/octi-server/code-checks.yml?logo=githubactions&label=Code%20tests)](https://github.com/d4rken-org/octi-server/actions)

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

**Migrating from `/etc/octi-sync-server`**: The old volume path is still detected automatically. Update your mount to `/etc/octi-server` when convenient, or set `OCTI_DATA_DIR` explicitly.
