package com.ytdlpk.app.service

import com.ytdlpk.app.model.AppLanguage
import com.ytdlpk.app.model.AppSettings
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

class SettingsRepositoryTest : StringSpec({
    "returns defaults when config file does not exist" {
        val appHome = createTempDirectory(prefix = "settings-test-")
        val repo = SettingsRepository(appHome)

        repo.load() shouldBe AppSettings()
    }

    "saves and loads settings" {
        val appHome = createTempDirectory(prefix = "settings-test-")
        val repo = SettingsRepository(appHome)
        val saved = AppSettings(
            outputDirectory = "/tmp/output",
            fileNameTemplate = "%(title)s-custom.%(ext)s",
            includeAutoSubs = true,
            language = AppLanguage.JAPANESE
        )

        repo.save(saved)

        repo.load() shouldBe saved
        appHome.resolve("config.json").readText().contains("outputDirectory") shouldBe true
    }

    "falls back to defaults when config json is invalid" {
        val appHome = createTempDirectory(prefix = "settings-test-")
        appHome.resolve("config.json").writeText("{invalid json")
        val repo = SettingsRepository(appHome)

        repo.load() shouldBe AppSettings()
    }
})
