package com.ytdlpk.app.util

import java.io.File
import java.awt.Color
import java.awt.KeyboardFocusManager
import java.awt.SystemTray
import java.awt.Taskbar
import java.awt.TrayIcon
import java.awt.Window
import java.awt.image.BufferedImage
import kotlin.math.roundToInt
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

fun isAppWindowFocused(): Boolean {
    return runCatching {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow?.isFocused == true
    }.getOrDefault(false)
}

fun showDesktopNotification(title: String, message: String) {
    NotificationTrayHolder.notify(title, message)
}

fun updateAppIconProgress(window: Window, isDownloading: Boolean, percent: Double?) {
    runCatching {
        if (!Taskbar.isTaskbarSupported()) return
        val taskbar = Taskbar.getTaskbar()
        val supportsState = taskbar.isSupported(Taskbar.Feature.PROGRESS_STATE_WINDOW)
        val supportsValue = taskbar.isSupported(Taskbar.Feature.PROGRESS_VALUE_WINDOW)
        val supportsBadge = taskbar.isSupported(Taskbar.Feature.ICON_BADGE_TEXT)

        if (!isDownloading) {
            if (supportsState) taskbar.setWindowProgressState(window, Taskbar.State.OFF)
            if (supportsBadge) taskbar.setIconBadge("")
            return
        }

        if (supportsState) {
            val state = if (percent == null) Taskbar.State.INDETERMINATE else Taskbar.State.NORMAL
            taskbar.setWindowProgressState(window, state)
        }
        if (percent != null && supportsValue) {
            val value = percent.roundToInt().coerceIn(0, 100)
            taskbar.setWindowProgressValue(window, value)
        }
        if (supportsBadge) {
            val badgeText = percent?.roundToInt()?.coerceIn(0, 100)?.let { "$it%" } ?: "..."
            taskbar.setIconBadge(badgeText)
        }
    }
}

private object NotificationTrayHolder {
    private val trayIcon: TrayIcon? by lazy {
        if (!SystemTray.isSupported()) return@lazy null
        runCatching {
            val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            try {
                g.color = Color(0x33, 0x99, 0xFF)
                g.fillOval(2, 2, 12, 12)
            } finally {
                g.dispose()
            }
            TrayIcon(image, "yt-dlpk").apply {
                isImageAutoSize = true
                SystemTray.getSystemTray().add(this)
            }
        }.getOrNull()
    }

    fun notify(title: String, message: String) {
        runCatching {
            trayIcon?.displayMessage(title, message, TrayIcon.MessageType.INFO)
        }
    }
}
