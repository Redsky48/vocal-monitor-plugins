package com.vocalmonitor.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Full-screen dialog overlay used by AudioGraphSheet (and other
 * sheets ported from slim).  Implementation diverges enough between
 * Android and Desktop to warrant `expect`/`actual`:
 *
 *  - Android's actual mirrors slim's existing Theme.kt logic —
 *    routes around the known `decorFitsSystemWindows` Dialog bug
 *    (https://issuetracker.google.com/issues/246909281), pulls
 *    real insets from the host Activity window, applies a hard-
 *    coded minimum bottom cushion for 3-button-nav devices.
 *  - Desktop's actual is just a plain Compose `Window` since the
 *    OS handles edge-to-edge layout itself and there's no system
 *    bar to dodge.
 */
@Composable
expect fun FullScreenDialog(
    onDismiss: () -> Unit,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = false,
    backgroundColor: Color = Color(0xFF0B0B0B),
    content: @Composable BoxScope.() -> Unit,
)
