package com.rhythmwatch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackModePolicyTest {
    @Test
    fun modeChangesDuringPlaybackAreMarkedForTheNextStart() {
        assertTrue(PlaybackModePolicy.changeAppliesOnNextStart(true, selectedMode = 1, sessionMode = 0))
        assertFalse(PlaybackModePolicy.changeAppliesOnNextStart(true, selectedMode = 0, sessionMode = 0))
        assertFalse(PlaybackModePolicy.changeAppliesOnNextStart(false, selectedMode = 1, sessionMode = 0))
    }
}
