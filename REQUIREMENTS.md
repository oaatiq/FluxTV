# FluxTV — Requirements traceability

How each requirement in the *FluxTV: Basic Product & System Requirements Specification* (plus the
added **Hardware resource utilization**, **Smartphone playlist setup** and **Multiple-playlist
management** sections) is met in this implementation.

## 3. Operating principle / Easy Mode
- **REQ-GEN-001/002** — `ui/AppViewModel.kt` (`AppMode.EASY`, `EasyOverlay`) exposes only watch,
  change channel, channel list, favorites, current-category list (Back).
- **REQ-GEN-003** — admin is a separate `AppMode.ADMIN` reached only by a deliberate gesture;
  `ui/admin/AdminScreen.kt`.
- **REQ-GEN-004** — no home page; `FluxApp` boots straight into `STARTING → EASY`.
- **REQ-GEN-005** — all operation via remote; `MainActivity.handleRemoteKey`.

## 4. Startup
- **REQ-START-001/002** — `AppViewModel.start()` enters EASY and restores
  `settings.lastChannelStableId`. If FluxTV is covered or left before that first tune, the channel is
  tuned once FluxTV is back on screen, never played behind another app. A channel is remembered as
  the last one once it has been on for 3 s (or FluxTV leaves the screen), not at every zap.
- **REQ-START-003** — no profile/playlist/engine/quality prompts; startup is automatic.
- **REQ-START-004** — instant start from `PlaylistCache`; a channel starts once 1.5 s is buffered
  (`PlayerFactory`), 5 s after a stall.
- **REQ-START-005/006** — `ui/startup/StartingScreen.kt` (calm, non-technical, auto-dismissed).
- **REQ-START-007** — a first refresh (no cache) is awaited for at most 20 s; then EASY opens with
  "Loading channels…" while it carries on, and the channel is tuned when it lands, so loading never
  hangs and a large list over a slow line still gets through (the download may take up to 10 min).
- **REQ-START-008** — on empty/failed restore, EASY still opens and recovery/next-channel apply. A
  first load that fails with nothing cached says FluxTV is trying again (not "add your playlist"),
  retries when the network returns and at growing intervals up to 15 min
  (`AppViewModel.recoverFirstLoad`), and tunes once channels arrive by any path.
- **REQ-SEN-003 (0 actions to watch)** — `BootReceiver` starts FluxTV after a boot and
  `StandbyWatcher` brings it to the front when the TV wakes from standby (the remote's power button
  suspends the TV and sends no boot signal), both behind the same preference.
- **Standby playback** — `MainActivity.onStop` → `FluxPlayerController.onStop` stops playback when
  FluxTV leaves the screen, and `AppViewModel.retuneIfReturning` re-tunes once it is back with a new
  video surface: many TV decoders cannot be moved onto a surface built after a standby (sound
  without picture), and a live stream needs rejoining by then regardless.

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
- **REQ-REM-005** — number keys tune directly (`NumberEntryOverlay`, `AppViewModel.onDigit`); the
  remote's last-channel key, where there is one, goes back to the previous channel.
- **REQ-REM-006** — ▲/▼ change channel without opening the list.

## 8–9. Channel list & favorites
- **REQ-CH-001/002/004** — `ChannelListOverlay` shows number/name/logo/★/current.
- **REQ-CH-003** — list is a side panel; playback keeps running underneath.
- **REQ-CH-005/006** — virtualised `LazyColumn` + direct number entry for far jumps.
- **REQ-CH-007** — a dead channel is just a row; the list stays usable.
- **REQ-FAV-001/002** — favorites via hold-OK (Easy Mode) or the admin Channels screen.
- **REQ-FAV-003** — *favorites-only* Easy Mode (`SettingsRepository.favoritesOnly`, lineup filter).
  Favorites belong to the playlist in use; one with none shows all its channels (`lineupOf`) rather
  than an empty screen.

## 10. Playlist
- **REQ-PL-001/002** — `M3uParser` + `PlaylistFetcher` accept M3U from URL, file, or content URI.
- **REQ-PL-003** — saved playlists and the one in use persisted in DataStore (`SavedPlaylist`); the
  one in use's parsed list cached as JSON, tagged with its playlist, written through `AtomicFile` so
  a power cut mid-write keeps the previous list (`PlaylistCache`).
- **REQ-PL-004** — `work/PlaylistRefreshWorker` refreshes the playlist in use periodically with no
  Viewer action; one refresh runs at a time, and the playlist is parsed as it downloads, never held
  as one string. The interval chosen in Preferences survives restarts. Repeated values (group titles,
  logos, tvg-ids) share one copy, and a playlist too large for the TV's memory fails the refresh,
  keeping the channels already loaded, instead of closing FluxTV.
- **REQ-PL-005** — `ChannelRepository.refresh` keeps the previous list on failure.
- **REQ-PL-006/007** — tolerant parser skips malformed entries; exact repeats (same tvg-id, name and
  address) are dropped, while versions of a channel that share a tvg-id (HD, FHD, SD) are all kept.
  Names keep their commas ("Sport 1, HD"): the title starts at the first comma outside a quoted
  attribute value.
- **REQ-PL-008** — stable IDs (`tvg-id` or name+URL hash; a later channel with an already used tvg-id
  gets both) preserve favorites & numbering on refresh. The hash still uses the name as it was
  read before names kept their commas (`M3uEntry.idName`), so no identity changed with that fix.
  Loading a different playlist address numbers its channels afresh; the channel on screen follows
  the playlist (a new address after a refresh, or the new provider's first channel).

## Smartphone playlist setup (added section)
- **Add or replace a playlist from a phone, never typing the link on the TV** — Settings →
  Playlists → *Add channels using your phone* (`ui/admin/AdminScreen.kt`, `PhoneSetupSection`) shows
  a QR code ("Scan with your phone to add channels.") for a page served by the TV itself
  (`setup/PhoneSetupServer.kt`): *Playlist link*, optional *Name*, *Send to TV*, and the TV's name
  and a four-digit TV code that the TV screen also shows.
- **Checked, with success or a plain-language error on both devices** — `AppViewModel.addPlaylist`
  downloads and reads the playlist (`ChannelRepository.check`) before saving it; the outcome
  (`setup/AddStatus.kt`) is shown in Settings and on the phone, worded by `describeProblem`. On
  success the playlist's channels and categories are available (at once for the first playlist,
  through *Use this playlist* for any other).
- **No app on the phone** — plain HTML in any browser, no script.
- **An invalid or inaccessible playlist does not replace the working list** — nothing is saved or
  changed unless it loaded; the phone gets the link back in the form to correct it.
- **Replacing a playlist takes an explicit administrator action** — a playlist added while another
  is in use is only saved; *Use this playlist* is the switch.
- **Only during an administrator-authorized setup session** — the page answers only while the QR
  screen is open in Settings (behind hold OK / MENU and the optional PIN), for 15 minutes at most,
  and not once FluxTV leaves the screen (`AppViewModel.startPhoneSetup`, `endPhoneSetup`). Every
  address carries a random code that only the QR code holds; a new session gets a new one.
- **Playlist links never shown in Easy Mode** — they appear only in Settings; the diagnostics history
  records playlist names, never links.
- **No phone needed afterwards** — the playlist is saved on the TV and refreshed by the TV.

## Multiple-playlist management (added section)
- **Save, name and manage several playlists in Administrator Mode → Settings → Playlists only** —
  `SavedPlaylist` list in `SettingsRepository`; `PlaylistsSection` lists them with the one in use
  marked *IN USE*; `PlaylistSection` offers *Use this playlist*, *Rename*, *Change link* and *Delete*.
  Added from the phone (or typed), with a name chosen there or the provider's address by default.
- **One playlist in use; never combined** — `FluxSettings.activePlaylist`; `ChannelRepository` holds
  one playlist's channels (`playlistId`) and refuses to refresh any other into them.
- **Adding does not activate** — except the very first playlist, which has nothing to replace.
- **Checked before activation; failure leaves the active playlist unchanged** —
  `AppViewModel.usePlaylist` → `ChannelRepository.activate`, which replaces the channels only once the
  new playlist has loaded; the setting follows only on success.
- **After activation, only the new playlist's channels and categories** — the channel list is built
  from the repository's channels, numbered afresh for the new playlist.
- **Favorites and last channel kept per playlist** — stored inside each `SavedPlaylist`, written for
  the playlist the channel came from (`saveLastChannel`, `toggleFavorite`).
- **Resume that playlist's last channel, else its first** — `tuneActivePlaylist` → `tuneStartChannel`
  when leaving Settings.
- **Active playlist remembered after restart** — `active_playlist` in DataStore; the cache is used
  only if it is that playlist's (`loadFromCache`).
- **Deleting the active playlist needs another activated first** — `SettingsRepository.deletePlaylist`
  refuses it, and Settings offers no Delete for it.
- **Easy Mode protection** — no playlist names, links or choices outside Settings; channel navigation
  stays within the playlist in use; failed playback and first-load retries only ever retry the
  playlist in use; the Viewer is never asked to choose.
- **Upgrade** — `SinglePlaylistMigration` turns the single playlist of earlier versions into the
  first saved playlist, in use, with its favorites and last channel (REQ-UPD-001/002).
- **Acceptance test** — README → Acceptance testing → *Multiple playlists*.

## 11–13. Playback, buffering, adaptive streaming
- **REQ-PLAY-001/002/004** — ExoPlayer live playback over common M3U/HLS/DASH/TS sources; a stream
  type with no player module (RTSP, Smooth Streaming) reads "Unable to play this channel" instead of
  closing the app (`FluxPlayerController.load`). Live TV never pauses itself when the audio output
  changes (HDMI counts as one), and picking the channel already on screen plays it on. An address
  whose format the player cannot recognise (HLS served without ".m3u8") is tried once as HLS, and
  one that plays that way is opened as HLS for the rest of the session (`tryAsHls`). A ".m3u"
  address is opened as HLS from its first try, and as it is if it turns out not to be HLS
  (`firstTryAsHls`, `tryAsPlain`). Kodi-style headers after "|" in an address are sent as headers
  (`streamRequestFor`), and the player name sent to providers can be set in Preferences. Channels and
  playlists share one OkHttp client, which accepts older TLS after the modern versions.
- **REQ-PLAY-003/REQ-REL-004** — `setEnableDecoderFallback(true)`; one bad channel/track doesn't
  break the next. Sound formats the TV cannot decode (AC-3, E-AC-3, MP2, DTS) go to Media3's FFmpeg
  extension; formats the TV decodes or passes through still go to the TV first.
- **REQ-PLAY-005** — large buffer + ABR minimise stutter/freezes.
- **REQ-PLAY-006 / REQ-BUF-004** — phases distinguish loading / transient / prolonged / failure in
  `FluxPlayerController`.
- **REQ-BUF-001/002** — `DefaultLoadControl` 30–60s buffer, `prioritizeTimeOverSizeThresholds`; where
  apps get 192 MB of heap or less, the buffer is capped at a quarter of it instead.
- **REQ-BUF-003** — short buffering shows no message; only a watchdog escalates: 8 s for a stall,
  15 s for a channel still starting, armed by every prepare.
- **REQ-BUF-005/006/007** — automatic recovery, no Retry needed, bounded by `RecoveryPolicy`.
- **REQ-BUF-008** — prolonged buffering → "Connection interrupted. Trying again…".
- **REQ-ADP-001..006** — `DefaultTrackSelector` adaptive selection; continuity over quality;
  automatic, no Viewer action.
- **REQ-ADP-007** — optional admin max-quality cap (`setMaxVideoHeight`).

## 14. Frame-rate adaptability
- **REQ-FPS-001/002** — `FrameRateController` + ExoPlayer AFR (`VIDEO_CHANGE_FRAME_RATE_STRATEGY_*`).
- **REQ-FPS-003/004** — only switches on fps change at equal resolution; mode de-dup; only once the
  new frame rate has held for 3 s, so zapping does not switch the TV's mode at every step. Turned
  off, the TV goes back to its own mode at once; turned on, the channel playing is matched.
- **REQ-FPS-005** — all guarded; if unavailable, playback continues unchanged.

## 15–17. Network, reliability, recovery
- **REQ-NET-001..006** — `NetworkMonitor` classifies connectivity; auto-reconnect without restart;
  per-channel failures isolated. Recovery only plays while FluxTV is on screen.
- **REQ-REL-001..008** — exceptions swallowed in data/cache layers; single reused ExoPlayer; config
  in DataStore/JSON survives crashes & updates; an unreadable settings file is replaced with defaults
  instead of crashing every start.
- **REQ-REC-001..005** — Detect→Recover→Inform; bounded backoff; success returns silently; a simple
  "Try again / change channel" prompt only when exhausted (`RecoveryOverlay`). A channel that cannot
  play at all (DRM-protected, or nothing this TV can decode) says so at once and is not retried. A
  picture that stops while the sound plays on (no new frame for 6 s after frames had been coming)
  gets a fresh decoder, at most 3 times per tune (`startFrozenPictureWatch`).

## 18. Performance
- **REQ-PERF-001/002** — immediate key feedback; overlays are plain Compose state toggles.
- **REQ-PERF-003/004** — single reused player; cached playlist for fast start.
- **REQ-PERF-005/007** — background refresh via WorkManager; UI stays responsive while loading. The
  start-up refresh waits for the first channel to play, and is skipped while the cache is fresh.
- **REQ-PERF-006/008** — virtualised lists, sorted and grouped off the main thread; one player
  instance, and the video view detached from it when it leaves the screen, avoid leak/stutter growth.

## 19. Device adaptability
- **REQ-DEV-001/002** — ExoPlayer/track selector use device capabilities and degrade gracefully.
- **REQ-DEV-003/004** — Compose + sp/dp scale across TV resolutions/densities.
- **REQ-DEV-005** — `Modifier.overscan()` keeps controls inside the safe area.

## 20–24. Errors, diagnostics, config protection, updates, security
- **§20** — `ErrorMapper` produces the exact Viewer-facing sentences; technical text kept for admin.
- **REQ-DIA-001..004** — admin-only Diagnostics screen + persisted `DiagnosticsStore` history, kept in
  memory and saved one appended line per event (the file is compacted at twice its 300-event cap).
  A crash leaves a note (`CrashNotes`) that the next start adds to the history.
- **REQ-CFG-001..005** — admin behind hold-OK/MENU and optional PIN; no config needed for daily use.
- **REQ-UPD-001..003** — DataStore + JSON cache survive updates; stable IDs absorb playlist changes.
- **REQ-SEC-001..005** — credentials never shown to the Viewer, nor written to the diagnostics
  history; the phone setup page answers only during a setup session and only under its random code;
  minimal permissions (INTERNET,
  ACCESS_NETWORK_STATE, WAKE_LOCK, RECEIVE_BOOT_COMPLETED, and SYSTEM_ALERT_WINDOW purely as the
  platform's exemption for opening itself when the TV turns on — never used to draw an overlay, and
  off until an administrator grants it); no analytics/telemetry.

## 25. Easy Mode state model
- `AppViewModel` implements `STARTING → WATCHING ⇄ CHANNEL LIST` with `recovery → WATCHING`.

## Hardware resource utilization (added section)
- **REQ-HW-001/002** — `DefaultRenderersFactory` selects the hardware `MediaCodec` decoder
  automatically; no Viewer configuration.
- **REQ-HW-003** — `DefaultTrackSelector` only chooses renditions the device can decode (codec,
  profile, resolution, frame rate).
- **REQ-HW-004** — `setEnableDecoderFallback(true)` permits a controlled software fallback; a decoder
  error gets one retry a moment later, and if all decoders fail the channel shows "Unable to play
  this channel." A software decoder is not carried to the next channel, which tries hardware again.
- **REQ-HW-005** — hardware decode + a single reused player keep CPU/RAM low for responsiveness.
- **REQ-HW-006** — Diagnostics shows **Hardware/Software (decoder name)** and logs any
  hardware→software fallback (`FluxPlayerController.onVideoDecoderInitialized`).
- **REQ-HW-007** — the Diagnostics *Decoding* field makes "hardware actually active" verifiable per
  reference stream (see README → Acceptance testing).

## 26. Non-goals (V1)
No VOD/series/DVR/recording/cloud/social/profiles/themes/complex-EPG or Viewer-facing codec,
resolution or buffer controls.
