package com.vocalmonitor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vocalmonitor.ui.NoteLabel
import com.vocalmonitor.ui.NoteLabelDim
import com.vocalmonitor.ui.PitchYellow
import kotlin.math.cos
import kotlin.math.sin

/**
 * DAW-style rotary knob. Vertical drag changes value — dragging up
 * increases, dragging down decreases. Coverage of the full
 * `[min, max]` range maps to roughly 200 dp of drag travel, which
 * lines up with typical phone screens (one finger sweep covers the
 * whole range without re-grabbing).
 *
 * Visual: a 56 dp circle with
 *   - a thin background ring (full 270° sweep) showing the available
 *     range,
 *   - a thicker yellow arc filling from the start of the sweep up to
 *     the current value,
 *   - a short pointer line from the centre to the current arc tip
 *     so the value reads at a glance,
 *   - the formatted value in the middle.
 *
 * The label sits below the knob so a knob can stand in a grid cell
 * of its own without needing a sibling label column.
 *
 * Commits at the end of the gesture via [onCommit] so each drag is
 * one undo entry — same shape as the auto-generated slider.
 */
@Composable
fun RotaryKnob(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Diameter of the knob circle in dp. Default matches the
     * compact chain-card layout; floating panels pass a larger or
     * smaller value here so the knob scales together with the rest
     * of the panel when the user resizes.
     */
    sizeDp: Int = 56,
    valueFormat: (Float) -> String = { v ->
        // Drop decimals for integer-ish ranges (those usually
        // represent ms / Hz / dB values that read weirdly with
        // trailing zeros); one decimal otherwise.
        if ((max - min) >= 50f) "%.0f".format(v) else "%.2f".format(v)
    },
) {
    val density = LocalDensity.current
    val latestValue = rememberUpdatedState(value)
    val latestOnChange = rememberUpdatedState(onChange)
    val latestOnCommit = rememberUpdatedState(onCommit)

    // 200 dp of finger travel = full sweep of the value range. Feels
    // right on a phone: not so sensitive that the user overshoots,
    // not so coarse that they have to re-grab to reach the extremes.
    val travelDp = 200f

    // Font sizes scale with the knob so a 96 dp knob doesn't wear
    // the same tiny label as a 40 dp one. Floor at 8 sp so they
    // never go unreadably small.
    val valueFontSp = (sizeDp * 0.18f).coerceAtLeast(8f)
    val labelFontSp = (sizeDp * 0.17f).coerceAtLeast(8f)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(sizeDp.dp)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { deltaPx ->
                        val deltaDp = with(density) { deltaPx.toDp().value }
                        // Up = negative deltaPx = increase value.
                        val rangeDelta = -deltaDp / travelDp * (max - min)
                        val curr = latestValue.value
                        val next = (curr + rangeDelta).coerceIn(min, max)
                        if (next != curr) latestOnChange.value(next)
                    },
                    onDragStopped = { latestOnCommit.value() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val cx = w / 2f
                val cy = h / 2f
                val outerR = minOf(w, h) / 2f - 2f
                val ringStroke = 2.dp.toPx()
                val arcStroke = 4.dp.toPx()

                // In Compose draw API angles start at 3 o'clock and
                // grow clockwise. Starting at 135° and sweeping 270°
                // puts the knob's gap at the bottom, like every DAW
                // knob ever shipped.
                val sweepStart = 135f
                val sweepFull = 270f
                val pct = if (max > min) ((value - min) / (max - min)).coerceIn(0f, 1f) else 0f
                val sweepActive = sweepFull * pct

                drawArc(
                    color = NoteLabelDim,
                    startAngle = sweepStart,
                    sweepAngle = sweepFull,
                    useCenter = false,
                    topLeft = Offset(cx - outerR, cy - outerR),
                    size = Size(outerR * 2, outerR * 2),
                    style = Stroke(ringStroke, cap = StrokeCap.Round),
                )
                if (sweepActive > 0f) {
                    drawArc(
                        color = PitchYellow,
                        startAngle = sweepStart,
                        sweepAngle = sweepActive,
                        useCenter = false,
                        topLeft = Offset(cx - outerR, cy - outerR),
                        size = Size(outerR * 2, outerR * 2),
                        style = Stroke(arcStroke, cap = StrokeCap.Round),
                    )
                }
                // Pointer line from inner to outer at current angle.
                val angleDeg = sweepStart + sweepActive
                val angleRad = Math.toRadians(angleDeg.toDouble())
                val inner = outerR * 0.35f
                val outer = outerR * 0.78f
                val pcx = cx + (cos(angleRad) * inner).toFloat()
                val pcy = cy + (sin(angleRad) * inner).toFloat()
                val pex = cx + (cos(angleRad) * outer).toFloat()
                val pey = cy + (sin(angleRad) * outer).toFloat()
                drawLine(
                    color = PitchYellow,
                    start = Offset(pcx, pcy),
                    end = Offset(pex, pey),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            Text(
                valueFormat(value),
                color = Color.White,
                fontSize = valueFontSp.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            color = NoteLabel,
            fontSize = labelFontSp.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(sizeDp.dp),
        )
    }
}
