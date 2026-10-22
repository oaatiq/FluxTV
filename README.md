# FluxTV

A deliberately simple, senior-friendly **IPTV player for Android TV**. Its whole identity is
*"turn on → watch TV."* Once an administrator has configured a playlist, the Viewer only ever deals
with **channels, favorites, volume, and programmes** — never M3U, HLS, bitrate, codecs, buffers,
FPS, servers or URLs.

Built for the person who will get the phone call when something stops working: reliability,
recovery, adaptation and buffering are engineered so the Viewer rarely has to do anything.

> This implements the *FluxTV: Basic Product & System Requirements Specification*. See
> [REQUIREMENTS.md](REQUIREMENTS.md) for a clause-by-clause map from each `REQ-*` to its code.

## Tech stack

- **Kotlin**, single-activity **Jetpack Compose** (with `androidx.tv:tv-material`) for the 10-foot UI
- **Media3 / ExoPlayer** for playback, adaptive streaming, buffering, recovery and diagnostics
- **DataStore** (settings/favorites) + a JSON playlist cache — no database, so config survives
  restarts and app updates with zero migration risk
- **WorkManager** for unattended periodic playlist refresh
- No Hilt/Room/KSP — a tiny manual DI container keeps the build simple and robust

Targets: `minSdk 24` (Android 7) · `targetSdk 34` · `compileSdk 35`.

## Build

Prerequisites:
- **JDK 17+** (the Android Gradle Plugin requires it). A JDK 21 is already used by the build scripts
  below. Android Studio's bundled JDK also works.
- **Android SDK** with platform 35 and build-tools 35 (already present on the dev machine; otherwise
  install via Android Studio > SDK Manager, or set `sdk.dir` in `local.properties`).

```bash
# from the project root
./gradlew :app:assembleDebug
```

On Windows where `JAVA_HOME` points at an older JDK, point Gradle at JDK 17+ for the build:

```bash
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot" ./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

For day-to-day use on the TV build the **release** variant instead. It is much smoother (on the MiTV,
about 40% less frame time when scrolling the channel list) and Android compiles it ahead of time when
it is installed:

```bash
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key (see `app/build.gradle.kts`), so it updates an existing
debug install in place and keeps your settings. It is not debuggable, so `adb shell run-as` won't work on it.

### Install on an Android TV box

```bash
adb connect <tv-ip>:5555        # or plug in over USB
adb install -r app/build/outputs/apk/release/app-release.apk
```

FluxTV then appears in the Android TV launcher's apps row (it registers a `LEANBACK_LAUNCHER`
entry). You can watch/operate it from a dev machine with `scrcpy`.

## First run

1. Launch FluxTV. With no playlist configured it opens **Settings** automatically.
2. In **Playlist**, enter your provider's **M3U URL** (or a local file path) and press **Save &
   Load**. There's a **Use sample test playlist** button for a quick public test feed.
3. Press **Back** repeatedly to return to TV. From now on, launching goes straight to live TV on the
   last channel.

HTTP (cleartext) playlists and streams are allowed, since IPTV feeds are frequently plain HTTP.

## Start on boot

By default FluxTV starts itself, straight into live TV, once the TV has finished booting. Turn it off
in **Settings → Preferences → Start FluxTV when the TV turns on**. It only starts after a real boot
(power loss, restart), not when the TV wakes from standby. Android 10 and newer block apps from
opening themselves in the background, so there it needs the system's "Display over other apps"
permission; Android 9 (e.g. the MiTV) needs nothing extra.

To see how long it took, open **Settings → Diagnostics → History**: after a boot-start there are three
`[boot]` lines (boot signal, FluxTV on screen, picture playing), each with the seconds since the TV
powered on, so the gaps between them are the start-up delay. The first line also says where the
start-up time went (process start, app init, setting read).

**Start-up speed.** Android gives the boot signal to every app in turn, so FluxTV's receiver asks for
the highest allowed priority. On the MiTV that moved the launch from 82–88 s to 54–61 s after power-on
(picture playing at 64–70 s instead of 91–104 s). A sideloaded *debug* APK is otherwise run uncompiled
(`status=run-from-apk`), which makes the first seconds after boot slower; after each `adb install` of a
debug build you can compile it on the TV (saved about 5 s of process start-up there). A release build
is compiled when it is installed, so it doesn't need this:

```bash
adb shell cmd package compile -m speed -f com.fluxtv.app
```

## Remote control

| Button | In TV (watching) | In the channel list |
|---|---|---|
| **▲ / Channel +** | Next channel | — |
| **▼ / Channel −** | Previous channel | — |
| **◀ ▶** | — | Switch Categories ⇄ current category ⇄ All ⇄ Favorites. **Categories** lists every category; **OK** on one shows its channels |
| **OK** | Open the channel list | Watch the highlighted channel |
| **Hold OK (2s)** | Open **Settings** | — |
| **OK (long-press on a row)** | — | Add/remove favorite ★ |
| **Back** | Show the channels in the current channel's category (e.g. Maroc) | Close the list |
| **0–9** | Go directly to a channel number | Go directly to a channel number |
| **Volume** | Standard TV volume | Standard TV volume |
| **MENU / SETTINGS** | Open **Settings** | — |

Back never exits the app accidentally. To leave, press **Home** (the app stays in the background), or to
close it completely open **Settings** (hold OK, or MENU) and choose **Exit FluxTV**. Accidental
repeated presses are debounced to one action per press.

## Administrator / Settings

Reached only by a deliberate gesture (**hold OK** or the **MENU** button), so ordinary navigation
can never expose destructive actions. Optionally protect it with a **PIN** (Preferences). Sections:

- **Playlist** — set the M3U source, refresh, see channel count / last update.
- **Channels & favorites** — curate favorites (OK toggles ★) and enable *favorites-only* Easy Mode,
  so a 2,000-channel subscription can show your grandfather just the handful he watches.
- **Preferences** — favorites-only, max quality (Auto / 720p / 1080p), refresh-rate matching,
  auto-refresh interval, and the Settings PIN.
- **Diagnostics** — live stream health and a persisted event history for troubleshooting: channel
  reachability, resolution, frame rate, **hardware vs software decoding**, codecs, bitrate,
  bandwidth, buffered duration, dropped frames, buffering events, recoveries and channel startup
  time.

## Acceptance testing

**The Grandfather Test (§27).** Without any explanation, a first-time user should be able to: turn
on and see TV; go to the next/previous channel; find a channel; adjust volume; see the other
channels in the current category (Back); and keep watching after a brief network interruption.

**Hardware decoding (REQ-HW-007).** For each reference stream, confirm in **Settings → Diagnostics**
that *Decoding* reads **Hardware (…)**, not merely that hardware was available. Any hardware→software
fallback is recorded in the diagnostics **History**.

## Project structure

```
app/src/main/java/com/fluxtv/app/
├─ data/            M3U parsing, settings (DataStore), JSON cache, repository
├─ playback/        ExoPlayer setup, recovery, error mapping, network & frame-rate, diagnostics
├─ ui/
│  ├─ easymode/     fullscreen player + overlays (channel list, number entry, recovery, banner)
│  ├─ admin/        PIN gate + playlist / channels / preferences / diagnostics
│  ├─ components/   large-type, high-contrast, focusable TV widgets
│  └─ AppViewModel  the Easy Mode state machine (STARTING → WATCHING ⇄ CHANNEL LIST)
├─ work/            WorkManager periodic refresh
└─ MainActivity     central remote-key handling + fullscreen + frame-rate matching
```

## Non-goals (V1)

No VOD/series/movie library, DVR/recording, cloud accounts, social features, multiple profiles,
themes/skins, complex EPG grids, or Viewer-facing codec/resolution/buffer knobs. These must never
compromise Easy Mode.
