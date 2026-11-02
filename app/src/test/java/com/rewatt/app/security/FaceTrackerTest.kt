package com.rewatt.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceTrackerTest {
    private val face = FaceBox(100f, 100f, 200f, 220f)
    private val other = FaceBox(600f, 100f, 700f, 220f)
    private val pass = TrackCheck(isFamily = true, similarity = 0.6f, confidence = 0.99f, name = "Aisyah")
    private val fail = TrackCheck(isFamily = false, similarity = 0.1f, confidence = 0.01f, name = null)

    private fun FaceBox.moved(dx: Float) = copy(left = left + dx, right = right + dx)

    @Test
    fun step_newFaceIsRecognisedAndSameFaceNextFrameIsNot() {
        val tracker = FaceTracker()
        var calls = 0
        tracker.step(listOf(face), 0L) { calls++; pass }
        tracker.step(listOf(face.moved(5f)), 100L) { calls++; pass }
        tracker.step(listOf(face.moved(10f)), 200L) { calls++; pass }

        assertEquals(1, calls)
    }

    @Test
    fun step_familyOnFirstPassingCheck() {
        val tracker = FaceTracker()
        val step = tracker.step(listOf(face), 0L) { pass }

        assertEquals(TrackStatus.FAMILY, step.faces.single().status)
        assertEquals("Aisyah", step.faces.single().name)
        assertEquals(1, step.newlyFamily.size)
    }

    @Test
    fun step_unknownOnlyAfterThreeFailsSpanningOneSecond() {
        val tracker = FaceTracker()
        var newlyUnknownAt: Long? = null
        var t = 0L
        while (t <= 2_000L && newlyUnknownAt == null) {
            val step = tracker.step(listOf(face), t) { fail }
            if (step.newlyUnknown.isNotEmpty()) newlyUnknownAt = t
            t += 100L
        }

        assertEquals(1_000L, newlyUnknownAt)
    }

    @Test
    fun step_oneBadFrameThenPassIsFamilyNotUnknown() {
        val tracker = FaceTracker()
        val checks = ArrayDeque(listOf(fail, fail, pass))
        var unknown = false
        var t = 0L
        repeat(10) {
            val step = tracker.step(listOf(face), t) { checks.removeFirstOrNull() ?: pass }
            unknown = unknown || step.newlyUnknown.isNotEmpty()
            t += 100L
        }

        assertTrue(!unknown)
        assertEquals(TrackStatus.FAMILY, tracker.step(listOf(face), t) { pass }.faces.single().status)
    }

    @Test
    fun step_familyStaysFamilyOnFailedPeriodicRecheck() {
        val tracker = FaceTracker()
        tracker.step(listOf(face), 0L) { pass }
        var t = 100L
        while (t <= 10_000L) {
            val step = tracker.step(listOf(face), t) { fail }
            assertTrue(step.newlyUnknown.isEmpty())
            assertEquals(TrackStatus.FAMILY, step.faces.single().status)
            t += 100L
        }
    }

    @Test
    fun step_sizeJumpForcesRecheckAndCanDemoteFamily() {
        val tracker = FaceTracker()
        tracker.step(listOf(face), 0L) { pass }
        // Same place, box suddenly much bigger (another face took the track): re-check at once.
        val bigger = FaceBox(70f, 70f, 240f, 260f) // ~2.7× the area, still overlapping enough to match
        var calls = 0
        val step = tracker.step(listOf(bigger), 100L) { calls++; fail }

        assertEquals(1, calls)
        assertEquals(TrackStatus.PENDING, step.faces.single().status)
    }

    @Test
    fun step_familyAndStrangerTogetherReportsTheStranger() {
        val tracker = FaceTracker()
        var unknownTrack: Int? = null
        var t = 0L
        while (t <= 2_000L) {
            val step = tracker.step(listOf(face, other), t) { i -> if (i == 0) pass else fail }
            step.newlyUnknown.firstOrNull()?.let { unknownTrack = it.trackId }
            t += 100L
        }
        val final = tracker.step(listOf(face, other), t) { i -> if (i == 0) pass else fail }

        assertEquals(TrackStatus.FAMILY, final.faces.first { it.box == face }.status)
        assertEquals(TrackStatus.UNKNOWN, final.faces.first { it.box == other }.status)
        assertEquals(final.faces.first { it.box == other }.trackId, unknownTrack)
    }

    @Test
    fun step_lostTrackIsDroppedAndReturningFaceIsRecognisedAgain() {
        val tracker = FaceTracker()
        var calls = 0
        tracker.step(listOf(face), 0L) { calls++; pass }
        tracker.step(emptyList(), 500L) { calls++; pass }
        assertEquals(1, tracker.activeCount)
        tracker.step(emptyList(), 1_100L) { calls++; pass }
        assertEquals(0, tracker.activeCount)
        tracker.step(listOf(face), 1_200L) { calls++; pass }

        assertEquals(2, calls)
    }

    @Test
    fun iou_identicalBoxesIsOneAndDisjointIsZero() {
        assertEquals(1f, face.iou(face), 1e-6f)
        assertEquals(0f, face.iou(other), 1e-6f)
    }
}
