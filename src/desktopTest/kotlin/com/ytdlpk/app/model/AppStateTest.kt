package com.ytdlpk.app.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class AppStateTest : StringSpec({
    fun format(id: String, kind: FormatKind) = FormatEntry(
        formatId = id,
        ext = "mp4",
        resolution = null,
        fps = null,
        vcodec = null,
        acodec = null,
        tbrKbps = null,
        abrKbps = null,
        note = null,
        kind = kind,
        rawText = ""
    )

    val allFormats = listOf(
        format("18", FormatKind.VIDEO_AUDIO),
        format("137", FormatKind.VIDEO_ONLY),
        format("140", FormatKind.AUDIO_ONLY),
        format("x", FormatKind.UNKNOWN)
    )

    "selectedFormat resolves by selectedFormatId" {
        val state = AppState(
            formats = allFormats,
            selectedFormatId = "137"
        )

        state.selectedFormat?.formatId shouldBe "137"
    }

    "filteredFormats returns only matching tab entries" {
        AppState(formats = allFormats, selectedFormatTab = FormatKind.VIDEO_AUDIO)
            .filteredFormats
            .map { it.formatId } shouldContainExactly listOf("18")

        AppState(formats = allFormats, selectedFormatTab = FormatKind.VIDEO_ONLY)
            .filteredFormats
            .map { it.formatId } shouldContainExactly listOf("137")

        AppState(formats = allFormats, selectedFormatTab = FormatKind.AUDIO_ONLY)
            .filteredFormats
            .map { it.formatId } shouldContainExactly listOf("140")
    }

    "unknown tab keeps all formats" {
        val filtered = AppState(formats = allFormats, selectedFormatTab = FormatKind.UNKNOWN)
            .filteredFormats
            .map { it.formatId }

        filtered shouldContainExactly listOf("18", "137", "140", "x")
    }
})
