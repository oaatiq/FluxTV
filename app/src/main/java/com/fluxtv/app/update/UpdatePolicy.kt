package com.fluxtv.app.update

/**
 * How this TV can install FluxTV's updates, best first. Which one applies is worked out each time
 * (see [UpdateManager.route]), as the administrator can change it at any moment.
 */
enum class InstallRoute {
    /** Shizuku runs FluxTV's helper as the shell user, which installs like `adb install`: no question, any Android version. */
    HELPER,

    /** Android 12+: Android installs an app's own update without asking, once it may install apps at all. */
    SILENT,

    /** Android asks before installing (Android 7 to 11, or 12+ when it will not do it silently). */
    CONFIRM,

    /** FluxTV has not been allowed to install apps ("Install unknown apps"), and there is no helper. */
    NOT_ALLOWED
}

/** What gives FluxTV the chance to install an update that is ready. */
enum class InstallTrigger {
    /** FluxTV is starting on screen (the TV has just been turned on, or FluxTV opened). */
    START,

    /** A look for updates finished (after the start, or the periodic background job). */
    CHECK,

    /** FluxTV has left the screen: standby, Home, or another app. */
    LEFT_SCREEN,

    /** The regular look while an update waits for FluxTV to leave the screen. */
    TICK,

    /** The administrator chose Install now in Settings → Updates. */
    MANUAL
}

enum class InstallAction {
    WAIT,

    /** Install now, with nothing of FluxTV on screen: nobody sees it happen. */
    INSTALL_QUIETLY,

    /** Install now while FluxTV is on screen: "Updating FluxTV…" shows, then FluxTV comes back by itself. */
    INSTALL_ON_SCREEN,

    /** Start an installation that Android will ask about, while FluxTV is on screen to show the question. */
    ASK
}

/** Everything [UpdatePolicy.decide] looks at, so that the decision can be tested away from a TV. */
data class InstallMoment(
    val route: InstallRoute,
    val trigger: InstallTrigger,
    /** FluxTV is on screen and the screen is on. */
    val onScreen: Boolean,
    /** After an update installed on screen, FluxTV can start itself again (see [UpdateManager.canRelaunch]). */
    val canRelaunch: Boolean,
    val television: Boolean,
    /** How long the downloaded update has been waiting to be installed. */
    val waitedMs: Long,
    /** Since Android last asked about an update; [Long.MAX_VALUE] if it never has. */
    val sinceLastAskMs: Long,
    /** The hour of the day on the TV's clock, 0–23. */
    val localHour: Int
)

/**
 * When FluxTV installs its updates. The aim is that nobody watching ever has to do anything, and
 * that an installation, which closes FluxTV for some seconds, falls where nobody is watching:
 *
 * - Whenever FluxTV is not on screen (the TV in standby, another app in front), at once.
 * - When FluxTV starts, before the first channel, behind a calm "Updating FluxTV…": the Viewer has
 *   just turned the TV on and is waiting for the picture anyway.
 * - A TV left on FluxTV day and night gets it in the night, once it has waited a day (3 days at the
 *   most, whatever the hour), and comes back on the same channel.
 *
 * An installation on screen needs FluxTV to be able to start itself again afterwards: without that,
 * the Viewer would be left at the TV's home screen, so then it only ever happens off screen. Where
 * Android asks before installing, it is asked when FluxTV starts, at most once a day, as the
 * question needs someone in front of the TV.
 */
object UpdatePolicy {

    private const val HOUR_MS = 3_600_000L

    /** An update waiting while FluxTV stays on screen goes in at night after this long… */
    const val NIGHT_INSTALL_AFTER_MS = 24 * HOUR_MS

    /** …and at any hour after this long. */
    const val ANY_HOUR_INSTALL_AFTER_MS = 72 * HOUR_MS

    /** The night hours an update that has waited a day may be installed in, on screen: 03:00–04:59. */
    val NIGHT_HOURS = 3..4

    /** Android's question about an update is asked again at most this often. */
    const val ASK_AGAIN_AFTER_MS = 20 * HOUR_MS

    fun decide(m: InstallMoment): InstallAction {
        if (m.trigger == InstallTrigger.MANUAL) {
            return when {
                !m.onScreen -> InstallAction.INSTALL_QUIETLY
                // The administrator is in front of the TV: Android's own screen says what it needs.
                m.route == InstallRoute.CONFIRM || m.route == InstallRoute.NOT_ALLOWED -> InstallAction.ASK
                else -> InstallAction.INSTALL_ON_SCREEN
            }
        }
        return when (m.route) {
            InstallRoute.NOT_ALLOWED -> InstallAction.WAIT
            InstallRoute.CONFIRM ->
                if (m.trigger == InstallTrigger.START && m.onScreen && m.sinceLastAskMs >= ASK_AGAIN_AFTER_MS) {
                    InstallAction.ASK
                } else {
                    InstallAction.WAIT
                }
            InstallRoute.HELPER, InstallRoute.SILENT -> when {
                !m.onScreen -> InstallAction.INSTALL_QUIETLY
                !m.canRelaunch -> InstallAction.WAIT
                m.trigger == InstallTrigger.START -> InstallAction.INSTALL_ON_SCREEN
                m.television && overdue(m.waitedMs, m.localHour) -> InstallAction.INSTALL_ON_SCREEN
                else -> InstallAction.WAIT
            }
        }
    }

    private fun overdue(waitedMs: Long, hour: Int): Boolean =
        waitedMs >= ANY_HOUR_INSTALL_AFTER_MS || (waitedMs >= NIGHT_INSTALL_AFTER_MS && hour in NIGHT_HOURS)

    /**
     * How long to wait after the [failures]th failed try at the same build before the next: soon at
     * first, then no more than once a day, so a build that keeps failing costs the TV next to nothing.
     */
    fun retryDelayMs(failures: Int): Long = when {
        failures <= 0 -> 0L
        failures == 1 -> HOUR_MS / 2
        failures == 2 -> 2 * HOUR_MS
        failures == 3 -> 6 * HOUR_MS
        else -> 24 * HOUR_MS
    }

    /** Android's reasons that mean this build can never be installed over this FluxTV. */
    private val PERMANENT_REASONS = listOf(
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE",
        "INSTALL_FAILED_INCONSISTENT_CERTIFICATES",
        "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES",
        "INSTALL_PARSE_FAILED_NO_CERTIFICATES",
        "INSTALL_FAILED_VERSION_DOWNGRADE",
        "INSTALL_FAILED_OLDER_SDK",
        "INSTALL_FAILED_NO_MATCHING_ABIS",
        "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE"
    )

    /** PackageInstaller.STATUS_FAILURE_CONFLICT and STATUS_FAILURE_INCOMPATIBLE. */
    private const val STATUS_FAILURE_CONFLICT = 5
    private const val STATUS_FAILURE_INCOMPATIBLE = 7

    /**
     * Whether a failure is one that trying the same build again cannot fix (another signing key, an
     * older build, an Android too old for it). [message] is what Android said; [status] the
     * PackageInstaller status, when it came from there. Such a build is not tried again by itself; a
     * newer one, or Install now, is.
     */
    fun isPermanent(message: String?, status: Int? = null): Boolean {
        val text = message.orEmpty()
        if (PERMANENT_REASONS.any { text.contains(it) }) return true
        return status == STATUS_FAILURE_CONFLICT || status == STATUS_FAILURE_INCOMPATIBLE
    }

    /** What Android's answer means, in words for the administrator. [message] is kept short. */
    fun describeFailure(message: String?): String {
        val text = message.orEmpty()
        return when {
            listOf("UPDATE_INCOMPATIBLE", "INCONSISTENT_CERTIFICATES", "NO_CERTIFICATES").any { text.contains(it) } ->
                "Android refused the update: it is signed with another key than the FluxTV on this TV"
            text.contains("VERSION_DOWNGRADE") -> "Android refused the update: it is older than the FluxTV on this TV"
            text.contains("OLDER_SDK") -> "Android refused the update: it needs a newer Android version"
            text.contains("NO_MATCHING_ABIS") -> "Android refused the update: it has no code for this TV's processor"
            text.contains("INSUFFICIENT_STORAGE") -> "Android could not install the update: not enough free space"
            text.isBlank() -> "Android could not install the update"
            else -> "Android could not install the update (${text.replace('\n', ' ').trim().take(160)})"
        }
    }
}
