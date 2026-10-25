# FluxTV — Requirements traceability

How each requirement in the *FluxTV: Basic Product & System Requirements Specification* (plus the
added **Hardware resource utilization** section) is met in this implementation.

## 3. Operating principle / Easy Mode
- **REQ-GEN-001/002** — `ui/AppViewModel.kt` (`AppMode.EASY`, `EasyOverlay`) exposes only watch,
  change channel, channel list, favorites, current-category list (Back).
- **REQ-GEN-003** — admin is a separate `AppMode.ADMIN` reached only by a deliberate gesture;
  `ui/admin/AdminScreen.kt`.
- **REQ-GEN-004** — no home page; `FluxApp` boots straight into `STARTING → EASY`.
- **REQ-GEN-005** — all operation via remote; `MainActivity.handleRemoteKey`.

## 4. Startup
- **REQ-START-001/002** — `AppViewModel.start()` enters EASY and restores
  `settings.lastChannelStableId`.
- **REQ-START-003** — no profile/playlist/engine/quality prompts; startup is automatic.
- **REQ-START-004** — instant start from `PlaylistCache`; modest player pre-roll in `PlayerFactory`.
- **REQ-START-005/006** — `ui/startup/StartingScreen.kt` (calm, non-technical, auto-dismissed).
- **REQ-START-007** — `withTimeoutOrNull(20s)` around the first refresh so loading never hangs.
- **REQ-START-008** — on empty/failed restore, EASY still opens and recovery/next-channel apply.
- **REQ-SEN-003 (0 actions to watch)** — `BootReceiver` starts FluxTV after a boot and
  `StandbyWatcher` brings it to the front when the TV wakes from standby (the remote's power button
  suspends the TV and sends no boot signal), both behind the same preference.
- **Standby playback** — `MainActivity.onStop` → `FluxPlayerController.onStop` stops playback when
  FluxTV leaves the screen, and `AppViewModel.retuneIfReturning` re-tunes once it is back with a new
  video surface: a decoder cannot be moved onto a surface built after a standby (sound without
  picture on the MiTV), and a live stream needs rejoining by then regardless.

## 5–6. Senior-friendly Easy Mode & visual accessibility
- **REQ-SEN-001/002** — full-screen `PlayerSurface`; overlays are transient, not persistent.
- **REQ-SEN-003** — 0 actions to watch; 1 action for next/prev/list/category list (`MainActivity`).
- **REQ-SEN-010/011/012** — large type (22–72sp) and high-contrast palette in `ui/theme/Theme.kt`
  and `ui/components/Components.kt`.
- **REQ-SEN-013** — status uses words, never color alone (`strings.xml`, `ErrorMapper`, overlays).
- **REQ-SEN-014** — `ChannelRow` / `ChannelInfoBanner` prioritise number, logo, name.
- **REQ-SEN-015** — strong focus highlight (accent fill + border) on every focusable component.
- **REQ-SEN-016/017/018** — only a subtle startup pulse; no pointer needed (D-pad focus model).

## 7. Remote-control behavior
- **REQ-REM-001/002** — one central handler maps each key to one action, consistently
  (`MainActivity.handleRemoteKey`).
- **REQ-REM-003** — `event.repeatCount == 0` gating ⇒ one action per press; no unstable repeats.
- **REQ-REM-004** — works with D-pad/OK/Back/Home/volume only; volume & Home never intercepted.
- **REQ-REM-005** — number keys tune directly (`NumberEntryOverlay`, `AppViewModel.onDigit`).
- **REQ-REM-006** — ▲/▼ change channel without opening the list.

## 8–9. Channel list & favorites
- **REQ-CH-001/002/004** — `ChannelListOverlay` shows number/name/logo/★/current.
- **REQ-CH-003** — list is a side panel; playback keeps running underneath.
- **REQ-CH-005/006** — virtualised `LazyColumn` + direct number entry for far jumps.
- **REQ-CH-007** — a dead channel is just a row; the list stays usable.
- **REQ-FAV-001/002** — favorites via hold-OK (Easy Mode) or the admin Channels screen.
- **REQ-FAV-003** — *favorites-only* Easy Mode (`SettingsRepository.favoritesOnly`, lineup filter).

## 10. Playlist
- **REQ-PL-001/002** — `M3uParser` + `PlaylistFetcher` accept M3U from URL, file, or content URI.
- **REQ-PL-003** — source persisted in DataStore; parsed list cached as JSON.
- **REQ-PL-004** — `work/PlaylistRefreshWorker` refreshes periodically with no Viewer action.
- **REQ-PL-005** — `ChannelRepository.refresh` keeps the previous list on failure.
- **REQ-PL-006/007** — tolerant parser skips malformed entries and de-duplicates.
- **REQ-PL-008** — stable IDs (`tvg-id` or name+URL hash) preserve favorites & numbering on refresh.

## 11–13. Playback, buffering, adaptive streaming
- **REQ-PLAY-001/002/004** — ExoPlayer live playback over common M3U/HLS/TS sources.
- **REQ-PLAY-003/REQ-REL-004** — `setEnableDecoderFallback(true)`; one bad channel/track doesn't
  break the next.
- **REQ-PLAY-005** — large buffer + ABR minimise stutter/freezes.
- **REQ-PLAY-006 / REQ-BUF-004** — phases distinguish loading / transient / prolonged / failure in
  `FluxPlayerController`.
- **REQ-BUF-001/002** — `DefaultLoadControl` 30–60s buffer, `prioritizeTimeOverSizeThresholds`.
- **REQ-BUF-003** — short buffering shows no message; only an 8s watchdog escalates.
- **REQ-BUF-005/006/007** — automatic recovery, no Retry needed, bounded by `RecoveryPolicy`.
- **REQ-BUF-008** — prolonged buffering → "Connection interrupted. Trying again…".
- **REQ-ADP-001..006** — `DefaultTrackSelector` adaptive selection; continuity over quality;
  automatic, no Viewer action.
- **REQ-ADP-007** — optional admin max-quality cap (`setMaxVideoHeight`).

## 14. Frame-rate adaptability
- **REQ-FPS-001/002** — `FrameRateController` + ExoPlayer AFR (`VIDEO_CHANGE_FRAME_RATE_STRATEGY_*`).
- **REQ-FPS-003/004** — only switches on fps change at equal resolution; mode de-dup.
- **REQ-FPS-005** — all guarded; if unavailable, playback continues unchanged.

## 15–17. Network, reliability, recovery
- **REQ-NET-001..006** — `NetworkMonitor` classifies connectivity; auto-reconnect without restart;
  per-channel failures isolated.
- **REQ-REL-001..008** — exceptions swallowed in data/cache layers; single reused ExoPlayer; config
  in DataStore/JSON survives crashes & updates.
- **REQ-REC-001..005** — Detect→Recover→Inform; bounded backoff; success returns silently; a simple
  "Try again / change channel" prompt only when exhausted (`RecoveryOverlay`).

## 18. Performance
- **REQ-PERF-001/002** — immediate key feedback; overlays are plain Compose state toggles.
- **REQ-PERF-003/004** — single reused player; cached playlist for fast start.
- **REQ-PERF-005/007** — background refresh via WorkManager; UI stays responsive while loading.
- **REQ-PERF-006/008** — virtualised lists; one player instance avoids leak/stutter growth.

## 19. Device adaptability
- **REQ-DEV-001/002** — ExoPlayer/track selector use device capabilities and degrade gracefully.
- **REQ-DEV-003/004** — Compose + sp/dp scale across TV resolutions/densities.
- **REQ-DEV-005** — `Modifier.overscan()` keeps controls inside the safe area.

## 20–24. Errors, diagnostics, config protection, updates, security
- **§20** — `ErrorMapper` produces the exact Viewer-facing sentences; technical text kept for admin.
- **REQ-DIA-001..004** — admin-only Diagnostics screen + persisted `DiagnosticsStore` history.
- **REQ-CFG-001..005** — admin behind hold-OK/MENU and optional PIN; no config needed for daily use.
- **REQ-UPD-001..003** — DataStore + JSON cache survive updates; stable IDs absorb playlist changes.
- **REQ-SEC-001..005** — credentials never shown to the Viewer; minimal permissions (INTERNET,
  ACCESS_NETWORK_STATE, WAKE_LOCK); no analytics/telemetry.

## 25. Easy Mode state model
- `AppViewModel` implements `STARTING → WATCHING ⇄ CHANNEL LIST` with `recovery → WATCHING`.

## Hardware resource utilization (added section)
- **REQ-HW-001/002** — `DefaultRenderersFactory` selects the hardware `MediaCodec` decoder
  automatically; no Viewer configuration.
- **REQ-HW-003** — `DefaultTrackSelector` only chooses renditions the device can decode (codec,
  profile, resolution, frame rate).
- **REQ-HW-004** — `setEnableDecoderFallback(true)` permits a controlled software fallback; if all
  decoders fail the channel shows "Unable to play this channel."
- **REQ-HW-005** — hardware decode + a single reused player keep CPU/RAM low for responsiveness.
- **REQ-HW-006** — Diagnostics shows **Hardware/Software (decoder name)** and logs any
  hardware→software fallback (`FluxPlayerController.onVideoDecoderInitialized`).
- **REQ-HW-007** — the Diagnostics *Decoding* field makes "hardware actually active" verifiable per
  reference stream (see README → Acceptance testing).

## 26. Non-goals (V1)
No VOD/series/DVR/recording/cloud/social/profiles/themes/complex-EPG or Viewer-facing codec,
resolution or buffer controls.
