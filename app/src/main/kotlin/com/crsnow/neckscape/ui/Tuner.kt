package com.crsnow.neckscape.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.crsnow.neckscape.theory.PitchTracker
import com.crsnow.neckscape.theory.TunedStrings
import com.crsnow.neckscape.theory.Tuning
import com.crsnow.neckscape.theory.openNote
import com.crsnow.neckscape.theory.tunerReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/** The one platform-specific piece of the tuner: mono samples (−1..1) from the microphone. */
interface AudioInput {
    /**
     * Starts listening: [onBlock] gets each block of samples (the first `count`
     * of the array) on a background thread; [onError] says why it can't, such as
     * no microphone or no permission to use it.
     */
    fun start(sampleRate: Int, onBlock: (FloatArray, Int) -> Unit, onError: (String) -> Unit)
    fun stop()
}

/** Provided by each platform's entry point; null (no tuner) where there's no microphone. */
val LocalAudioInput = staticCompositionLocalOf<AudioInput?> { null }

private const val SAMPLE_RATE = 44_100
private val InTune = Color(0xFF43A047)
private val Near = Color(0xFFFB8C00)
private val Off = Color(0xFFE53935)

private fun centsColor(cents: Double) = when {
    abs(cents) <= 5 -> InTune
    abs(cents) <= 15 -> Near
    else -> Off
}

/**
 * Listens to the microphone and shows how far the string being played is from
 * its note in [tuning]: the string is found automatically (or picked), a needle
 * shows the cents, and each string gets a tick once it's held in tune. Tapping a
 * string also plays its note, for tuning by ear.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TunerDialog(tuning: Tuning, tuningName: String, onDismiss: () -> Unit) {
    val input = LocalAudioInput.current
    val player = LocalNotePlayer.current
    val heard = remember { MutableStateFlow<Double?>(null) }
    val problem = remember { MutableStateFlow<String?>(null) }
    val hz by heard.collectAsState()
    val error by problem.collectAsState()
    var locked by remember(tuning) { mutableStateOf<Int?>(null) }
    var previous by remember(tuning) { mutableStateOf<Int?>(null) }
    val tuned = remember(tuning) { TunedStrings() }
    var tunedNow by remember(tuning) { mutableStateOf(emptySet<Int>()) }
    // While a reference note plays, the microphone hears it: don't let it tick the string.
    var referenceUntil by remember { mutableLongStateOf(0L) }

    // Only while the app is on screen: the microphone stops in the background.
    LifecycleStartEffect(input) {
        val tracker = PitchTracker(SAMPLE_RATE)
        problem.value = null
        input?.start(SAMPLE_RATE, { block, count -> if (tracker.feed(block, count)) heard.value = tracker.hz }) { problem.value = it }
        onStopOrDispose {
            input?.stop()
            heard.value = null
        }
    }

    val reading = hz?.let { tunerReading(it, tuning, locked, previous) }
    LaunchedEffect(reading) {
        if (reading != null) previous = reading.string
        val now = System.nanoTime()
        if (now >= referenceUntil && tuned.update(reading, now / 1e9)) tunedNow = tuned.strings.toSet()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Tuner") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tuningName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    input == null -> Text("There's no microphone to listen with.")
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        reading?.let { tuning.openNote(it.string) } ?: "–",
                        fontSize = 44.sp, fontWeight = FontWeight.Bold,
                        color = reading?.let { centsColor(it.stringCents) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.padding(bottom = 8.dp)) {
                        Text(
                            reading?.let {
                                val c = it.stringCents.roundToInt()
                                when {
                                    abs(it.stringCents) <= 5 -> "In tune"
                                    c < 0 -> "−${-c} ¢ · tune up"
                                    else -> "+$c ¢ · tune down"
                                }
                            } ?: "Play one string",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            reading?.let { "Hearing ${it.note}  ·  ${"%.1f".format(it.hz)} Hz" } ?: " ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                CentsMeter(reading?.stringCents)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = locked == null, onClick = { locked = null }, label = { Text("Auto") })
                    for (s in tuning.midi.indices) {
                        val target = reading?.string == s
                        FilterChip(
                            selected = locked == s,
                            onClick = {
                                locked = if (locked == s) null else s
                                player?.playNote(tuning.midi[s])
                                referenceUntil = System.nanoTime() + 2_500_000_000L
                            },
                            label = {
                                Text(
                                    tuning.openNote(s) + if (s in tunedNow) " ✓" else "",
                                    fontWeight = if (target) FontWeight.Bold else FontWeight.Normal,
                                    color = if (s in tunedNow) InTune else Color.Unspecified,
                                )
                            },
                        )
                    }
                }
                Text(
                    "Tap a string to hear its note and tune just that one; Auto follows whichever you play.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** −50 … +50 cents, with the in-tune band shaded and a needle at [cents] (none when nothing's heard). */
@Composable
private fun CentsMeter(cents: Double?) {
    val needle by animateFloatAsState((cents ?: 0.0).coerceIn(-50.0, 50.0).toFloat(), label = "needle")
    val scale = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(Modifier.fillMaxWidth().height(48.dp)) {
        fun x(c: Float) = size.width * (c + 50f) / 100f
        val mid = size.height * 0.45f
        drawRoundRect(track, Offset(0f, mid - 6.dp.toPx()), Size(size.width, 12.dp.toPx()), CornerRadius(6.dp.toPx()))
        drawRect(InTune.copy(alpha = 0.35f), Offset(x(-5f), mid - 6.dp.toPx()), Size(x(5f) - x(-5f), 12.dp.toPx()))
        for (c in -50..50 step 10) {
            val long = c % 50 == 0
            drawLine(scale.copy(alpha = if (c == 0) 1f else 0.5f), Offset(x(c.toFloat()), mid + 9.dp.toPx()), Offset(x(c.toFloat()), mid + (if (long) 18 else 14).dp.toPx()), 1.5f.dp.toPx())
        }
        if (cents != null) {
            val color = centsColor(cents)
            drawLine(color, Offset(x(needle), 0f), Offset(x(needle), mid + 8.dp.toPx()), 4.dp.toPx())
            drawCircle(color, 6.dp.toPx(), Offset(x(needle), mid))
        }
    }
}
