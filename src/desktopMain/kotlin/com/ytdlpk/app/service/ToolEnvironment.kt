package com.ytdlpk.app.service

import java.io.File
import java.nio.file.Path

internal fun detectToolOs(): String {
    val name = System.getProperty("os.name").lowercase()
    return when {
        name.contains("win") -> "windows"
        name.contains("mac") -> "mac"
        else -> "linux"
    }
}

internal fun detectToolArch(): String = when (System.getProperty("os.arch").lowercase()) {
    "aarch64", "arm64" -> "arm64"
    else -> "x64"
}

internal fun systemToolDirectories(os: String = detectToolOs()): List<Path> {
    val environment = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .filter(String::isNotBlank).map(Path::of)
    val home = Path.of(System.getProperty("user.home"))
    val defaults = when (os) {
        "mac" -> listOf("/opt/homebrew/bin", "/usr/local/bin", "/opt/local/bin", "/usr/bin", "/bin").map(Path::of)
        "windows" -> emptyList()
        else -> listOf(home.resolve(".local/bin")) +
            listOf("/home/linuxbrew/.linuxbrew/bin", "/usr/local/bin", "/usr/bin", "/bin", "/snap/bin").map(Path::of)
    }
    return (environment + defaults + listOf(home.resolve(".deno/bin"))).distinct()
}
