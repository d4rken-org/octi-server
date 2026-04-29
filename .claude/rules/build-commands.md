# Build Commands

## Building

```bash
# Build distribution
./gradlew clean installDist

# Assemble only (CI uses this)
./gradlew assemble
```

Output: `./build/install/octi-server/`

## Testing

```bash
# Run all tests
./gradlew test

# Full checks (tests + verification)
./gradlew check

# Run a single test class
./gradlew test --tests "eu.darken.octi.server.module.ModuleFlowTest"

# Run a single test method
./gradlew test --tests "eu.darken.octi.server.module.ModuleFlowTest.writing a module"
```

## Running Locally

```bash
./build/install/octi-server/bin/octi-server --datapath=./octi-data
```

CLI arguments:
- `--datapath=<path>` (required) — data storage directory
- `--port=<num>` — server port (default: 8080)
- `--debug` — verbose logging
- `--disable-rate-limits` — disable rate limiting

## Docker

```bash
docker run -v octi-data:/etc/octi-server -p 8080:8080 ghcr.io/d4rken-org/octi-server
```

Environment variables: `OCTI_PORT`, `OCTI_DEBUG`, `OCTI_DATA_DIR`

## CI

- **code-checks.yml**: runs `assemble` + `check` on push to main and PRs
- **release-tag.yml**: builds `installDist`, zips, creates GitHub release on `v*` tags
- JDK 21 (temurin), Gradle 9.4.1

## Context Management

When running gradle builds or tests, use the Task tool with a sub-agent to keep verbose output isolated from the main conversation context. Run gradle directly only when the user explicitly requests full output.
