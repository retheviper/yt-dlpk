package com.ytdlpk.app.model

import kotlinx.serialization.Serializable

@Serializable
enum class CookieSource { NONE, BROWSER, FILE }

@Serializable
enum class CookieBrowser(val argument: String, val label: String) {
    CHROME("chrome", "Chrome"),
    SAFARI("safari", "Safari"),
    FIREFOX("firefox", "Firefox"),
    EDGE("edge", "Edge"),
    BRAVE("brave", "Brave"),
    CHROMIUM("chromium", "Chromium"),
    OPERA("opera", "Opera"),
    VIVALDI("vivaldi", "Vivaldi"),
    WHALE("whale", "Whale")
}

@Serializable
data class CookieSettings(
    val source: CookieSource = CookieSource.NONE,
    val browser: CookieBrowser = CookieBrowser.CHROME,
    val browserProfile: String = "",
    val filePath: String = ""
)
