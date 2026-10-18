package com.rhythmwatch

object PlaybackModePolicy {
    fun modeForNewSession(selectedMode: Int): Int = selectedMode.coerceIn(0, 1)

    fun changeAppliesOnNextStart(playing: Boolean, selectedMode: Int, sessionMode: Int?): Boolean =
        playing && sessionMode != null && modeForNewSession(selectedMode) != sessionMode
}
