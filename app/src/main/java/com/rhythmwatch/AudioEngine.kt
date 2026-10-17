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
    private external fun nativeSetBeat(beats: Int)
    private external fun nativeSetSound(sound: Int)
    private external fun nativeSetMode(mode: Int)
    private external fun nativeConfigure(bpm: Double, levels: FloatArray)
    fun start(): Boolean = nativeStart()
    @Synchronized fun play(playing: Boolean): Boolean {
        if (!playing) {
            nativePlay(false)
            return true
        }
        if (!nativeStart()) return false
        nativePlay(true)
        return true
    }
    fun reset() = nativeReset()
    fun consumeStageComplete(): Boolean = nativeConsumeStageComplete()
    fun setBeat(beats: Int) = nativeSetBeat(beats)
    fun setSound(sound: Int) = nativeSetSound(sound)
    fun setMode(mode: Int) = nativeSetMode(mode)
    fun configure(bpm: Int, levels: List<Float>) = nativeConfigure(bpm.toDouble(), levels.toFloatArray())
    @Synchronized override fun close() {
        nativeStop()
    }
}
