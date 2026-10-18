package com.rhythmwatch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

data class MetronomeUiState(
    val bpm: Int = 120,
    val playing: Boolean = false,
    val starting: Boolean = false,
    val beatCount: Int = 4,
    val sound: Int = 1,
    val program: Int = 1,
    val mode: Int = 0,
    val sessionMode: Int? = null,
    val levels: List<Float> = listOf(.80f, .55f, .90f, .40f, 1f, 1f),
    val storeSequence: Int = 0,
    val audioError: Boolean = false
)

class MetronomeService : Service() {
    private enum class EngineAction { START, STOP }
    private data class EngineCommand(val action: EngineAction, val request: Long)

    inner class LocalBinder : Binder() {
        val controller: MetronomeService get() = this@MetronomeService
    }

    private val binder = LocalBinder()
    // The metronome is designed to mix with backing tracks, so it deliberately does not claim audio focus.
    private val engine = AudioEngine()
    private val mutableState = MutableStateFlow(MetronomeUiState())
    val state = mutableState.asStateFlow()
    private val commandQueue = Channel<EngineCommand>(Channel.UNLIMITED)
    private val playbackRequest = AtomicLong(0)
    private val engineDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Pulse200-AudioController").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var preferences: android.content.SharedPreferences? = null
    private var foreground = false
    private var activeRequest = 0L

    override fun onCreate() {
        super.onCreate()
        preferences = getSharedPreferences("rhythm_watch", MODE_PRIVATE)
        mutableState.value = loadInitialState()

        serviceScope.launch(engineDispatcher) {
            for (command in commandQueue) {
                when (command.action) {
                    EngineAction.START -> startEngine(command.request)
                    EngineAction.STOP -> stopEngine(command.request)
                }
            }
        }
        serviceScope.launch(engineDispatcher) {
            mutableState.collect { current ->
                engine.configure(current.bpm, current.levels)
                engine.setBeat(current.beatCount)
                engine.setSound(current.sound)
                engine.setMode(current.sessionMode ?: current.mode)
            }
        }
        serviceScope.launch {
            mutableState.collect { current ->
                if (foreground) updateNotification(current)
            }
        }
        serviceScope.launch {
            mutableState.collectLatest { current ->
                delay(250)
                preferences?.edit()
                    ?.putInt("last_bpm", current.bpm)
                    ?.putInt("last_beat", current.beatCount)
                    ?.putInt("last_sound", current.sound)
                    ?.putInt("last_program", current.program)
                    ?.putInt("last_mode", current.mode)
                    ?.putString("last_levels", current.levels.joinToString(","))
                    ?.apply()
            }
        }
        serviceScope.launch(engineDispatcher) {
            var lastDiagnosticsAt = 0L
            while (true) {
                delay(25)
                when {
                    engine.consumeStageComplete() -> finishStage(playbackRequest.get())
                    engine.consumeStreamFailure() -> reportAudioFailure(playbackRequest.get())
                }
                if (BuildConfig.DEBUG && mutableState.value.playing) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastDiagnosticsAt >= 5_000L) {
                        android.util.Log.d("Pulse200Audio", engine.diagnostics())
                        lastDiagnosticsAt = now
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundNotification()
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    fun setTempo(bpm: Int) {
        mutableState.update { it.copy(bpm = bpm.coerceIn(30, 250), audioError = false) }
    }

    fun setBeatCount(beats: Int) {
        mutableState.update { it.copy(beatCount = beats.coerceIn(0, 9)) }
    }

    fun setSound(sound: Int) {
        mutableState.update { it.copy(sound = sound.coerceIn(1, 2)) }
    }

    fun setMode(mode: Int) {
        mutableState.update { it.copy(mode = mode.coerceIn(0, 1)) }
    }

    fun setLevel(index: Int, value: Float) {
        if (index !in 0..5 || !value.isFinite()) return
        mutableState.update { current ->
            current.copy(levels = current.levels.toMutableList().also {
                it[index] = value.coerceIn(0f, 1f)
            })
        }
    }

    fun selectProgram(program: Int) {
        val selected = program.coerceIn(1, PROGRAM_COUNT)
        val preset = loadProgram(selected) ?: RhythmProgram(120, 4)
        mutableState.update {
            it.copy(program = selected, bpm = preset.bpm, beatCount = preset.beat)
        }
    }

    fun storeProgram() {
        val current = mutableState.value
        preferences?.edit()
            ?.putString("program_${current.program}", RhythmProgram(current.bpm, current.beatCount).encode())
            ?.apply()
        mutableState.update { it.copy(storeSequence = it.storeSequence + 1) }
    }

    fun startPlayback() {
        val request = playbackRequest.incrementAndGet()
        mutableState.update { it.copy(playing = false, starting = true, audioError = false) }
        commandQueue.trySend(EngineCommand(EngineAction.START, request))
    }

    fun stopPlayback() {
        val request = playbackRequest.incrementAndGet()
        mutableState.update { it.copy(playing = false, starting = false, sessionMode = null) }
        commandQueue.trySend(EngineCommand(EngineAction.STOP, request))
    }

    override fun onDestroy() {
        runCatching {
            kotlinx.coroutines.runBlocking {
                withContext(engineDispatcher) {
                    engine.play(false)
                    engine.close()
                }
            }
        }
        serviceScope.cancel()
        commandQueue.close()
        engineDispatcher.close()
        super.onDestroy()
    }

    private suspend fun startEngine(request: Long) {
        if (request != playbackRequest.get()) return
        val current = mutableState.value
        val sessionMode = PlaybackModePolicy.modeForNewSession(current.mode)
        engine.configure(current.bpm, current.levels)
        engine.setBeat(current.beatCount)
        engine.setSound(current.sound)
        engine.setMode(sessionMode)
        if (engine.play(true)) {
            activeRequest = request
            if (request == playbackRequest.get()) {
                mutableState.update { it.copy(playing = true, starting = false, sessionMode = sessionMode, audioError = false) }
            }
        } else {
            engine.close()
            if (request == playbackRequest.get()) {
                mutableState.update { it.copy(playing = false, starting = false, sessionMode = null, audioError = true) }
                stopForegroundService(request)
            }
        }
    }

    private suspend fun stopEngine(request: Long) {
        engine.play(false)
        engine.close()
        if (request == playbackRequest.get()) {
            activeRequest = 0
            mutableState.update { it.copy(playing = false, starting = false, sessionMode = null) }
            stopForegroundService(request)
        }
    }

    private suspend fun finishStage(request: Long) {
        if (request == 0L || request != activeRequest || request != playbackRequest.get()) return
        engine.play(false)
        engine.close()
        if (request != playbackRequest.get()) return
        activeRequest = 0
        val nextProgram = mutableState.value.program % PROGRAM_COUNT + 1
        val next = loadProgram(nextProgram) ?: RhythmProgram(120, 4)
        mutableState.update {
            it.copy(
                playing = false,
                starting = false,
                sessionMode = null,
                program = nextProgram,
                bpm = next.bpm,
                beatCount = next.beat
            )
        }
        stopForegroundService(request)
    }

    private suspend fun reportAudioFailure(request: Long) {
        if (request == 0L || request != activeRequest || request != playbackRequest.get()) return
        engine.play(false)
        engine.close()
        if (request != playbackRequest.get()) return
        activeRequest = 0
        mutableState.update { it.copy(playing = false, starting = false, sessionMode = null, audioError = true) }
        stopForegroundService(request)
    }

    private suspend fun stopForegroundService(request: Long) {
        withContext(Dispatchers.Main.immediate) {
            if (request == playbackRequest.get() && !mutableState.value.playing &&
                !mutableState.value.starting && foreground
            ) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foreground = false
                stopSelf()
            }
        }
    }

    private fun startForegroundNotification() {
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(mutableState.value),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(mutableState.value))
        }
        foreground = true
    }

    private fun updateNotification(current: MetronomeUiState) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(current))
    }

    private fun buildNotification(current: MetronomeUiState) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                when {
                    current.audioError -> "Uscita audio non disponibile"
                    current.playing -> "In riproduzione · ${current.bpm} BPM"
                    current.starting -> "Avvio…"
                    else -> "In pausa"
                }
            )
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOngoing(current.playing || current.starting)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "STOP",
                PendingIntent.getService(
                    this, 1, Intent(this, MetronomeService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Metronomo", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun loadInitialState(): MetronomeUiState {
        val prefs = preferences ?: return MetronomeUiState()
        val levels = prefs.getString("last_levels", null)
            ?.split(',')
            ?.takeIf { it.size == 6 }
            ?.mapNotNull { it.toFloatOrNull()?.takeIf(Float::isFinite)?.coerceIn(0f, 1f) }
            ?.takeIf { it.size == 6 }
            ?: MetronomeUiState().levels
        return MetronomeUiState(
            bpm = prefs.getInt("last_bpm", 120).coerceIn(30, 250),
            beatCount = prefs.getInt("last_beat", 4).coerceIn(0, 9),
            sound = prefs.getInt("last_sound", 1).coerceIn(1, 2),
            program = prefs.getInt("last_program", 1).coerceIn(1, PROGRAM_COUNT),
            mode = prefs.getInt("last_mode", 0).coerceIn(0, 1),
            levels = levels
        )
    }

    private fun loadProgram(slot: Int): RhythmProgram? =
        RhythmProgram.decode(preferences?.getString("program_$slot", null))

    private data class RhythmProgram(val bpm: Int, val beat: Int) {
        fun encode() = "$bpm|$beat"

        companion object {
            fun decode(raw: String?): RhythmProgram? = runCatching {
                val parts = raw!!.split('|')
                require(parts.size >= 2)
                RhythmProgram(parts[0].toInt().coerceIn(30, 250), parts[1].toInt().coerceIn(0, 9))
            }.getOrNull()
        }
    }

    companion object {
        const val ACTION_START = "com.rhythmwatch.action.START"
        const val ACTION_STOP = "com.rhythmwatch.action.STOP"
        const val CHANNEL_ID = "rhythm_watch_playback"
        const val NOTIFICATION_ID = 101
        private const val PROGRAM_COUNT = 30
    }
}
