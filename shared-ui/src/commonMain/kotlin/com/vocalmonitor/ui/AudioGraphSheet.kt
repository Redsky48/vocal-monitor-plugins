package com.vocalmonitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vocalmonitor.audio.EffectKind
import com.vocalmonitor.audio.GraphEdge
import com.vocalmonitor.audio.GraphNode
import com.vocalmonitor.audio.NodeId
import com.vocalmonitor.audio.NodeKind
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Vocal Monitor — interactive audio-graph editor.
 *
 * **Port of slim's `AudioGraphSheet`**, distilled to the core
 * graph-editor surface so it works on both Android and Desktop
 * via Compose Multiplatform.  Identical physical interactions to
 * the original — every gesture path, hit-test radius, magnet-snap
 * threshold, and Bezier control-point recipe is copied byte-for-
 * byte from slim's gesture handler.
 *
 * Interactions:
 *   - Drag empty area                    = pan canvas
 *   - Two-finger pinch (mobile)          = zoom about midpoint
 *   - Drag a node body                   = move that node
 *   - Drag an output port (right ●)      = rubber-band wire with
 *                                          60-dp magnet snap to the
 *                                          nearest FREE input port
 *   - Tap an input port (left ●)         = delete its incoming edge
 *   - Tap an edge (wire)                 = select / delete
 *   - Tap a node                         = select (highlight)
 *   - Tap empty                          = deselect
 *
 * Items deliberately deferred to slim's full version (Phase 4c
 * targets bringing them in via shared-ui extensions):
 *   - Per-effect inspector panel (NodeEffectCard family)
 *   - Add-plugin dialog (uses [AudioGraphViewModel.addJsPluginToChain]
 *     directly for now — DAW exposes a toolbar)
 *   - ChainPresetBar (available as separate composable already)
 *   - PluginPanel inline render — see [PluginVisualSurface] expect/actual
 */

// ── Layout tunables — same defaults as slim ─────────────────────
private const val UNIT_DP = 60f
private const val NODE_W  = 2.4f
private const val NODE_H  = 1.0f
private const val PORT_TOUCH_RADIUS_DP = 22f
private const val EDGE_TOUCH_RADIUS_DP = 12f
private const val WIRE_SNAP_RADIUS_DP  = 60f

@Composable
fun AudioGraphSheet(
    viewModel: AudioGraphViewModel,
    onDismiss: () -> Unit,
) {
    FullScreenDialog(
        onDismiss = onDismiss,
        backgroundColor = Color(0xFF111111),
    ) {
        var addPluginOpen by remember { mutableStateOf(false) }
        var selectedNodeId by remember { mutableStateOf<NodeId?>(null) }

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── Header ─────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Audio Graph",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }

            // ── Chain preset bar ───────────────────────────────
            ChainPresetBar(viewModel = viewModel)

            // ── Gesture-hint pills ──────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HintPill(Icons.Default.PanTool, "Drag nodes")
                HintPill(Icons.Default.TouchApp, "Tap to select")
                HintPill(Icons.Default.Cable, "Connect ports")
            }

            // ── Graph canvas ────────────────────────────────────
            Box(Modifier.weight(1f)) {
                GraphCanvas(
                    viewModel = viewModel,
                    selectedNodeId = selectedNodeId,
                    onSelectNode = { selectedNodeId = it },
                )
            }

            // ── Selected-node action sheet (Android-parity) ─────
            // Mirrors slim's bottom inspector: title row with bypass
            // toggle + a horizontally-scrolling row of action tiles
            // (Bypass / Edit settings / Duplicate / Delete / Disconnect).
            SelectedNodeActionSheet(
                viewModel = viewModel,
                selectedId = selectedNodeId,
                onClose = { selectedNodeId = null },
            )

            // ── Bottom action bar (Android-parity) ──────────────
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { addPluginOpen = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null,
                        tint = PitchYellow, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add node", color = PitchYellow, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium)
                }
                OutlinedButton(
                    onClick = { viewModel.resetGraphToDefault() },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null,
                        tint = PitchYellow, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Reset to default", color = PitchYellow, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium)
                }
            }
        }

        if (addPluginOpen) {
            AddPluginDialog(
                viewModel = viewModel,
                onDismiss = { addPluginOpen = false },
                onPicked = { id ->
                    viewModel.addJsPluginToChain(id)
                    addPluginOpen = false
                },
            )
        }
    }
}

@Composable
private fun AddPluginDialog(
    viewModel: AudioGraphViewModel,
    onDismiss: () -> Unit,
    onPicked: (String) -> Unit,
) {
    val plugins by viewModel.jsPlugins.collectAsState()
    var filter by remember { mutableStateOf("") }
    val filtered = remember(plugins, filter) {
        val q = filter.trim().lowercase()
        if (q.isEmpty()) plugins
        else plugins.filter { it.id.lowercase().contains(q) || it.displayName.lowercase().contains(q) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add plugin node") },
        text = {
            Column {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    singleLine = true,
                    label = { Text("Filter") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("${filtered.size} of ${plugins.size} plugins",
                    color = NoteLabelDim, fontSize = 10.sp)
                Spacer(Modifier.height(4.dp))
                Column(
                    Modifier
                        .height(360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    filtered.forEach { p ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPicked(p.id) }
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(PitchYellow, CircleShape),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(p.id, color = Color.White, fontSize = 12.sp,
                                fontWeight = FontWeight.Medium)
                            if (p.displayName != p.id) {
                                Spacer(Modifier.width(8.dp))
                                Text(p.displayName, color = NoteLabel, fontSize = 11.sp)
                            }
                        }
                    }
                    if (filtered.isEmpty()) {
                        Text("no matches", color = NoteLabelDim, fontSize = 11.sp,
                            modifier = Modifier.padding(6.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Renders a plugin's visual canvas as the only thing on the window.
 * Triggered from the Fullscreen action tile when the plugin opts in
 * via `"fullscreen": true` in plugin.json — useful for voice games,
 * drawing tools, etc. that benefit from filling the screen.
 *
 * Uses the SAME [visualPluginFor] handle the inline NodeBody uses, so
 * the live audio loop driving the small canvas keeps driving this one
 * automatically — no extra wiring needed.
 */
@Composable
private fun FullScreenPluginDialog(
    viewModel: AudioGraphViewModel,
    node: GraphNode,
    onDismiss: () -> Unit,
) {
    val plugin = remember(node.id, node.label) {
        viewModel.visualPluginFor(node.id)
    }
    FullScreenDialog(
        onDismiss = onDismiss,
        dismissOnBackPress = true,
        dismissOnClickOutside = false,
        backgroundColor = Color(0xFF000000),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (plugin != null) {
                PluginVisualSurface(
                    plugin = plugin,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    "Plugin not loaded",
                    color = NoteLabel,
                    modifier = Modifier.padding(20.dp),
                )
            }
            // Floating exit button — top-right, on top of the canvas.
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            ) {
                Icon(
                    Icons.Default.FullscreenExit,
                    contentDescription = "Exit fullscreen",
                    tint = PitchYellow,
                )
            }
        }
    }
}

@Composable
private fun SelectedNodeActionSheet(
    viewModel: AudioGraphViewModel,
    selectedId: NodeId?,
    onClose: () -> Unit,
) {
    if (selectedId == null) return
    val graph by viewModel.graph.collectAsState()
    val node = graph.nodes.firstOrNull { it.id == selectedId }
    if (node == null) {
        // Node was removed elsewhere — clear selection silently.
        onClose()
        return
    }
    var fullscreenOpen by remember(node.id) { mutableStateOf(false) }
    val catalogEntry = viewModel.catalogEntryFor(node.label)
    val canFullscreen = node.kind == NodeKind.Effect &&
        catalogEntry?.fullscreenCapable == true

    if (fullscreenOpen) {
        FullScreenPluginDialog(
            viewModel = viewModel,
            node = node,
            onDismiss = { fullscreenOpen = false },
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Drag-handle indicator.
        Box(
            Modifier.fillMaxWidth().padding(bottom = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(width = 36.dp, height = 3.dp)
                    .background(NoteLabelDim, RoundedCornerShape(2.dp)),
            )
        }

        // Title row: icon tile + name + badges + bypass switch.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(Color(0xFF0E0E0E), RoundedCornerShape(8.dp))
                    .border(1.dp, PitchYellow.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.GraphicEq, contentDescription = null,
                    tint = PitchYellow, modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    node.label.ifBlank { node.kind.name }.lowercase().replace(' ', '-'),
                    color = Color.White, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NodeBadge(label = node.kind.name)
                    val effectKindName: String? = when {
                        node.effectKind == EffectKind.JsPlugin -> "JsPlugin"
                        node.effectKind != null -> "Effect"
                        else -> null
                    }
                    effectKindName?.let { NodeBadge(label = it) }
                }
            }
            if (node.kind == NodeKind.Effect) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        if (node.bypass) "Bypassed" else "Active",
                        color = if (node.bypass) NoteLabelDim else PitchYellow,
                        fontSize = 10.sp,
                    )
                    Switch(
                        checked = !node.bypass,
                        onCheckedChange = { viewModel.setNodeBypass(node.id, !it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = PitchYellow,
                            checkedTrackColor = PitchYellow.copy(alpha = 0.4f),
                        ),
                    )
                }
            }
        }

        // Per-kind body: Input shows a Mic/File source picker, Output
        // shows a device picker, Effect shows the action-tile grid.
        when (node.kind) {
            NodeKind.Input  -> InputSourcePicker(viewModel, node)
            NodeKind.Output -> OutputDevicePicker(viewModel, node)
            else -> {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (node.kind == NodeKind.Effect) {
                        ActionTile(
                            icon = Icons.Default.PowerSettingsNew,
                            label = if (node.bypass) "Un-bypass" else "Bypass",
                            tint = PitchYellow,
                            onClick = { viewModel.setNodeBypass(node.id, !node.bypass) },
                        )
                        ActionTile(
                            icon = Icons.Default.Tune,
                            label = "Edit\nsettings",
                            tint = PitchYellow,
                            onClick = { viewModel.toggleGraphNodeExpanded(node.id) },
                        )
                        if (canFullscreen) {
                            ActionTile(
                                icon = Icons.Default.Fullscreen,
                                label = "Fullscreen",
                                tint = PitchYellow,
                                onClick = { fullscreenOpen = true },
                            )
                        }
                        ActionTile(
                            icon = Icons.Default.ContentCopy,
                            label = "Duplicate",
                            tint = PitchYellow,
                            onClick = { viewModel.duplicateNode(node.id) },
                        )
                        ActionTile(
                            icon = Icons.Default.Delete,
                            label = "Delete",
                            tint = Color(0xFFE25656),
                            onClick = {
                                viewModel.removeNode(node.id)
                                onClose()
                            },
                        )
                        ActionTile(
                            icon = Icons.Default.LinkOff,
                            label = "Disconnect",
                            tint = NoteLabel,
                            onClick = { viewModel.disconnectAllForNode(node.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Encoded format on `node.encodedState` for Input nodes:
 *   `mic[:DeviceName]`   — use that input device (or system default)
 *   `file:<absolute path>` — play this audio file in a loop
 * Encoded format on Output nodes:
 *   `device[:DeviceName]` — write to that output (or system default)
 */
@Composable
private fun InputSourcePicker(viewModel: AudioGraphViewModel, node: GraphNode) {
    val state = node.encodedState
    val isFile = state.startsWith("file:")
    val filePath = if (isFile) state.removePrefix("file:") else ""
    val micDevice = if (state.startsWith("mic:")) state.removePrefix("mic:") else ""

    // Switch the always-on capture line to whatever device the user
    // picks here.  We do NOT stop on unmount — the audio loop drives
    // every cached plugin instance, so it has to stay alive while
    // the DAW window is open, not just while this picker is visible.
    DisposableEffect(isFile, micDevice) {
        if (!isFile) viewModel.startMicMonitoring(micDevice.ifBlank { null })
        onDispose {}
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Source", color = NoteLabel, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceChip(
                label = "Mic",
                icon = Icons.Default.Mic,
                selected = !isFile,
                onClick = {
                    if (isFile) viewModel.setNodeEncodedState(node.id, "mic")
                },
            )
            SourceChip(
                label = "Audio file",
                icon = Icons.Default.AudioFile,
                selected = isFile,
                onClick = {
                    val picked = pickAudioFile() ?: return@SourceChip
                    viewModel.setNodeEncodedState(node.id, "file:$picked")
                },
            )
        }
        if (isFile) {
            Text(
                filePath.ifBlank { "(no file)" }.substringAfterLast('/').substringAfterLast('\\'),
                color = PitchYellow, fontSize = 12.sp, maxLines = 1,
            )
            OutlinedButton(onClick = {
                val picked = pickAudioFile() ?: return@OutlinedButton
                viewModel.setNodeEncodedState(node.id, "file:$picked")
            }) {
                Icon(Icons.Default.FolderOpen, contentDescription = null,
                    tint = PitchYellow, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Change file", color = PitchYellow, fontSize = 12.sp)
            }
        } else {
            DeviceDropdown(
                label = "Input device",
                devices = viewModel.audioInputDevices,
                selected = micDevice.ifBlank { "Default" },
                onSelect = { d ->
                    viewModel.setNodeEncodedState(
                        node.id,
                        if (d == "Default") "mic" else "mic:$d",
                    )
                },
            )
            MicLevelBar(viewModel)
        }
    }
}

/**
 * Horizontal level bar — green up to ~70%, amber, then red toward
 * clip.  Reads `viewModel.micLevel` which the platform impl pushes
 * RMS into ~30Hz while monitoring is active.  Includes a tiny "live"
 * dot that pulses when the signal is above the silence floor so the
 * user can tell "0 because quiet" from "0 because broken".
 */
@Composable
private fun MicLevelBar(viewModel: AudioGraphViewModel) {
    val level by viewModel.micLevel.collectAsState()
    val live = level > 0.001f
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        if (live) Color(0xFF66CC66) else Color(0xFF555555),
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (live) "Live · ${(level * 100f).toInt()}%" else "No signal",
                color = if (live) NoteLabel else NoteLabelDim,
                fontSize = 10.sp,
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF0E0E0E)),
        ) {
            val w = size.width
            val h = size.height
            // Logarithmic-ish stretch so quiet speech is visible without
            // shouting; tweakable later.
            val display = (level * 2f).coerceIn(0f, 1f)
            val fillW = w * display
            // Three-segment colouring: green / amber / red.
            val green = Color(0xFF66CC66)
            val amber = Color(0xFFE3B544)
            val red   = Color(0xFFE25656)
            val greenEnd = w * 0.6f
            val amberEnd = w * 0.85f
            if (fillW > 0f) {
                val gW = kotlin.math.min(fillW, greenEnd)
                drawRect(green, size = androidx.compose.ui.geometry.Size(gW, h))
                if (fillW > greenEnd) {
                    val aW = kotlin.math.min(fillW, amberEnd) - greenEnd
                    drawRect(
                        amber,
                        topLeft = Offset(greenEnd, 0f),
                        size = androidx.compose.ui.geometry.Size(aW, h),
                    )
                }
                if (fillW > amberEnd) {
                    drawRect(
                        red,
                        topLeft = Offset(amberEnd, 0f),
                        size = androidx.compose.ui.geometry.Size(fillW - amberEnd, h),
                    )
                }
            }
        }
    }
}

@Composable
private fun OutputDevicePicker(viewModel: AudioGraphViewModel, node: GraphNode) {
    val state = node.encodedState
    val device = when {
        state.startsWith("device:") -> state.removePrefix("device:")
        else -> "Default"
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Output destination", color = NoteLabel, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        DeviceDropdown(
            label = "Output device",
            devices = viewModel.audioOutputDevices,
            selected = device,
            onSelect = { d ->
                viewModel.setNodeEncodedState(
                    node.id,
                    if (d == "Default") "device" else "device:$d",
                )
            },
        )
    }
}

@Composable
private fun SourceChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val border = if (selected) PitchYellow else Color(0xFF2A2A2A)
    val tint = if (selected) PitchYellow else NoteLabel
    Row(
        Modifier
            .background(Color(0xFF0F0F0F), RoundedCornerShape(8.dp))
            .border(if (selected) 1.5.dp else 1.dp, border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DeviceDropdown(
    label: String,
    devices: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, color = NoteLabelDim, fontSize = 10.sp)
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(selected, color = PitchYellow, fontSize = 12.sp, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Text("▾", color = PitchYellow, fontSize = 12.sp)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Color(0xFF1A1A1A)),
            ) {
                devices.forEach { d ->
                    DropdownMenuItem(
                        text = { Text(d, color = if (d == selected) PitchYellow else NoteLabel, fontSize = 12.sp) },
                        onClick = { onSelect(d); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun NodeBadge(label: String) {
    Row(
        Modifier
            .background(Color(0xFF0E0E0E), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(6.dp).background(PitchYellow, RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.width(4.dp))
        Text(label, color = NoteLabel, fontSize = 10.sp)
    }
}

@Composable
private fun ActionTile(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .size(width = 72.dp, height = 64.dp)
            .background(Color(0xFF0F0F0F), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = tint, fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2, lineHeight = 11.sp,
        )
    }
}

@Composable
private fun HintPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(
        Modifier
            .background(Color(0xFF1A1A1A), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = NoteLabel, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = NoteLabel, fontSize = 11.sp)
    }
}

@Composable
private fun GraphCanvas(
    viewModel: AudioGraphViewModel,
    selectedNodeId: NodeId?,
    onSelectNode: (NodeId?) -> Unit,
) {
    val graph by viewModel.graph.collectAsState()
    val expandedSet by viewModel.expandedGraphNodes.collectAsState()
    val pinnedSet by viewModel.pinnedPanels.collectAsState()
    val density = LocalDensity.current
    val unitPx = with(density) { UNIT_DP.dp.toPx() }
    val portTouchPx = with(density) { PORT_TOUCH_RADIUS_DP.dp.toPx() }
    val edgeTouchPx = with(density) { EDGE_TOUCH_RADIUS_DP.dp.toPx() }
    val wireSnapPx  = with(density) { WIRE_SNAP_RADIUS_DP.dp.toPx() }

    // Viewport.
    var canvasW by remember { mutableIntStateOf(1) }
    var canvasH by remember { mutableIntStateOf(1) }
    var scale by remember { mutableFloatStateOf(1f) }
    var panX  by remember { mutableFloatStateOf(0f) }
    var panY  by remember { mutableFloatStateOf(0f) }

    // Selection + wire-drag state.
    val selectedNode: NodeId? = selectedNodeId
    var selectedEdge by remember { mutableStateOf<Pair<NodeId, NodeId>?>(null) }
    var wireFromId by remember { mutableStateOf<NodeId?>(null) }
    var wireToPos  by remember { mutableStateOf<Offset?>(null) }
    var draggingNodeId by remember { mutableStateOf<NodeId?>(null) }

    // Rect map: graph-space px per node, populated by the overlay
    // pass that draws the node body and read by both the Skia
    // draw scope (edges + ports) and the hit-test helpers.
    val rectByNode = remember { mutableStateMapOf<NodeId, FloatArray>() }
    DisposableEffect(graph.nodes.map { it.id }.toSet()) {
        val live = graph.nodes.map { it.id }.toSet()
        rectByNode.keys.toList().forEach { if (it !in live) rectByNode.remove(it) }
        onDispose { }
    }
    // Populate the rect map from current node positions + expansion.
    for (n in graph.nodes) {
        val expanded = n.id in expandedSet
        val (gw, gh) = if (expanded) 5f to 4f else NODE_W to NODE_H
        rectByNode[n.id] = floatArrayOf(
            n.gridX * unitPx,
            n.gridY * unitPx,
            gw * unitPx,
            gh * unitPx,
        )
    }

    // ── Hit-test helpers (mirror slim's exact priority + radii) ─
    fun hitOutputPort(screenPos: Offset): GraphNode? {
        var best: GraphNode? = null
        var bestDist = portTouchPx
        for (n in graph.nodes) {
            if (n.kind == NodeKind.Output) continue
            val rect = rectByNode[n.id] ?: continue
            val portX = (rect[0] + rect[2]) * scale + panX
            val portY = (rect[1] + rect[3] / 2f) * scale + panY
            val d = sqrt((screenPos.x - portX) * (screenPos.x - portX)
                       + (screenPos.y - portY) * (screenPos.y - portY))
            if (d <= bestDist) { best = n; bestDist = d }
        }
        return best
    }
    fun hitInputPort(screenPos: Offset): GraphNode? {
        var bestFree: GraphNode? = null
        var bestFreeDist = portTouchPx
        var bestAny: GraphNode? = null
        var bestAnyDist = portTouchPx
        val occupied = graph.edges.map { it.to }.toSet()
        for (n in graph.nodes) {
            if (n.kind == NodeKind.Input) continue
            val rect = rectByNode[n.id] ?: continue
            val portX = rect[0] * scale + panX
            val portY = (rect[1] + rect[3] / 2f) * scale + panY
            val d = sqrt((screenPos.x - portX) * (screenPos.x - portX)
                       + (screenPos.y - portY) * (screenPos.y - portY))
            if (d <= bestAnyDist) { bestAny = n; bestAnyDist = d }
            if (n.id !in occupied && d <= bestFreeDist) {
                bestFree = n; bestFreeDist = d
            }
        }
        return bestFree ?: bestAny
    }
    fun hitNodeBody(screenPos: Offset): GraphNode? {
        for (n in graph.nodes.reversed()) {
            val rect = rectByNode[n.id] ?: continue
            val left = rect[0] * scale + panX
            val top = rect[1] * scale + panY
            val right = left + rect[2] * scale
            val bottom = top + rect[3] * scale
            if (screenPos.x in left..right && screenPos.y in top..bottom) return n
        }
        return null
    }
    fun snapToNearestFreeInputPort(rawScreenPos: Offset, fromNodeId: NodeId): Offset {
        val occupied = graph.edges.map { it.to }.toSet()
        var best: Offset? = null
        var bestDist = wireSnapPx
        for (n in graph.nodes) {
            if (n.kind == NodeKind.Input) continue
            if (n.id == fromNodeId) continue
            if (n.id in occupied) continue
            val rect = rectByNode[n.id] ?: continue
            val portX = rect[0] * scale + panX
            val portY = (rect[1] + rect[3] / 2f) * scale + panY
            val d = sqrt((rawScreenPos.x - portX) * (rawScreenPos.x - portX)
                       + (rawScreenPos.y - portY) * (rawScreenPos.y - portY))
            if (d < bestDist) {
                bestDist = d
                best = Offset(portX, portY)
            }
        }
        return best ?: rawScreenPos
    }
    fun hitEdgeNear(screenPos: Offset): GraphEdge? {
        var best: GraphEdge? = null
        var bestDist = edgeTouchPx
        for (e in graph.edges) {
            val fr = rectByNode[e.from] ?: continue
            val to = rectByNode[e.to] ?: continue
            val p0x = (fr[0] + fr[2]) * scale + panX
            val p0y = (fr[1] + fr[3] / 2f) * scale + panY
            val p3x = to[0] * scale + panX
            val p3y = (to[1] + to[3] / 2f) * scale + panY
            val midX = (p0x + p3x) / 2f
            val p1x = midX; val p1y = p0y
            val p2x = midX; val p2y = p3y
            var prevX = p0x; var prevY = p0y
            for (i in 1..16) {
                val t = i / 16f
                val u = 1f - t
                val bx = u*u*u*p0x + 3*u*u*t*p1x + 3*u*t*t*p2x + t*t*t*p3x
                val by = u*u*u*p0y + 3*u*u*t*p1y + 3*u*t*t*p2y + t*t*t*p3y
                val d = distanceToSegment(screenPos, Offset(prevX, prevY), Offset(bx, by))
                if (d <= bestDist) { best = e; bestDist = d }
                prevX = bx; prevY = by
            }
        }
        return best
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF050505), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .onSizeChanged {
                val firstFit = canvasW <= 1
                canvasW = it.width.coerceAtLeast(1)
                canvasH = it.height.coerceAtLeast(1)
                if (firstFit && graph.nodes.isNotEmpty()) {
                    val bx0 = graph.nodes.minOf { it.gridX } - 0.5f
                    val by0 = graph.nodes.minOf { it.gridY } - 0.5f
                    val bx1 = graph.nodes.maxOf { it.gridX } + NODE_W + 0.5f
                    val by1 = graph.nodes.maxOf { it.gridY } + NODE_H + 0.5f
                    val wPx = (bx1 - bx0) * unitPx
                    val hPx = (by1 - by0) * unitPx
                    val sx = canvasW / wPx
                    val sy = canvasH / hPx
                    scale = minOf(sx, sy).coerceIn(0.3f, 4f)
                    panX = -bx0 * unitPx * scale + 12f
                    panY = -by0 * unitPx * scale + 12f
                }
            }
            // Mouse-wheel zoom (Desktop) — scroll-up zooms in, scroll-down
            // zooms out, anchored on the cursor position so the point under
            // the mouse stays put.  Touch pinch is still handled inside the
            // gesture block below.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.type != PointerEventType.Scroll) continue
                        val change = ev.changes.firstOrNull() ?: continue
                        val dy = change.scrollDelta.y
                        if (dy == 0f) continue
                        val zoom = if (dy < 0f) 1.1f else 1f / 1.1f
                        val newScale = (scale * zoom).coerceIn(0.3f, 4f)
                        if (newScale == scale) {
                            change.consume()
                            continue
                        }
                        val anchor = change.position
                        val ratio = newScale / scale
                        panX = anchor.x - (anchor.x - panX) * ratio
                        panY = anchor.y - (anchor.y - panY) * ratio
                        scale = newScale
                        change.consume()
                    }
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downPos = down.position
                    val slop = viewConfiguration.touchSlop

                    val hitOut = hitOutputPort(downPos)
                    val hitIn  = if (hitOut == null) hitInputPort(downPos) else null
                    val hitNode = if (hitOut == null && hitIn == null) hitNodeBody(downPos) else null
                    val hitEdge = if (hitOut == null && hitIn == null && hitNode == null)
                        hitEdgeNear(downPos) else null

                    if (hitEdge != null) {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val active = ev.changes.filter { it.pressed }
                            if (active.isEmpty()) {
                                // Open inspector instead of deleting outright.
                                // User picks Keep / Delete inside the dialog.
                                selectedEdge = hitEdge.from to hitEdge.to
                                break
                            }
                            val drift = active.first().position - downPos
                            if (drift.getDistance() > slop) break
                            active.first().consume()
                        }
                        return@awaitEachGesture
                    }
                    if (hitOut != null) {
                        wireFromId = hitOut.id
                        wireToPos = downPos
                        while (true) {
                            val ev = awaitPointerEvent()
                            val active = ev.changes.filter { it.pressed }
                            if (active.isEmpty()) {
                                val pos = wireToPos
                                if (pos != null) {
                                    val target = hitInputPort(pos)
                                    if (target != null && target.id != hitOut.id) {
                                        viewModel.connectNodes(hitOut.id, target.id)
                                    }
                                }
                                wireFromId = null
                                wireToPos = null
                                break
                            }
                            val raw = active.first().position
                            wireToPos = snapToNearestFreeInputPort(raw, hitOut.id)
                            active.first().consume()
                        }
                        return@awaitEachGesture
                    }
                    if (hitIn != null) {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val active = ev.changes.filter { it.pressed }
                            if (active.isEmpty()) {
                                val incoming = viewModel.graph.value.edges.lastOrNull { it.to == hitIn.id }
                                if (incoming != null) {
                                    viewModel.disconnectNodes(incoming.from, incoming.to)
                                }
                                break
                            }
                            active.first().consume()
                        }
                        return@awaitEachGesture
                    }

                    // Node drag OR empty pan.  Also handles pinch
                    // zoom when a second finger lands.
                    var dragNodeId: NodeId? = null
                    var pastSlop = false
                    var isPinch = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val active = ev.changes.filter { it.pressed }
                        if (active.isEmpty()) {
                            if (!pastSlop) onSelectNode(hitNode?.id)
                            draggingNodeId = null
                            break
                        }
                        if (active.size >= 2) {
                            isPinch = true
                            val zoom = ev.calculateZoom()
                            val pan = ev.calculatePan()
                            if (zoom != 1f) {
                                val newScale = (scale * zoom).coerceIn(0.3f, 4f)
                                val mid = active.fold(Offset.Zero) { acc, c -> acc + c.position } / active.size.toFloat()
                                val ratio = newScale / scale
                                panX = mid.x - (mid.x - panX) * ratio
                                panY = mid.y - (mid.y - panY) * ratio
                                scale = newScale
                            }
                            panX += pan.x
                            panY += pan.y
                            ev.changes.forEach { it.consume() }
                            continue
                        }
                        if (isPinch) continue
                        val cur: PointerInputChange = active.first()
                        val total = cur.position - downPos
                        if (!pastSlop && total.getDistance() > slop) pastSlop = true
                        if (pastSlop) {
                            val delta = cur.positionChange()
                            if (hitNode != null) {
                                if (dragNodeId == null) {
                                    dragNodeId = hitNode.id
                                    draggingNodeId = hitNode.id
                                }
                                val dx = delta.x / (unitPx * scale)
                                val dy = delta.y / (unitPx * scale)
                                val snap = viewModel.graph.value.nodes
                                val tentative = snap.map { n ->
                                    if (n.id == dragNodeId) {
                                        n.copy(gridX = n.gridX + dx, gridY = n.gridY + dy)
                                    } else n
                                }
                                val packed = resolveOverlapsPerNode(
                                    nodes = tentative,
                                    pinnedId = dragNodeId,
                                    sizeOf = { id ->
                                        if (id in expandedSet) 5f to 4f
                                        else NODE_W to NODE_H
                                    },
                                )
                                viewModel.setNodePositions(packed)
                            } else {
                                panX += delta.x
                                panY += delta.y
                            }
                            cur.consume()
                        }
                    }
                }
            },
    ) {
        // Background dotted grid in screen space.
        Canvas(Modifier.fillMaxSize()) {
            val gridStep = unitPx * scale
            if (gridStep > 6f) {
                val w = size.width
                val h = size.height
                var gx = panX % gridStep
                while (gx < w) {
                    var gy = panY % gridStep
                    while (gy < h) {
                        drawCircle(NoteLabelDim.copy(alpha = 0.18f),
                            radius = 1.2f, center = Offset(gx, gy))
                        gy += gridStep
                    }
                    gx += gridStep
                }
            }
        }

        // Transformed graph layer.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale; scaleY = scale
                    translationX = panX; translationY = panY
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                // Edges first.
                for (e in graph.edges) {
                    val from = rectByNode[e.from] ?: continue
                    val to = rectByNode[e.to] ?: continue
                    val outPort = Offset(from[0] + from[2], from[1] + from[3] / 2f)
                    val inPort = Offset(to[0], to[1] + to[3] / 2f)
                    val midX = (outPort.x + inPort.x) / 2f
                    val path = Path().apply {
                        moveTo(outPort.x, outPort.y)
                        cubicTo(midX, outPort.y, midX, inPort.y, inPort.x, inPort.y)
                    }
                    val isHot = selectedNode != null &&
                        (e.from == selectedNode || e.to == selectedNode)
                    drawPath(
                        path,
                        color = if (isHot) PitchYellow else PitchYellow.copy(alpha = 0.45f),
                        style = Stroke(width = if (isHot) 2.5f else 1.6f),
                    )
                    val arrowR = 3f / scale
                    drawCircle(
                        color = if (isHot) PitchYellow else PitchYellow.copy(alpha = 0.6f),
                        radius = arrowR,
                        center = inPort,
                    )
                }
                // Ports — constant screen size via /scale.
                val portR = 4f / scale
                for (n in graph.nodes) {
                    val rect = rectByNode[n.id] ?: continue
                    val cy = rect[1] + rect[3] / 2f
                    if (n.kind != NodeKind.Input) {
                        drawCircle(NoteLabel, portR, Offset(rect[0], cy))
                    }
                    if (n.kind != NodeKind.Output) {
                        drawCircle(NoteLabel, portR, Offset(rect[0] + rect[2], cy))
                    }
                }
                // Rubber-band wire on top.
                val wf = wireFromId
                val wt = wireToPos
                if (wf != null && wt != null) {
                    val fr = rectByNode[wf]
                    if (fr != null) {
                        val outPort = Offset(fr[0] + fr[2], fr[1] + fr[3] / 2f)
                        val wireGraph = Offset((wt.x - panX) / scale, (wt.y - panY) / scale)
                        val midX = (outPort.x + wireGraph.x) / 2f
                        val path = Path().apply {
                            moveTo(outPort.x, outPort.y)
                            cubicTo(midX, outPort.y, midX, wireGraph.y, wireGraph.x, wireGraph.y)
                        }
                        drawPath(path, color = PitchYellow, style = Stroke(width = 2.5f / scale))
                        drawCircle(PitchYellow, 8f / scale, wireGraph)
                    }
                }
            }
            // Node-body overlays.
            for (n in graph.nodes) {
                val rect = rectByNode[n.id] ?: continue
                NodeBody(
                    viewModel = viewModel,
                    node = n,
                    widthPx = rect[2],
                    heightPx = rect[3],
                    selected = n.id == selectedNode,
                    expanded = n.id in expandedSet,
                    pinned = n.id in pinnedSet,
                    densityRatio = density.density,
                    onToggleExpand = {
                        // Predict the next expanded set, re-pack with this
                        // node pinned so it stays put, then toggle.  Siblings
                        // get pushed out of the way at the same time the
                        // size animation expands this card.
                        val nextExpanded =
                            if (n.id in expandedSet) expandedSet - n.id
                            else expandedSet + n.id
                        val packed = resolveOverlapsPerNode(
                            nodes = viewModel.graph.value.nodes,
                            pinnedId = n.id,
                            sizeOf = { id ->
                                if (id in nextExpanded) 5f to 4f
                                else NODE_W to NODE_H
                            },
                        )
                        viewModel.setNodePositions(packed)
                        viewModel.toggleGraphNodeExpanded(n.id)
                    },
                    modifier = Modifier
                        .offset { IntOffset(rect[0].toInt(), rect[1].toInt()) }
                        .size(
                            (rect[2] / density.density).dp,
                            (rect[3] / density.density).dp,
                        ),
                )
            }
        }

        // Floating zoom controls — stacked +/-/fit, top-right corner.
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ZoomCtl(Icons.Default.ZoomIn, "Zoom in") {
                val newScale = (scale * 1.25f).coerceIn(0.3f, 4f)
                val mid = Offset(canvasW / 2f, canvasH / 2f)
                val ratio = newScale / scale
                panX = mid.x - (mid.x - panX) * ratio
                panY = mid.y - (mid.y - panY) * ratio
                scale = newScale
            }
            ZoomCtl(Icons.Default.ZoomOut, "Zoom out") {
                val newScale = (scale / 1.25f).coerceIn(0.3f, 4f)
                val mid = Offset(canvasW / 2f, canvasH / 2f)
                val ratio = newScale / scale
                panX = mid.x - (mid.x - panX) * ratio
                panY = mid.y - (mid.y - panY) * ratio
                scale = newScale
            }
            ZoomCtl(Icons.Default.FitScreen, "Fit to screen") {
                if (graph.nodes.isNotEmpty()) {
                    val bx0 = graph.nodes.minOf { it.gridX } - 0.5f
                    val by0 = graph.nodes.minOf { it.gridY } - 0.5f
                    val bx1 = graph.nodes.maxOf { it.gridX } + NODE_W + 0.5f
                    val by1 = graph.nodes.maxOf { it.gridY } + NODE_H + 0.5f
                    val wPx = (bx1 - bx0) * unitPx
                    val hPx = (by1 - by0) * unitPx
                    val sx = canvasW / wPx
                    val sy = canvasH / hPx
                    scale = minOf(sx, sy).coerceIn(0.3f, 4f)
                    panX = -bx0 * unitPx * scale + 12f
                    panY = -by0 * unitPx * scale + 12f
                }
            }
        }
    }

    // Edge inspector — opens when the user taps a wire.  Mirrors slim's
    // EdgeInspectorDialog: shows source-output / target-input gain sliders
    // and a Keep / Delete pair.  Self-clears if the underlying edge is
    // removed by some other path (e.g. node delete cascading).
    selectedEdge?.let { (from, to) ->
        val edgeStillExists = graph.edges.any { it.from == from && it.to == to }
        if (!edgeStillExists) {
            selectedEdge = null
        } else {
            EdgeInspectorDialog(
                viewModel = viewModel,
                from = from,
                to = to,
                onDismiss = { selectedEdge = null },
            )
        }
    }
}

@Composable
private fun EdgeInspectorDialog(
    viewModel: AudioGraphViewModel,
    from: NodeId,
    to: NodeId,
    onDismiss: () -> Unit,
) {
    val graph by viewModel.graph.collectAsState()
    val fromNode = graph.nodes.firstOrNull { it.id == from } ?: run { onDismiss(); return }
    val toNode = graph.nodes.firstOrNull { it.id == to } ?: run { onDismiss(); return }
    FullScreenDialog(
        onDismiss = onDismiss,
        dismissOnClickOutside = true,
        backgroundColor = Color.Black.copy(alpha = 0.55f),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 24.dp)
                .background(Color(0xFF111111), RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(10.dp))
                .padding(14.dp),
        ) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Cable,
                        contentDescription = null,
                        tint = PitchYellow,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Connection",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text("Close", color = PitchYellow) }
                }
                Text(
                    "${fromNode.label.ifBlank { fromNode.kind.name }}  →  " +
                        toNode.label.ifBlank { toNode.kind.name },
                    color = NoteLabel,
                    fontSize = 12.sp,
                )

                GainRow(
                    label = "Output of " + fromNode.label.ifBlank { fromNode.kind.name },
                    db = fromNode.outputGainDb,
                    onChange = { viewModel.setNodeOutputGain(fromNode.id, it) },
                )
                GainRow(
                    label = "Input of " + toNode.label.ifBlank { toNode.kind.name },
                    db = toNode.inputGainDb,
                    onChange = { viewModel.setNodeInputGain(toNode.id, it) },
                )

                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) { Text("Keep", color = PitchYellow) }
                    Button(
                        onClick = {
                            viewModel.disconnectNodes(from, to)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE25656),
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Delete")
                    }
                }
            }
        }
    }
}

@Composable
private fun GainRow(label: String, db: Float, onChange: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = NoteLabel,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatDb(db),
                color = PitchYellow,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
        Slider(
            value = db,
            onValueChange = onChange,
            valueRange = -24f..24f,
            colors = SliderDefaults.colors(
                thumbColor = PitchYellow,
                activeTrackColor = PitchYellow,
            ),
        )
    }
}

private fun formatDb(db: Float): String {
    val sign = if (db >= 0f) "+" else "-"
    val absVal = if (db >= 0f) db else -db
    val whole = absVal.toInt()
    val frac = ((absVal - whole) * 10f + 0.5f).toInt().coerceIn(0, 9)
    return "$sign$whole.$frac dB"
}

/**
 * Pairwise rigid-body separation pass.  For every node pair, if
 * their AABBs (plus a small visual gap [padding]) overlap, pushes
 * them apart along the smaller-overlap axis.  [pinnedId] (typically
 * the node being dragged or expanded) is held fixed — siblings get
 * the displacement.  Multiple iterations let pushes cascade so
 * A → B → C stays a chain.
 *
 * Ported verbatim from slim — same defaults, same axis preference,
 * same pinned semantics.  Cheap for the 5–20-node graphs we
 * actually see (O(N² × iters), iters ≤ 12).
 */
private fun resolveOverlapsPerNode(
    nodes: List<GraphNode>,
    pinnedId: NodeId?,
    sizeOf: (NodeId) -> Pair<Float, Float>,
    padding: Float = 0.10f,
    maxIters: Int = 12,
): Map<NodeId, Pair<Float, Float>> {
    val pos = HashMap<NodeId, Pair<Float, Float>>(nodes.size)
    for (n in nodes) pos[n.id] = n.gridX to n.gridY
    for (iter in 0 until maxIters) {
        var anyMoved = false
        for (i in nodes.indices) {
            for (j in i + 1 until nodes.size) {
                val a = nodes[i]
                val b = nodes[j]
                val pa = pos[a.id] ?: continue
                val pb = pos[b.id] ?: continue
                val (aw, ah) = sizeOf(a.id)
                val (bw, bh) = sizeOf(b.id)
                val cxa = pa.first + aw / 2f
                val cya = pa.second + ah / 2f
                val cxb = pb.first + bw / 2f
                val cyb = pb.second + bh / 2f
                val dx = cxb - cxa
                val dy = cyb - cya
                val minDx = (aw + bw) / 2f + padding
                val minDy = (ah + bh) / 2f + padding
                val ox = minDx - abs(dx)
                val oy = minDy - abs(dy)
                if (ox <= 0f || oy <= 0f) continue
                var pushX = 0f
                var pushY = 0f
                if (ox < oy) {
                    pushX = if (dx >= 0f) ox else -ox
                } else {
                    pushY = if (dy >= 0f) oy else -oy
                }
                when {
                    a.id == pinnedId -> {
                        pos[b.id] = (pb.first + pushX) to (pb.second + pushY)
                    }
                    b.id == pinnedId -> {
                        pos[a.id] = (pa.first - pushX) to (pa.second - pushY)
                    }
                    else -> {
                        val hx = pushX / 2f
                        val hy = pushY / 2f
                        pos[a.id] = (pa.first - hx) to (pa.second - hy)
                        pos[b.id] = (pb.first + hx) to (pb.second + hy)
                    }
                }
                anyMoved = true
            }
        }
        if (!anyMoved) break
    }
    return pos
}

@Composable
private fun ZoomCtl(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = NoteLabel, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun NodeBody(
    viewModel: AudioGraphViewModel,
    node: GraphNode,
    widthPx: Float,
    heightPx: Float,
    selected: Boolean,
    expanded: Boolean,
    pinned: Boolean,
    densityRatio: Float,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (selected) PitchYellow else Color(0xFF2A2A35)
    val bg = if (selected) Color(0xFF2A2520) else Color(0xFF181820)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(6.dp)),
    ) {
        Column(Modifier.fillMaxSize()) {
            // Title bar.
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF22232C))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = node.label.takeIf { it.isNotEmpty() }
                        ?: node.effectKind?.name
                        ?: node.kind.name,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.SansSerif,
                    modifier = Modifier.weight(1f),
                )
                if (node.kind == NodeKind.Effect) {
                    Icon(
                        imageVector = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = null,
                        tint = if (pinned) PitchYellow else NoteLabel,
                        modifier = Modifier
                            .size(14.dp)
                            .clickable {
                                if (pinned) viewModel.unpinPanel(node.id)
                                else viewModel.pinPanel(node.id)
                            },
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (expanded) "▾" else "▸",
                        color = NoteLabel,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable(onClick = onToggleExpand),
                    )
                }
            }
            // Expanded body content — minted visual plugin instance
            // (one per node, persisted across recompositions) drawn
            // by PluginVisualSurface.  Non-visual plugins (pure-audio
            // ones whose class doesn't implement
            // VocalMonitorVisualPlugin) get a plain label placeholder.
            if (expanded && node.kind == NodeKind.Effect) {
                val pluginId = node.label
                // Same cached instance the audio loop pushes samples into.
                val visualPlugin = remember(node.id, pluginId) {
                    if (pluginId.isEmpty()) null
                    else viewModel.visualPluginFor(node.id)
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF0A0B0E)),
                ) {
                    if (visualPlugin != null) {
                        PluginVisualSurface(
                            plugin = visualPlugin,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            text = pluginId.ifEmpty { "(plugin)" },
                            color = NoteLabel,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(6.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val abx = b.x - a.x; val aby = b.y - a.y
    val apx = p.x - a.x; val apy = p.y - a.y
    val ablen2 = abx * abx + aby * aby
    if (ablen2 < 1e-6f) return sqrt(apx * apx + apy * apy)
    val t = ((apx * abx + apy * aby) / ablen2).coerceIn(0f, 1f)
    val cx = a.x + t * abx
    val cy = a.y + t * aby
    return sqrt((p.x - cx) * (p.x - cx) + (p.y - cy) * (p.y - cy))
}
