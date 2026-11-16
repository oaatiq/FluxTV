package com.fluxtv.app.update

import com.fluxtv.app.update.InstallAction.ASK
import com.fluxtv.app.update.InstallAction.INSTALL_ON_SCREEN
import com.fluxtv.app.update.InstallAction.INSTALL_QUIETLY
import com.fluxtv.app.update.InstallAction.WAIT
import com.fluxtv.app.update.InstallRoute.CONFIRM
import com.fluxtv.app.update.InstallRoute.HELPER
import com.fluxtv.app.update.InstallRoute.NOT_ALLOWED
import com.fluxtv.app.update.InstallRoute.SILENT
import com.fluxtv.app.update.InstallTrigger.CHECK
import com.fluxtv.app.update.InstallTrigger.LEFT_SCREEN
import com.fluxtv.app.update.InstallTrigger.MANUAL
import com.fluxtv.app.update.InstallTrigger.START
import com.fluxtv.app.update.InstallTrigger.TICK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {

    private val hour = 3_600_000L

    private fun decide(
        route: InstallRoute,
        trigger: InstallTrigger,
        onScreen: Boolean,
        canRelaunch: Boolean = true,
        television: Boolean = true,
        waitedMs: Long = 0,
        sinceLastAskMs: Long = Long.MAX_VALUE,
        hourOfDay: Int = 20
    ) = UpdatePolicy.decide(
        InstallMoment(route, trigger, onScreen, canRelaunch, television, waitedMs, sinceLastAskMs, hourOfDay)
    )

    @Test
    fun aSilentTvInstallsAsSoonAsFluxTvIsOffScreen() {
        for (route in listOf(HELPER, SILENT)) {
            for (trigger in listOf(CHECK, LEFT_SCREEN, TICK)) {
                assertEquals("$route $trigger", INSTALL_QUIETLY, decide(route, trigger, onScreen = false))
            }
            // Even when FluxTV could not come back: nobody is watching it.
            assertEquals(INSTALL_QUIETLY, decide(route, LEFT_SCREEN, onScreen = false, canRelaunch = false))
        }
    }

    @Test
    fun whileWatchingItWaits() {
        for (route in listOf(HELPER, SILENT)) {
            assertEquals(WAIT, decide(route, CHECK, onScreen = true))
            assertEquals(WAIT, decide(route, TICK, onScreen = true, waitedMs = 2 * hour))
        }
    }

    @Test
    fun atTheStartItInstallsOnScreenOnlyIfFluxTvComesBack() {
        assertEquals(INSTALL_ON_SCREEN, decide(SILENT, START, onScreen = true))
        assertEquals(INSTALL_ON_SCREEN, decide(HELPER, START, onScreen = true))
        // Without a way back the Viewer would be left at the TV's home screen.
        assertEquals(WAIT, decide(SILENT, START, onScreen = true, canRelaunch = false))
    }

    @Test
    fun aTvNeverLeftGetsItAtNightAfterADayAndAtAnyHourAfterThree() {
        assertEquals(WAIT, decide(SILENT, TICK, onScreen = true, waitedMs = 23 * hour, hourOfDay = 3))
        assertEquals(INSTALL_ON_SCREEN, decide(SILENT, TICK, onScreen = true, waitedMs = 25 * hour, hourOfDay = 3))
        assertEquals(INSTALL_ON_SCREEN, decide(HELPER, TICK, onScreen = true, waitedMs = 25 * hour, hourOfDay = 4))
        assertEquals(WAIT, decide(SILENT, TICK, onScreen = true, waitedMs = 25 * hour, hourOfDay = 21))
        assertEquals(INSTALL_ON_SCREEN, decide(SILENT, TICK, onScreen = true, waitedMs = 73 * hour, hourOfDay = 21))
        // Not without a way back, and not on a phone, where someone is holding it.
        assertEquals(WAIT, decide(SILENT, TICK, onScreen = true, waitedMs = 73 * hour, canRelaunch = false))
        assertEquals(WAIT, decide(SILENT, TICK, onScreen = true, waitedMs = 73 * hour, television = false))
    }

    @Test
    fun whereAndroidAsksItAsksAtTheStartAtMostOnceADay() {
        assertEquals(ASK, decide(CONFIRM, START, onScreen = true))
        assertEquals(WAIT, decide(CONFIRM, START, onScreen = true, sinceLastAskMs = 2 * hour))
        assertEquals(ASK, decide(CONFIRM, START, onScreen = true, sinceLastAskMs = 21 * hour))
        // Never in the background, where nobody could answer, nor in the middle of a programme.
        assertEquals(WAIT, decide(CONFIRM, LEFT_SCREEN, onScreen = false))
        assertEquals(WAIT, decide(CONFIRM, CHECK, onScreen = true))
        assertEquals(WAIT, decide(CONFIRM, TICK, onScreen = true, waitedMs = 100 * hour, hourOfDay = 3))
    }

    @Test
    fun withoutPermissionNothingHappensByItself() {
        for (trigger in listOf(START, CHECK, LEFT_SCREEN, TICK)) {
            assertEquals(WAIT, decide(NOT_ALLOWED, trigger, onScreen = trigger == START))
            assertEquals(WAIT, decide(NOT_ALLOWED, trigger, onScreen = false))
        }
    }

    @Test
    fun installNowDoesWhatTheAdministratorAsks() {
        assertEquals(INSTALL_ON_SCREEN, decide(SILENT, MANUAL, onScreen = true, canRelaunch = false))
        assertEquals(INSTALL_ON_SCREEN, decide(HELPER, MANUAL, onScreen = true))
        assertEquals(ASK, decide(CONFIRM, MANUAL, onScreen = true, sinceLastAskMs = 0))
        // Android's own screen then says what it needs.
        assertEquals(ASK, decide(NOT_ALLOWED, MANUAL, onScreen = true))
    }

    @Test
    fun failuresAreRetriedLessAndLessOften() {
        assertEquals(0L, UpdatePolicy.retryDelayMs(0))
        val delays = (1..6).map { UpdatePolicy.retryDelayMs(it) }
        assertEquals(delays.sorted(), delays)
        assertEquals(hour / 2, delays.first())
        assertEquals(24 * hour, delays.last())
    }

    @Test
    fun aBuildSignedWithAnotherKeyIsNotTriedAgain() {
        assertTrue(UpdatePolicy.isPermanent("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.fluxtv.app signatures do not match]"))
        assertTrue(UpdatePolicy.isPermanent("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]"))
        assertTrue(UpdatePolicy.isPermanent(null, status = 5))
        assertTrue(UpdatePolicy.isPermanent("", status = 7))
        assertFalse(UpdatePolicy.isPermanent("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]"))
        assertFalse(UpdatePolicy.isPermanent("Failure [FluxTV: the update arrived damaged at the helper]"))
        assertFalse(UpdatePolicy.isPermanent(null, status = 6))
    }

    @Test
    fun failuresAreSaidInWords() {
        assertTrue(UpdatePolicy.describeFailure("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: x]").contains("another key"))
        assertTrue(UpdatePolicy.describeFailure("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]").contains("free space"))
        assertEquals("Android could not install the update", UpdatePolicy.describeFailure(null))
    }

    @Test
    fun silentUpdatesNeedARecentTarget() {
        assertEquals(29, UpdateManager.targetNeededForSilentUpdates(31))
        assertEquals(30, UpdateManager.targetNeededForSilentUpdates(33))
        assertEquals(31, UpdateManager.targetNeededForSilentUpdates(34))
        assertEquals(33, UpdateManager.targetNeededForSilentUpdates(35))
        assertEquals(34, UpdateManager.targetNeededForSilentUpdates(36))
        assertEquals(35, UpdateManager.targetNeededForSilentUpdates(37))
        // FluxTV's own target (34) meets every Android up to 16; Android 17 needs 35.
        assertTrue((31..36).all { UpdateManager.targetNeededForSilentUpdates(it) <= 34 })
    }
}
