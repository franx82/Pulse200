package com.rhythmwatch

import android.os.Bundle
import android.os.BatteryManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val Cyan = Color(0xFF36D8FF)
private val LCDInk = Color(0xFF00334E)
private val Silver = Color(0xFFB9BDC2)
private val Labels = Color(0xFFAEB2B8)
private val names = listOf("QUARTER", "EIGHTH", "SIXTEENTH", "TRIPLET", "BEAT", "MASTER")
private const val PROGRAM_COUNT = 30
private const val SOUND_COUNT = 2

class MainActivity : ComponentActivity() {
    private lateinit var notificationPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>
    private var controller by mutableStateOf<MetronomeService?>(null)
    private var serviceBound = false
    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
            controller = (binder as? MetronomeService.LocalBinder)?.controller
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            controller = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationPermissionLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (!granted) {
                android.widget.Toast.makeText(
                    this,
                    "La riproduzione continua; le notifiche sono disattivate.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            RhythmWatchApp(
                controller,
                getSharedPreferences("rhythm_watch", MODE_PRIVATE),
                ::requestNotificationPermission
            )
        }
    }

    override fun onStart() {
        super.onStart()
        serviceBound = bindService(
            android.content.Intent(this, MetronomeService::class.java),
            serviceConnection,
            BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
            controller = null
        }
        super.onStop()
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val preferences = getSharedPreferences("rhythm_watch", MODE_PRIVATE)
        if (!preferences.getBoolean("notification_permission_asked", false)) {
            preferences.edit().putBoolean("notification_permission_asked", true).apply()
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun RhythmWatchApp(controller: MetronomeService?, preferences: android.content.SharedPreferences, requestNotificationPermission: () -> Unit) {
    val context = LocalContext.current
    val fallbackState = remember(preferences) { initialUiState(preferences) }
    val boundState = controller?.state?.collectAsState()
    val uiState = boundState?.value ?: fallbackState
    val bpm = uiState.bpm
    val playing = uiState.playing
    val starting = uiState.starting
    val beatCount = uiState.beatCount
    val sound = uiState.sound
    val program = uiState.program
    val mode = uiState.mode
    val modePending = PlaybackModePolicy.changeAppliesOnNextStart(playing, uiState.mode, uiState.sessionMode)
    val levels = uiState.levels
    var storeConfirmation by remember { mutableStateOf(false) }
    var tapSequence by remember { mutableStateOf(TapTempoSequenceState()) }
    var tempoError by remember { mutableStateOf<String?>(null) }
    var tempoFlashing by remember { mutableStateOf(false) }
    var backlightEnabled by remember { mutableStateOf(true) }
    var backlightAwake by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var batteryPercent by remember { mutableIntStateOf((context.getSystemService(android.content.Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0,100)) }

    fun registerInteraction() {
        lastInteraction = android.os.SystemClock.elapsedRealtime()
        if (backlightEnabled) backlightAwake = true
    }

    fun togglePlayback() {
        registerInteraction()
        if (playing || starting) {
            controller?.stopPlayback()
        } else {
            requestNotificationPermission()
            val serviceIntent = android.content.Intent(context, MetronomeService::class.java)
                .setAction(MetronomeService.ACTION_START)
            ContextCompat.startForegroundService(context, serviceIntent)
            controller?.startPlayback()
        }
    }

    fun activateControl(index: Int) {
        registerInteraction()
        when (index) {
            1 -> controller?.storeProgram()
            2 -> controller?.setSound(sound % SOUND_COUNT + 1)
            3 -> controller?.setMode((mode + 1) % 2)
            4 -> controller?.setBeatCount((beatCount + 1) % 10)
        }
    }

    fun longPressControl(index: Int) {
        if (index == 1) {
            registerInteraction()
            backlightEnabled = !backlightEnabled
            backlightAwake = backlightEnabled
        }
    }

    fun tapTempo() {
        val now = android.os.SystemClock.elapsedRealtime()
        registerInteraction()
        tapSequence = TapTempoSequence.tap(tapSequence, now)
        if (tapSequence.tapCount == 1) {
            tempoError = null
            tempoFlashing = false
        }
        tapSequence.estimate?.let { estimate ->
            tempoError = estimate.error
            estimate.bpm?.let { controller?.setTempo(it) }
            tempoFlashing = false
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) batteryPercent = (level * 100 / scale).coerceIn(0,100)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(tapSequence.lastTapMs) {
        val tapAt = tapSequence.lastTapMs
        if (tapAt > 0L) {
            kotlinx.coroutines.delay(TapTempoSequence.TIMEOUT_MS + 1)
            if (TapTempoSequence.hasTimedOut(tapSequence, android.os.SystemClock.elapsedRealtime())) {
                tapSequence = TapTempoSequence.clearAfterTimeout(tapSequence)
                tempoError = null
                tempoFlashing = true
                kotlinx.coroutines.delay(1_000)
                if (TapTempoSequence.hasTimedOut(tapSequence, android.os.SystemClock.elapsedRealtime())) tempoFlashing = false
            }
        }
    }
    LaunchedEffect(lastInteraction, backlightEnabled) {
        if (backlightEnabled) {
            backlightAwake = true
            val touchedAt = lastInteraction
            kotlinx.coroutines.delay(10_000)
            if (lastInteraction == touchedAt && backlightEnabled) backlightAwake = false
        } else {
            backlightAwake = false
        }
    }
    LaunchedEffect(uiState.storeSequence) {
        if (uiState.storeSequence > 0) {
            storeConfirmation = true
            kotlinx.coroutines.delay(2_000)
            storeConfirmation = false
        }
    }
    LaunchedEffect(uiState.audioError) {
        if (uiState.audioError) {
            android.widget.Toast.makeText(
                context,
                "Uscita audio non disponibile. Tocca START per riprovare.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF050607)) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                val availableWidth = maxWidth
                val compact = maxHeight < 680.dp || availableWidth < 360.dp
                val veryCompact = maxHeight < 480.dp
                val fontScale = LocalDensity.current.fontScale
                val sidePadding = (availableWidth * .035f).coerceIn(10.dp, 22.dp)
                val verticalPadding = if (veryCompact) 3.dp else if (compact) 5.dp else 8.dp
                val sectionGap = if (veryCompact) 3.dp else if (compact) 6.dp else 12.dp
                val tapHeight = if (veryCompact) 48.dp else if (compact) 54.dp else 66.dp
                val baseDisplayHeight = if (veryCompact) 84.dp else if (compact) 108.dp else 132.dp
                val displayHeight = baseDisplayHeight +
                    (if (veryCompact) 50.dp else if (compact) 30.dp else 20.dp) * (fontScale - 1f).coerceAtLeast(0f)
                val transportHeight = when {
                    veryCompact -> 50.dp
                    compact -> (maxHeight * .11f).coerceIn(58.dp, 72.dp)
                    else -> (maxHeight * .105f).coerceIn(72.dp, 92.dp)
                }
                val controlButtonSize = if (veryCompact) 32.dp else if (compact) 38.dp else (availableWidth * .12f).coerceIn(38.dp, 52.dp)
                val compactControlsHeight = maxOf(60.dp, 60.dp + 12.dp * (fontScale - 1f).coerceAtLeast(0f))
                Column(Modifier.widthIn(max = 560.dp).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF08090A), Color(0xFF202226), Color(0xFF08090A)))).padding(horizontal = sidePadding, vertical = verticalPadding), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(if (veryCompact) 1.dp else sectionGap))
                    TapTempoButton(Modifier.fillMaxWidth().height(tapHeight), compact, ::tapTempo)
                    Spacer(Modifier.height(sectionGap))
                    LcdDisplay(bpm, beatCount, sound, program, playing, if(mode == 0) "PRACTICE" else "STAGE", storeConfirmation, tempoError, tempoFlashing, backlightAwake, batteryPercent, uiState.audioError, modePending, displayHeight, compact, veryCompact)
                    Spacer(Modifier.height(if (veryCompact) 3.dp else sectionGap))
                    Row(Modifier.fillMaxWidth().weight(1f).heightIn(min = if (veryCompact) 68.dp else if (compact) 82.dp else 150.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        names.forEachIndexed { i, label ->
                            Fader(label, levels[i], i >= 4, Modifier.weight(1f), compact, veryCompact) {
                                registerInteraction()
                                controller?.setLevel(i, it)
                            }
                        }
                    }
                    Spacer(Modifier.height(if (veryCompact) 4.dp else if (compact) 10.dp else 18.dp))
                    if (veryCompact) {
                        Row(Modifier.fillMaxWidth().height(compactControlsHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            ProgramStepper(Modifier.weight(1.2f), availableWidth, controlButtonSize, compact = true,
                                onPrevious = { registerInteraction(); controller?.selectProgram(if(program == 1) PROGRAM_COUNT else program - 1) },
                                onNext = { registerInteraction(); controller?.selectProgram(program % PROGRAM_COUNT + 1) })
                            listOf("STORE", "SOUND", "MODE", "BEAT").forEachIndexed { item, label ->
                                val index = item + 1
                                ControlButton(label, index, sound, mode, beatCount, controlButtonSize, compact = true,
                                    modifier = Modifier.weight(.62f),
                                    onActivate = { activateControl(index) },
                                    onLongPress = { longPressControl(index) })
                            }
                            Text(when { playing -> "■ STOP"; starting -> "… START"; else -> "▶ START" }, Modifier.weight(1.65f).fillMaxHeight().transportButtonSurface().clickable(role = Role.Button, onClick = ::togglePlayback).wrapContentHeight(Alignment.CenterVertically), color = Silver, textAlign = TextAlign.Center, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = .5.sp)
                            TempoKnob(bpm, Modifier.size(50.dp)) {
                                registerInteraction()
                                tempoError = null
                                tempoFlashing = false
                                controller?.setTempo(it.coerceIn(30, 250))
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            ProgramStepper(Modifier.weight(2f), availableWidth, controlButtonSize,
                                onPrevious = { registerInteraction(); controller?.selectProgram(if(program == 1) PROGRAM_COUNT else program - 1) },
                                onNext = { registerInteraction(); controller?.selectProgram(program % PROGRAM_COUNT + 1) })
                            listOf("STORE", "SOUND", "MODE", "BEAT").forEachIndexed { item, label ->
                                val index = item + 1
                                ControlButton(label, index, sound, mode, beatCount, controlButtonSize, compact,
                                    Modifier.weight(1f),
                                    onActivate = { activateControl(index) },
                                    onLongPress = { longPressControl(index) })
                            }
                        }
                        Spacer(Modifier.height(if (compact) 5.dp else 8.dp))
                        Row(Modifier.fillMaxWidth().height(if (compact) 17.dp else 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.weight(1f))
                            Text("TEMPO", Modifier.width(transportHeight), color = Labels, fontWeight = FontWeight.Bold, fontSize = if (compact) 8.sp else 10.sp, lineHeight = if (compact) 11.sp else 14.sp, textAlign = TextAlign.Center)
                        }
                        Spacer(Modifier.height(if (compact) 3.dp else 5.dp))
                        Row(Modifier.fillMaxWidth().height(transportHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(when { playing -> "■   STOP"; starting -> "…   STARTING"; else -> "▶   START / STOP" }, Modifier.weight(1f).fillMaxHeight().transportButtonSurface().clickable(role = Role.Button, onClick = ::togglePlayback).wrapContentHeight(Alignment.CenterVertically), color = Silver, textAlign = TextAlign.Center, fontSize = if (compact) 14.sp else 18.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                            TempoKnob(bpm, Modifier.size(transportHeight)) {
                                registerInteraction()
                                tempoError = null
                                tempoFlashing = false
                                controller?.setTempo(it.coerceIn(30, 250))
                            }
                        }
                    }
                }
            }
        }
    }
}
}

private fun initialUiState(preferences: android.content.SharedPreferences): MetronomeUiState {
    val defaults = MetronomeUiState()
    val savedLevels = preferences.getString("last_levels", null)
        ?.split(',')
        ?.mapNotNull { raw -> raw.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) }
        ?.takeIf { it.size == 6 }
        ?: defaults.levels
    return defaults.copy(
        bpm = preferences.getInt("last_bpm", defaults.bpm).coerceIn(30, 250),
        beatCount = preferences.getInt("last_beat", defaults.beatCount).coerceIn(0, 9),
        sound = preferences.getInt("last_sound", defaults.sound).coerceIn(1, SOUND_COUNT),
        program = preferences.getInt("last_program", defaults.program).coerceIn(1, PROGRAM_COUNT),
        mode = preferences.getInt("last_mode", defaults.mode).coerceIn(0, 1),
        levels = savedLevels
    )
}

private fun Modifier.transportButtonSurface(): Modifier =
    clip(RoundedCornerShape(15.dp))
        .background(Brush.verticalGradient(listOf(Color(0xFF37393B), Color(0xFF111213))))

@Composable
private fun TapTempoButton(modifier: Modifier, compact: Boolean, onTap: () -> Unit) {
    Column(
        modifier.transportButtonSurface().clickable(role = Role.Button, onClick = onTap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(5) {
                Box(Modifier.size(if (compact) 9.dp else 11.dp).clip(CircleShape).background(Color(0xFF050606)))
            }
        }
        Spacer(Modifier.height(if (compact) 4.dp else 7.dp))
        Text("TAP TEMPO", color = Silver, fontWeight = FontWeight.Black, fontSize = if (compact) 15.sp else 18.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun LcdDisplay(bpm: Int, beat: Int, sound: Int, program: Int, playing: Boolean, modeName: String, storeConfirmation: Boolean, tempoError: String?, tempoFlashing: Boolean, backlightAwake: Boolean, batteryPercent: Int, audioError: Boolean, modePending: Boolean, displayHeight: androidx.compose.ui.unit.Dp, compact: Boolean, veryCompact: Boolean) {
    var flashVisible by remember { mutableStateOf(true) }
    LaunchedEffect(tempoFlashing) {
        flashVisible = true
        while (tempoFlashing) { kotlinx.coroutines.delay(100); flashVisible = !flashVisible }
    }
    val ink = if (backlightAwake) LCDInk else Color(0xFF6E858E)
    val display = if (backlightAwake) Brush.verticalGradient(listOf(Color(0xFF28C9F3), Color(0xFF4AD8FA), Color(0xFF21BEEB))) else Brush.verticalGradient(listOf(Color(0xFF0A2027), Color(0xFF102A32), Color(0xFF08191F)))
    val density = LocalDensity.current
    val titleFontSize = if (veryCompact) 8.sp else if (compact) 9.sp else 10.sp
    val statusFontSize = if (compact) 8.sp else 9.sp
    val titleHeight = maxOf(
        if (veryCompact) 13.dp else if (compact) 15.dp else 18.dp,
        with(density) { titleFontSize.toDp() + 2.dp }
    )
    val statusHeight = maxOf(
        if (veryCompact) 14.dp else if (compact) 16.dp else 18.dp,
        with(density) { statusFontSize.toDp() + 2.dp }
    )
    val mainValueSize = if (veryCompact) 34.sp else if (compact) 45.sp else 57.sp
    val secondaryValueSize = if (veryCompact) 25.sp else if (compact) 30.sp else 37.sp
    Column(Modifier.fillMaxWidth().height(displayHeight).clip(RoundedCornerShape(12.dp)).background(display).padding(horizontal = if (compact) 6.dp else 9.dp, vertical = if (compact) 4.dp else 9.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            LcdField(modeName, program.toString().padStart(2, '0'), secondaryValueSize, titleHeight, titleFontSize, ink, Modifier.weight(1.3f))
            DividerLine(ink)
            val tempoDisplaySize = if (tempoError != null || audioError) {
                if (veryCompact) 31.sp else if (compact) 38.sp else 47.sp
            } else mainValueSize
            LcdField("TEMPO", if (audioError) "Err" else tempoError ?: bpm.toString(), tempoDisplaySize, titleHeight, titleFontSize, ink, Modifier.weight(2.2f), if(tempoFlashing && !flashVisible) 0f else 1f)
            DividerLine(ink)
            LcdField("BEAT", beat.toString(), secondaryValueSize, titleHeight, titleFontSize, ink, Modifier.weight(.9f))
            DividerLine(ink)
            LcdField("SOUND", sound.toString(), secondaryValueSize, titleHeight, titleFontSize, ink, Modifier.weight(.8f))
        }
        Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
        Row(Modifier.fillMaxWidth().height(statusHeight).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Canvas(Modifier.width(12.dp).height(7.dp)) {
                    val stroke = .8.dp.toPx()
                    val bodyWidth = size.width - 1.5.dp.toPx()
                    drawRoundRect(ink, size = Size(bodyWidth, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()), style = Stroke(stroke))
                    drawRect(ink, Offset(bodyWidth, size.height * .30f), Size(1.5.dp.toPx(), size.height * .40f))
                    val inset = 1.5.dp.toPx()
                    drawRect(ink, Offset(inset, inset), Size((bodyWidth - inset * 2) * batteryPercent / 100f, size.height - inset * 2))
                }
                Text("${batteryPercent}%", color = ink, fontSize = statusFontSize, fontWeight = FontWeight.SemiBold)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                when {
                    storeConfirmation -> Text("✓ STORED", color = ink, fontSize = statusFontSize, fontWeight = FontWeight.Bold, maxLines = 1)
                    modePending -> Text("MODE NEXT START", color = ink, fontSize = statusFontSize, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                Canvas(Modifier.size(7.dp)) {
                    if (playing) {
                        drawPath(Path().apply { moveTo(0f, 0f); lineTo(size.width, size.height / 2); lineTo(0f, size.height); close() }, ink)
                    } else {
                        drawRect(ink)
                    }
                }
                Text(if (audioError) "ERROR" else if (playing) "PLAY" else "STOP", color = ink, fontSize = statusFontSize, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun LcdField(title: String, value: String, valueSize: androidx.compose.ui.unit.TextUnit, titleHeight: androidx.compose.ui.unit.Dp, titleFontSize: androidx.compose.ui.unit.TextUnit, ink: Color, modifier: Modifier, valueAlpha: Float = 1f) {
    Column(modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(titleHeight), contentAlignment = Alignment.Center) {
            Text(title, color = ink, fontSize = titleFontSize, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            SegmentNumber(value, valueSize, ink, valueAlpha)
        }
    }
}

@Composable
private fun DividerLine(ink: Color) {
    Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 3.dp).background(ink.copy(alpha = .35f)))
}

@Composable
private fun SegmentNumber(value: String, size: androidx.compose.ui.unit.TextUnit, color: Color = LCDInk, alpha: Float = 1f) {
    Text(value, modifier = Modifier.alpha(alpha), color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = size, letterSpacing = (-3).sp, maxLines = 1)
}

@Composable
private fun HardwareButton(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension * .47f
        drawCircle(Color.Black.copy(alpha = .55f), radius, center + Offset(0f, 2.dp.toPx()))
        drawCircle(Brush.verticalGradient(listOf(Color(0xFF626A71), Color(0xFF343C43), Color(0xFF242C32))), radius, center)
        drawCircle(Color(0xFF0C1115), radius - 2.dp.toPx(), center)
        val faceRadius = radius - 4.dp.toPx()
        drawCircle(Brush.verticalGradient(listOf(Color(0xFF30373D), Color(0xFF1B2228), Color(0xFF11171C))), faceRadius, center)
        drawCircle(Color(0xFF747F88).copy(alpha = .35f), radius - .5.dp.toPx(), center, style = Stroke(.7.dp.toPx()))
        drawArc(Color(0xFFB3BDC5).copy(alpha = .27f), 210f, 120f, false,
            Offset(center.x - faceRadius, center.y - faceRadius), Size(faceRadius * 2, faceRadius * 2), style = Stroke(.8.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun ProgramStepper(modifier: Modifier, width: androidx.compose.ui.unit.Dp, buttonSize: androidx.compose.ui.unit.Dp, compact: Boolean = false, onPrevious: () -> Unit, onNext: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("PROGRAM", color = Labels, fontSize = if (compact) 7.sp else (width.value * .022f).coerceIn(7f, 9f).sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(if (compact) 3.dp else 8.dp))
        Row(Modifier.fillMaxWidth()) {
        listOf("Program precedente" to onPrevious, "Program successivo" to onNext).forEach { (description, action) ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickable(role = Role.Button, onClick = action).semantics {
                        contentDescription = description
                    }, contentAlignment = Alignment.Center) {
                        HardwareButton(Modifier.size(buttonSize))
                    }
                }
            }
        }
        if (!compact) {
            Row(Modifier.fillMaxWidth()) {
                Text("−", color = Silver, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text("+", color = Silver, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ControlButton(
    label: String,
    index: Int,
    sound: Int,
    mode: Int,
    beatCount: Int,
    buttonSize: androidx.compose.ui.unit.Dp,
    compact: Boolean,
    modifier: Modifier,
    onActivate: () -> Unit,
    onLongPress: () -> Unit
) {
    val latestActivate by rememberUpdatedState(onActivate)
    val latestLongPress by rememberUpdatedState(onLongPress)
    Column(
        modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).pointerInput(index, sound, mode, beatCount) {
            detectTapGestures(onTap = { latestActivate() }, onLongPress = { latestLongPress() })
        }.semantics {
            contentDescription = label
            role = Role.Button
            when (index) {
                2 -> stateDescription = "Sound $sound"
                3 -> stateDescription = if (mode == 0) "Practice" else "Stage"
                4 -> stateDescription = "$beatCount beats per bar"
            }
            onClick { latestActivate(); true }
            if (index == 1) onLongClick { latestLongPress(); true }
        },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = Labels, fontSize = if (compact) 6.sp else 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(if (compact) 2.dp else 8.dp))
        HardwareButton(Modifier.size(buttonSize))
    }
}

@Composable
private fun Fader(label: String, value: Float, accent: Boolean, modifier: Modifier, compact: Boolean, veryCompact: Boolean, onChange: (Float) -> Unit) {
    val active = if(accent) Color(0xFF3EE0FF) else Color(0xFF13C7FF)
    val density = LocalDensity.current
    val labelFontSize = if (compact) 6.sp else 8.sp
    val valueFontSize = if (compact) 10.sp else 12.sp
    val labelHeight = maxOf(
        if (veryCompact) 11.dp else if (compact) 12.dp else 14.dp,
        with(density) { labelFontSize.toDp() + 2.dp }
    )
    val valueHeight = maxOf(
        if (compact) 14.dp else 18.dp,
        with(density) { valueFontSize.toDp() + 2.dp }
    )
    val displayLabel = if (compact) when (label) {
        "QUARTER" -> "QUART"
        "EIGHTH" -> "8TH"
        "SIXTEENTH" -> "16TH"
        "TRIPLET" -> "TRIP"
        else -> label
    } else label
    Column(modifier.fillMaxHeight().clip(RoundedCornerShape(9.dp)).background(Brush.verticalGradient(listOf(Color(0xFF25272A), Color(0xFF111214)))).padding(vertical = if (veryCompact) 3.dp else if (compact) 5.dp else 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(labelHeight), contentAlignment = Alignment.Center) {
            Text(displayLabel, Modifier.padding(horizontal = 2.dp), color = Labels, fontSize = labelFontSize, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(if (veryCompact) 18.dp else if (compact) 24.dp else 32.dp), contentAlignment = Alignment.Center) {
            if (label != "BEAT") FaderIcon(label, Modifier.size(if (veryCompact) 18.dp else if (compact) 22.dp else 28.dp))
        }
        VerticalFader(label, value, active, Modifier.weight(1f).fillMaxWidth(), compact, veryCompact) { onChange(it) }
        Box(Modifier.fillMaxWidth().height(valueHeight), contentAlignment = Alignment.Center) {
            Text("${(value*100).roundToInt()}", color = Silver, fontFamily = FontFamily.Monospace, fontSize = valueFontSize, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FaderIcon(label: String, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val unit = size.minDimension / 32f
        withTransform({ scale(unit, unit, pivot = Offset.Zero) }) {
            fun noteHead(x: Float) {
                rotate(-15f, Offset(x, 25f)) {
                    drawOval(Silver, Offset(x - 3.3f, 22.8f), Size(6.6f, 4.4f))
                }
            }
            when (label) {
                "QUARTER" -> {
                    noteHead(14f)
                    drawLine(Silver, Offset(17f, 24.5f), Offset(17f, 6f), 1.5f)
                }
                "EIGHTH", "SIXTEENTH", "TRIPLET" -> {
                    val triplet = label == "TRIPLET"
                    val xs = if (triplet) listOf(5f, 15.5f, 26f) else listOf(7f, 22f)
                    val top = if (triplet) 13f else 7f
                    xs.forEach { x ->
                        noteHead(x)
                        drawLine(Silver, Offset(x + 3f, 24.5f), Offset(x + 3f, top), 1.5f)
                    }
                    drawLine(Silver, Offset(xs.first() + 3f, top), Offset(xs.last() + 3f, top), 1.8f)
                    if (label == "SIXTEENTH") drawLine(Silver, Offset(xs.first() + 3f, top + 4f), Offset(xs.last() + 3f, top + 4f), 1.8f)
                    if (triplet) {
                        val three = Path().apply {
                            moveTo(13.5f, 3.5f)
                            cubicTo(19.5f, 1f, 20.5f, 7f, 16f, 7f)
                            cubicTo(21f, 7f, 20f, 12f, 13.5f, 10f)
                        }
                        drawPath(three, Silver, style = Stroke(1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                "MASTER" -> {
                    drawPath(Path().apply {
                        moveTo(5f, 13f); lineTo(10f, 13f); lineTo(17f, 8f)
                        lineTo(17f, 26f); lineTo(10f, 21f); lineTo(5f, 21f); close()
                    }, Silver)
                    val waves = Path().apply {
                        moveTo(21f, 12f); cubicTo(24f, 15f, 24f, 19f, 21f, 22f)
                        moveTo(25f, 8f); cubicTo(31f, 13f, 31f, 21f, 25f, 26f)
                    }
                    drawPath(waves, Silver, style = Stroke(1.5f, cap = StrokeCap.Round))
                }
            }
        }
    }
}

@Composable
private fun VerticalFader(label: String, value: Float, color: Color, modifier: Modifier, compact: Boolean, veryCompact: Boolean, onChange: (Float) -> Unit) {
    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onChange)
    val knobHalfDp = if (veryCompact) 13.dp else if (compact) 16.dp else 20.dp
    val knobHalfPx = with(LocalDensity.current) { knobHalfDp.toPx() }
    fun knobMargin(height: Float) = kotlin.math.min(knobHalfPx, height * .32f)
    fun valueAt(y: Float, height: Float): Float {
        val margin = knobMargin(height)
        val top = margin
        val bottom = (height - margin).coerceAtLeast(top + 1f)
        return (1f - (y - top) / (bottom - top)).coerceIn(0f, 1f)
    }
    Canvas(modifier.semantics {
        contentDescription = "$label volume"
        stateDescription = "${(value * 100).roundToInt()} percent"
        progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
        setProgress { target -> latestOnChange(target.coerceIn(0f, 1f)); true }
    }.pointerInput(Unit) {
        detectTapGestures(
            onTap = { position ->
                latestOnChange(valueAt(position.y, size.height.toFloat()))
            },
            onDoubleTap = { latestOnChange(0f) }
        )
    }.pointerInput(Unit) {
        detectDragGestures(
            onDragStart = { position ->
                latestOnChange(valueAt(position.y, size.height.toFloat()))
            },
            onDrag = { change, _ ->
                latestOnChange(valueAt(change.position.y, size.height.toFloat()))
                change.consume()
            }
        )
    }) {
        val trackW = size.width * .42f
        val x = (size.width-trackW)/2
        val margin = knobMargin(size.height)
        val top = margin
        val bottom = (size.height-margin).coerceAtLeast(top+1f)
        drawRoundRect(Color(0xFF030405), Offset(x,top), Size(trackW,bottom-top), cornerRadius=androidx.compose.ui.geometry.CornerRadius(trackW/2))
        val y=value*(bottom-top)
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF0877B6),color,Color(0xFF0877B6))), Offset(x+trackW*.26f,bottom-y), Size(trackW*.48f,y), cornerRadius=androidx.compose.ui.geometry.CornerRadius(trackW/3))
        for(i in 1..9) {
            val ty=top+(bottom-top)*i/10
            drawLine(Color(0xFF777B7F), Offset(0f,ty), Offset(size.width*.21f,ty), 1.dp.toPx())
            drawLine(Color(0xFF777B7F), Offset(size.width*.79f,ty), Offset(size.width,ty), 1.dp.toPx())
        }
        val knobY=bottom-(bottom-top)*value
        val knobHeight = margin * 2
        drawRoundRect(Color(0xFF030405), Offset(size.width*.20f,knobY-margin), Size(size.width*.60f,knobHeight), cornerRadius=androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()))
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF777A7D),Color(0xFF17191B),Color(0xFF44474A))), Offset(size.width*.24f,knobY-margin*.9f), Size(size.width*.52f,knobHeight*.9f), cornerRadius=androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
        val grooveStep = knobHeight / 5
        for(i in -1..2) drawLine(Color(0xFF9EA2A6), Offset(size.width*.29f,knobY+i*grooveStep), Offset(size.width*.71f,knobY+i*grooveStep), 1.dp.toPx())
    }
}

@Composable
private fun TempoKnob(value: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onChange)
    val density = LocalDensity.current.density
    Box(modifier.semantics {
            contentDescription = "Tempo"
            stateDescription = "$value BPM"
            progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 30f..250f, 219)
            setProgress { target -> latestOnChange(target.roundToInt().coerceIn(30, 250)); true }
        }.pointerInput(Unit) {
            var dragValue = latestValue.toFloat()
            var lastDragTime = 0L
            detectDragGestures(
                onDragStart = { dragValue = latestValue.toFloat(); lastDragTime = android.os.SystemClock.uptimeMillis() },
                onDrag = { change, drag ->
                    change.consume()
                    val elapsedMs = (change.uptimeMillis - lastDragTime).coerceAtLeast(1L)
                    lastDragTime = change.uptimeMillis
                    val speedDpPerSecond = kotlin.math.abs(drag.y / density) * 1000f / elapsedMs
                    val sensitivity = when {
                        speedDpPerSecond < 100f -> .30f
                        speedDpPerSecond < 500f -> .30f + (speedDpPerSecond - 100f) / 400f * 1.20f
                        else -> 1.5f
                    }
                    dragValue = (dragValue - drag.y / density * sensitivity).coerceIn(30f, 250f)
                    latestOnChange(dragValue.roundToInt())
                }
            )
        }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val c=Offset(size.width/2,size.height/2); val r=size.minDimension*.45f
                drawCircle(Color(0xFF050607),r,c)
                drawCircle(Brush.verticalGradient(listOf(Color(0xFF66696C),Color(0xFF191B1D),Color(0xFF070809))),r*.88f,c)
                for(i in 0..20) {
                    val a=(-220+i*13.5)*PI/180.0
                    val p1=Offset(c.x+cos(a).toFloat()*r*.91f,c.y+sin(a).toFloat()*r*.91f)
                    val p2=Offset(c.x+cos(a).toFloat()*r*.99f,c.y+sin(a).toFloat()*r*.99f)
                    drawLine(Color(0xFF9EA1A4),p1,p2,2.dp.toPx())
                }
                val fraction=(value-30)/220f; val a=(-220+fraction*270)*PI/180
                val indicator=Offset(c.x+cos(a).toFloat()*r*.79f,c.y+sin(a).toFloat()*r*.79f)
                drawCircle(Color(0xFF050607),5.dp.toPx(),indicator)
                drawCircle(Cyan,3.5.dp.toPx(),indicator)
            }
            Text(value.toString(), color = Silver, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
}

