package com.fluxtv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefreshRateMatchTest {

    private fun choose(fps: Float, rates: List<Float>, current: Float, home: Float = 60f): Float? =
        RefreshRateMatch.choose(fps, rates, rates.indexOf(current), home)?.let { rates[it] }

    @Test
    fun twentyFiveFpsGoesToFiftyHertz() {
        assertEquals(50f, choose(25f, listOf(60f, 50f, 30f, 24f), current = 60f))
    }

    @Test
    fun aMultipleNearTheTvsOwnRateWinsOverTheFirstListed() {
        // 25 Hz matches too, and comes first, but would make menus as jerky as 25 pictures a second.
        assertEquals(50f, choose(25f, listOf(25f, 50f, 60f), current = 60f))
    }

    @Test
    fun noModeFourPercentOff() {
        // A 24 Hz mode for 25 fps leaves a frame out every second.
        assertNull(choose(25f, listOf(60f, 24f, 30f), current = 60f))
        assertNull(choose(50f, listOf(60f, 48f), current = 60f))
    }

    @Test
    fun aMatchingModeInUseIsKept() {
        assertEquals(60f, choose(30f, listOf(30f, 60f, 120f), current = 60f))
        assertEquals(50f, choose(25f, listOf(25f, 50f, 60f), current = 50f, home = 50f))
    }

    @Test
    fun exactBeatsATenthOfAPercentOff() {
        assertEquals(59.94f, choose(29.97f, listOf(60f, 59.94f), current = 60f))
        assertEquals(23.976f, choose(23.976f, listOf(60f, 24f, 23.976f), current = 60f))
    }

    @Test
    fun aTenthOfAPercentOffIsBetterThanNoMatch() {
        assertEquals(24f, choose(23.976f, listOf(60f, 24f, 50f), current = 60f))
    }

    @Test
    fun nothingToChooseFrom() {
        assertNull(choose(25f, emptyList(), current = 60f))
        assertNull(choose(0f, listOf(60f), current = 60f))
    }
}
