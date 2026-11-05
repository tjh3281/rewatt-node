package com.rewatt.app.energy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightsControllerTest {
    private val lightsOnAutoOff = LightsState(lightsOn = true, lastCameraLightsOn = true, autoOff = true)

    /** Runs [LightsController.step] once a second from [from] to [to] (inclusive) with the same inputs. */
    private fun run(state: LightsState, from: Long, to: Long, liveCount: Int?, camera: Boolean?): LightsState {
        var s = state
        var t = from
        while (t <= to) {
            s = LightsController.step(s, t, liveCount, camera)
            t += 1_000
        }
        return s
    }

    @Test
    fun cameraReadingSetsTheLights() {
        val s = LightsController.step(LightsState(), 0, liveCount = 1, cameraLightsOn = true)
        assertEquals(true, s.lightsOn)
        assertEquals(false, LightsController.step(s, 1_000, 1, cameraLightsOn = false).lightsOn)
    }

    @Test
    fun emptyTenSecondsStartsFiveSecondCountdownThenLightsOff() {
        val beforeCountdown = run(lightsOnAutoOff, 0, 9_000, liveCount = 0, camera = true)
        assertNull(beforeCountdown.countdownEndsAt)
        assertEquals(true, beforeCountdown.lightsOn)

        val countdown = run(beforeCountdown, 10_000, 10_000, liveCount = 0, camera = true)
        assertEquals(15_000L, countdown.countdownEndsAt)
        assertEquals(5, countdown.countdownSecondsLeft(10_000))
        assertEquals(1, countdown.countdownSecondsLeft(14_000))

        val almost = run(countdown, 11_000, 14_000, liveCount = 0, camera = true)
        assertEquals(true, almost.lightsOn)

        val off = run(almost, 15_000, 15_000, liveCount = 0, camera = true)
        assertEquals(false, off.lightsOn)
        assertTrue(off.offByApp)
        assertNull(off.countdownEndsAt)
    }

    @Test
    fun someoneAppearingCancelsTheCountdown() {
        val counting = run(lightsOnAutoOff, 0, 11_000, liveCount = 0, camera = true)
        assertEquals(15_000L, counting.countdownEndsAt)
        val back = LightsController.step(counting, 12_000, liveCount = 1, cameraLightsOn = true)
        assertNull(back.countdownEndsAt)
        assertNull(back.emptySince)
        assertEquals(true, back.lightsOn)
        // Empty again: the 10 s start over.
        val again = run(back, 13_000, 22_000, liveCount = 0, camera = true)
        assertNull(again.countdownEndsAt)
    }

    @Test
    fun noLiveViewIsNotEmpty() {
        val s = run(lightsOnAutoOff, 0, 30_000, liveCount = null, camera = true)
        assertNull(s.countdownEndsAt)
        assertEquals(true, s.lightsOn)
    }

    @Test
    fun autoOffOffMeansNoCountdown() {
        val s = run(lightsOnAutoOff.copy(autoOff = false), 0, 30_000, liveCount = 0, camera = true)
        assertNull(s.countdownEndsAt)
        assertEquals(true, s.lightsOn)
    }

    @Test
    fun appOffStaysOffUntilSomeoneEnters() {
        val off = run(lightsOnAutoOff, 0, 15_000, liveCount = 0, camera = true)
        assertEquals(false, off.lightsOn)
        // The real light is still on (no relay): the camera keeps reading "on", the app keeps "off".
        val stillOff = run(off, 16_000, 60_000, liveCount = 0, camera = true)
        assertEquals(false, stillOff.lightsOn)
        assertNull(stillOff.countdownEndsAt)
        // Someone walks in: the lights follow the camera again.
        val entered = LightsController.step(stillOff, 61_000, liveCount = 1, cameraLightsOn = true)
        assertEquals(true, entered.lightsOn)
        assertFalse(entered.offByApp)
    }

    @Test
    fun manualSwitchAndSchedule() {
        val c = LightsController()
        c.switchLights(false)
        assertEquals(false, c.state.value.lightsOn)
        assertTrue(c.state.value.offByApp)

        c.schedule(turnOn = true, atMillis = 5_000)
        c.tick(4_000, liveCount = 0, cameraLightsOn = null)
        assertEquals(false, c.state.value.lightsOn)
        c.tick(5_000, liveCount = 0, cameraLightsOn = null)
        assertEquals(true, c.state.value.lightsOn)
        assertNull(c.state.value.schedule)
        assertFalse(c.state.value.offByApp)
    }

    @Test
    fun everyLightsChangeIsRecordedWithItsReason() {
        // The first reading only sets the lights: no change to notify.
        val first = LightsController.step(LightsState(autoOff = true), 0, liveCount = 1, cameraLightsOn = true)
        assertNull(first.lastChange)

        val auto = run(first, 1_000, 16_000, liveCount = 0, camera = true)
        assertEquals(LightsChange(1, on = false, LightsChange.Reason.AUTO_OFF), auto.lastChange)

        // Someone walks in and the camera still sees the light on.
        val back = LightsController.step(auto, 17_000, liveCount = 1, cameraLightsOn = true)
        assertEquals(LightsChange(2, on = true, LightsChange.Reason.CAMERA), back.lastChange)

        // The same reading again changes nothing.
        assertEquals(back.lastChange, LightsController.step(back, 18_000, 1, true).lastChange)

        val c = LightsController()
        c.switchLights(true) // from "no reading yet": not a change
        assertNull(c.state.value.lastChange)
        c.switchLights(false)
        assertEquals(LightsChange(1, on = false, LightsChange.Reason.MANUAL), c.state.value.lastChange)
        c.schedule(turnOn = true, atMillis = 1_000)
        c.tick(1_000, liveCount = null, cameraLightsOn = null)
        assertEquals(LightsChange(2, on = true, LightsChange.Reason.SCHEDULE), c.state.value.lastChange)
    }

    @Test
    fun autoOffAndScheduleWorkTogether() {
        val c = LightsController()
        c.switchLights(true)
        c.setAutoOff(true)
        c.schedule(turnOn = true, atMillis = 60_000)
        // Auto-off still switches the lights off while a schedule is pending…
        for (t in 0L..15_000L step 1_000L) c.tick(t, liveCount = 0, cameraLightsOn = null)
        assertEquals(false, c.state.value.lightsOn)
        assertEquals(LightsSchedule(true, 60_000), c.state.value.schedule)
        // …and the schedule still switches them on later.
        c.tick(60_000, liveCount = 1, cameraLightsOn = null)
        assertEquals(true, c.state.value.lightsOn)
    }
}
