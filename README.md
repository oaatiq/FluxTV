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
- **ZXing** (core only) to draw the QR code a phone scans to send a playlist to the TV
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

The release build also goes through R8, which shrinks and optimizes the code. Class names are left
as they are, so crashes in `adb logcat` read the same as in a debug build. R8 removes code it cannot
see being used, so anything Android or Shizuku creates by name needs a rule in `app/proguard-rules.pro`
(the Shizuku wake helper has one). After changing those rules, or adding a library that loads classes
by name, check a release build on the TV: a channel plays, Settings opens, and with Shizuku running,
**Settings → Preferences** reaches *Shizuku helper active*. `tools/power-cycle-test.sh` covers the
standby cases.

### Install on an Android TV box

```bash
adb connect <tv-ip>:5555        # or plug in over USB
adb install -r app/build/outputs/apk/release/app-release.apk
```

FluxTV then appears in the Android TV launcher's apps row (it registers a `LEANBACK_LAUNCHER`
entry). You can watch/operate it from a dev machine with `scrcpy`.

## First run

1. Launch FluxTV. With no playlist configured it opens **Settings → Playlists** automatically.
2. Choose **Add channels using your phone** and scan the QR code with the phone's camera. The phone
   opens a page from the TV itself (nothing to install): paste the provider's **M3U link**, press
   **Send to TV**, and both the phone and the TV say whether it worked. The phone has to be on the
   same Wi-Fi as the TV. Without a phone, **Type a link on the TV** takes the link (or a local file
   path) from the remote or a keyboard, and has a **Use sample test playlist** button for a quick
   public test feed.
3. Press **Back** repeatedly, or **Watch TV**, to return to TV. From now on, launching goes straight
   to live TV on the last channel, with no phone needed.

If the playlist cannot be loaded and none was saved before (the TV started before its network was
up, or the provider is down), TV shows *Can't load the channels* and FluxTV keeps trying: as soon as
the network is back, and otherwise at growing intervals up to every 15 minutes. TV starts on its own
when the channels arrive.

HTTP (cleartext) playlists and streams are allowed, since IPTV feeds are frequently plain HTTP.
Channels can be HLS, DASH or plain MPEG-TS over HTTP, as long as they are not DRM-protected. An
HLS channel whose address does not end in `.m3u8` plays too: one ending in `.m3u` is opened as HLS
straight away, and when the player cannot tell another stream's format, FluxTV tries it once as HLS.
The playlist itself can be `.m3u` or `.m3u8` either way. A channel in a format FluxTV has no player
for (RTSP, Smooth Streaming) shows *Unable to play this channel* rather than closing the app.

## Start when the TV turns on

By default FluxTV starts itself, straight into live TV, whenever the TV comes on. Turn it off in
**Settings → Preferences → Start FluxTV when the TV turns on**.

**Android 10 and newer need one permission.** Those versions refuse to let an app open itself while
none of it is on screen, which is exactly what starting after a boot or a wake asks for, and the
platform's documented exemption is "Display over other apps". FluxTV asks for it in the manifest so
it appears in that screen's list, never draws an overlay with it, and leaves the decision to the
administrator: when it is missing, **Settings → Preferences** offers **Allow FluxTV to open itself**
right under the toggle, which opens Android's screen for it. By adb instead:

```bash
adb shell appops set com.fluxtv.app SYSTEM_ALERT_WINDOW allow
```

Android 9 and older (e.g. the MiTV) never blocked it and need nothing extra. Android may also let a
start through on its own when FluxTV still owns a task on the Recents screen, so it can work without
the permission — the `[wake]`/`[boot]` lines in the Diagnostics history say whether it actually did.

A TV turns on in two different ways, and FluxTV covers both:

- **A real boot** (power loss, restart). Android sends `BOOT_COMPLETED`, which `BootReceiver` picks
  up from the manifest even when FluxTV is not running.
- **Waking from standby**, which is what the remote's power button actually does: it suspends the TV
  rather than switching it off, so no boot signal follows and the TV simply shows whatever it had on
  screen before. `StandbyWatcher` listens for the wake (`ACTION_SCREEN_ON`) and brings FluxTV to the
  front. Android only delivers that signal to receivers registered in code, so this half works while
  FluxTV's process is alive — Android normally keeps it cached through a standby — and the wake of a
  TV that killed the process in the meantime goes unheard. The `[wake]` lines in **Settings →
  Diagnostics → History** record each screen-off and wake, and what FluxTV did about it.

Because waking the TV is treated as turning it on, FluxTV also comes to the front when the TV wakes
while another app was left on screen. Turn the preference off if that is not what you want.

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

### After a force-stop

Force-stopping FluxTV (Settings → Apps → Force stop) leaves it in Android's *stopped* state, and that
state is not cleared by a reboot: the app gets no boot signal, has no process to hear a wake, and a
wake from standby does not send Home either, so nothing inside FluxTV can bring it back. Only a
process that is not FluxTV's can, and FluxTV uses one when [Shizuku](https://shizuku.rikka.app/) is
running on the TV: it asks Shizuku to run a small helper (`WakeDaemon`) in a process of its own, as
the shell user. A force-stop of FluxTV does not touch that process. It checks the screen state twice a
second, and when the TV wakes while FluxTV has no process it starts FluxTV the way `adb shell am start`
does. While FluxTV is running, `StandbyWatcher` does this and the helper stays out of the way.
**Settings → Preferences** shows *Allow FluxTV to use Shizuku* (once, in Shizuku's own dialog) and then
*Shizuku helper active*; the Diagnostics history records `Shizuku helper running` and, after a wake
that needed it, `The TV woke while FluxTV was not running: the Shizuku helper started it`.

The helper lives as long as Shizuku does. On a TV that is not rooted, Shizuku has to be started again
after each restart (Android 9: from a computer over adb), and until then a force-stopped FluxTV stays
off, through a restart too, until someone opens it. FluxTV starts the helper again as soon as it runs
with Shizuku up, and an app update replaces it, so the helper never runs old code. If Shizuku shows
FluxTV as not allowed after an update, switch it off and on in Shizuku's list (or press the Settings
button again). Without Shizuku, FluxTV behaves exactly as before.

FluxTV deliberately does not register as the TV's Home app, although Android starts the Home app at
boot and on the Home key, which would also bring a stopped app back: as the Home app it could not be
left, because Back and *Exit FluxTV* would just start it again. Tried on the MiTV, and given up.

Another app that starts itself at power-on can still cover FluxTV a few seconds after it comes up (on
the MiTV, AT4K's boot receiver did, once FluxTV was the Home app). Nothing in FluxTV fights that, but
if it happens before FluxTV has tuned its first channel, FluxTV waits and tunes once it is back on
screen instead of playing behind the other app.
Force-stopping the other app keeps it from receiving the boot signal until someone opens it by hand;
Android 9 does not let the shell disable just its receiver.

## Standby and the picture

Going into standby takes the video surface away from FluxTV, and a decoder that was playing cannot be
relied on to move onto the new surface the TV builds on wake: many TV decoders accept it and never
produce another frame, which looks like a channel playing with a black screen. So FluxTV stops
playback when it leaves the screen and re-tunes the channel once it is back with a surface of its
own, which builds a fresh decoder and rejoins the live edge — a live stream has moved on during a
standby anyway. The same applies on the way back from Settings and from another app. Expect the
couple of seconds a normal channel change takes, with the channel banner while it loads.

## Remote control

| Button | In TV (watching) | In the channel list |
|---|---|---|
| **▲ / Channel +** | Next channel | — |
| **▼ / Channel −** | Previous channel | — |
| **◀ ▶** | — | **◀** moves from the channels to the categories, **▶** goes back into the channels |
| **▲ ▼** | — | Move through the channels, or through the categories (the channels on the right follow) |
| **OK** | Open the channel list | Watch the highlighted channel; on a category, open its channels |
| **Hold OK (2s)** | Open **Settings** | — |
| **OK (long-press on a row)** | — | Add/remove favorite ★ |
| **Back** | Show the channel list (the current channel's category, e.g. Maroc) | Step up: channels → categories → leave FluxTV |
| **0–9** | Go directly to a channel number | Go directly to a channel number |
| **Volume** | Standard TV volume | Standard TV volume |
| **MENU / SETTINGS** | Open **Settings** | — |

The channel list has two columns: **categories** on the left (Favorites and All channels first, then the
playlist's own groups with their channel counts) and the **channels** of the highlighted category on the
right. It opens on the category of the channel you are watching, with that channel selected.

Back steps back like other IPTV players: watching → the channel list → the categories column → leave
FluxTV. The last channel (one watched for a few seconds) is remembered, so the next launch resumes
it. Choosing the channel that is already playing just closes the list, and plays it on if something
had paused it. **Home** also leaves (the app stays in the background), and **Settings** (hold OK, or
MENU) has **Exit FluxTV**. Accidental repeated presses are debounced to one action per press.

## Administrator / Settings

Reached only by a deliberate gesture (**hold OK** or the **MENU** button), so ordinary navigation
can never expose destructive actions. Optionally protect it with a **PIN** (Preferences). Sections:

- **Playlists** — add playlists (from a phone, or typed), see the saved ones with the one in use
  marked **IN USE**, and open one to **Use this playlist**, refresh it, rename it, change its link or
  delete it. See [Playlists](#playlists) below.
- **Channels & favorites** — curate favorites (OK toggles ★) and enable *favorites-only* Easy Mode,
  so a 2,000-channel subscription can show your grandfather just the handful he watches. Favorites
  belong to the playlist in use; a playlist with none shows all its channels even in favorites-only
  mode, rather than nothing.
- **Preferences** — favorites-only, max quality (Auto / 720p / 1080p), refresh-rate matching,
  auto-refresh interval, and the Settings PIN.
- **Diagnostics** — live stream health and a persisted event history for troubleshooting: channel
  reachability, resolution, frame rate, **hardware vs software decoding**, codecs, bitrate,
  bandwidth, buffered duration, dropped frames, buffering events, recoveries and channel startup
  time.

## Playlists

FluxTV keeps any number of saved playlists, each with a name the administrator chooses, and shows the
channels of **one** of them, the one in use. Channels of different playlists are never combined, and
everything about choosing between them lives in **Settings → Playlists**: Easy Mode never shows a
playlist name, link or choice, and the Viewer is never asked to pick one.

- **Adding never switches.** A new playlist is checked (downloaded and read) and saved only if it
  works; a link that does not work changes nothing and gets a plain-language reason. It is not put in
  use either, except the very first playlist, which has nothing to replace. Switching is always the
  administrator's explicit **Use this playlist**.
- **Switching is checked first.** **Use this playlist** loads the playlist before it replaces
  anything; if it cannot be loaded, the playlist in use stays as it was. Once it loads, leaving
  Settings resumes that playlist's own last channel, or its first.
- **Each playlist keeps its own** favorites and last channel, so switching away and back finds them
  as they were. The playlist in use is remembered across restarts, and only it is refreshed.
- **The playlist in use cannot be deleted**: put another one in use first.
- Playback trouble never switches playlists by itself; FluxTV keeps retrying the one in use.
- Updating from a version with a single playlist keeps it, as the first saved playlist and the one in
  use, with its favorites and last channel.

### Adding channels from a phone

**Add channels using your phone** shows a QR code. While that screen is open, FluxTV answers on the
home network at `http://<tv-address>:8790/<code>` (another port if 8790 is taken), and that address
is what the QR code holds; the screen also shows it in text for a phone without a camera. The page
names the TV and shows a four-digit TV code that the TV screen also shows, so it is clear which TV
the link goes to. It has a **Playlist link** field, an optional **Name**, and **Send to TV**. The TV
then checks the link and both screens say how it went; on the phone, a link that did not work comes
back in the form to be corrected.

The phone page is a setup session, not a service that is always on:

- It answers only while the QR screen is open in Settings (behind the PIN, if one is set). Leaving
  that screen, the TV going to standby or another app taking over ends it, as do 15 minutes. A new
  session has a new address.
- Every address it answers starts with a random 12-character code that only the QR code carries, so
  a device on the network that has not scanned the TV's screen gets nothing.
- The link travels over the home network as plain HTTP, the way most IPTV playlists are fetched
  anyway. The page is not cached, and FluxTV logs playlist names, never links.

## Acceptance testing

**The Grandfather Test (§27).** Without any explanation, a first-time user should be able to: turn
on and see TV; go to the next/previous channel; find a channel; adjust volume; see the other
channels in the current category (Back); and keep watching after a brief network interruption.

**Multiple playlists.** Save two playlists, put the second in use in **Settings → Playlists**, then
restart FluxTV (or the TV). TV comes back on the second playlist's channels, on its last channel,
with no playlist prompt or choice anywhere outside Settings.

**Hardware decoding (REQ-HW-007).** For each reference stream, confirm in **Settings → Diagnostics**
that *Decoding* reads **Hardware (…)**, not merely that hardware was available. Any hardware→software
fallback is recorded in the diagnostics **History**.

**Power off / on from the remote.** The remote's power button suspends the TV rather than switching
it off, which is a different path from a restart and has to be tested on the device itself. Both
cases below end in **Settings → Diagnostics → History**, where `[wake]` and `[standby]` lines record
what FluxTV saw and did.

1. *Picture comes back.* While watching, power off, wait ~30 s, power on. Expect the channel banner
   and a picture within the few seconds a channel change takes — sound without a picture is the
   failure this is testing for. The history should show `[standby] FluxTV left the screen` and then
   a fresh `[startup] Preparing …`.
2. *FluxTV comes back on its own.* Press Home so another app is on screen, then power off, wait
   ~30 s, power on. Expect FluxTV on screen by itself. The history should show
   `[wake] SCREEN_ON: bringing FluxTV to the front` followed by `[wake] SCREEN_ON: FluxTV is on
   screen`. `FluxTV did not come up` instead means Android refused the start: grant the permission
   above. No `[wake]` line at all means the TV never announced the wake to the app (or its process
   had been killed), which the app cannot work around.

`tools/power-cycle-test.sh` runs both cases over adb from a machine on the same network as the TV,
and prints the evidence for each (focused app, the `FluxTV` logcat lines, any self-start Android
refused, and the tail of the diagnostics history):

```bash
tools/power-cycle-test.sh 192.168.2.135                        # adb over the network
tools/power-cycle-test.sh <serial> --install app-debug.apk --grant
```

It uses `KEYCODE_SLEEP`/`KEYCODE_WAKEUP`, which is the same screen-off/screen-on the power button
goes through and is quicker to repeat than the remote. A box that leaves the network while suspended
cannot be woken over adb at all: the script says so, waits for the power button on the remote, and
carries on when the box answers again — and a vendor's deeper standby is worth one pass with the
remote regardless. Case 1 ends in "look at the TV", because no adb command can see a picture.

So that both cases can be followed from outside the app — they happen while nobody can open
Settings — the `[boot]`, `[wake]` and `[standby]` history lines are also written to logcat under the
tag `FluxTV` (`adb logcat -s FluxTV:I`). Playback lines are not: those carry the channel URL, and a
playlist URL can carry credentials.

Devices: Xiaomi MiTV (MStar, Android 9) and MINIX NEO U8K-ULTRA (Amlogic S928X-K, Android 11 AOSP —
the Android version that needs the permission above).

## Project structure

```
app/src/main/java/com/fluxtv/app/
├─ data/            M3U parsing, settings (DataStore), JSON cache, repository
├─ playback/        ExoPlayer setup, recovery, error mapping, network & frame-rate, diagnostics
├─ setup/           the phone page behind the QR code (a small web server, during setup only)
├─ ui/
│  ├─ easymode/     fullscreen player + overlays (channel list, number entry, recovery, banner)
│  ├─ admin/        PIN gate + playlists (QR code, saved list) / channels / preferences / diagnostics
│  ├─ components/   large-type, high-contrast, focusable TV widgets
│  └─ AppViewModel  the Easy Mode state machine (STARTING → WATCHING ⇄ CHANNEL LIST)
├─ work/            WorkManager periodic refresh
└─ MainActivity     central remote-key handling + fullscreen + frame-rate matching
```

The launcher artwork in `app/src/main/res` — the TV home-screen tile (banner) and the app icon — is
built from `art/fluxtv-logo.webp` by `tools/make_launcher_art.py`. It shows only on the TV's home
screen and in app lists, never inside FluxTV.

## Non-goals (V1)

No VOD/series/movie library, DVR/recording, cloud accounts, social features, multiple profiles,
themes/skins, complex EPG grids, or Viewer-facing codec/resolution/buffer knobs. These must never
compromise Easy Mode.
