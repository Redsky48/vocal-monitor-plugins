package com.vocalmonitor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Vocal Monitor brand palette ────────────────────────────────
// Pulled straight from slim's Theme.kt — same hex values so the
// Android and Desktop hosts render byte-for-byte identical chrome.

val PitchYellow    = Color(0xFFFFEB3B)
val LiveCyan       = Color(0xFF4FC3F7)
val GraphBg        = Color(0xFF000000)
val GridLine       = Color(0xFF6E6E6E)         // brighter natural-key line
val GridLineMajor  = Color(0xFF9E9E9E)         // bright C-line
val GridLineSharp  = Color(0xFF3A3A3A)         // dim sharp/flat line
val NoteLabel      = Color(0xFFCFCFCF)
val NoteLabelDim   = Color(0xFF6E6E6E)

private val DarkColors = darkColorScheme(
    primary    = PitchYellow,
    onPrimary  = Color.Black,
    secondary  = PitchYellow,
    background = GraphBg,
    onBackground = Color.White,
    surface    = GraphBg,
    onSurface  = Color.White,
)

@Composable
fun VocalMonitorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
