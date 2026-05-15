package com.vocalmonitor.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.vocalmonitor.audio.NodeId
import com.vocalmonitor.daw.DesktopGraphViewModel
import com.vocalmonitor.desktop.DesktopPluginEngine
import com.vocalmonitor.ui.AudioGraphSheet
import java.io.File

/**
 * Vocal Monitor DAW — Compose Desktop entry point.
 *
 * Phase B: full shared `AudioGraphSheet` from `shared-ui` renders
 * here.  Same composable Android slim renders — proves the
 * Compose Multiplatform shared-UI strategy works end-to-end.
 *
 * Audio engine (JavaSoundIO + graph routing) re-lands in Phase C.
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Vocal Monitor DAW",
        state = rememberWindowState(width = 1400.dp, height = 900.dp),
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            App()
        }
    }
}

@Composable
private fun App() {
    val repoRoot = remember { findRepoRoot() }
    val pluginEngine = remember {
        DesktopPluginEngine(repoRoot).also { it.loadAllFromRepo() }
    }
    val viewModel = remember {
        DesktopGraphViewModel(
            pluginEngine,
            initialGraph = DesktopGraphViewModel.demoGraph(),
            initialExpanded = setOf(
                NodeId("demo-spectrum"),
                NodeId("demo-glow"),
                NodeId("demo-formant"),
            ),
        ).also { it.refreshPluginCatalogue() }
    }
    AudioGraphSheet(
        viewModel = viewModel,
        onDismiss = { /* No-op for window mode; close via window X */ },
    )
}

private fun findRepoRoot(): File {
    var d: File? = File(".").absoluteFile.canonicalFile
    while (d != null) {
        if (File(d, "plugins").isDirectory && File(d, "manifest.json").isFile) return d
        d = d.parentFile
    }
    return File(".").absoluteFile
}
