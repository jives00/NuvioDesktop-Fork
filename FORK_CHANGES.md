# Fork Changes — Direct Scrobble to Trakt App

This fork of [NuvioMedia/NuvioDesktop](https://github.com/NuvioMedia/NuvioDesktop) adds direct
scrobbling to a self-hosted Trakt clone (`https://trakt.berek.xyz`) without requiring a Trakt.tv
account, and points the in-app updater at this repo's releases instead of upstream's.

It is the desktop counterpart to [`Nuvio-Fork`](https://github.com/jives00/Nuvio-Fork) (Android TV)
and talks to the same server endpoints. All changes are tagged `// [FORK]` in-code.

**Windows x64 only.** macOS builds are deliberately not produced — upstream's DMGs are Apple-signed
and notarized, which needs an Apple Developer certificate this fork doesn't have.

---

## What Was Changed

### Auto-Update

`composeApp/src/desktopMain/kotlin/com/nuvio/app/features/updater/AppUpdaterPlatform.desktop.kt`

The updater checks the GitHub Releases API, picks the release asset matching the host OS and
architecture, downloads it, and runs `msiexec /i` on Windows. Two lines redirect it at this fork:

```kotlin
// [FORK] In-app updater points at the fork's releases, not upstream's.
owner = "jives00",
repo = "NuvioDesktop-Fork",
```

Upstream owns the rest of the `updater/` package; there are no other fork changes inside it.

Fork releases carry the **same version tag as the upstream release they were built from**
(`0.1.19-alpha`, not a fork-suffixed variant). That is deliberate: the updater compares the remote
tag against `AppVersionConfig.DESKTOP_VERSION_NAME`, so matching tags mean an installed fork build
correctly sees itself as up to date.

### Direct Scrobble

| File | Change |
|---|---|
| `composeApp/src/commonMain/kotlin/com/nuvio/app/features/tracking/DirectScrobbleRepository.kt` | **New file** — builds the payload and POSTs it. No upstream equivalent, so it can never conflict. |
| `composeApp/src/commonMain/kotlin/com/nuvio/app/features/tracking/TrackingScrobbleCoordinator.kt` | Two lines — one in `scrobble`, one in `scrobbleSeek`. |
| `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimePlaybackActions.kt` | `emitDirectScrobbleStopForExit()` plus one call in `flushWatchProgress`. See "Exit never clears now_playing" below. |
| `composeApp/build.gradle.kts` | One block in `GenerateRuntimeConfigsTask` generating `ScrobbleConfig`. |

`DirectScrobbleRepository` is **not** registered via `TrackingProviderRegistry.registerScrobbler`.
`TrackingProviderId` is a closed enum that drives the tracking settings UI, and this endpoint is
always-on rather than something the user connects — so the coordinator calls it directly, outside
the connected-provider dispatch. It fires whether or not Trakt.tv or Simkl are connected.

**The action mapping is load-bearing:**

| `TrackingScrobbleAction` | Endpoint | `paused` |
|---|---|---|
| `START` | `start` | `false` |
| `PAUSE` | `stop` | `true` |
| `STOP` | `stop` | `false` |

The server models a pause as a stop that keeps `now_playing` alive. Collapsing `PAUSE` and `STOP`
into the same call regresses into one of two bugs: either `now_playing` never clears on a real stop
(it lingers ~4 hours until the session times out), or a pause wrongly clears the dashboard hero.

#### Exit never clears now_playing

Upstream gates its stop scrobble behind `shouldSendStopScrobble(hasActiveScrobble, progress)`, which
is `hasActiveScrobble || progress >= 80f`. But `emitTrackingScrobbleTerminal` sets
`hasRequestedScrobbleStartForCurrentItem = false` as soon as a **pause** is emitted. So the sequence
*play → pause → back out* sends no stop at all: the back handler (`onBackWithProgress` →
`flushWatchProgress()`) hits the guard and returns. There is also no stop button in the player, so
backing out is the normal way to end playback.

Upstream never had to care — Trakt.tv expires its own now-watching state — but the self-hosted
server holds `now_playing` until it times out hours later. `flushWatchProgress`'s `STOP` branch
therefore also calls `emitDirectScrobbleStopForExit()`, which bypasses the guard and always sends a
direct stop with `paused = false`. It snapshots `currentTrackingMedia` with the same
`snapshotTrackingScrobbleItemInputs()` fallback `emitTrackingScrobbleTerminal` uses, because the
preceding pause nulls it out.

Because that adds a second stop on the paths where upstream's stop *does* fire,
`DirectScrobbleRepository` collapses identical consecutive sends within 10s. The dedupe key includes
`paused`, so a pause followed by a real exit is correctly treated as two distinct calls.

Episode numbers go through `TraktEpisodeMappingService.resolveEpisodeMapping`, the same absolute →
season/episode mapping `TraktScrobbleRepository` uses, so both endpoints receive identical numbering.

A scrobble is skipped entirely when the media reference carries no trakt/imdb/tmdb/tvdb id — the
server would otherwise have to fuzzy-match on title and could attach the play to the wrong show.

### Config

`local.properties` (not committed):

```
NUVIO_SUPABASE_URL=<same value as Nuvio-Fork>
NUVIO_SUPABASE_ANON_KEY=<same value as Nuvio-Fork>
SCROBBLE_API_URL=https://trakt.berek.xyz/api/scrobble/nuvio/
SCROBBLE_API_KEY=<value from the Trakt server .env>
```

`TRAKT_CLIENT_ID` / `TRAKT_CLIENT_SECRET` are intentionally absent — the fork bypasses Trakt.tv
entirely, so its native integration stays unused. Sentry DSNs are absent too; `runtimeConfigValue`
falls back to `""` and Sentry stays disabled.

---

## CI

Upstream's `desktop-release.yml` is unusable here: it is `workflow_dispatch`-only, rejects any mode
other than `target=all` (so it always wants macOS), hard-fails when the Apple and Sentry secrets are
missing, and enforces "the version bump must be the final commit before release." Two fork workflows
replace it.

### `.github/workflows/sync-upstream.yml`

Daily at 08:00 UTC. Compares upstream's latest **release tag** against this fork's latest release
tag; if they differ, it merges upstream at that tag, pushes to `Dev`, and dispatches `fork-release`.

It watches releases rather than the `Dev` branch tip because **upstream's version file is not a
reliable signal** — `composeApp/Configuration/DesktopVersion.properties` on `Dev` currently reads
`0.1.17-alpha` even though the `0.1.19-alpha` bump commit is already an ancestor of it. Upstream
bumps on a release branch, tags, merges, and the tip reverts. Watching that file would have missed
both 0.1.18 and 0.1.19.

On conflict it opens a draft PR and emails. There is no auto-resolver yet — add one once a recurring
conflict shape actually shows up.

### `.github/workflows/fork-release.yml`

Builds the Windows MSI and publishes it as a release. Normally dispatched by the sync job with an
explicit `version`; run it manually to ship a fork-only change.

Notes:
- `NUVIO_DESKTOP_VERSION_NAME` is passed as an env var to override the stale version file.
- Only the Windows LFS objects are pulled; a full `git lfs pull` would drag in the macOS runtimes.
- `:desktopSentry:sentryUploadSourceBundleJava` is dropped (no Sentry auth token). The
  `generateSentryDebugMetaProperties` task it depends on works fine with an empty DSN.
- Re-running for a version that already has a release replaces it. **This does not push an update
  to anyone already on that version** — the updater compares version numbers, not build dates.

### Upstream's workflow files

Left untouched in the tree and **disabled through the Actions API** instead:

```bash
for w in android-release desktop-release close-stale-issues close-unlabeled-issues \
         pr-template-check stale-needs-info triage-needs-info; do
  gh workflow disable "$w.yml" --repo jives00/NuvioDesktop-Fork
done
```

Deleting them would produce a delete/modify conflict every time upstream edited one. Disabling
leaves no diff to conflict on. The one that actually matters is `pr-template-check`, which would
otherwise fail against the sync job's own conflict PRs.

### Repository secrets

| Secret | Purpose |
|---|---|
| `NUVIO_DESKTOP_LOCAL_PROPERTIES_BASE64` | base64 of `local.properties` (Supabase + scrobble config) |
| `SYNC_PAT` | PAT for the sync job — needs `repo` + `workflow`; `GITHUB_TOKEN` cannot push to `.github/workflows/` and its pushes never trigger downstream runs |
| `GMAIL_APP_PASSWORD` | conflict notification email |

---

## Merging Upstream Changes

1. **`DirectScrobbleRepository.kt`** — new file, will never conflict. But it depends on upstream
   types: `TrackingMediaReference`, `TrackingScrobbleAction`, `TrackingScrobbleEvent`,
   `TraktEpisodeMappingService.resolveEpisodeMapping`, `httpRequestRaw`, and
   `AppVersionPolicy.displayVersionName`. A rename upstream breaks compilation loudly, which is the
   good failure mode.
2. **`TrackingScrobbleCoordinator.kt`** — if upstream restructures dispatch, ensure both `scrobble`
   and `scrobbleSeek` still call `DirectScrobbleRepository.scrobble(action, event)`, and that it
   stays **outside** the connected-provider path so it fires with no tracker connected.
3. **`PlayerScreenRuntimePlaybackActions.kt`** — `flushWatchProgress`'s `STOP` branch must keep
   calling `emitDirectScrobbleStopForExit()`. Losing it during a conflict silently regresses the
   "now_playing never clears on exit" bug, which looks fine right up until you pause something.
4. **`build.gradle.kts`** — re-apply the `ScrobbleConfig` block if upstream reworks
   `GenerateRuntimeConfigsTask`. It is a self-contained addition, not an edit to existing lines.
5. **`AppUpdaterPlatform.desktop.kt`** — re-apply `owner`/`repo` after any merge touching
   `releaseSource`. Also verify `includePrereleases` still admits the fork's release style.
6. **Workflows** — ours are fork-specific filenames, so they never conflict with upstream's.

---

## Local Development

A local **MSI build is not possible without Visual Studio Build Tools** — `player_bridge.cpp` is
compiled with `cl.exe`, and the build also needs the `Microsoft.Web.WebView2` NuGet package. CI's
`windows-2022` runner has both. Locally, verify Kotlin changes with:

```bash
./gradlew :composeApp:compileKotlinDesktop
```

`git submodule status` reports `no submodule mapping found in .gitmodules for path 'libass-android'`.
That is an upstream inconsistency — a gitlink with no `.gitmodules` entry — and is harmless for
desktop builds.

---

## Server-Side Counterpart

Handled by the Trakt app (`https://github.com/jives00/trakt`), unchanged for this fork:

- `POST /api/scrobble/nuvio/start` — updates now-playing
- `POST /api/scrobble/nuvio/stop` — below the watch-completion threshold, clears `now_playing`
  unless `paused: true`, in which case the session stays alive with progress frozen. At or above the
  threshold, always clears and records watch history regardless of `paused`.

Auth: `X-Api-Key` header matching `SCROBBLE_API_KEY` in the server's `.env`.
