# MotionTV: FluxTV on Google Play

FluxTV is published on Google Play as **MotionTV**, package `io.github.oaatiq.motiontv`. Google Play
already lists another company's IPTV app called "FluxTV", under the package FluxTV uses
(`com.fluxtv.app`), so Play would refuse that package, and the same name on the same kind of app
risks rejection as impersonation.

MotionTV is a separate app. It installs **beside** a FluxTV, never over it. The FluxTV that TVs
already run keeps its name, package, key and settings, and keeps updating from GitHub releases;
nothing here changes those builds.

This folder holds everything the Play listing needs:

| Path | What |
|---|---|
| `listing/en-US/` | Title, short and full description (the layout `fastlane supply` reads) |
| `graphics/` | Icon, feature graphic, Android TV banner and phone screenshots |
| `PRIVACY.md` | The privacy policy Play asks for (fill in the two placeholders first) |
| `demo-playlist.m3u` | Openly licensed films and test streams, for reviewers and "Use sample test playlist" |
| `review/fgs-media-playback-demo.mp4` | The video Play asks for with the foreground service declaration |

`tools/make_play_art.py` rebuilds the banner and graphics.

## What the Play edition leaves out

It is the `play` build type in `app/build.gradle.kts`, with its own manifest and resources in
`app/src/play`. Compared with FluxTV:

| | FluxTV (GitHub) | MotionTV (Google Play) |
|---|---|---|
| Name, package | FluxTV, `com.fluxtv.app` | MotionTV, `io.github.oaatiq.motiontv` |
| Updates | Updates itself from GitHub releases | Google Play updates it. Play's policy allows an app from Play no other way, so the updater and `REQUEST_INSTALL_PACKAGES` are out |
| Starting when the TV wakes | `StandbyWatcher`, plus `WakeListenerService` (a *special use* foreground service) on Android 14+ | `StandbyWatcher` only. Play accepts a special-use service case by case, and only for tasks that last as long as needed. Start at boot works the same |
| Shizuku helper | Yes | No |
| Sample playlist | iptv-org's index | `demo-playlist.m3u` (iptv-org lists channels whose rights Play reviewers cannot check) |
| `targetSdk` | 34 | 36, which Play has required of phone apps since 31 August 2026 |

The code reads these as `BuildConfig.APP_NAME`, `SELF_UPDATE`, `WAKE_SERVICE`, `SHIZUKU`,
`SAMPLE_PLAYLIST_URL` and `SAMPLE_PLAYLIST_NAME`. Every name a person reads comes from `AppName`.

Checked on the Android 17 emulator with 16 KB memory pages, at target 36: a playlist added, live HLS
and DASH and the films play, Back steps through Settings and out, Settings → Updates says Google Play
updates it, and "Keep playing with the screen off" starts its media service with the screen off.
The native libraries (FFmpeg, graphics-path, DataStore) are 16 KB aligned, as Play requires.

## Building the bundle to upload

Play takes an Android App Bundle signed with your **upload key**. Google then signs the APKs it
delivers with an app signing key of its own (Play App Signing).

The upload key is the release key FluxTV releases are signed with, unless `FLUXTV_UPLOAD_KEYSTORE`
names another one. Reusing it means there is no new key to back up; keep that keystore and its
password as safe as you already do. If an upload key is ever lost, Play support can reset it, which
is not possible for the release key's own job of updating TVs.

```powershell
$env:FLUXTV_KEYSTORE = "C:\path\to\fluxtv-release.p12"
$env:FLUXTV_KEYSTORE_PASSWORD = Get-Content "C:\path\to\password.txt"
$env:FLUXTV_KEY_ALIAS = "fluxtv-release"
.\gradlew.bat :app:bundlePlay
```

The bundle is `app/build/outputs/bundle/play/app-play.aab`. Without a key set, Gradle signs it with
the debug key, which is fine for trying it on a device but which Play refuses.

`./gradlew :app:assemblePlay` builds an APK of the same edition to install with `adb install`.

**Version numbers** work as for FluxTV: the build number is the number of commits, so build from a
commit on `main`, and each upload must have a higher build number than the last.

## Publishing, step by step

Play Console moves its pages around from time to time; the names below are those of late 2026.

### 1. Developer account

Sign up at [play.google.com/console](https://play.google.com/console): a one-time US$25 fee and an
identity check. An **organization** account also needs a D-U-N-S number.

A **personal** account created after 13 November 2023 must run a **closed test with at least 12
testers, opted in for 14 days in a row**, before it may publish to production
([Google's rule](https://support.google.com/googleplay/android-developer/answer/14151465)). Family
and friends with a Google account and an Android phone or TV are enough; they need to stay opted in
and actually open the app now and then.

### 2. Create the app

**Home → Create app**: name *MotionTV*, default language English (United States), *App*, *Free*,
and accept the declarations.

### 3. A first release on a testing track

**Test and release → Testing → Internal testing** (or *Closed testing* for the 12-tester rule) →
create a track, add testers by email or a Google Group, then **Create release**:

- **App signing**: choose *Use Google-generated key*. MotionTV is a new app with a package of its
  own, so nothing depends on its key.
- Upload `app-play.aab`. The key it is signed with becomes the app's upload key.
- Release notes, for example: *First release of MotionTV.*

Testers get a link to opt in and install it from Play, on phones and on Android TV.

### 4. Store listing

**Grow users → Store presence → Main store listing**:

- **App name, short and full description**: copy them from `listing/en-US/`.
- **App icon**: `graphics/icon-512.png`. **Feature graphic**: `graphics/feature-graphic.png`.
- **Phone screenshots**: the four `graphics/phone-*` files.
- **Android TV**: banner `graphics/tv-banner-1280x720.png`, and the three `graphics/tv-*`
  screenshots (1920 × 1080).

The screenshots show the demo playlist. A TV box's own screenshot leaves the video black (on the
MiniX, an Amlogic box, the picture is a hardware layer that `screencap` does not see), so
`tv-2-settings.png` is from the MiniX and the two with a picture are from an emulator with no
touchscreen at 1920 × 1080, where MotionTV takes its TV layout (`DeviceKind`) and the picture is
drawn by the GPU: an AVD with `hw.screen=no-touch`, `hw.lcd.width=1920`, `hw.lcd.height=1080`,
`hw.lcd.density=320`, `hw.dPad=yes`. Phone screenshots: the Pixel AVD with `adb shell wm size
1080x1920`, since Play takes no screenshot longer than twice its width.

**Store settings**: category *Video Players & Editors*, a contact email (shown publicly).

### 5. App content

**Monitor and improve → Policy and programs → App content**. Answers that match the app:

- **Privacy policy**: fill in `PRIVACY.md`'s developer name and contact email, merge it to `main`,
  and give its address: `https://github.com/oaatiq/FluxTV/blob/main/play/PRIVACY.md`.
- **Ads**: no ads.
- **App access**: *All or some functionality is restricted* → add an instruction named *Demo
  playlist*, no user name or password, with: *MotionTV plays the user's own M3U playlist and ships
  no channels. On first start it opens Settings → Playlists: choose Add a playlist link → Use sample
  test playlist → Check and add, then Back. The demo playlist
  (https://raw.githubusercontent.com/oaatiq/FluxTV/main/play/demo-playlist.m3u) holds openly licensed
  films and public test streams.* An IPTV player that shows reviewers nothing to play is often turned
  down for "minimum functionality", which is why this matters.
- **Content rating**: answer the questionnaire for an app that is not a game. MotionTV itself holds
  no violence, sexual content, bad language, drugs or gambling, lets users neither talk to each other
  nor share their location, sells nothing, and is not a web browser: *No* throughout.
- **Target audience**: *18 and over* only. That keeps it outside the Families policy, which fits an
  app playing whatever a provider's playlist holds.
- **Data safety**: *Does your app collect or share any of the required user data types?* **No.** The
  app sends nothing to you or to anyone else; it only fetches what the user's own playlist names
  (`PRIVACY.md`).
- **Advertising ID**: not used.
- **Government apps**: no. **Financial features**: none. **Health**: none. **News app**: no.
- **Foreground service permissions**: *Media playback*. Describe it as: *On phones and tablets, a
  user who turns on Settings → Preferences → "Keep playing with the screen off" keeps hearing the
  channel they watch after turning the screen off. The service runs only while that channel plays with
  the screen off, shows a media notification with a Stop button, and ends when the screen comes back
  on or the user stops it. Without it, the sound stops when the screen goes off.* For the video,
  upload `review/fgs-media-playback-demo.mp4` (YouTube *unlisted*, or Google Drive shared by link)
  and paste its link. It shows the setting being turned on, the channel playing, then the screen
  going off and coming back.

### 6. Android TV

**Test and release → Advanced settings → Form factors → Add form factor → Android TV**, then opt in
on the track you release to. Google reviews TV apps separately against the
[TV app quality guidelines](https://developer.android.com/docs/quality-guidelines/tv-app-quality):
remote-only navigation, a banner, Back leading out to the home screen, no touchscreen required.
MotionTV meets these; the TV review can take longer than the phone one.

### 7. Production

After the closed test (if your account needs it), **Dashboard → Apply for production**, then
**Test and release → Production → Countries / regions** and **Create release** with the same bundle
or a newer one. Reviews usually take a few days.

## Updating MotionTV later

Commit to `main`, build `:app:bundlePlay` again (the build number has gone up), upload it as a new
release on the track you want, and roll it out. Play updates MotionTV on users' devices by itself.

## Things to keep in mind

- **IPTV players are watched closely.** Keep the listing, screenshots and demo playlist free of TV
  channels, channel logos and provider names, and keep the "does not include any channels" line in
  the description. Players have been taken down after rights holders' complaints even when they
  shipped no content.
- **Starting by itself.** On a TV, MotionTV opens on the last channel when the TV is turned on, as
  FluxTV does, and Settings → Preferences turns that off. If the TV review objects, making it off by
  default in the Play edition is a one-line change (`startOnBoot` in `SettingsRepository`).
- **Developer verification for FluxTV.** Since 30 September 2026 in Brazil, Indonesia, Singapore and
  Thailand, and everywhere in 2027, certified Android devices install apps only from developers who
  registered them ([Android Developers Blog](https://developer.android.com/blog/posts/android-developer-verification-rolling-out-to-all-developers-on-play-console-and-android-developer-console)).
  The same Play Console account can register `com.fluxtv.app` with its release key, so GitHub
  releases keep installing, and updating themselves, once that reaches your country.
