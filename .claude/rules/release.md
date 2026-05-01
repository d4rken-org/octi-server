# Releasing

## Flow at a glance

```
release-prepare.yml (workflow_dispatch)
  └─ Job 1: compute-and-validate  (always)
       reads gradle.properties version=, computes next, checks tag collision, writes summary
  └─ Job 2: push-and-dispatch  (only when dry_run=false)
       mints App token, bumps gradle.properties, commits "Release: vX.Y.Z", tags, atomic push
           │
           └─► release-tag.yml fires automatically from the App-token push
                 └─ Job 1: validate-tag  (always)
                      regex check on tag name + gradle.properties consistency
                 └─ Job 2: release-github  (needs validate-tag, gated by foss-production)
                      multi-arch Docker push to ghcr.io + installDist zip + GitHub Release
```

## Inputs for `release-prepare.yml`

| Input | Type | Default | Description |
|-------|------|---------|-------------|
| `bump_type` | choice (patch/minor/major) | `patch` | Auto-increment strategy. Ignored when `version` is set. |
| `version` | string | `''` | Explicit version override (e.g. `1.1.0`, `2.0.0-rc1`). Ignores `bump_type`. |
| `expected_current` | string | `''` | Safety check: fail if `gradle.properties` version doesn't match. Defends against concurrent queued runs. |
| `dry_run` | boolean | `true` | When true, only Job 1 runs (validate + print plan). Set to `false` to actually push. |

Examples:
- Normal patch release: `bump_type=patch`, `dry_run=false`
- First RC: `version=1.1.0-rc1`, `dry_run=false`
- Promote RC to stable: `version=1.1.0`, `dry_run=false`

## Version source of truth

`gradle.properties` — the single `version=X.Y.Z` line. Gradle reads it automatically; `build.gradle.kts` does not set `version`.

## Cancel window

When `dry_run=false`, Job 1 ends (summary appears on screen) and Job 2 starts runner spin-up (~5–15 seconds). That window is the manual-cancel affordance — watch the run page after dispatching. Job 1's validation catches most issues before Job 2 starts.

## Five gotchas worth memorizing

1. **App-token pushes fire `on: push: tags`** — do NOT add `gh workflow run release-tag.yml`. App tokens aren't `GITHUB_TOKEN`, so they fire workflow triggers. Adding an explicit dispatch produces duplicate runs (capod #568).
2. **Use `app-slug` output, not `gh api /app`** — `/app` requires a signed App JWT, not the installation token; it 401s (capod #567).
3. **Use `client-id`, not `app-id`** — `app-id` is deprecated upstream and prints warnings. Secret name is `RELEASE_APP_CLIENT_ID` (capod #569).
4. **Atomic push** — `git push --atomic origin HEAD:refs/heads/main "refs/tags/vX.Y.Z"`. Both the bump commit and the tag land together or neither does.
5. **`workflow_dispatch` only sees workflows on the default branch** — merging to `main` is required before you can dispatch `release-prepare.yml`.

## Recovery procedures

**Build failure after tag push** (Docker/GH Release step failed, tag exists, gradle.properties already bumped):
1. Go to `release-tag.yml` → Run workflow → target the tag ref (e.g. `v1.0.1`), set `dry_run=false`.
2. `validate-tag` re-checks format + consistency. `release-github` re-runs with `foss-production` approval.

**Needs rollback** (tag pushed but you want to undo before the release is published):
1. Delete the remote tag: `git push origin --delete vX.Y.Z`
2. Revert the bump commit on `main` (or cherry-pick a revert): `git revert <sha>` + push.
3. Re-cut from the correct state.

## Prerequisites (one-time setup)

- `d4rken-org-releaser` GitHub App installed on this repo (already used by capod).
- Org secrets `RELEASE_APP_CLIENT_ID` and `RELEASE_APP_PRIVATE_KEY` accessible to this repo.
- App added as bypass actor in any branch/tag protection rulesets covering `main` and `v*` tags (`GITHUB_TOKEN` cannot be a bypass actor — only installed Apps can).
