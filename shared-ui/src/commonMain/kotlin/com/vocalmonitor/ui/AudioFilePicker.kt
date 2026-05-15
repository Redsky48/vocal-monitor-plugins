package com.vocalmonitor.ui

/**
 * Blocking native file picker for an audio file.  Returns the
 * absolute path the user chose, or `null` if they cancelled.
 *
 * Implemented on Desktop via AWT's [java.awt.FileDialog] (the only
 * truly-native chooser the JDK exposes — Swing's JFileChooser is
 * cross-platform but visibly non-native on Windows / macOS).  An
 * Android actual would launch a SAF intent or a platform document
 * picker, but slim already has its own UI for that so this surface
 * doesn't need to live in commonMain for Android.
 *
 * Must be called from the UI thread.  Will hang the thread until
 * the dialog closes — call from a Compose `LaunchedEffect` or from
 * a button click handler that doesn't need to return immediately.
 */
expect fun pickAudioFile(): String?
