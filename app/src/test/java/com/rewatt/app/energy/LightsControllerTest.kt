package com.rewatt.app.energy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightsControllerTest {
    private val lightsOnAutoOff = LightsState(lightsOn = true, autoOff = true)

    /**
     * Runs [LightsController.step] once a second from [from] to [to] (inclusive) with the same inputs;
     * the camera takes a new reading every 10 s (at whole 10 s), like the live view.
     */
    private fun run(state: LightsState, from: Long, to: Long, liveCount: Int?, camera: Boolean?): LightsState {
        var s = state
        var t = from
        while (t <= to) {
            val readAt = if (camera == null) null else t - t % 10_000
            s = LightsController.step(s, t, liveCount, camera, readAt)
            t += 1_000
        }
        return s
    }

    @Test
    fun everyCameraReadingSetsTheLightsEvenWithNobodyThere() {
        val on = LightsController.step(LightsState(), 0, liveCount = 0, cameraLightsOn = true, cameraReadAt = 0)
        assertEquals(true, on.lightsOn)
        val off = LightsController.step(on, 10_000, liveCount = 0, cameraLightsOn = false, cameraReadAt = 10_000)
        assertEquals(false, off.lightsOn)
        // No live view at all (no headcount): still follows the camera.
        assertEquals(true, LightsController.step(off, 20_000, liveCount = null, cameraLightsOn = true, cameraReadAt = 20_000).lightsOn)
    }

    @Test
    fun anAppSwitchIsCorrectedByTheNextReading() {
        val c = LightsController()
        c.tick(0, liveCount = 0, cameraLightsOn = true, cameraReadAt = 0)
        c.switchLights(false, nowMillis = 1_000)
        assertEquals(false, c.state.value.lightsOn)
        // A reading taken right after the switch is too early to trust…
        c.tick(2_000, liveCount = 0, cameraLightsOn = true, cameraReadAt = 2_000)
        assertEquals(false, c.state.value.lightsOn)
        // …the next one shows the light is really still on.
        c.tick(10_000, liveCount = 0, cameraLightsOn = true, cameraReadAt = 10_000)
        assertEquals(true, c.state.value.lightsOn)
    }

    @Test
    fun emptyForEmptyMsStartsTheCountdownThenLightsOff() {
        val empty = LightsController.EMPTY_MS
        val offAt = empty + LightsController.COUNTDOWN_MS
        val beforeCountdown = run(lightsOnAutoOff, 0, empty - 1_000, liveCount = 0, camera = true)
        assertNull(beforeCountdown.countdownEndsAt)
        assertEquals(true, beforeCountdown.lightsOn)

        val countdown = run(beforeCountdown, empty, empty, liveCount = 0, camera = true)
        assertEquals(offAt, countdown.countdownEndsAt)
        assertEquals((LightsController.COUNTDOWN_MS / 1_000).toInt(), countdown.countdownSecondsLeft(empty))
        assertEquals(1, countdown.countdownSecondsLeft(offAt - 1_000))

        val almost = run(countdown, empty + 1_000, offAt - 1_000, liveCount = 0, camera = true)
        assertEquals(true, almost.lightsOn)

        val off = run(almost, offAt, offAt, liveCount = 0, camera = true)
        assertEquals(false, off.lightsOn)
        assertNull(off.countdownEndsAt)
    }

    @Test
    fun someoneAppearingCancelsTheCountdown() {
        val empty = LightsController.EMPTY_MS
        val counting = run(lightsOnAutoOff, 0, empty + 1_000, liveCount = 0, camera = true)
        assertEquals(empty + LightsController.COUNTDOWN_MS, counting.countdownEndsAt)
        val back = LightsController.step(counting, empty + 2_000, liveCount = 1, cameraLightsOn = true, cameraReadAt = counting.lastCameraReadAt)
        assertNull(back.countdownEndsAt)
        assertNull(back.emptySince)
        assertEquals(true, back.lightsOn)
        // Empty again: the wait starts over (no countdown until EMPTY_MS have passed again).
        val again = run(back, empty + 3_000, 2 * empty + 2_000, liveCount = 0, camera = true)
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
    fun autoOffDoesNotRepeatWhileTheLightIsReallyStillOn() {
        val off = run(lightsOnAutoOff, 0, 15_000, liveCount = 0, camera = true)
        assertEquals(false, off.lightsOn)
        assertTrue(off.autoOffSpent)
        // Nobody turned the light off for real: the next reading shows it on, and Auto-off waits.
        val stillOn = run(off, 16_000, 60_000, liveCount = 0, camera = true)
        assertEquals(true, stillOn.lightsOn)
        assertNull(stillOn.countdownEndsAt)
        assertEquals(LightsChange.Reason.CAMERA, stillOn.lastChange?.reason)
        // Someone comes and goes: Auto-off counts down again.
        val entered = run(stillOn, 61_000, 61_000, liveCount = 1, camera = true)
        assertFalse(entered.autoOffSpent)
        val left = run(entered, 62_000, 77_000, liveCount = 0, camera = true)
        assertEquals(false, left.lightsOn)
        assertEquals(LightsChange.Reason.AUTO_OFF, left.lastChange?.reason)
    }

    @Test
    fun lightsReallyOffLetsAutoOffCountAgain() {
        val off = run(lightsOnAutoOff, 0, 15_000, liveCount = 0, camera = true)
        val dark = run(off, 16_000, 20_000, liveCount = 0, camera = false)
        assertFalse(dark.autoOffSpent)
        assertEquals(false, dark.lightsOn)
    }

    @Test
    fun manualSwitchAndSchedule() {
        val c = LightsController()
        c.switchLights(false, nowMillis = 0)
        assertEquals(false, c.state.value.lightsOn)

        c.schedule(turnOn = true, atMillis = 5_000)
        c.tick(4_000, liveCount = 0, cameraLightsOn = null, cameraReadAt = null)
        assertEquals(false, c.state.value.lightsOn)
        c.tick(5_000, liveCount = 0, cameraLightsOn = null, cameraReadAt = null)
        assertEquals(true, c.state.value.lightsOn)
        assertNull(c.state.value.schedule)
    }

    @Test
    fun everyLightsChangeIsRecordedWithItsReason() {
        // The first reading only sets the lights: no change to notify.
        val first = LightsController.step(LightsState(autoOff = true), 0, liveCount = 1, cameraLightsOn = true, cameraReadAt = 0)
        assertNull(first.lastChange)

        val auto = run(first, 1_000, 16_000, liveCount = 0, camera = true)
        assertEquals(LightsChange(1, on = false, LightsChange.Reason.AUTO_OFF), auto.lastChange)

        // The next camera reading still sees the light on.
        val back = LightsController.step(auto, 20_000, liveCount = 0, cameraLightsOn = true, cameraReadAt = 20_000)
        assertEquals(LightsChange(2, on = true, LightsChange.Reason.CAMERA), back.lastChange)

        // The same reading again changes nothing.
        assertEquals(back.lastChange, LightsController.step(back, 21_000, 0, true, 20_000).lastChange)

        val c = LightsController()
        c.switchLights(true, nowMillis = 0) // from "no reading yet": not a change
        assertNull(c.state.value.lastChange)
        c.switchLights(false, nowMillis = 0)
        assertEquals(LightsChange(1, on = false, LightsChange.Reason.MANUAL), c.state.value.lastChange)
        c.schedule(turnOn = true, atMillis = 1_000)
        c.tick(1_000, liveCount = null, cameraLightsOn = null, cameraReadAt = null)
        assertEquals(LightsChange(2, on = true, LightsChange.Reason.SCHEDULE), c.state.value.lastChange)
    }

    @Test
    fun autoOffAndScheduleWorkTogether() {
        val c = LightsController()
        c.switchLights(true, nowMillis = 0)
        c.setAutoOff(true)
        c.schedule(turnOn = true, atMillis = 60_000)
        // Auto-off still switches the lights off while a schedule is pending…
        for (t in 0L..15_000L step 1_000L) c.tick(t, liveCount = 0, cameraLightsOn = null, cameraReadAt = null)
        assertEquals(false, c.state.value.lightsOn)
        assertEquals(LightsSchedule(true, 60_000), c.state.value.schedule)
        // …and the schedule still switches them on later.
        c.tick(60_000, liveCount = 1, cameraLightsOn = null, cameraReadAt = null)
        assertEquals(true, c.state.value.lightsOn)
    }

    @Test
    fun aLinkedPlugIsTheTruthAndTheCameraIsIgnored() {
        val c = LightsController()
        c.plugReading(true, readAt = 0)
        assertTrue(c.state.value.plugLinked)
        assertEquals(true, c.state.value.lightsOn)
        // The camera thinks the room is dark: ignored while the plug is linked.
        c.tick(10_000, liveCount = 0, cameraLightsOn = false, cameraReadAt = 10_000)
        assertEquals(true, c.state.value.lightsOn)
        // Switched off from the Tapo app: shows here, as a PLUG change (no notification, no command).
        c.plugReading(false, readAt = 12_000)
        assertEquals(false, c.state.value.lightsOn)
        assertEquals(LightsChange.Reason.PLUG, c.state.value.lastChange?.reason)
    }

    @Test
    fun whileThePlugIsLinkedCameraReadingsNeverChangeTheBulb() {
        // Plug says ON; for a whole minute the camera reads the room as dark, then as bright while the
        // plug says OFF: the bulb always shows the plug, and no camera change is ever recorded.
        var s = LightsController.plugStep(LightsState(), true, readAt = 0)
        s = run(s, 0, 60_000, liveCount = 1, camera = false)
        assertEquals(true, s.lightsOn)
        s = LightsController.plugStep(s, false, readAt = 61_000)
        s = run(s, 61_000, 120_000, liveCount = 1, camera = true)
        assertEquals(false, s.lightsOn)
        assertTrue(s.lastChange?.reason != LightsChange.Reason.CAMERA)
    }

    @Test
    fun aPlugReadingFromBeforeTheAppSwitchedIsStale() {
        val c = LightsController()
        c.plugReading(false, readAt = 0)
        c.switchLights(true, nowMillis = 5_000)
        // Read while the switch was still on its way: keeps the app's switch.
        c.plugReading(false, readAt = 4_900)
        assertEquals(true, c.state.value.lightsOn)
        assertEquals(LightsChange.Reason.MANUAL, c.state.value.lastChange?.reason)
        // Read after: the plug is the truth (here the switch didn't take).
        c.plugReading(false, readAt = 8_000)
        assertEquals(false, c.state.value.lightsOn)
    }

    @Test
    fun whenThePlugUnlinksTheCameraTakesOverAgain() {
        val c = LightsController()
        c.plugReading(true, readAt = 0)
        c.plugReading(null, readAt = 3_000)
        assertFalse(c.state.value.plugLinked)
        c.tick(10_000, liveCount = 0, cameraLightsOn = false, cameraReadAt = 10_000)
        assertEquals(false, c.state.value.lightsOn)
    }

    @Test
    fun autoOffWithThePlugCountsDownAgainAfterThePlugReportsOff() {
        val offAt = LightsController.EMPTY_MS + LightsController.COUNTDOWN_MS
        var s = LightsController.plugStep(lightsOnAutoOff, true, readAt = 0)
        s = run(s, 0, offAt, liveCount = 0, camera = null)
        assertEquals(false, s.lightsOn)
        assertEquals(LightsChange.Reason.AUTO_OFF, s.lastChange?.reason)
        assertTrue(s.autoOffSpent)
        s = LightsController.plugStep(s, false, readAt = offAt + 1_000)
        assertFalse(s.autoOffSpent)
    }
}
