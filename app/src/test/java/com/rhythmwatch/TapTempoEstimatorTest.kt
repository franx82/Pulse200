package com.rhythmwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapTempoEstimatorTest {
    @Test
    fun usesTheMeanIntervalRatherThanTheMeanOfIndividualTempos() {
        val result = TapTempoEstimator.estimate(listOf(500L, 1_000L))

        assertEquals(80, result.bpm)
        assertNull(result.error)
    }

    @Test
    fun excludesIntervalsOutsideTheSupportedTempoRange() {
        val result = TapTempoEstimator.estimate(listOf(500L, 1_000L, 100L, 500L))

        assertEquals(90, result.bpm)
        assertNull(result.error)
    }

    @Test
    fun reportsOutOfRangeTapsWithoutChangingTempo() {
        assertEquals(TapTempoEstimate(null, "Hi"), TapTempoEstimator.estimate(listOf(100L, 120L)))
        assertEquals(TapTempoEstimate(null, "Lo"), TapTempoEstimator.estimate(listOf(2_200L, 2_300L)))
    }

    @Test
    fun acceptsTheSupportedBpmLimits() {
        assertEquals(250, TapTempoEstimator.estimate(listOf(240L)).bpm)
        assertEquals(30, TapTempoEstimator.estimate(listOf(2_000L)).bpm)
    }

    @Test
    fun emptyOrNonPositiveIntervalsDoNotCreateAnEstimate() {
        assertEquals(TapTempoEstimate(null), TapTempoEstimator.estimate(emptyList()))
        assertEquals(TapTempoEstimate(null), TapTempoEstimator.estimate(listOf(0L, -1L)))
    }

    @Test
    fun regularAndIrregularFiveTapSequencesUseTheirMeanIntervals() {
        var regular = TapTempoSequence.tap(TapTempoSequenceState(), 1_000L)
        repeat(4) { index -> regular = TapTempoSequence.tap(regular, 1_500L + index * 500L) }
        assertEquals(120, regular.estimate?.bpm)

        var irregular = TapTempoSequence.tap(TapTempoSequenceState(), 1_000L)
        listOf(400L, 500L, 600L, 500L).forEach { interval ->
            irregular = TapTempoSequence.tap(irregular, irregular.lastTapMs + interval)
        }
        assertEquals(120, irregular.estimate?.bpm)
    }

    @Test
    fun timeoutClearsTheSequenceAndTheNextTapStartsAgain() {
        val afterTap = TapTempoSequence.tap(TapTempoSequenceState(), 1_000L)
        assertFalse(TapTempoSequence.hasTimedOut(afterTap, 3_500L))
        assertTrue(TapTempoSequence.hasTimedOut(afterTap, 3_501L))

        val cleared = TapTempoSequence.clearAfterTimeout(afterTap)
        val restarted = TapTempoSequence.tap(cleared, 3_501L)
        assertEquals(1, restarted.tapCount)
        assertEquals(3_501L, restarted.lastTapMs)
        assertNull(restarted.estimate)
    }
}
