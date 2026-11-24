# FluxTV

A deliberately simple, senior-friendly **IPTV player for Android TV**, which also runs on **Android
phones and tablets**, worked by touch there (see [On a phone or tablet](#on-a-phone-or-tablet)). Its
whole identity is *"turn on → watch TV."* Once an administrator has configured a playlist, the Viewer only ever deals
with **channels, favorites, volume, and programmes** — never M3U, HLS, bitrate, codecs, buffers,
FPS, servers or URLs.

Built for the person who will get the phone call when something stops working: reliability,
recovery, adaptation and buffering are engineered so the Viewer rarely has to do anything.

> This implements the *FluxTV: Basic Product & System Requirements Specification*. See
> [REQUIREMENTS.md](REQUIREMENTS.md) for a clause-by-clause map from each `REQ-*` to its code.

## Tech stack

- **Kotlin**, single-activity **Jetpack Compose** (with `androidx.tv:tv-material`) for the 10-foot UI,
  and a touch layout of its own for phones and tablets
- **Media3 / ExoPlayer** for playback, adaptive streaming, buffering, recovery and diagnostics, with
  its FFmpeg extension for sound formats the TV cannot decode, over the same **OkHttp** client the
  playlist downloads use
- **DataStore** (settings/favorites) + a JSON playlist cache — no database, so config survives
  restarts and app updates with zero migration risk
- **WorkManager** for unattended periodic playlist refresh and update checks
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
To sign with a keystore of its own instead, set `FLUXTV_KEYSTORE` (and `FLUXTV_KEYSTORE_PASSWORD`,
`FLUXTV_KEY_ALIAS`, `FLUXTV_KEY_PASSWORD`, or the matching `-Pfluxtv.…` properties); it must hold the
key the TVs' FluxTV is signed with, or they cannot update to it (see [Updates](#updates)).

**A build for TVs that already updated from a release** must be signed the way releases are: a TV
that installed one carries the release key, and Android then refuses builds signed with a debug key.
Sign the release APK with the release key and its key lineage:

```bash
FLUXTV_KEYSTORE=/path/to/fluxtv-release.p12 FLUXTV_KEYSTORE_PASSWORD=… tools/sign_release.sh app/build/outputs/apk/release/app-release.apk
```

**Version numbers.** The build number (`versionCode`) is the number of commits up to the one built,
so every later commit builds a higher one; that is how a TV knows a build is newer than its own. The
version name is `fluxtv.versionName` in `gradle.properties` followed by it (`1.1.64`), or what
`-PversionName=1.2.0` says. A shallow clone counts too few commits: fetch the whole history
(`git fetch --unshallow`) before building a release. `-PversionCode=…` overrides the count.

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

### Install on a phone or tablet

The same APK installs on a phone (Android 7 or newer): copy it over and open it, or with USB debugging
on, `adb install -r app/build/outputs/apk/release/app-release.apk`. FluxTV appears among the phone's
apps.

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
when the channels arrive. Meanwhile OK opens Settings, to check the playlist.

HTTP (cleartext) playlists and streams are allowed, since IPTV feeds are frequently plain HTTP.
Channels can be HLS, DASH or plain MPEG-TS over HTTP, as long as they are not DRM-protected. An
HLS channel whose address does not end in `.m3u8` plays too: one ending in `.m3u` is opened as HLS
straight away, and when the player cannot tell another stream's format, FluxTV tries it once as HLS.
The playlist itself can be `.m3u` or `.m3u8` either way. Headers written after a `|` in a channel's
address, as in playlists made for Kodi (`http://…|User-Agent=…&Referer=…`), are sent as headers.
Sound in AC-3, E-AC-3, MP2 or DTS plays on TVs without a decoder of their own for it (FFmpeg). A
channel in a format FluxTV has no player for (RTSP, Smooth Streaming) shows *Unable to play this
channel* rather than closing the app.

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

A prompt or another app's see-through screen opened over FluxTV is not leaving the screen: the
picture still shows, so on a TV the channel plays on under it. Android's "Start recording or
casting?" when someone asks to see the TV's screen is one, "Allow USB debugging?" another; either
used to freeze the channel until someone answered it with the remote. On a phone such a screen
still pauses the channel.

## When a channel stops

Every problem that keeps a channel from playing normally is shown on screen for as long as it lasts,
with a picture that can be understood without reading, and goes away by itself once the channel plays
again. FluxTV fixes what it can by itself; nothing asks the Viewer to choose anything. The same three
signs are used throughout: **three slowly moving dots** mean "wait, FluxTV is on it", an **icon**
says why (the table further down), and a few plain words say it for those who read.

| What happens | What the screen shows | What FluxTV does |
|---|---|---|
| A channel takes more than 1.5 s to start, or stops to load for more than 1.5 s | The moving dots, in the middle | Waits; starts it again after 15 s (start) or 8 s (stall) |
| FluxTV is trying the channel again | Icon + dots + *Connection interrupted. Trying again…* (or *No network connection.*, *Channel temporarily unavailable.*) | Tries again, with growing pauses, about 45 s in all |
| Those tries did not bring it back | The full *Reconnecting…* screen (below) | Tries again every 30 s, for as long as the channel is on screen |
| The network is gone, while the channel still plays what it buffered | *No network connection*, crossed-out Wi-Fi, top right | Starts the channel again the moment the network is back |
| The channel keeps stopping to load (3 times in 2 minutes) | *Slow connection*, faint Wi-Fi, top right | Lowers the picture quality by itself; the notice goes after 2 minutes without a stop |
| The picture freezes while the sound plays on | The dots while the picture restarts; if it keeps freezing, *The picture has stopped.* with a paused TV | Restarts the picture at once 3 times, then every 30 s while it stays stopped. A busy box that is only falling behind (dropping frames, not frozen) is left to catch up instead of restarted |
| The TV cannot show this channel's picture | *Sound only.* with a loudspeaker | Nothing can: the sound plays on |
| Another app took the sound and paused the channel | *Paused.* with the pause sign, and **OK Play** | OK plays it again |

A lost or slow connection also shows in the channel list, under its title, and a lost network on the
start-up screen. Both follow what Android reports; a channel whose data keeps arriving although
Android reports no network does not show it, as a TV can report that wrongly.

If the channel still does not come back (about 45 s), a calm full screen takes over, made
to be understood without reading: the channel's **logo and number**, the same **slowly moving
dots**, and one word, *Reconnecting…*. There is nothing to choose: FluxTV keeps bringing the channel
back by itself, and the picture simply returns. OK tries again at once; ▲ ▼ change channel as always.

For whoever looks after the TV, the reason is in small print at the bottom, with an icon:

| Icon | Reason |
|---|---|
| Wi-Fi crossed out | No network connection |
| Faint Wi-Fi | The connection to the channel keeps failing (server not answering, or too slow) |
| Padlock | The provider refused the channel: often another TV on the same subscription, or an ended subscription |
| Magnifier | The provider does not have the channel (moved, or removed from the playlist) |
| TV | This TV's decoder failed, perhaps only for now |
| "i" | Temporarily unavailable: the provider is not sending it |

The same icons go with the message while FluxTV is still trying again, before this screen.

A channel this TV cannot play at all (protected, or in a format it cannot decode) shows a
**crossed-out TV** and "▲ ▼ Change channel" instead of the dots: nothing will bring it back.
**Settings → Diagnostics → History** has the details, such as the HTTP status the provider answered.

## Remote control

| Button | In TV (watching) | In the channel list |
|---|---|---|
| **▲ / Channel +** | Next channel | — |
| **▼ / Channel −** | Previous channel | — |
| **◀ ▶** | — | **◀** moves from the channels to the categories, and from the categories to **⚙ Settings**; **▶** goes back |
| **▲ ▼** | — | Move through the channels, or through the categories (the channels on the right follow) |
| **OK** | Open the channel list (with no channels yet: open **Settings**) | Watch the highlighted channel; on a category, open its channels; on ⚙, open **Settings** |
| **Hold OK (2s)** | Open **Settings** | — |
| **OK (long-press on a row)** | — | Add/remove favorite ★ |
| **Back** | Show the channel list (the current channel's category, e.g. Maroc) | Step up: channels → categories (or ⚙) → leave FluxTV |
| **0–9** | Go directly to a channel number | Go directly to a channel number |
| **Last channel** (on remotes that have it) | Back to the channel watched before; again to return | Same |
| **Info / Guide** (on remotes that have it) | Show the channel banner with what is on now and next; again to hide it | — |
| **Volume** | Standard TV volume | Standard TV volume |
| **MENU / SETTINGS** | Open **Settings** | — |

The channel list has two columns: **categories** on the left (Favorites and All channels first, then the
playlist's own groups with their channel counts) and the **channels** of the highlighted category on the
right. It opens on the category of the channel you are watching, with that channel selected. The time is at
the top right of the list. At the far left of the list is **⚙ Settings**: from the categories, press
**◀** once more.

**Settings with any remote.** Hold OK and MENU open Settings, but a TV's own remote often sends
neither: when an Android TV box is worked with the TV's remote through HDMI-CEC (an LG or Samsung
remote, among others), the TV keeps the MENU or ⚙ key for its own menu, and holding a key can reach
FluxTV as several short presses. **OK, ◀, ◀, OK** — open the list, go to the categories, then to ⚙ —
uses only the arrows and OK, which every remote sends, and takes the same four presses from any
category. While there are no channels to list (*Loading channels…*, *Can't load the channels*, *No
channels yet*), the screen shows **Open Settings** and OK opens it.

Back takes two presses: watching → the channel list → leave FluxTV (◀ in the list is what reaches
the categories and Settings). The last channel (one watched for a few seconds) is remembered, so the next launch resumes
it. Choosing the channel that is already playing just closes the list, plays it on if something
had paused it, and shows its banner, so it is also a way to see what is on. **Home** also leaves (the app stays in the background), and **Settings** (⚙ in the
channel list, hold OK, or MENU) has **Exit FluxTV**. Accidental repeated presses are debounced to one action per press.

## On a phone or tablet

FluxTV recognises a phone or tablet by itself (a device that is not a TV and has a touchscreen; a TV
box without a touchscreen is always treated as a TV) and lays Easy Mode out for touch. The channels,
favorites, programme guide, text size and Settings are the same as on the TV.

**Held upright**, the picture is at the top. Under it are the channel playing (number, logo, name,
and what is on now and next with a progress bar), big **Previous** and **Next** buttons, a ★ for the
favorites and a ⚙ for Settings, and below them the channel list, always on screen: **tap a channel to
watch it**. Above the list are a **Find a channel** field (by name, accents ignored, or by number,
across all channels) and the categories as a row of chips (Favorites, All channels, then the
playlist's own), starting on the category of the channel playing. When the channel changes, the list
scrolls to keep it in sight.

**Sideways** (turn the phone, or press the full-screen button in the corner of the picture), the
picture fills the screen. A tap shows the controls for a few seconds: **‹ Previous**, **Channels** and
**Next ›** in the middle, the channel playing with its ★ at the bottom, and the clock, ⚙ Settings and
the button back to upright at the top. **Channels** opens the same list as upright from the left; tap
a channel to watch it, or tap the picture beside the list to close it. Each channel change shows the
channel banner, as on the TV.

| Gesture | Does |
|---|---|
| **Tap a channel** | Watch it (the one already playing: show what is on, and play on if it was paused) |
| **Swipe up / down on the picture** | Next / previous channel (like ▲ / ▼ on the remote) |
| **Press and hold a channel** | Add it to the favorites, or take it out; a message says which |
| **Tap the picture** (sideways) | Show or hide the controls |
| **Back** | Close the list, the controls or the keyboard; then leave FluxTV, as in any phone app |

The full-screen button turns the screen at once, whichever way the phone is held. With auto-rotate
on, the phone takes over again once it is held the new way; with the rotation locked, the screen
stays as the button left it, so full screen works with the lock on too.

What a phone does differently from a TV:

- **It never opens by itself.** Starting when the TV turns on (after a boot, or a wake from standby)
  and the Shizuku helper are for TVs only: on a phone FluxTV opens when it is opened, and its boot
  receiver is switched off.
- **Unplugged headphones pause the channel** instead of playing it out loud; a large **Play** button
  starts it again, as it does after another app took the sound. A phone call pauses it and it plays
  on afterwards. (A TV never pauses for the sound output: losing HDMI counts as the same thing.)
- **Settings** is reached with the ⚙ button (behind the PIN, if one is set), has a **Back** button at
  the top of each screen, and lays its buttons out on more lines where the screen is narrow. A
  playlist is added with **Add a playlist link** (paste the link): the QR code for adding channels
  from a phone is not offered on the phone itself. **Start FluxTV when the TV turns on** and **Match
  TV refresh rate** are left out.
- The status bar shows while the phone is upright and hides sideways, and the screen stays on while
  FluxTV is open, as on the TV.

## Banner, programme guide and clock

Each channel change shows a banner at the bottom with the channel's number, logo, name and category.
When there is a programme guide, the banner also has two lines, **Now** (with when it started and
when it ends, and a thin bar under it for how much of it has gone) and **Next** (with when it
starts), and stays on a little longer (7 s instead of 4.5 s) to be read.
A large clock with the day and date shows at the top right together with the banner. The Info or
Guide key on remotes that have one shows the banner again without changing channel.

The guide is the XMLTV file the playlist names in its first line (`#EXTM3U url-tvg="…"`, or
`x-tvg-url`), or the link set for the playlist in **Settings → Playlists → (playlist) → Programme
guide**, which comes first. Gzipped guides (`.xml.gz`) are fine. FluxTV fetches it in the
background about 20 seconds after the first channel plays, so it never holds up the picture, then
again when it is 12 hours old (it checks every hour), and when the playlist in use or its guide link
changes. It keeps only what the banner needs, the playlist's channels from 3 hours ago to 36 hours
ahead, titles only, and saves that, so the banner has it right after a restart. A guide that cannot
be fetched leaves the last one in place, and it is tried again an hour later. When a playlist
refresh brings channels the guide was not cut down for, the guide is fetched again at the next hourly
look, so new channels get their programmes too. Titles written with HTML names for letters
(`M&eacute;t&eacute;o`), as some generated guides have them, show as letters (`Météo`).

A channel is found in the guide by its `tvg-id` (with or without the feed iptv-org guides add:
`AlAoula.ma` finds `AlAoula.ma@SD`, while an exact id always wins, so `2MMonde.ma` never gets the
`2MMonde.ma@Plus1` feed's times), else by its name, else by its `tvg-name`:
`FR: TF1 HD`, `FR - TF1 HD`, `|FR| TF1`, `TF1 (backup)` and a guide's `TF1` all match, because a
short country tag in front, bracketed notes, words like HD/FHD/4K, accents, spaces and punctuation
are left out of the comparison. A playlist whose guide is in another time zone can say so with
`tvg-shift` (in hours, on a channel or on the `#EXTM3U` line for all of them), and the guide's
times are moved by it. A programme with titles in several
languages shows the one in the TV's language. **Settings → Playlists → (playlist)** says how many
channels have Now and Next and when the guide was fetched, or why it could not be; the Diagnostics
history has a `[guide]` line for each fetch (never the link, which can hold the account details).

The channel list shows what is on now after each channel name, in smaller grey letters on the same
line, so rows keep their height and a long title is cut short before the name is. Channels the guide
does not have show their name alone, as before. **Settings → Preferences → Show what is on in the
channel list** turns this off.

This is deliberately not a guide grid (see Non-goals): the Viewer gets what is on, without a new
screen to learn. A missing, old or unreachable guide never holds up or stops a channel: the banner
and the list simply leave the programme out.

## Text size

**Settings → Preferences → Text size on TV** makes all the text the Viewer sees, the banner, clock,
channel list and messages, 25% (**Large**) or 50% (**Extra large**) larger than **Normal**. The
channel list's panel and columns widen with it, so names keep as much of their length as they can.
Settings itself keeps its normal size, since the administrator uses it; the line under the choices
shows a channel name at the chosen size.

## Even volume between channels

Channels are broadcast at very different loudness, so without help the Viewer turns the volume up on
one channel and down on the next. **Settings → Preferences → Even out the volume between channels**
(on by default) brings quiet channels up by as much as 10 dB and loud ones down by as much as 12 dB.
It follows each channel's level over a few seconds, so the sound inside a programme keeps its shape;
after a channel change it adapts faster for a few seconds. Silence is not turned up, and a limiter
keeps the result under full scale. Switching it off or on takes effect at once.

It works on the sound FluxTV decodes (on the TV's own decoders or FFmpeg). Sound the TV passes
through undecoded to an amplifier or soundbar (AC-3 over HDMI, say) cannot be levelled.
**Settings → Diagnostics → Volume levelling** shows the gain it is applying to the channel playing,
or says that it is off or not possible for this sound.

## Administrator / Settings

Reached only by a deliberate step — **⚙ Settings** at the far left of the channel list (OK, ◀, ◀, OK),
**hold OK**, or the **MENU** button — so watching and changing channels never lead into it by
themselves; the ⚙ works with every remote (see [Settings with any remote](#remote-control)).
Optionally protect it with a **PIN** (Preferences). Sections:

- **Playlists** — add playlists (from a phone, or typed), see the saved ones with the one in use
  marked **IN USE**, and open one to **Use this playlist**, refresh it, rename it, change its link,
  set its programme guide link, or delete it. See [Playlists](#playlists) below.
- **Channels & favorites** — curate favorites (OK toggles ★) and enable *favorites-only* Easy Mode,
  so a 2,000-channel subscription can show your grandfather just the handful he watches. Favorites
  belong to the playlist in use; a playlist with none shows all its channels even in favorites-only
  mode, rather than nothing.
- **Preferences** — favorites-only, text size on TV (Normal / Large / Extra large), even volume
  between channels, max quality (Auto / 720p / 1080p), refresh-rate matching (switching it off puts
  the TV back in its own mode at once), auto-refresh interval, the **player
  name** sent to the provider (User-Agent: some providers only serve players they know; empty means
  `FluxTV/1.0`; it is used for every saved playlist), and the Settings PIN.
- **Diagnostics** — live stream health and a persisted event history for troubleshooting: channel
  reachability, resolution, frame rate, **hardware vs software decoding**, codecs, bitrate,
  bandwidth, buffered duration, dropped frames, buffering events, recoveries, channel startup
  time and the volume levelling gain. If FluxTV ever closes by itself, the next start adds a *crash*
  line saying what failed and where; a picture that froze while the sound played on is restarted
  and noted under *decoder*. Each *error* line says what caused it: the HTTP status the provider
  answered (`HTTP 403 Forbidden`: refused, often another TV on the same subscription; `HTTP 404`:
  no such channel; `HTTP 5xx`: the provider's server failing), or what failed underneath, such as
  a certificate the TV does not trust or a server name that does not resolve. *recovery* lines show
  the healing behind the error screen: each background try (with a new player and connections),
  each playlist reload it asked for, and *playing again after N background tries* once it worked.
  The version of FluxTV running is at the top.
- **Updates** — the version running and the newest one, what the updater is doing, **Check for
  updates** and **Install now**, how updates install on this TV and what it still needs for that
  (with the button to allow it), **Update FluxTV automatically** (on by default), and the update
  address. See [Updates](#updates).

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
- **Channel numbers stay put.** A refresh keeps each channel's number, favorite and last-channel
  mark; channels the provider adds get the next numbers after the last one, and removed ones go
  without moving the others. This holds when the provider moves to another server too, within the
  same link or through **Change link**: a channel it still has is recognised by its name and its
  address apart from the server (`http://server:port`), or else by its name and category. A new
  link to a list that is mostly another one starts again at #1.
- **The playlist in use cannot be deleted**: put another one in use first.
- Playback trouble never switches playlists by itself; FluxTV keeps retrying the one in use. A
  channel the provider keeps refusing or no longer has does get the playlist in use reloaded (at
  most every 30 minutes), in case the provider moved it to a new address or renewed its token.
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

## Updates

FluxTV updates itself. Nobody in front of the TV has to do anything, and on most TVs nobody is asked
anything either: a new version is found, downloaded and checked in the background, installed while
nobody watches, and FluxTV comes back by itself on the new version, with its playlists, favorites,
channel numbers and settings as they were.

### How an update goes

1. **Looking.** A minute and a half after FluxTV starts, and every 6 hours in the background (also
   while the TV is in standby), FluxTV reads the `update.json` of the newest release and compares
   the build number there with its own. By default that is
   `https://github.com/oaatiq/FluxTV/releases/latest/download/update.json`, which GitHub always
   serves from the newest release; **Settings → Updates → Update address** points a TV elsewhere.
2. **Downloading.** A newer build is downloaded at once, in the background. While a channel plays,
   the download takes at most 15% of the line (as the player measures it), so the picture never
   stalls for it; off screen it goes at full speed. A download cut short goes on from where it
   stopped. A phone on mobile data waits for Wi-Fi.
3. **Checking.** The file must have the size and SHA-256 the release states, be FluxTV, be the build
   announced and newer than the one installed, and be signed with the same key as the FluxTV on the
   TV. It is checked again just before it is installed. A file that fails is never installed.
4. **Installing**, at a moment nobody is watching:
   - as soon as FluxTV is off screen: the TV in standby (the remote's power button), Home, another
     app — the moment the update is ready, or the moment FluxTV leaves the screen;
   - when FluxTV starts (after the TV was switched off at the plug), before the first channel;
   - on a TV that never leaves FluxTV, in the night (3:00–5:00) once the update has waited a day, or
     at any hour after three days.

   While it installs with FluxTV on screen, a calm screen shows an arrow coming down into a tray,
   the same slowly moving dots as everywhere else ("wait, FluxTV is on it") and *Updating FluxTV…*;
   the keys do nothing but the volume. Some seconds later FluxTV is back, on the same channel.
5. **Afterwards.** The new version writes `[update] Updated from 1.1.64 (build 64) to 1.2.0 (build
   70), silently, by Android` in **Settings → Diagnostics → History**, and **Settings → Updates** and
   **Diagnostics** show the new version.

**Settings and data are kept.** Android replaces an app in place and keeps all of its data: the
playlists, favorites, last channels and preferences (DataStore), the saved channels and programme
guide. FluxTV only ever installs builds of itself signed with its own key, the one kind Android
installs over it; anything else could only be installed by removing FluxTV first, which would lose
all that, and FluxTV never does it.

**A failed update changes nothing.** Android installs an update in one step or not at all: a failed
installation leaves the FluxTV that was there, working, with everything set in it. FluxTV tries
again later, 30 minutes, 2 hours, 6 hours and then a day after each failure. A build Android can
never install here (signed with another key, older, made for a newer Android) is not tried again by
itself; a newer build or **Install now** is. Each step and each failure has an `[update]` line in the
history, also written to logcat (`adb logcat -s FluxTV:I`), so an update can be followed over adb
while nobody can open Settings.

### Silent installation: what each Android allows

| Device | How updates install | What it needs, once |
|---|---|---|
| Any Android 7+, with [Shizuku](https://shizuku.rikka.app/) running | **Silently**, on every version: FluxTV's Shizuku helper installs it as `adb install` does, and starts FluxTV again | FluxTV allowed in Shizuku (**Settings → Updates** or **Preferences**) |
| Android 12 and newer | **Silently**: Android installs an app's own update without asking | *Install unknown apps* allowed for FluxTV |
| Android 8 to 11, no Shizuku | Android asks *Install / Cancel* before each installation. FluxTV shows the question when it starts, at most once a day | *Install unknown apps* allowed for FluxTV |
| Android 7, no Shizuku | The same question | *Unknown sources* on (it is, where FluxTV was installed from a file) |

Why it is so:

- An ordinary app can only install apps through Android's installer, and that asks the user, with
  one exception: since **Android 12** an app may update **itself** without a question
  (`PackageInstaller.SessionParams.setRequireUserAction(USER_ACTION_NOT_REQUIRED)`), when it may
  install apps at all (*Install unknown apps*), declares `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, and
  targets a recent Android: API 29 on Android 12, 30 on 13, 31 on 14, 33 on 15, **34 on 16** and 35 on
  17. FluxTV targets 34, which is enough up to Android 16: raise `targetSdk` to 35 before TVs move to
  Android 17. If Android asks anyway, FluxTV notices, drops that attempt (it never leaves a question
  on screen at night or in standby) and shows the question the next time it starts.
- The shell user, the one `adb` works as, may install apps without asking, on every version. Shizuku
  runs FluxTV's existing helper (the one that starts FluxTV after a force-stop) as that user, so
  where Shizuku runs, the helper installs the update and starts FluxTV again.
- Looked at and not used: making FluxTV the device owner (silent installs, but only on a device set
  up without any account, by `adb shell dpm set-device-owner`, and it locks the TV down); a system
  app (only for the TV's maker); `su` on a rooted box (a root manager asks on screen and can say no).
  On a rooted box, Shizuku can start itself with root at every boot, which gives the helper with
  nothing more to do.

**Setting a TV up, once.** By remote: **Settings → Updates** says how updates install there and, if
something is missing, offers the button that opens Android's screen for it (*Allow FluxTV to install
updates*, *Allow FluxTV to use Shizuku*). By adb, which also works where a TV's settings do not have
those screens:

```bash
adb shell appops set com.fluxtv.app REQUEST_INSTALL_PACKAGES allow
```

An update installed while FluxTV is on screen closes it, and Android 10 and newer let FluxTV start
itself again only with *Display over other apps*, the permission starting with the TV already needs
([Start when the TV turns on](#start-when-the-tv-turns-on)); the Shizuku helper does not need it.
Without it, updates are installed only while FluxTV is off screen, never on screen:

```bash
adb shell appops set com.fluxtv.app SYSTEM_ALERT_WINDOW allow
```

For example, a **Raspberry Pi 5 with LineageOS 23 (Android 16)** and no Shizuku: updates install
silently by Android. Give FluxTV both permissions above once (the two `appops` commands, as
LineageOS's TV settings may not show those screens), and **Settings → Updates** reads *Silently: this
Android version installs FluxTV's own updates without asking anyone*.

**The first version with updates is installed by hand**, with adb as before: a FluxTV from before
cannot update itself. Earlier builds were all build 1, so this one, built on the same machine (the
same key), installs over them as an update and keeps their settings. From then on each TV updates
itself.

### Publishing a release

Every FluxTV looks at the newest release of this repository, so publishing one is all it takes:

1. **Once:** add the repository secrets `FLUXTV_KEYSTORE_BASE64` (the release keystore,
   base64-encoded) and `FLUXTV_KEYSTORE_PASSWORD` (Settings → Secrets and variables → Actions). The
   key alias defaults to `fluxtv-release`; `FLUXTV_KEY_ALIAS` and `FLUXTV_KEY_PASSWORD` override it.
   The workflow signs with that key and the key lineage in `signing/fluxtv.lineage`
   (`tools/sign_release.sh`): APK Signature Scheme v3 key rotation, which lets a release update a
   FluxTV signed with any earlier key in the chain, the debug keys the TVs were first set up with,
   and keep its settings. It works from Android 9. Only the v3 signature is written (v1 and v2 cannot
   carry the lineage), so Android 7 and 8 cannot install a release, and its `update.json` says
   Android 9 (`minSdk` 28) so FluxTV there does not try. Android installs an update only when its key is the same or one the lineage
   proves it took over from, so the workflow stops without the keystore, and checks that the APK
   carries the v3 signature. Optionally, the repository variable `FLUXTV_CERT_SHA256` (the
   certificate digest `apksigner verify --print-certs` prints) makes it check the exact certificate
   too. Keep that keystore and its password safe and backed up: losing either means TVs can no
   longer be updated without reinstalling FluxTV, and whoever has them can sign builds the TVs accept.
2. **Each release:** tag a commit and push the tag:

   ```bash
   git tag v1.2.0
   ```

   ```bash
   git push origin v1.2.0
   ```

   The *Release* workflow (`.github/workflows/release.yml`) runs the tests, builds and signs the
   release APK, and publishes a GitHub release `v1.2.0` with `FluxTV-1.2.0.apk` and its
   `update.json`. TVs find it within 6 hours, or at once with **Settings → Updates → Check for
   updates**. It can also be started from the Actions tab with a version name.

Without GitHub Actions, build and describe the release on the dev machine, then upload both files to
a GitHub release named `v1.2.0` (the asset names must stay `FluxTV-1.2.0.apk` and `update.json`):

```bash
./gradlew :app:assembleRelease -PversionName=1.2.0
```

```bash
cp app/build/outputs/apk/release/app-release.apk FluxTV-1.2.0.apk
```

```bash
python3 tools/make_update_manifest.py FluxTV-1.2.0.apk --metadata app/build/outputs/apk/release/output-metadata.json --url https://github.com/oaatiq/FluxTV/releases/download/v1.2.0/FluxTV-1.2.0.apk
```

Any web server the TVs reach works as well: put the APK and an `update.json` made without `--url`
(the APK is then found next to it) in one folder, and set that `update.json`'s address in
**Settings → Updates → Update address**.

### Limits

- A build that crashes at start cannot be taken back by itself: Android does not let an app go back
  to an older build without removing it, which would lose its data. Publishing a fixed build is the
  way out; the background job that installs it runs apart from the screen, so a build whose screen
  crashes still gets it.
- On Android 8 to 11 without Shizuku, someone has to answer Android's question (FluxTV asks it when
  it starts, at most once a day). Shizuku removes the question.
- Without *Display over other apps* (and without Shizuku), Android 10+ installs updates only while
  FluxTV is off screen, so a TV that is never put into standby nor switched off waits for the
  administrator's **Install now**.
- The download needs the release's address to be reachable from the TV; a private repository is
  not. Point the update address at a web server the TVs reach instead.

## Acceptance testing

**The Grandfather Test (§27).** Without any explanation, a first-time user should be able to: turn
on and see TV; go to the next/previous channel; find a channel; adjust volume; see the other
channels in the current category (Back); and keep watching after a brief network interruption.

**Banner, guide and clock.** With a playlist that names a guide (or a guide link set for it), change
channel about a minute after FluxTV starts: the banner shows Now and Next with their times, and the
clock and date show at the top right. Press Info (or choose the channel already playing in the list)
to see the banner again. Open the channel list: channels in the guide show their current programme
after the name; the others show the name alone. With the guide link set to an address that does
not answer, channels change as quickly as without a guide. **Settings → Playlists → (playlist)** says how many channels have Now and
Next.

**Text size.** Switch **Text size on TV** to Extra large, go back to TV, and open the channel list:
names, numbers and categories are all larger, and the list is wider.

**Even volume.** Change between a quiet channel and a loud one: after a few seconds both play at
about the same volume. **Settings → Diagnostics → Volume levelling** shows a positive gain on the
quiet one and a negative gain on the loud one.

**Multiple playlists.** Save two playlists, put the second in use in **Settings → Playlists**, then
restart FluxTV (or the TV). TV comes back on the second playlist's channels, on its last channel,
with no playlist prompt or choice anywhere outside Settings.

**Every streaming problem on screen.** While a channel plays, unplug the TV's network cable (or switch
off the Wi-Fi router): within a few seconds *No network connection* shows at the top right while the
picture plays on, then the dots and *No network connection.* in the middle once the buffer runs out,
then the *Reconnecting…* screen. Plug it back in: the channel comes back by itself and every notice
goes. Limit the TV's bandwidth below the channel's (a router's QoS setting): the dots show at each
stop, and *Slow connection* after the third within two minutes. Tune a channel whose picture the TV
cannot decode: *Sound only.* **Settings → Diagnostics → History** has a `[network]` line for each loss
and return, and a `[buffering]` line when the slow connection is shown.

**Settings with any remote.** With a remote that has no MENU key (or the TV's own remote through
HDMI-CEC), press OK, ◀, ◀: the hint under the list reads *OK: open Settings* and ⚙ is highlighted; OK
opens Settings (the PIN first, if one is set), and **Back to TV** returns to the channel. From ⚙, ▶
goes back to the categories, and ▲ ▼ stay on ⚙. Holding ◀ in the channels stops at the categories.
Then point the playlist at a link that does not load and restart FluxTV: *Can't load the channels*
shows **Open Settings**, and OK opens it.

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

**Updates.** Install a build numbered below the next release (for a test, `./gradlew
:app:assembleRelease -PversionCode=60`), publish a release (or serve one from the dev machine: run
`tools/make_update_manifest.py` on a higher-numbered APK without `--url`, then `python3 -m http.server`
in its folder, and set `http://<computer>:8000/update.json` as the update address), and open
**Settings → Updates**:

1. *A newer build is found:* **Check for updates** shows it as *Newest*.
2. *It downloads by itself:* the screen counts the download up, then says it is downloaded and
   checked; the history has `Found…`, `Downloading…` and `…downloaded and checked`. Cut the network
   during the download and bring it back: it goes on from where it stopped.
3. *It installs as silently as the TV allows:* **Settings → Updates** says how. Put the TV into
   standby with the remote: the history (or `adb logcat -s FluxTV:I`) shows `Installing…`, and the
   installation happens with nobody asked (Shizuku or Android 12+). Or switch the TV off at the
   plug and on: *Updating FluxTV…*, then FluxTV comes back by itself on its channel.
4. *Data is kept:* the playlists, favorites, channel numbers, text size and PIN are as before.
5. *A failed update leaves FluxTV working:* publish a build signed with another key: it is refused
   before installing (*not signed with this FluxTV's key*), never tried again by itself, and FluxTV
   plays on. An `update.json` with a wrong SHA-256: *damaged*, fetched again later.
6. *The new version is reported:* **Settings → Updates** and **Diagnostics** show the new version,
   and the history has `Updated from … to …`.

**Phone.** Install on a phone and open FluxTV: it opens on the phone layout, never the TV's. Upright,
tap channels in the list, swipe up and down on the picture, and press and hold a channel to make it a
favorite (the message says so, and Favorites lists it). Type part of a name in *Find a channel*. Turn
the phone sideways: the picture fills the screen and a tap shows the controls, whose **Channels**
opens the list. With the rotation lock on, the full-screen button still turns it sideways and back.
Unplug headphones while a channel plays: it pauses and offers Play. Lock and unlock the phone, and
restart it: FluxTV does not open by itself. On a TV, everything above is as before: the remote's
controls, starting when the TV turns on, and refresh-rate matching.

## Project structure

```
app/src/main/java/com/fluxtv/app/
├─ data/            M3U parsing, settings (DataStore), JSON cache, repository, programme guide (XMLTV)
├─ playback/        ExoPlayer setup, recovery, error mapping, network & frame-rate, volume levelling,
│                   diagnostics
├─ setup/           the phone page behind the QR code (a small web server, during setup only)
├─ ui/
│  ├─ easymode/     fullscreen player + overlays (channel list, number entry, recovery, banner, clock,
│  │                StreamNotices for every streaming problem); TouchEasyModeScreen is the phone and
│  │                tablet layout
│  ├─ admin/        PIN gate + playlists (QR code, saved list) / channels / preferences / diagnostics /
│  │                updates
│  ├─ components/   large-type, high-contrast, focusable TV widgets
│  └─ AppViewModel  the Easy Mode state machine (STARTING → WATCHING ⇄ CHANNEL LIST)
├─ update/          FluxTV's own updates: release description, download, checks, when and how to
│                   install (Shizuku helper or Android's installer), the background job
├─ work/            WorkManager periodic refresh
├─ DeviceKind       TV or phone/tablet, which decides the layout and whether FluxTV starts by itself
└─ MainActivity     central remote-key handling + fullscreen + frame-rate matching; orientation and
                    system bars on a phone
```

The launcher artwork in `app/src/main/res` — the TV home-screen tile (banner) and the app icon — is
built from `art/fluxtv-logo.webp` by `tools/make_launcher_art.py`. It shows only on the TV's home
screen and in app lists, never inside FluxTV.

## Non-goals (V1)

No VOD/series/movie library, DVR/recording, cloud accounts, social features, multiple profiles,
themes/skins, complex EPG grids, or Viewer-facing codec/resolution/buffer knobs. These must never
compromise Easy Mode.
