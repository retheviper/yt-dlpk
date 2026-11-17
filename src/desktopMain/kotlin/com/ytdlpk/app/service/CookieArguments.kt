package com.ytdlpk.app.service

import com.ytdlpk.app.model.CookieSettings
import com.ytdlpk.app.model.CookieSource
import java.nio.file.Files
import java.nio.file.Path

internal fun CookieSettings.arguments(): List<String> = when (source) {
    CookieSource.NONE -> emptyList()
    CookieSource.BROWSER -> {
        require(browserProfile.none { it == '\u0000' || it == '\n' || it == '\r' }) {
            "Browser profile contains invalid control characters"
        }
        val profile = browserProfile.trim().takeIf { it.isNotEmpty() }
        listOf("--cookies-from-browser", browser.argument + (profile?.let { ":$it" } ?: ""))
    }
    CookieSource.FILE -> {
        require(filePath.isNotBlank()) { "Select a Netscape cookies.txt file in Settings > Download Rules." }
        val path = Path.of(filePath)
        require(Files.isRegularFile(path) && Files.isReadable(path)) { "Cookie file is not a readable file: $filePath" }
        listOf("--cookies", path.toAbsolutePath().toString())
    }
}
