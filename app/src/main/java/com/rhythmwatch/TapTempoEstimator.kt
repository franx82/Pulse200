package com.rhythmwatch

import kotlin.math.roundToInt

data class TapTempoEstimate(val bpm: Int?, val error: String? = null)

object TapTempoEstimator {
    private const val MIN_INTERVAL_MS = 240L
    private const val MAX_INTERVAL_MS = 2_000L

    fun estimate(intervalsMs: List<Long>): TapTempoEstimate {
        val valid = intervalsMs.filter { it in MIN_INTERVAL_MS..MAX_INTERVAL_MS }
        if (valid.isNotEmpty()) {
            return TapTempoEstimate((60_000.0 / valid.average()).roundToInt().coerceIn(30, 250))
        }

        val positive = intervalsMs.filter { it > 0L }
        if (positive.isEmpty()) return TapTempoEstimate(null)
        val rawTempo = 60_000.0 / positive.average()
        return TapTempoEstimate(null, if (rawTempo < 30.0) "Lo" else "Hi")
    }
}

data class TapTempoSequenceState(
    val lastTapMs: Long = 0L,
    val tapCount: Int = 0,
    val intervalsMs: List<Long> = emptyList(),
    val estimate: TapTempoEstimate? = null
)

object TapTempoSequence {
    const val TIMEOUT_MS = 2_500L

    fun tap(previous: TapTempoSequenceState, nowMs: Long): TapTempoSequenceState {
        if (previous.lastTapMs <= 0L || nowMs <= previous.lastTapMs ||
            nowMs - previous.lastTapMs > TIMEOUT_MS
        ) {
            return TapTempoSequenceState(lastTapMs = nowMs, tapCount = 1)
        }

        val intervals = (previous.intervalsMs + (nowMs - previous.lastTapMs)).takeLast(4)
        val count = previous.tapCount + 1
        return TapTempoSequenceState(
            lastTapMs = nowMs,
            tapCount = count,
            intervalsMs = intervals,
            estimate = if (count >= 5) TapTempoEstimator.estimate(intervals) else null
        )
    }

    fun hasTimedOut(state: TapTempoSequenceState, nowMs: Long): Boolean =
        state.lastTapMs > 0L && nowMs - state.lastTapMs > TIMEOUT_MS

    fun clearAfterTimeout(state: TapTempoSequenceState): TapTempoSequenceState =
        state.copy(tapCount = 0, intervalsMs = emptyList(), estimate = null)
}
