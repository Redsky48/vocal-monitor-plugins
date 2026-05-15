package com.vocalmonitor.ui

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Desktop actual — AWT `FileDialog` in LOAD mode.  Native dialog on
 * every OS the JDK targets.  Filter is a `FilenameFilter` that
 * accepts the file extensions Java Sound's `AudioSystem` knows how
 * to decode out of the box (wav / au / aif/aiff) plus mp3 (covered
 * by jlayer once we add that codec, but still picker-acceptable so
 * the user isn't surprised when a future audio engine lands).
 */
actual fun pickAudioFile(): String? {
    val dlg = FileDialog(null as Frame?, "Choose audio file", FileDialog.LOAD).apply {
        setFilenameFilter { _, name ->
            val n = name.lowercase()
            n.endsWith(".wav") || n.endsWith(".au") ||
                n.endsWith(".aif") || n.endsWith(".aiff") ||
                n.endsWith(".mp3") || n.endsWith(".flac") ||
                n.endsWith(".ogg")
        }
        isVisible = true
    }
    val dir = dlg.directory ?: return null
    val file = dlg.file ?: return null
    return File(dir, file).absolutePath
}
