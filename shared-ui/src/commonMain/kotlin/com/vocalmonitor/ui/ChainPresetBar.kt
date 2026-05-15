package com.vocalmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "FX chain preset" bar — drops the entire effect graph in one tap.
 * Includes the factory presets shipped with the app and the user's own
 * saved chains. Used at the top of both the linear effects list and the
 * graph editor so swapping a whole vibe takes one click from anywhere.
 */
@Composable
fun ChainPresetBar(
    viewModel: AudioGraphViewModel,
    modifier: Modifier = Modifier,
) {
    val userPresets by viewModel.userChainPresets.collectAsState()
    val factory = viewModel.factoryChainPresets
    var menuOpen by remember { mutableStateOf(false) }
    var saveDialogOpen by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var deleteCandidate by remember { mutableStateOf<String?>(null) }

    Row(
        modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Bookmark,
            contentDescription = "Chain presets",
            tint = PitchYellow,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "Chain presets",
            color = NoteLabel,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Box {
            TextButton(onClick = { menuOpen = true }) {
                Text("Load ▾", color = PitchYellow, fontSize = 11.sp)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (factory.isNotEmpty()) {
                    Text(
                        "FACTORY",
                        color = NoteLabel,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    factory.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.name) },
                            onClick = {
                                viewModel.loadChainPreset(preset)
                                menuOpen = false
                            },
                        )
                    }
                }
                if (userPresets.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        "MY CHAINS",
                        color = NoteLabel,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    userPresets.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.name) },
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        menuOpen = false
                                        deleteCandidate = preset.name
                                    },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        modifier = Modifier.size(16.dp),
                                        tint = NoteLabelDim,
                                    )
                                }
                            },
                            onClick = {
                                viewModel.loadChainPreset(preset)
                                menuOpen = false
                            },
                        )
                    }
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Save current as…") },
                    onClick = {
                        menuOpen = false
                        newName = ""
                        saveDialogOpen = true
                    },
                )
            }
        }
    }

    if (saveDialogOpen) {
        AlertDialog(
            onDismissRequest = { saveDialogOpen = false },
            title = { Text("Save chain preset") },
            text = {
                Column {
                    Text(
                        "Snapshot the entire effect graph — every node, every parameter, " +
                            "every wire. Loads back with one tap.",
                        color = NoteLabel,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        label = { Text("Preset name") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = newName.trim()
                    if (n.isNotEmpty()) viewModel.saveCurrentChainAsPreset(n)
                    saveDialogOpen = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { saveDialogOpen = false }) { Text("Cancel") }
            },
        )
    }

    deleteCandidate?.let { name ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete chain preset") },
            text = { Text("Remove \"$name\" from your saved chains?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteChainPreset(name)
                    deleteCandidate = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") }
            },
        )
    }
}
