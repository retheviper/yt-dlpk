package com.ytdlpk.app.service

import com.ytdlpk.app.model.AppSettings
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

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
        settingsFile.writeText(json.encodeToString(AppSettings.serializer(), settings))
    }
}
