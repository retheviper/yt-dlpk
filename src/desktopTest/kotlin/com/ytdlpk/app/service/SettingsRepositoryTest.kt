package com.ytdlpk.app.service

import com.ytdlpk.app.model.AppLanguage
import com.ytdlpk.app.model.AppSettings
import com.ytdlpk.app.model.CookieBrowser
import com.ytdlpk.app.model.CookieSettings
import com.ytdlpk.app.model.CookieSource
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
            language = AppLanguage.JAPANESE,
            cookieSettings = CookieSettings(CookieSource.BROWSER, CookieBrowser.FIREFOX, "Profile 1", "/cookies.txt")
        )

        repo.save(saved)

        repo.load() shouldBe saved
        appHome.resolve("config.json").readText().contains("outputDirectory") shouldBe true
    }

    "existing configs keep their settings and default to no cookie authentication" {
        val appHome = createTempDirectory(prefix = "settings-test-")
        appHome.resolve("config.json").writeText("""{"outputDirectory":"/existing/downloads","language":"日本語"}""")
        val loaded = SettingsRepository(appHome).load()
        loaded.outputDirectory shouldBe "/existing/downloads"
        loaded.language shouldBe AppLanguage.JAPANESE
        loaded.cookieSettings shouldBe CookieSettings()
    }

    "falls back to defaults when config json is invalid" {
        val appHome = createTempDirectory(prefix = "settings-test-")
        appHome.resolve("config.json").writeText("{invalid json")
        val repo = SettingsRepository(appHome)

        repo.load() shouldBe AppSettings()
    }
})
