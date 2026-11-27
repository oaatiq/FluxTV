package com.fluxtv.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.IntentCompat
import com.fluxtv.app.AppName
import com.fluxtv.app.BuildConfig
import com.fluxtv.app.ShizukuWake
import com.fluxtv.app.canStartItself
import com.fluxtv.app.data.cache.DiagnosticsStore
import com.fluxtv.app.data.model.DiagnosticsEvent
import com.fluxtv.app.data.settings.FluxSettings
import com.fluxtv.app.data.settings.SettingsRepository
import com.fluxtv.app.isFluxTvOnScreen
import com.fluxtv.app.launchFluxTv
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class UpdatePhase {
    /** Not looked yet. */
    IDLE,
    CHECKING,
    UP_TO_DATE,

    /** A newer build exists and is not downloaded (yet). */
    AVAILABLE,
    DOWNLOADING,

    /** Downloaded and checked: installs at the next good moment (see [UpdatePolicy]). */
    READY,
    INSTALLING
}

/** What Settings → Updates shows, and what tells the screen to show "Updating FluxTV…". */
data class UpdateStatus(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val latest: UpdateInfo? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val lastCheckMs: Long = 0,
    /** The last thing that went wrong, in words, until it works. */
    val problem: String? = null,
    val nextTryMs: Long = 0,
    /** [latest] is a build Android will never install over this FluxTV; only Install now tries it. */
    val blocked: Boolean = false,
    /** An installation is under way while FluxTV is on screen: "Updating FluxTV…" covers the screen. */
    val updatingOnScreen: Boolean = false
)

/**
 * FluxTV's automatic updates: looks for a newer build, downloads and checks it in the background,
 * and installs it at a moment nobody is watching, as silently as the TV allows.
 *
 * **Looking.** A minute and a half after FluxTV starts, and every 6 hours from a background job
 * ([UpdateWorker]), FluxTV reads the release's `update.json` ([UpdateInfo]) and compares its build
 * number with its own.
 *
 * **Downloading.** A newer build is downloaded at once, in the background, slowly while a channel
 * plays so that the picture keeps its bandwidth ([UpdateDownloader]), and checked: size, SHA-256,
 * that it is FluxTV, newer, and signed with this FluxTV's key ([ApkVerifier]). It is checked again
 * just before it is installed.
 *
 * **Installing**, by the best way this TV has ([InstallRoute]):
 * 1. the Shizuku helper, silently on any Android version ([ShizukuWake.install]);
 * 2. Android's installer, silently on Android 12 and newer ([SessionInstall]);
 * 3. Android's installer with its question on screen, on Android 7 to 11.
 * When ([UpdatePolicy]): as soon as FluxTV is off screen (standby, another app), or when it starts,
 * behind "Updating FluxTV…", or in the night on a TV that never leaves FluxTV. If FluxTV was on
 * screen, it comes back by itself on the new version ([afterPackageReplaced]).
 *
 * **Keeping FluxTV working.** Android replaces an app in one step or not at all, and keeps its data:
 * a failed installation leaves the FluxTV that was there, with everything set in it. A failure is
 * tried again later, at growing intervals ([UpdatePolicy.retryDelayMs]); one that cannot ever work
 * (a build signed with another key) is not tried again by itself. Each step is written to the
 * Diagnostics history under `[update]`, and the new version records the update when it first runs.
 *
 * **The Google Play edition** (`BuildConfig.SELF_UPDATE` false, play/README.md) never looks, downloads
 * or installs: Google Play updates it. Only the record of a new version is kept there.
 */
class UpdateManager(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val diagnosticsStore: DiagnosticsStore,
    private val httpClient: () -> OkHttpClient,
    private val television: Boolean,
    /** The player's estimate of the line's speed, in bits per second; 0 when it has none. */
    private val lineBitsPerSecond: () -> Long,
    /** A phone plays a channel with its screen off: FluxTV is in use though nothing is on screen. */
    private val playingWithScreenOff: () -> Boolean = { false }
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = File(appContext.noBackupFilesDir, "updates")
    private val store = UpdateStore(dir)

    /** One look, download or installation at a time. */
    private val work = Mutex()
    private val recordLock = Any()
    private val userAgent = "FluxTV/${BuildConfig.VERSION_NAME} (updates)"
    private val downloader = UpdateDownloader(httpClient, userAgent)

    val installedCode: Long = BuildConfig.VERSION_CODE.toLong()
    val installedName: String = BuildConfig.VERSION_NAME
    val installedLabel: String get() = versionLabel(installedName, installedCode)

    /** On a TV: the Shizuku helper, set once the application has it. */
    @Volatile
    var helper: ShizukuWake? = null

    /** The helper answered that it cannot install (one started by an older FluxTV). */
    @Volatile
    private var helperCannotInstall = false

    @Volatile
    private var current = UpdateRecord()

    private class Activity(val phase: UpdatePhase, val done: Long = 0, val total: Long = 0, val onScreen: Boolean = false)

    @Volatile
    private var activity: Activity? = null

    private val _status = MutableStateFlow(UpdateStatus())
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    /** The update this process is the result of, while FluxTV may still have to come back on screen. */
    @Volatile
    private var justUpdated: InstallAttempt? = null

    private class PendingSession(val versionCode: Long, val mayAsk: Boolean) {
        val outcome = CompletableDeferred<SessionOutcome>()
        @Volatile
        var abandoned = false
    }

    private sealed class SessionOutcome {
        data object Success : SessionOutcome()
        data object Asking : SessionOutcome()
        data object NeedsUser : SessionOutcome()
        data class Failed(val message: String?, val status: Int) : SessionOutcome()
    }

    @Volatile
    private var pendingSession: PendingSession? = null

    private var startCheckScheduled = false
    private var tickJob: Job? = null
    private var leftScreenJob: Job? = null

    @Volatile
    private var rateCheckedMs = 0L

    @Volatile
    private var rate = Long.MAX_VALUE

    /** Reading the saved state, and noticing a new version, before anything else is done. */
    private val loaded = scope.async {
        dir.mkdirs()
        noteVersion()
    }

    // ---------- what the rest of FluxTV calls ----------

    /**
     * FluxTV is starting on screen, before its first channel. An update downloaded earlier is
     * installed now if this TV can do it without a question and bring FluxTV back afterwards, behind
     * "Updating FluxTV…"; where Android asks first, its question is shown (at most once a day).
     * Returns at once when there is nothing to do, or once an installation has failed, so that TV
     * starts as usual; a successful one closes FluxTV before returning. Its work (checking the
     * file, waiting for Android) is done off the caller's thread, which may be the main one.
     */
    suspend fun installAtStart(): Unit = withContext(Dispatchers.IO) {
        if (!BuildConfig.SELF_UPDATE) return@withContext
        loaded.await()
        if (current.readyVersionCode == 0L) return@withContext
        // Never hold up the start behind a download or a look that is under way.
        if (!work.tryLock()) return@withContext
        try {
            maybeInstall(InstallTrigger.START, assumeOnScreen = true)
        } finally {
            work.unlock()
        }
    }

    /** Looks for an update a little after FluxTV starts, once its first channel has had its turn. */
    fun onAppStarted() {
        if (!BuildConfig.SELF_UPDATE || startCheckScheduled) return
        startCheckScheduled = true
        scope.launch {
            delay(STARTUP_CHECK_DELAY_MS)
            runAutomatic()
        }
    }

    /** The periodic background job, and the look after the start. */
    suspend fun runAutomatic(): Unit = withContext(Dispatchers.IO) {
        if (!BuildConfig.SELF_UPDATE) return@withContext
        loaded.await()
        work.withLock {
            lookAndFetch(manual = false)
            maybeInstall(InstallTrigger.CHECK)
        }
    }

    /**
     * FluxTV has left the screen (standby, Home, another app): the moment to install an update that
     * waited. A short pause first makes sure it really left; the TV is kept awake meanwhile, as a TV
     * going into standby could otherwise be suspended before the installation is done.
     */
    fun onLeftScreen() {
        if (!BuildConfig.SELF_UPDATE || current.readyVersionCode == 0L) return
        leftScreenJob?.cancel()
        leftScreenJob = scope.launch {
            keepingAwake(LEFT_SCREEN_SETTLE_MS + INSTALL_WAKE_MS) {
                delay(LEFT_SCREEN_SETTLE_MS)
                if (onScreen()) return@keepingAwake
                work.withLock { maybeInstall(InstallTrigger.LEFT_SCREEN) }
            }
        }
    }

    /** Settings → Updates → Check for updates: looks now, and downloads what it finds. */
    fun checkNow() {
        if (!BuildConfig.SELF_UPDATE) return
        scope.launch {
            loaded.await()
            work.withLock {
                lookAndFetch(manual = true)
                maybeInstall(InstallTrigger.CHECK)
            }
        }
    }

    /** Settings → Updates → Install now: whatever the hour, and even a build that failed before. */
    fun installNow() {
        if (!BuildConfig.SELF_UPDATE) return
        scope.launch {
            loaded.await()
            work.withLock {
                val latest = current.latest
                if (latest == null || current.readyVersionCode != latest.versionCode) lookAndFetch(manual = true)
                maybeInstall(InstallTrigger.MANUAL)
            }
        }
    }

    /** How this TV can install updates now (it changes with Shizuku and the administrator's permissions). */
    fun route(): InstallRoute {
        if (helper?.state?.value == ShizukuWake.State.ACTIVE && !helperCannotInstall) return InstallRoute.HELPER
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !appContext.packageManager.canRequestPackageInstalls()) {
            return InstallRoute.NOT_ALLOWED
        }
        if (silentInstallerPossible() && !current.androidAsks) return InstallRoute.SILENT
        return InstallRoute.CONFIRM
    }

    /**
     * Whether FluxTV, closed by its own update while on screen, can start itself again: the helper
     * does it as the shell user; otherwise FluxTV starts itself, which Android 10+ allows only with
     * "Display over other apps" (the same permission starting with the TV needs).
     */
    fun canRelaunch(route: InstallRoute = route()): Boolean =
        route == InstallRoute.HELPER || canStartItself(appContext)

    // ---------- Android's answers ----------

    /**
     * The first moment of a new version (MY_PACKAGE_REPLACED). The update itself is recorded when
     * the process starts ([noteVersion]); here FluxTV comes back on screen if it was there when the
     * update began, unless the helper has started it already.
     */
    suspend fun afterPackageReplaced(): Unit = withContext(Dispatchers.IO) {
        loaded.await()
        val attempt = justUpdated ?: return@withContext
        justUpdated = null
        if (!attempt.relaunch) return@withContext
        delay(RELAUNCH_CHECK_MS)
        if (isFluxTvOnScreen()) return@withContext
        launchFluxTv(appContext, fromBoot = false)
        record(
            if (canStartItself(appContext)) "Starting ${AppName} again on the new version"
            else "Starting ${AppName} again on the new version (without \"Display over other apps\", " +
                "this Android version may refuse it)"
        )
    }

    /** How an installation through Android's installer went ([SessionInstall]). */
    suspend fun onInstallStatus(intent: Intent): Unit = withContext(Dispatchers.IO) {
        loaded.await()
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val versionCode = intent.getLongExtra(SessionInstall.EXTRA_VERSION_CODE, 0L)
        val pending = pendingSession?.takeIf { it.versionCode == versionCode }

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                // At the start, Android can answer before FluxTV is fully on screen: it gets a moment.
                if (pending?.mayAsk == true && confirm != null && awaitOnScreen(ON_SCREEN_WAIT_MS)) {
                    try {
                        appContext.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        mutate { r ->
                            r.copy(
                                lastAskedMs = System.currentTimeMillis(),
                                installing = r.installing?.copy(route = InstallRoute.CONFIRM)
                            )
                        }
                        record("Android asks on screen before installing the update")
                        pending.outcome.complete(SessionOutcome.Asking)
                    } catch (e: Exception) {
                        SessionInstall.abandon(appContext, sessionId)
                        pending.outcome.complete(SessionOutcome.Failed("its question could not be shown: ${e.javaClass.simpleName}", status))
                    }
                } else {
                    // Nobody to answer it now: dropped, and asked when FluxTV next starts on screen.
                    pending?.abandoned = true
                    SessionInstall.abandon(appContext, sessionId)
                    if (pending != null) {
                        pending.outcome.complete(SessionOutcome.NeedsUser)
                    } else {
                        mutate { it.copy(androidAsks = true, installing = null) }
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Normally never seen: Android closes FluxTV to replace it. The new version records it.
                pending?.outcome?.complete(SessionOutcome.Success)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                if (pending?.abandoned == true) return@withContext
                pending?.outcome?.complete(SessionOutcome.Failed(message, status))
                mutate {
                    it.copy(
                        installing = null,
                        problem = "The installation was cancelled on screen; Android asks again when ${AppName} " +
                            "next starts (at most once a day)"
                    )
                }
                record("The installation was cancelled on screen")
                activity = null
                publish()
            }
            else -> {
                if (pending != null && !pending.outcome.isCompleted) {
                    pending.outcome.complete(SessionOutcome.Failed(message, status))
                } else if (current.installing != null) {
                    // An answer that came after FluxTV stopped waiting, or to a FluxTV started since.
                    mutate { failed(it.copy(installing = null), UpdatePolicy.describeFailure(message), UpdatePolicy.isPermanent(message, status)) }
                }
            }
        }
    }

    // ---------- looking and downloading ----------

    private suspend fun settings(): FluxSettings =
        withTimeoutOrNull(SETTINGS_TIMEOUT_MS) { settingsRepository.settings.first() } ?: FluxSettings()

    /** Looks for a newer build and, unless it waits for something, downloads it. */
    private suspend fun lookAndFetch(manual: Boolean) {
        val s = settings()
        look(manual, s)
        val info = current.latest ?: return
        if (current.readyVersionCode == info.versionCode && apkFile(info).exists()) return
        if (!manual) {
            if (!s.autoUpdate) return
            if (current.blockedVersionCode == info.versionCode) return
            if (System.currentTimeMillis() < current.nextTryMs) return
            // A phone on mobile data waits for Wi-Fi; a TV's channels use far more than an update.
            if (!television && isMetered()) {
                mutate { it.copy(problem = "Waiting for Wi-Fi to download the update") }
                return
            }
        }
        download(info)
    }

    private suspend fun look(manual: Boolean, s: FluxSettings) {
        val now = System.currentTimeMillis()
        if (!manual && now - current.lastCheckMs < MIN_CHECK_GAP_MS) return
        val url = s.updateUrl.ifBlank { BuildConfig.UPDATE_URL }.trim()
        if (url.isEmpty()) {
            mutate { it.copy(lastCheckMs = now, checkProblem = "No update address is set") }
            return
        }
        setActivity(Activity(UpdatePhase.CHECKING))
        val found = try {
            fetchManifest(url)
        } catch (e: ManifestException) {
            lookFailed(e.message ?: "The update description could not be read")
            return
        } catch (e: IOException) {
            lookFailed("The update address could not be reached (${e.message ?: e.javaClass.simpleName})")
            return
        } finally {
            setActivity(null)
        }
        if (found == null) {
            lookFailed("No release has been published at the update address yet")
            return
        }

        if (found.versionCode <= installedCode) {
            mutate { it.copy(lastCheckMs = now, checkProblem = null, latest = null, readyVersionCode = 0, problem = null) }
            removeFiles(keep = null)
            return
        }
        val isNew = current.latest?.versionCode != found.versionCode
        mutate {
            val r = it.copy(lastCheckMs = now, checkProblem = null, latest = found)
            if (!isNew) r
            else r.copy(readyVersionCode = 0, readySinceMs = 0, failures = 0, nextTryMs = 0, problem = null, androidAsks = false)
        }
        if (isNew) {
            removeFiles(keep = found.versionCode)
            record("Found ${AppName} ${found.label} (this is ${installedLabel})")
        }
        if (found.minSdk > Build.VERSION.SDK_INT && current.blockedVersionCode != found.versionCode) {
            mutate {
                it.copy(
                    blockedVersionCode = found.versionCode,
                    problem = "${AppName} ${found.label} needs a newer Android version than this TV has"
                )
            }
            record("${AppName} ${found.label} needs Android API ${found.minSdk}; this TV has ${Build.VERSION.SDK_INT}")
        }
    }

    private fun lookFailed(reason: String) {
        val before = current.checkProblem
        mutate { it.copy(lastCheckMs = System.currentTimeMillis(), checkProblem = reason) }
        // The same failure every 6 hours would bury the history.
        if (reason != before) record("Looking for updates failed: $reason")
    }

    /** The newest build the release describes, or null when there is no release at all (HTTP 404). */
    private fun fetchManifest(url: String): UpdateInfo? {
        val request = try {
            Request.Builder().url(url).header("User-Agent", userAgent).header("Cache-Control", "no-cache").build()
        } catch (_: IllegalArgumentException) {
            throw ManifestException("The update address is not a web address")
        }
        val client = httpClient().newBuilder().callTimeout(60, TimeUnit.SECONDS).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw ManifestException("The update address answered HTTP ${response.code}")
            val source = response.body?.source() ?: throw ManifestException("The update address sent nothing")
            if (source.request(MAX_MANIFEST_BYTES + 1)) throw ManifestException("The update description is far too large")
            return UpdateManifest.parse(source.buffer.readUtf8(), url)
        }
    }

    private suspend fun download(info: UpdateInfo) {
        setActivity(Activity(UpdatePhase.DOWNLOADING, 0, info.size))
        val file = apkFile(info)
        if (!File(file.path + ".part").exists()) record("Downloading ${AppName} ${info.label}")
        var shownMs = 0L
        val result = try {
            downloader.download(info, file, ::allowedBytesPerSecond) { done, total ->
                val now = SystemClock.elapsedRealtime()
                if (now - shownMs >= PROGRESS_EVERY_MS) {
                    shownMs = now
                    setActivity(Activity(UpdatePhase.DOWNLOADING, done, total))
                }
            }
        } finally {
            setActivity(null)
        }
        when (result) {
            is DownloadResult.Failed -> mutate { failed(it, result.reason, permanent = false) }
            DownloadResult.Done -> {
                val rejected = ApkVerifier.check(appContext, file, info)
                if (rejected != null) {
                    // The file is exactly what the release published, so fetching it again cannot help.
                    file.delete()
                    mutate { failed(it, rejected, permanent = true) }
                    return
                }
                mutate {
                    it.copy(
                        readyVersionCode = info.versionCode,
                        readySinceMs = System.currentTimeMillis(),
                        problem = null
                    )
                }
                record("${AppName} ${info.label} downloaded and checked; it installs ${whenText(route())}")
            }
        }
    }

    // ---------- installing ----------

    private suspend fun maybeInstall(trigger: InstallTrigger, assumeOnScreen: Boolean = false) {
        val r = current
        val info = r.latest ?: return
        if (r.readyVersionCode != info.versionCode) return
        val file = apkFile(info)
        if (!file.exists()) {
            mutate { it.copy(readyVersionCode = 0) }
            return
        }
        val now = System.currentTimeMillis()
        if (trigger != InstallTrigger.MANUAL) {
            if (!settings().autoUpdate) return
            if (r.blockedVersionCode == info.versionCode || now < r.nextTryMs) return
        }
        // Someone is listening with the phone's screen off: installing would close FluxTV and cut the
        // sound. The listening's end counts as leaving the screen (onLeftScreen), and lets it in.
        if (trigger != InstallTrigger.MANUAL && playingWithScreenOff()) return
        // The helper is started anew with FluxTV's process: give it a moment to be there.
        helper?.awaitHelper(HELPER_WAIT_MS)

        val route = route()
        val onScreen = assumeOnScreen || onScreen()
        val action = UpdatePolicy.decide(
            InstallMoment(
                route = route,
                trigger = trigger,
                onScreen = onScreen,
                canRelaunch = canRelaunch(route),
                television = television,
                waitedMs = now - r.readySinceMs,
                sinceLastAskMs = if (r.lastAskedMs == 0L) Long.MAX_VALUE else now - r.lastAskedMs,
                localHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            )
        )
        // Android may unexpectedly ask even where it should not; its question is shown only to someone
        // who is surely in front of the TV (it is starting, or the administrator asked), never at night.
        val someoneThere = trigger == InstallTrigger.START || trigger == InstallTrigger.MANUAL
        when (action) {
            InstallAction.WAIT -> if (route == InstallRoute.HELPER || route == InstallRoute.SILENT) keepLooking()
            InstallAction.INSTALL_QUIETLY -> install(info, file, route, onScreen = false, mayAsk = false)
            InstallAction.INSTALL_ON_SCREEN -> install(info, file, route, onScreen = true, mayAsk = someoneThere)
            InstallAction.ASK -> install(info, file, route, onScreen = true, mayAsk = true, ask = true)
        }
    }

    /**
     * Installs [file]. [onScreen]: FluxTV is on screen, so it shows "Updating FluxTV…" (unless
     * Android's question is expected instead, [ask]) and comes back on the new version. [mayAsk]:
     * if Android wants to ask, its question may be shown now.
     */
    private suspend fun install(
        info: UpdateInfo,
        file: File,
        route: InstallRoute,
        onScreen: Boolean,
        mayAsk: Boolean,
        ask: Boolean = false
    ) {
        // Checked again: the file has waited on the TV since it was downloaded.
        ApkVerifier.check(appContext, file, info)?.let { reason ->
            file.delete()
            mutate { failed(it.copy(readyVersionCode = 0), reason, permanent = false) }
            return
        }
        val attempt = InstallAttempt(
            versionCode = info.versionCode,
            versionName = info.versionName,
            fromVersionCode = installedCode,
            fromVersionName = installedName,
            route = route,
            relaunch = onScreen,
            startedMs = System.currentTimeMillis()
        )
        // On disk before Android may close FluxTV: the new version reads it.
        mutate { it.copy(installing = attempt) }
        val covering = onScreen && !ask
        setActivity(Activity(UpdatePhase.INSTALLING, onScreen = covering))
        record("Installing ${AppName} ${info.label} ${describeRoute(route)}" + if (onScreen) " (${AppName} was on screen)" else "")
        try {
            keepingAwake(INSTALL_WAKE_MS) {
                // The picture gives way to "Updating FluxTV…" and the channel stops before FluxTV closes.
                if (covering) delay(COVER_SETTLE_MS)
                if (route != InstallRoute.HELPER || !installWithHelper(info, file, onScreen)) {
                    installWithSession(info, file, mayAsk)
                }
            }
        } finally {
            setActivity(null)
        }
    }

    /**
     * Has the Shizuku helper install it. False when the helper cannot (one started by an older
     * FluxTV) and Android's installer should be used instead; true when this is over either way.
     */
    private suspend fun installWithHelper(info: UpdateInfo, file: File, relaunch: Boolean): Boolean {
        when (val result = helper?.install(file, info.sha256, relaunch) ?: ShizukuWake.HelperInstall.Unavailable) {
            ShizukuWake.HelperInstall.Installed -> delay(AFTER_SUCCESS_WAIT_MS)
            ShizukuWake.HelperInstall.Unavailable -> {
                helperCannotInstall = true
                record("The Shizuku helper cannot install updates (it is an older one); Android's installer is used")
                // Straight on with Android's installer, if it needs nobody; otherwise at the next chance.
                if (route() == InstallRoute.SILENT) {
                    mutate { r -> r.copy(installing = r.installing?.copy(route = InstallRoute.SILENT)) }
                    return false
                }
                mutate { it.copy(installing = null) }
            }
            is ShizukuWake.HelperInstall.Failed -> mutate {
                failed(
                    it.copy(installing = null),
                    UpdatePolicy.describeFailure(result.message),
                    UpdatePolicy.isPermanent(result.message)
                )
            }
        }
        return true
    }

    private suspend fun installWithSession(info: UpdateInfo, file: File, mayAsk: Boolean) {
        val pending = PendingSession(info.versionCode, mayAsk)
        pendingSession = pending
        val sessionId = try {
            SessionInstall.commit(appContext, file, info.versionCode)
        } catch (e: Exception) {
            pendingSession = null
            mutate { failed(it.copy(installing = null), "Android's installer could not take the update (${e.javaClass.simpleName} ${e.message ?: ""})".trim(), permanent = false) }
            return
        }
        val outcome = withTimeoutOrNull(SESSION_ANSWER_MS) { pending.outcome.await() }
        pendingSession = null
        when (outcome) {
            null -> {
                SessionInstall.abandon(appContext, sessionId)
                mutate { failed(it.copy(installing = null), "Android did not answer about the installation", permanent = false) }
            }
            SessionOutcome.Success -> delay(AFTER_SUCCESS_WAIT_MS)
            // Android's question is on screen; its answer arrives later (onInstallStatus, or the new version).
            SessionOutcome.Asking -> Unit
            SessionOutcome.NeedsUser -> {
                mutate {
                    it.copy(
                        installing = null,
                        androidAsks = true,
                        problem = "Android wants to ask before installing on this TV; it asks when ${AppName} next starts"
                    )
                }
                record("Android wants to ask before installing the update; it will, when ${AppName} next starts on screen")
            }
            is SessionOutcome.Failed -> mutate {
                failed(
                    it.copy(installing = null),
                    UpdatePolicy.describeFailure(outcome.message),
                    UpdatePolicy.isPermanent(outcome.message, outcome.status)
                )
            }
        }
    }

    /** While an update waits for FluxTV to leave the screen, the night (or a long wait) may let it in. */
    private fun keepLooking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (true) {
                delay(TICK_MS)
                if (current.readyVersionCode == 0L) break
                if (work.tryLock()) {
                    try {
                        maybeInstall(InstallTrigger.TICK)
                    } finally {
                        work.unlock()
                    }
                }
            }
        }
    }

    // ---------- the record ----------

    /** Notices a new version at the process's start, however it was installed, and records it. */
    private fun noteVersion() {
        var r = store.read()
        if (r.lastRunVersionCode != installedCode) {
            val attempt = r.installing?.takeIf { it.versionCode == installedCode }
            record(
                when {
                    attempt != null ->
                        "Updated from ${versionLabel(attempt.fromVersionName, attempt.fromVersionCode)} to " +
                            "$installedLabel, ${describeRoute(attempt.route)}"
                    !BuildConfig.SELF_UPDATE && r.lastRunVersionCode == 0L ->
                        "${AppName} $installedLabel; Google Play keeps it up to date"
                    !BuildConfig.SELF_UPDATE ->
                        "${AppName} is now $installedLabel (it was build ${r.lastRunVersionCode}; updated by Google Play)"
                    r.lastRunVersionCode == 0L -> "${AppName} $installedLabel; it looks for updates by itself"
                    else -> "${AppName} is now $installedLabel (it was build ${r.lastRunVersionCode}; installed from outside ${AppName})"
                }
            )
            justUpdated = attempt
            val latest = r.latest?.takeIf { it.versionCode > installedCode }
            r = r.copy(
                lastRunVersionCode = installedCode,
                installing = null,
                latest = latest,
                readyVersionCode = r.readyVersionCode.takeIf { latest != null && it == latest.versionCode } ?: 0,
                failures = 0,
                nextTryMs = 0,
                problem = null,
                androidAsks = false,
                blockedVersionCode = r.blockedVersionCode.takeIf { it > installedCode } ?: 0
            )
            store.write(r)
            removeFiles(keep = latest?.versionCode)
        } else if (r.installing != null) {
            // An installation began and FluxTV is still the same: it did not happen.
            r = failed(r.copy(installing = null), "The last installation did not finish", permanent = false)
            store.write(r)
        }
        current = r
        publish()
    }

    private fun failed(r: UpdateRecord, reason: String, permanent: Boolean): UpdateRecord {
        val failures = r.failures + 1
        val next = System.currentTimeMillis() + UpdatePolicy.retryDelayMs(failures)
        record(
            reason + if (permanent) "; this build is not tried again by itself (Settings → Updates → Install now)"
            else "; trying again after ${timeText(next)}"
        )
        return r.copy(
            failures = failures,
            nextTryMs = next,
            problem = reason,
            blockedVersionCode = if (permanent) r.latest?.versionCode ?: r.blockedVersionCode else r.blockedVersionCode
        )
    }

    private fun mutate(change: (UpdateRecord) -> UpdateRecord) {
        synchronized(recordLock) {
            val updated = change(current)
            if (updated != current) {
                current = updated
                store.write(updated)
            }
        }
        publish()
    }

    private fun setActivity(value: Activity?) {
        activity = value
        publish()
    }

    private fun publish() {
        val r = current
        val a = activity
        val latest = r.latest
        val phase = a?.phase ?: when {
            latest == null -> if (r.lastCheckMs > 0 && r.checkProblem == null) UpdatePhase.UP_TO_DATE else UpdatePhase.IDLE
            r.readyVersionCode == latest.versionCode -> UpdatePhase.READY
            else -> UpdatePhase.AVAILABLE
        }
        _status.value = UpdateStatus(
            phase = phase,
            latest = latest,
            downloadedBytes = a?.done ?: 0,
            totalBytes = a?.total ?: 0,
            lastCheckMs = r.lastCheckMs,
            problem = r.problem ?: r.checkProblem,
            nextTryMs = r.nextTryMs,
            blocked = latest != null && r.blockedVersionCode == latest.versionCode,
            updatingOnScreen = a?.onScreen == true
        )
    }

    private fun record(message: String) {
        diagnosticsStore.append(DiagnosticsEvent(System.currentTimeMillis(), null, "update", message))
    }

    // ---------- the TV ----------

    private fun apkFile(info: UpdateInfo) = File(dir, "FluxTV-${info.versionCode}.apk")

    /** Deletes downloads other than build [keep]'s (finished or not), and anything left in the folder. */
    private fun removeFiles(keep: Long?) {
        val keepNames = keep?.let { setOf("FluxTV-$it.apk", "FluxTV-$it.apk.part") }.orEmpty()
        dir.listFiles()?.forEach { f ->
            if (f.name.startsWith("FluxTV-") && f.name !in keepNames) f.delete()
        }
    }

    /** FluxTV is on screen and the screen is on: an installation now would be seen. */
    private fun onScreen(): Boolean {
        val power = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val interactive = power?.isInteractive ?: true
        return interactive && isFluxTvOnScreen()
    }

    private suspend fun awaitOnScreen(timeoutMs: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (onScreen()) return true
            if (SystemClock.elapsedRealtime() >= end) return false
            delay(250)
        }
    }

    private fun isMetered(): Boolean = try {
        (appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    } catch (_: Exception) {
        false
    }

    /**
     * How fast the download may go: as fast as the line allows while FluxTV is off screen, and only
     * a small part of it (15%, from the player's estimate) while a channel plays, so that the
     * picture never stalls for an update. Looked at every few seconds, not at every piece.
     */
    private fun allowedBytesPerSecond(): Long {
        val now = SystemClock.elapsedRealtime()
        if (now - rateCheckedMs < RATE_RECHECK_MS) return rate
        rateCheckedMs = now
        rate = if (!onScreen()) {
            Long.MAX_VALUE
        } else {
            val line = lineBitsPerSecond()
            if (line <= 0) DEFAULT_ON_SCREEN_BYTES_PER_S
            else (line / 8 * 15 / 100).coerceIn(MIN_ON_SCREEN_BYTES_PER_S, MAX_ON_SCREEN_BYTES_PER_S)
        }
        return rate
    }

    private fun silentInstallerPossible(): Boolean {
        val sdk = Build.VERSION.SDK_INT
        if (sdk < Build.VERSION_CODES.S) return false
        return appContext.applicationInfo.targetSdkVersion >= targetNeededForSilentUpdates(sdk)
    }

    private suspend fun <T> keepingAwake(ms: Long, block: suspend () -> T): T {
        val power = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val lock = try {
            power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FluxTV:update")?.apply {
                setReferenceCounted(false)
                acquire(ms)
            }
        } catch (_: Exception) {
            null
        }
        try {
            return block()
        } finally {
            try {
                if (lock?.isHeld == true) lock.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun whenText(route: InstallRoute): String = when (route) {
        InstallRoute.HELPER, InstallRoute.SILENT ->
            "silently once ${AppName} is off screen (standby), or when it next starts"
        InstallRoute.CONFIRM -> "when ${AppName} next starts, after Android's question"
        InstallRoute.NOT_ALLOWED -> "once ${AppName} is allowed to install apps (Settings → Updates)"
    }

    private fun timeText(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

    companion object {
        /** How long after the start the first look waits: the first channel and the playlist go first. */
        private const val STARTUP_CHECK_DELAY_MS = 90_000L

        /** Looks closer together than this are skipped (a restart soon after the last look). */
        private const val MIN_CHECK_GAP_MS = 30 * 60_000L

        private const val MAX_MANIFEST_BYTES = 64L * 1024
        private const val SETTINGS_TIMEOUT_MS = 3_000L
        private const val PROGRESS_EVERY_MS = 500L
        private const val HELPER_WAIT_MS = 5_000L

        /** How long Android's question waits for FluxTV to be on screen to show over it. */
        private const val ON_SCREEN_WAIT_MS = 8_000L

        /** FluxTV must stay off screen this long before an update is installed. */
        private const val LEFT_SCREEN_SETTLE_MS = 5_000L

        /** Long enough for "Updating FluxTV…" to replace the picture and the channel to stop. */
        private const val COVER_SETTLE_MS = 1_500L

        /** The TV is kept awake for at most this long while an update is installed. */
        private const val INSTALL_WAKE_MS = 4 * 60_000L

        /** How long Android has to answer an installation through its installer. */
        private const val SESSION_ANSWER_MS = 3 * 60_000L

        /** After Android said it installed, FluxTV is about to be closed; nothing more is done. */
        private const val AFTER_SUCCESS_WAIT_MS = 60_000L

        /** After the update, how long the helper gets to have started FluxTV before FluxTV does it. */
        private const val RELAUNCH_CHECK_MS = 3_000L

        /** How often an update waiting for FluxTV to leave the screen looks again. */
        private const val TICK_MS = 15 * 60_000L

        private const val RATE_RECHECK_MS = 3_000L
        private const val DEFAULT_ON_SCREEN_BYTES_PER_S = 128L * 1024
        private const val MIN_ON_SCREEN_BYTES_PER_S = 32L * 1024
        private const val MAX_ON_SCREEN_BYTES_PER_S = 512L * 1024

        /**
         * The target SDK an app must have for Android ([sdk]) to install its own update without a
         * question, as PackageInstaller.SessionParams.setRequireUserAction documents it (Android 12
         * to 17). Newer Androids follow the same pattern, as a guess: if Android asks after all,
         * FluxTV notices and asks on screen instead. FluxTV's targetSdk 34 is enough up to
         * Android 16; Android 17 needs 35.
         */
        fun targetNeededForSilentUpdates(sdk: Int): Int = when {
            sdk <= 32 -> 29 // Android 12, 12L
            sdk == 33 -> 30 // Android 13
            sdk == 34 -> 31 // Android 14
            sdk == 35 -> 33 // Android 15
            sdk == 36 -> 34 // Android 16
            sdk == 37 -> 35 // Android 17
            else -> sdk - 2
        }

        fun describeRoute(route: InstallRoute): String = when (route) {
            InstallRoute.HELPER -> "silently, through the Shizuku helper"
            InstallRoute.SILENT -> "silently, by Android"
            InstallRoute.CONFIRM -> "after Android's question on screen"
            InstallRoute.NOT_ALLOWED -> "by Android, once allowed"
        }
    }
}
