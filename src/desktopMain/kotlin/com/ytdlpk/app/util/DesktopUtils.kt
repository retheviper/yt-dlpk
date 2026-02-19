package com.ytdlpk.app.util

import java.io.File
import javax.swing.JFileChooser

fun pickDirectory(initialPath: String?): String? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
        if (!initialPath.isNullOrBlank()) {
            currentDirectory = File(initialPath)
        }
    }
    val result = chooser.showOpenDialog(null)
    return if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
}

fun formatDuration(seconds: Long?): String {
    if (seconds == null) return "-"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
