plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/** A Gradle property (`-Pname=…`, or gradle.properties) or else an environment variable, if either is set. */
fun setting(property: String, environment: String): String? =
    (providers.gradleProperty(property).orNull ?: providers.environmentVariable(environment).orNull)
        ?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The number of commits up to this one. Each commit on main adds one, so a later build always has a
 * larger version code than an earlier one, which is how a TV tells that a build is newer than its own
 * (Settings → Updates) and what Android requires of an update. Null where git cannot say (a source
 * archive, or a shallow clone, whose count is far too low: fetch the whole history to build a release).
 */
fun commitCount(): Int? = try {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().toIntOrNull()
} catch (_: Exception) {
    null
}

// -PversionCode / FLUXTV_VERSION_CODE come first, so a release can be numbered by hand if ever needed.
val fluxVersionCode: Int = setting("versionCode", "FLUXTV_VERSION_CODE")?.toIntOrNull() ?: commitCount() ?: 1
val fluxVersionName: String = setting("versionName", "FLUXTV_VERSION_NAME")
    ?: "${providers.gradleProperty("fluxtv.versionName").orNull ?: "1.0"}.$fluxVersionCode"

/**
 * Where FluxTV looks for a newer build: a small JSON file describing the latest release (see
 * tools/make_update_manifest.py). GitHub always serves the newest release's copy at this address.
 * Settings → Updates can point a TV elsewhere.
 */
val fluxUpdateUrl: String = setting("fluxtv.updateUrl", "FLUXTV_UPDATE_URL")
    ?: "https://github.com/oaatiq/FluxTV/releases/latest/download/update.json"

/**
 * The key releases are signed with. Android installs an update only when it is signed with the same
 * key as the FluxTV already on the TV, so every build meant to update TVs must use the key their
 * FluxTV was signed with: for TVs set up so far, the debug key of the machine that built it
 * (~/.android/debug.keystore, whose password and alias are the defaults below). Without a keystore
 * set, release builds keep using this machine's debug key, as before.
 */
val releaseKeystore: String? = setting("fluxtv.keystore", "FLUXTV_KEYSTORE")

/**
 * The Google Play edition: the `play` build type, `./gradlew :app:bundlePlay` (play/README.md).
 *
 * Google Play already lists another company's "FluxTV" under com.fluxtv.app, so this edition has a
 * name and package of its own, MotionTV, and installs beside a FluxTV rather than over it; the FluxTV
 * that TVs already run (GitHub releases) is not touched by it. It is FluxTV without what an app from
 * Google Play may not do or would have to justify to Google: updating itself (Google Play updates it),
 * the Shizuku helper, and the foreground service that listens for the TV to wake (special use). Its
 * sample playlist is a short list of openly licensed test streams. It targets the API level Google
 * Play requires of phone apps; the other builds keep `targetSdk` below.
 */
val playApplicationId = "io.github.oaatiq.motiontv"
val playAppName = "MotionTV"
val playTargetSdk = 36
val playSamplePlaylist = "https://raw.githubusercontent.com/oaatiq/FluxTV/main/play/demo-playlist.m3u"

/**
 * The key the Google Play edition is signed with for uploading to Google Play (its upload key; Google
 * signs what it delivers with a key of its own). Without one set, it is the release key above.
 */
val uploadKeystore: String? = setting("fluxtv.uploadKeystore", "FLUXTV_UPLOAD_KEYSTORE")

android {
    namespace = "com.fluxtv.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fluxtv.app"
        minSdk = 24
        // Android 12 and newer install an app's own update without asking only while it targets a
        // recent version (two below the Android on the TV: 34 is enough up to Android 16, Android
        // 17 needs 35): keep this up to date, or updates there ask for a confirmation again
        // (README → Updates).
        targetSdk = 34
        versionCode = fluxVersionCode
        versionName = fluxVersionName
        buildConfigField("String", "UPDATE_URL", "\"$fluxUpdateUrl\"")
        // What the Google Play edition (`play` below) does differently; these are FluxTV's.
        buildConfigField("String", "APP_NAME", "\"FluxTV\"")
        buildConfigField("boolean", "SELF_UPDATE", "true")
        buildConfigField("boolean", "WAKE_SERVICE", "true")
        buildConfigField("boolean", "SHIZUKU", "true")
        buildConfigField("String", "SAMPLE_PLAYLIST_URL", "\"https://iptv-org.github.io/iptv/index.m3u\"")
        buildConfigField("String", "SAMPLE_PLAYLIST_NAME", "\"IPTV-Org Sample\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = setting("fluxtv.keystorePassword", "FLUXTV_KEYSTORE_PASSWORD") ?: "android"
                keyAlias = setting("fluxtv.keyAlias", "FLUXTV_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = setting("fluxtv.keyPassword", "FLUXTV_KEY_PASSWORD") ?: storePassword
            }
        }
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = file(uploadKeystore)
                storePassword = setting("fluxtv.uploadKeystorePassword", "FLUXTV_UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = setting("fluxtv.uploadKeyAlias", "FLUXTV_UPLOAD_KEY_ALIAS") ?: "upload"
                keyPassword = setting("fluxtv.uploadKeyPassword", "FLUXTV_UPLOAD_KEY_PASSWORD") ?: storePassword
            }
        }
    }

    testOptions {
        // The update logic's tests run on the build machine; Android calls there return defaults.
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Sideloaded personal build: signed with the debug key so `assembleRelease` produces an APK
            // that updates an existing debug install in place (keeps settings). Release is about 40%
            // faster to draw than debug on the MiTV and is compiled ahead of time when installed.
            // A keystore set as above (FLUXTV_KEYSTORE) signs it instead; it must hold the same key.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // R8 shrinks and optimizes the release build; Compose expects it in a release build and is
            // slower without it. proguard-rules.pro keeps what Android or Shizuku create by name.
            // Test a release build on the TV after changing those rules (see README → Build).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // The Google Play edition (see playApplicationId above): a release build, whose own manifest
        // and resources are in src/play. Google Play refuses a bundle signed with a debug key, which
        // is what this falls back to when no key is set, for a build to try on a device.
        create("play") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.findByName("upload")
                ?: signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            buildConfigField("String", "APP_NAME", "\"$playAppName\"")
            buildConfigField("boolean", "SELF_UPDATE", "false")
            buildConfigField("boolean", "WAKE_SERVICE", "false")
            buildConfigField("boolean", "SHIZUKU", "false")
            buildConfigField("String", "SAMPLE_PLAYLIST_URL", "\"$playSamplePlaylist\"")
            buildConfigField("String", "SAMPLE_PLAYLIST_NAME", "\"$playAppName demo\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.media3.common.util.UnstableApi")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

androidComponents {
    // The Google Play edition's own package and target API level (a build type cannot set either).
    beforeVariants(selector().withBuildType("play")) { variant ->
        variant.targetSdk = playTargetSdk
    }
    onVariants(selector().withBuildType("play")) { variant ->
        variant.applicationId.set(playApplicationId)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.foundation)
    implementation("androidx.compose.animation:animation")
    implementation(libs.androidx.tv.material)
    // Icons for the touch controls on phones and tablets. tv-material already brings this library in;
    // it is named here because FluxTV uses it directly.
    implementation(libs.androidx.material.icons.core)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    // DASH (.mpd) channels are common in public playlists, the sample one included; without this
    // module Media3 cannot open them at all.
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource.okhttp)
    // Sound in AC-3, E-AC-3, MP2 or DTS on a TV with no decoder of its own for it (picture, no
    // sound). Adds about 1.4 MB of native code per processor type to the APK.
    implementation(libs.jellyfin.media3.ffmpeg.decoder)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    // Draws the QR code a phone scans to send a playlist to the TV (Settings → Playlists).
    implementation(libs.zxing.core)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
