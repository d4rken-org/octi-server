# Device Capabilities

The server is a **dumb pipe** for per-peer feature-capability negotiation. Clients publish
capability tags; the server parses, validates, persists, and echoes them — it doesn't
interpret tag values.

Shipped in [octi-server#23](https://github.com/d4rken-org/octi-server/pull/23) (this repo) and
[octi#309](https://github.com/d4rken-org/octi/pull/309) (Android client side).

## Wire contract (cross-platform)

The contract is shared by **all four** implementations (this server, Android, desktop, web).
Drift here breaks interop.

### Tag format

- `<namespace>:<value>` — ASCII, lowercase namespace.
- Regex: `[a-z][a-z0-9]*:[A-Za-z0-9._\-]+`
- Marker convention: `<namespace>:_reported` — emitted by any producer that participates in a
  namespace. Servers don't act on it (no value interpretation), but consumers do.

### Limits (enforced by `parseCapabilitiesHeader`)

| Limit | Constant | Value |
|---|---|---|
| Max tags per device | `MAX_CAPABILITY_TAGS` | 64 |
| Max length per tag | `MAX_CAPABILITY_TAG_LENGTH` | 128 |
| Max header byte length | `MAX_CAPABILITY_HEADER_LENGTH` | 4096 |

**On any bad tag the whole set is rejected** — no partial acceptance. The device is treated
as not reporting capabilities (existing stored value preserved if any). One WARN log per
rejection.

### Wire transport

- **Inbound**: `Octi-Device-Capabilities` HTTP header on any authenticated state-updating
  request. Value is a JSON-stringified `Array<String>`.
- **Outbound**: `capabilities` field on each device in `GET /v1/devices` response. Proper
  JSON array element (not stringified).
- **CORS**: allowed in `Server.kt`'s CORS plugin alongside the other `Octi-Device-*` headers.

## Behavior

| When | Action |
|---|---|
| Header present, valid | Parse, validate, store via `DeviceMetadataPatch.capabilities` |
| Header present, malformed (bad JSON, non-array, oversize, bad tag, non-string element) | WARN log, treat as absent — no state change |
| Header absent on a state-updating request | Preserve previously-stored capabilities (matches version/platform/label pattern) |
| Routes that don't update device state | Header ignored |

**No downgrade clearing today**: if a device downgrades to a client that no longer sends the
header, its stored capabilities remain. Acceptable trade-off for now; see octi-server#23's
follow-up note for the conservative alternative (clear on version/platform change without
header).

## Where the code lives

| File | Role |
|---|---|
| `src/main/kotlin/eu/darken/octi/server/common/HttpExtensions.kt` | `parseCapabilitiesHeader(raw: String?): Set<String>?` — single source of truth for tag parsing + validation. Limits and regex live here. |
| `src/main/kotlin/eu/darken/octi/server/common/HttpExtensions.kt` | `DeviceMetadataPatch.capabilities`; `touchAuthenticatedDevice` propagates it on update |
| `src/main/kotlin/eu/darken/octi/server/device/Device.kt` | `Device.Data.capabilities: Set<String>?` — persisted JSON field on disk |
| `src/main/kotlin/eu/darken/octi/server/device/DeviceRepo.kt` | `createDevice(capabilities: Set<String>? = null)` |
| `src/main/kotlin/eu/darken/octi/server/device/DevicesResponse.kt` | Response DTO field; goes out as JSON array on `GET /v1/devices` |
| `src/main/kotlin/eu/darken/octi/server/device/DeviceRoute.kt` | Maps `Device.capabilities` → `DevicesResponse.Device.capabilities` |
| `src/main/kotlin/eu/darken/octi/server/account/AccountRoute.kt` | Reads header at register |
| `src/main/kotlin/eu/darken/octi/server/ws/WsRoute.kt` | Reads header on WebSocket upgrade |
| `src/main/kotlin/eu/darken/octi/server/Server.kt` | CORS allowHeader entry |

Tests:

| File | Coverage |
|---|---|
| `src/test/.../ParseCapabilitiesHeaderTest.kt` | Direct unit tests on the parser — every rejection path + happy path |
| `src/test/.../device/DeviceFlowTest.kt` | End-to-end: register / authenticated update / list — stored, echoed, preserved on absent, rejected on bad |
| `src/test/.../common/CorsFlowTest.kt` | Preflight allows the header |

## Adding support for a new capability value

Nothing to do — the server is intentionally values-agnostic. As long as a new tag matches the
regex and fits the limits, it goes through unchanged.

If the tag format itself changes (regex, limits), update `parseCapabilitiesHeader` in
`HttpExtensions.kt` and coordinate the change across all four implementations.

## Storage migration

The new field has a default of `null` on `Device.Data` so existing on-disk JSON without
`capabilities` loads cleanly via kotlinx-serialization. No migration needed.

## Cross-references

- **Android** (producer + consumer): `octi/.claude/rules/device-capabilities.md`. Worked
  example for the namespace system.
- **Desktop** (producer + consumer port): `app-desktop/.claude/rules/device-capabilities.md`.
- **Web** (producer only today): `octi-web/.claude/rules/device-capabilities.md`.

Sister PRs: [octi#308](https://github.com/d4rken-org/octi/pull/308) (platform scoping fix
that motivated capabilities), [octi#309](https://github.com/d4rken-org/octi/pull/309)
(Android implementation), [octi-server#23](https://github.com/d4rken-org/octi-server/pull/23)
(this server-side implementation).
