package com.rhythmwatch

import android.os.Bundle
import android.os.BatteryManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    private var engine: AudioEngine? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = AudioEngine()
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { RhythmWatchApp(engine!!, getSharedPreferences("rhythm_watch", MODE_PRIVATE)) }
    }
}

private data class RhythmProgram(val bpm: Int, val beat: Int, val sound: Int) {
    fun encode() = listOf(bpm, beat, sound).joinToString("|")
    companion object {
        fun decode(raw: String?): RhythmProgram? = runCatching {
            val parts = raw!!.split('|')
            require(parts.size >= 3)
            RhythmProgram(parts[0].toInt().coerceIn(30,250), parts[1].toInt().coerceIn(0,9), parts[2].toInt().coerceIn(1,SOUND_COUNT))
        }.getOrNull()
    }
}

@Composable
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun RhythmWatchApp(engine: AudioEngine, preferences: android.content.SharedPreferences) {
    val context = LocalContext.current
    val playbackScope = rememberCoroutineScope()
    val audioDispatcher = remember { Dispatchers.IO.limitedParallelism(1) }
    val playbackRequest = remember { AtomicInteger(0) }
    var bpm by remember { mutableIntStateOf(preferences.getInt("last_bpm",120).coerceIn(30,250)) }
    var playing by remember { mutableStateOf(false) }
    var beatCount by remember { mutableIntStateOf(preferences.getInt("last_beat",4).coerceIn(0,9)) }
    var sound by remember { mutableIntStateOf(preferences.getInt("last_sound",1).coerceIn(1,SOUND_COUNT)) }
    var program by remember { mutableIntStateOf(preferences.getInt("last_program",1).coerceIn(1,PROGRAM_COUNT)) }
    var mode by remember { mutableIntStateOf(preferences.getInt("last_mode",0).coerceIn(0,1)) }
    var storeConfirmation by remember { mutableStateOf(false) }
    var storeSequence by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }
    var tapCount by remember { mutableIntStateOf(0) }
    var tempoError by remember { mutableStateOf<String?>(null) }
    var tempoFlashing by remember { mutableStateOf(false) }
    var backlightEnabled by remember { mutableStateOf(true) }
    var backlightAwake by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var batteryPercent by remember { mutableIntStateOf((context.getSystemService(android.content.Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0,100)) }
    val tapIntervals = remember { mutableStateListOf<Long>() }
    val programs = remember { mutableStateListOf<RhythmProgram?>(*Array(PROGRAM_COUNT) { null }).also { slots ->
        repeat(PROGRAM_COUNT) { slots[it] = RhythmProgram.decode(preferences.getString("program_${it+1}", null)) }
    } }
    val levels = remember { mutableStateListOf(.80f, .55f, .90f, .40f, 1f, 1f).also { current ->
        preferences.getString("last_levels", null)?.split(',')?.takeIf { it.size == 6 }?.forEachIndexed { i, value -> runCatching { current[i] = value.toFloat().coerceIn(0f,1f) } }
    } }

    fun selectProgram(next: Int) {
        program = next.coerceIn(1,PROGRAM_COUNT)
        val selected = programs[program-1] ?: RhythmProgram(120,4,1)
        bpm = selected.bpm; beatCount = selected.beat; sound = selected.sound
    }

    fun storeProgram() {
        val saved = RhythmProgram(bpm, beatCount, sound)
        programs[program-1] = saved
        preferences.edit().putString("program_$program", saved.encode()).apply()
        storeSequence++
    }

    fun registerInteraction() {
        lastInteraction = android.os.SystemClock.elapsedRealtime()
        if (backlightEnabled) backlightAwake = true
    }

    fun tapTempo() {
        val now = android.os.SystemClock.elapsedRealtime()
        registerInteraction()
        if (lastTap == 0L || now - lastTap > 2_500L) {
            tapIntervals.clear()
            tapCount = 1
            lastTap = now
            tempoError = null
            tempoFlashing = false
            return
        }
        val interval = now - lastTap
        lastTap = now
        tapCount++
        tapIntervals.add(interval)
        while (tapIntervals.size > 4) tapIntervals.removeAt(0)
        if (tapCount >= 5 && tapIntervals.isNotEmpty()) {
            val measured = tapIntervals.map { 60_000.0 / it }.average()
            tempoError = when {
                measured < 30.0 -> "Lo"
                measured > 250.0 -> "Hi"
                else -> null
            }
            bpm = measured.roundToInt().coerceIn(30,250)
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

    LaunchedEffect(engine) { engine.configure(bpm, levels.toList()); engine.setBeat(beatCount) }
    LaunchedEffect(bpm, levels.toList()) { engine.configure(bpm, levels.toList()) }
    LaunchedEffect(beatCount) { engine.setBeat(beatCount) }
    LaunchedEffect(sound) { engine.setSound(sound) }
    LaunchedEffect(mode) { engine.setMode(mode) }
    LaunchedEffect(lastTap) {
        val tapAt = lastTap
        if (tapAt > 0L) {
            kotlinx.coroutines.delay(2_500)
            if (lastTap == tapAt) {
                tapCount = 0
                tapIntervals.clear()
                tempoError = null
                tempoFlashing = true
                kotlinx.coroutines.delay(1_000)
                if (lastTap == tapAt) tempoFlashing = false
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
    LaunchedEffect(engine, playing, program, mode) {
        while (playing) {
            kotlinx.coroutines.delay(25)
            if (engine.consumeStageComplete()) {
                withContext(audioDispatcher) { engine.close() }
                playing = false
                context.stopService(android.content.Intent(context, MetronomeService::class.java).setAction(MetronomeService.ACTION_STOP))
                val stageMode = mode
                selectProgram(program % PROGRAM_COUNT + 1)
                mode = stageMode
                break
            }
        }
    }
    LaunchedEffect(storeSequence) {
        if (storeSequence > 0) {
            storeConfirmation = true
            kotlinx.coroutines.delay(2_000)
            storeConfirmation = false
        }
    }
    LaunchedEffect(bpm, beatCount, sound, program, mode, levels.toList()) {
        preferences.edit().putInt("last_bpm", bpm).putInt("last_beat", beatCount)
            .putInt("last_sound", sound).putInt("last_program", program).putInt("last_mode", mode)
            .putString("last_levels", levels.joinToString(",")).apply()
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF050607)) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                val availableWidth = maxWidth
                val sidePadding = (availableWidth * .035f).coerceIn(10.dp, 22.dp)
                val transportHeight = (maxHeight * .105f).coerceIn(72.dp, 92.dp)
                Column(Modifier.widthIn(max = 560.dp).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF08090A), Color(0xFF202226), Color(0xFF08090A)))).padding(horizontal = sidePadding, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pulse200Logo(Modifier.weight(1f).padding(vertical = 8.dp), (availableWidth.value * .06f).coerceIn(18f, 25.2f).sp)
                        TapTempoBank(::tapTempo)
                    }
                    Spacer(Modifier.height(12.dp))
                    LcdDisplay(bpm, beatCount, sound, program, playing, if(mode == 0) "PRACTICE" else "STAGE", storeConfirmation, tempoError, tempoFlashing, backlightAwake, batteryPercent)
                    Spacer(Modifier.height(9.dp))
                    Row(Modifier.fillMaxWidth().weight(1f).heightIn(min = 150.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        names.forEachIndexed { i, label ->
                            Fader(label, levels[i], i >= 4, Modifier.weight(1f)) { registerInteraction(); levels[i] = it }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        listOf("PROGRAM", "STORE", "SOUND", "MODE", "BEAT").forEachIndexed { index, label ->
                            if (index == 0) {
                                ProgramStepper(Modifier.weight(2f), availableWidth, (availableWidth * .12f).coerceIn(38.dp, 52.dp),
                                    onPrevious = { registerInteraction(); selectProgram(if(program == 1) PROGRAM_COUNT else program - 1) },
                                    onNext = { registerInteraction(); selectProgram(program % PROGRAM_COUNT + 1) })
                            } else {
                                Column(Modifier.weight(1f).pointerInput(index, sound, mode, beatCount, backlightEnabled) {
                                    detectTapGestures(
                                        onTap = {
                                            registerInteraction()
                                            when(index) {
                                                1 -> storeProgram()
                                                2 -> sound = sound % SOUND_COUNT + 1
                                                3 -> mode = (mode + 1) % 2
                                                4 -> beatCount = (beatCount + 1) % 10
                                            }
                                        },
                                        onLongPress = {
                                            if (index == 1) {
                                                registerInteraction()
                                                backlightEnabled = !backlightEnabled
                                                backlightAwake = backlightEnabled
                                            }
                                        }
                                    )
                                }, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(label, color = Labels, fontSize = (availableWidth.value * .022f).coerceIn(7f, 9f).sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Spacer(Modifier.height(8.dp))
                                    val buttonSize = (availableWidth * .12f).coerceIn(38.dp, 52.dp)
                                    HardwareButton(Modifier.size(buttonSize))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().height(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        Text("TEMPO", Modifier.width(transportHeight), color = Labels, fontWeight = FontWeight.Bold, fontSize = 10.sp, textAlign = TextAlign.Center)
                    }
                    Row(Modifier.fillMaxWidth().height(transportHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if(playing) "■   STOP" else "▶   START / STOP", Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(15.dp)).background(Brush.verticalGradient(listOf(Color(0xFF37393B), Color(0xFF111213)))).pointerInput(playing) { detectTapGestures(onTap = {
                        registerInteraction()
                        val next = !playing
                        playing = next // Give immediate visual feedback; audio stream work runs off the UI thread.
                        val request = playbackRequest.incrementAndGet()
                        if(next) {
                            val serviceIntent = android.content.Intent(context, MetronomeService::class.java).setAction(MetronomeService.ACTION_START)
                            ContextCompat.startForegroundService(context, serviceIntent)
                            engine.reset()
                            playbackScope.launch(audioDispatcher) {
                                val started = playbackRequest.get() == request && engine.play(true)
                                if (!started && playbackRequest.get() == request) {
                                    withContext(Dispatchers.Main) {
                                        if (playbackRequest.get() == request) {
                                            playing = false
                                            context.stopService(serviceIntent)
                                        }
                                    }
                                } else if (!started) {
                                    engine.play(false)
                                    engine.close()
                                }
                            }
                        } else {
                            engine.play(false)
                            context.stopService(android.content.Intent(context, MetronomeService::class.java).setAction(MetronomeService.ACTION_STOP))
                            playbackScope.launch(audioDispatcher) { engine.close() }
                        }
                    }) }.wrapContentHeight(Alignment.CenterVertically), color = Silver, textAlign = TextAlign.Center, fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    TempoKnob(bpm, Modifier.size(transportHeight)) { registerInteraction(); tempoError = null; tempoFlashing = false; bpm = it.coerceIn(30,250) }
                    }
                }
            }
        }
    }
}
}

@Composable
private fun Pulse200Logo(modifier: Modifier, fontSize: androidx.compose.ui.unit.TextUnit) {
    val condensed = remember {
        FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL))
    }
    Text("PULSE 200", modifier, color = Color(0xFFA6ACB1), fontFamily = condensed, fontWeight = FontWeight.SemiBold, fontSize = fontSize, letterSpacing = 1.sp, textAlign = TextAlign.Center, maxLines = 1)
}

@Composable
private fun TapTempoBank(onTap: () -> Unit) {
    Column(Modifier.width(130.dp).clip(RoundedCornerShape(13.dp)).background(Brush.verticalGradient(listOf(Color(0xFF393B3D), Color(0xFF101112)))).pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }.padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) { repeat(4) { Box(Modifier.size(13.dp).clip(CircleShape).background(Color(0xFF050606))) } }
        Spacer(Modifier.height(4.dp)); Text("TAP TEMPO", color = Labels, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

@Composable
private fun LcdDisplay(bpm: Int, beat: Int, sound: Int, program: Int, playing: Boolean, modeName: String, storeConfirmation: Boolean, tempoError: String?, tempoFlashing: Boolean, backlightAwake: Boolean, batteryPercent: Int) {
    var flashVisible by remember { mutableStateOf(true) }
    LaunchedEffect(tempoFlashing) {
        flashVisible = true
        while (tempoFlashing) { kotlinx.coroutines.delay(100); flashVisible = !flashVisible }
    }
    val ink = if (backlightAwake) LCDInk else Color(0xFF6E858E)
    val display = if (backlightAwake) Brush.verticalGradient(listOf(Color(0xFF28C9F3), Color(0xFF4AD8FA), Color(0xFF21BEEB))) else Brush.verticalGradient(listOf(Color(0xFF0A2027), Color(0xFF102A32), Color(0xFF08191F)))
    Row(Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(12.dp)).background(display).padding(horizontal = 9.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        LcdField(modeName, program.toString().padStart(2, '0'), if(storeConfirmation) "✓ STORED" else "▣ ${batteryPercent}%", 37.sp, ink, Modifier.weight(1.3f))
        DividerLine(95.dp)
        LcdField("TEMPO", tempoError ?: bpm.toString(), if(playing) "▶ TEMPO" else "TEMPO", if(tempoError != null) 47.sp else 57.sp, ink, Modifier.weight(2.2f), if(tempoFlashing && !flashVisible) 0f else 1f)
        DividerLine(95.dp)
        LcdField("BEAT", beat.toString(), "BEAT", 37.sp, ink, Modifier.weight(.9f))
        DividerLine(95.dp)
        LcdField("SOUND", sound.toString(), "SOUND", 37.sp, ink, Modifier.weight(.8f))
    }
}

@Composable
private fun LcdField(title: String, value: String, footer: String, valueSize: androidx.compose.ui.unit.TextUnit, ink: Color, modifier: Modifier, valueAlpha: Float = 1f) {
    Column(modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.Center) {
            Text(title, color = ink, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            SegmentNumber(value, valueSize, ink, valueAlpha)
        }
        Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.Center) {
            Text(footer, color = ink, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun DividerLine(height: androidx.compose.ui.unit.Dp) {
    Box(Modifier.width(1.dp).height(height).background(LCDInk.copy(alpha = .65f)))
}

@Composable
private fun SegmentNumber(value: String, size: androidx.compose.ui.unit.TextUnit, color: Color = LCDInk, alpha: Float = 1f) {
    Text(value, modifier = Modifier.alpha(alpha), color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = size, letterSpacing = (-3).sp, maxLines = 1)
}

@Composable
private fun HardwareButton(modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFF45484B), Color(0xFF181A1C), Color(0xFF050607)))).padding(5.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFF202326), Color(0xFF08090A)))))
}

@Composable
private fun ProgramStepper(modifier: Modifier, width: androidx.compose.ui.unit.Dp, buttonSize: androidx.compose.ui.unit.Dp, onPrevious: () -> Unit, onNext: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("PROGRAM", color = Labels, fontSize = (width.value * .022f).coerceIn(7f, 9f).sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf(onPrevious, onNext).forEach { action ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(buttonSize).pointerInput(Unit) { detectTapGestures(onTap = { action() }) }) {
                        HardwareButton(Modifier.fillMaxSize())
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("−", color = Silver, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text("+", color = Silver, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Fader(label: String, value: Float, accent: Boolean, modifier: Modifier, onChange: (Float) -> Unit) {
    val active = if(accent) Color(0xFF3EE0FF) else Color(0xFF13C7FF)
    Column(modifier.fillMaxHeight().clip(RoundedCornerShape(9.dp)).background(Brush.verticalGradient(listOf(Color(0xFF25272A), Color(0xFF111214)))).padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.Center) {
            Text(label, Modifier.padding(horizontal = 3.dp), color = Labels, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.Center) {
            if (label == "TRIPLET") {
                TripletIcon()
            } else {
                Text(when(label) { "QUARTER" -> "♪"; "EIGHTH" -> "♫"; "SIXTEENTH" -> "♬"; "BEAT" -> ""; else -> "◖))" }, color = Silver, fontSize = 21.sp, lineHeight = 24.sp)
            }
        }
        VerticalFader(value, active, Modifier.weight(1f).fillMaxWidth()) { onChange(it) }
        Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.Center) {
            Text("${(value*100).roundToInt()}", color = Silver, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TripletIcon(modifier: Modifier = Modifier) {
    Column(modifier.height(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("3", color = Silver, fontSize = 8.sp, lineHeight = 8.sp, fontWeight = FontWeight.Normal)
        Canvas(Modifier.width(26.dp).height(16.dp)) {
            val xs = listOf(size.width * .18f, size.width * .50f, size.width * .82f)
            val headY = size.height * .72f
            xs.forEach { x ->
                drawOval(Silver, Offset(x - 2.dp.toPx(), headY - 1.5.dp.toPx()), Size(5.dp.toPx(), 3.5.dp.toPx()))
                drawLine(Silver, Offset(x + 2.dp.toPx(), headY), Offset(x + 2.dp.toPx(), 2.dp.toPx()), 1.dp.toPx())
            }
            drawLine(Silver, Offset(xs.first() + 2.dp.toPx(), 2.dp.toPx()), Offset(xs.last() + 2.dp.toPx(), 2.dp.toPx()), 1.dp.toPx())
        }
    }
}

@Composable
private fun VerticalFader(value: Float, color: Color, modifier: Modifier, onChange: (Float) -> Unit) {
    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onChange)
    val knobHalfPx = with(LocalDensity.current) { 20.dp.toPx() }
    fun valueAt(y: Float, height: Float): Float {
        val top = knobHalfPx
        val bottom = (height - knobHalfPx).coerceAtLeast(top + 1f)
        return (1f - (y - top) / (bottom - top)).coerceIn(0f, 1f)
    }
    Canvas(modifier.pointerInput(Unit) {
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
        val top = knobHalfPx
        val bottom = (size.height-knobHalfPx).coerceAtLeast(top+1f)
        drawRoundRect(Color(0xFF030405), Offset(x,top), Size(trackW,bottom-top), cornerRadius=androidx.compose.ui.geometry.CornerRadius(trackW/2))
        val y=value*(bottom-top)
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF0877B6),color,Color(0xFF0877B6))), Offset(x+trackW*.26f,bottom-y), Size(trackW*.48f,y), cornerRadius=androidx.compose.ui.geometry.CornerRadius(trackW/3))
        for(i in 1..9) {
            val ty=top+(bottom-top)*i/10
            drawLine(Color(0xFF777B7F), Offset(0f,ty), Offset(size.width*.21f,ty), 1.dp.toPx())
            drawLine(Color(0xFF777B7F), Offset(size.width*.79f,ty), Offset(size.width,ty), 1.dp.toPx())
        }
        val knobY=bottom-(bottom-top)*value
        drawRoundRect(Color(0xFF030405), Offset(size.width*.20f,knobY-20.dp.toPx()), Size(size.width*.60f,40.dp.toPx()), cornerRadius=androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()))
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF777A7D),Color(0xFF17191B),Color(0xFF44474A))), Offset(size.width*.24f,knobY-18.dp.toPx()), Size(size.width*.52f,36.dp.toPx()), cornerRadius=androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
        for(i in -1..2) drawLine(Color(0xFF9EA2A6), Offset(size.width*.29f,knobY+i*7.dp.toPx()), Offset(size.width*.71f,knobY+i*7.dp.toPx()), 1.dp.toPx())
    }
}

@Composable
private fun TempoKnob(value: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onChange)
    val density = LocalDensity.current.density
    Box(modifier.pointerInput(Unit) {
            var startValue = latestValue
            var accumulatedDrag = 0f
            var lastDragTime = 0L
            detectDragGestures(
                onDragStart = { startValue = latestValue; accumulatedDrag = 0f; lastDragTime = android.os.SystemClock.uptimeMillis() },
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
                    accumulatedDrag += -drag.y / density * sensitivity
                    latestOnChange((startValue + accumulatedDrag).roundToInt().coerceIn(30,250))
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

