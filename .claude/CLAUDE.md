# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Octi Server

Sync server for the [Octi](https://github.com/d4rken-org/octi) Android app. Devices in the same account push/pull module data through this server, with real-time WebSocket notifications.

- **Package**: `eu.darken.octi.server`
- **Architecture**: Ktor 3 + Dagger 2 DI + file-based JSON persistence (no database)
- **Key tech**: Kotlin coroutines, kotlinx-serialization, Netty, KSP

## Rules

- [Architecture](rules/architecture.md) — Domain hierarchy, routing, persistence, sync flow, auth
- [Build Commands](rules/build-commands.md) — Gradle commands, running locally, Docker, CI
- [Testing](rules/testing.md) — TestRunner, integration tests, helpers
- [Commit Guidelines](rules/commit-guidelines.md) — Commit message format and examples
- [Releasing](rules/release.md) — Release flow, inputs, gotchas, recovery
