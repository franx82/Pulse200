package com.rhythmwatch

import java.io.Closeable

/** Thin JNI facade. Audio is rendered on Oboe's low-latency native callback, never on UI timers. */
class AudioEngine : Closeable {
    companion object { init { System.loadLibrary("rhythmwatch") } }
    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativePlay(playing: Boolean)
    private external fun nativeReset()
    private external fun nativeConsumeStageComplete(): Boolean
    private external fun nativeConsumeStreamFailure(): Boolean
    private external fun nativeIsPlaying(): Boolean
    private external fun nativeDiagnostics(): String
    private external fun nativeSetBeat(beats: Int)
    private external fun nativeSetSound(sound: Int)
    private external fun nativeSetMode(mode: Int)
    private external fun nativeConfigure(bpm: Double, levels: FloatArray)
    @Synchronized fun start(): Boolean = nativeStart()
    @Synchronized fun play(playing: Boolean): Boolean {
        if (!playing) {
            nativePlay(false)
            return true
        }
        if (!nativeStart()) return false
        nativeReset()
        nativePlay(true)
        return true
    }
    @Synchronized fun reset() = nativeReset()
    @Synchronized fun consumeStageComplete(): Boolean = nativeConsumeStageComplete()
    @Synchronized fun consumeStreamFailure(): Boolean = nativeConsumeStreamFailure()
    @Synchronized fun isPlaying(): Boolean = nativeIsPlaying()
    @Synchronized fun diagnostics(): String = nativeDiagnostics()
    @Synchronized fun setBeat(beats: Int) = nativeSetBeat(beats)
    @Synchronized fun setSound(sound: Int) = nativeSetSound(sound)
    @Synchronized fun setMode(mode: Int) = nativeSetMode(mode)
    @Synchronized fun configure(bpm: Int, levels: List<Float>) {
        if (levels.size == 6 && levels.all(Float::isFinite)) {
            nativeConfigure(bpm.toDouble(), levels.toFloatArray())
        }
    }
    @Synchronized override fun close() {
        nativePlay(false)
        nativeStop()
    }
}
