package com.ytdlpk.app.service

import com.ytdlpk.app.model.AppSettings
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import java.nio.file.Files

class SettingsRepository(
    private val appHome: Path
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val settingsFile = appHome.resolve("config.json")

    fun load(): AppSettings {
        return runCatching {
            if (!settingsFile.exists()) return AppSettings()
            json.decodeFromString(AppSettings.serializer(), settingsFile.readText())
        }.getOrElse { AppSettings() }
    }

    fun save(settings: AppSettings) {
        appHome.createDirectories()
        val tempFile = appHome.resolve("${settingsFile.fileName}.tmp")
        tempFile.writeText(json.encodeToString(AppSettings.serializer(), settings))
        runCatching {
            Files.move(tempFile, settingsFile, REPLACE_EXISTING, ATOMIC_MOVE)
        }.getOrElse {
            Files.move(tempFile, settingsFile, REPLACE_EXISTING)
        }
    }
}
