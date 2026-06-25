package com.ytdlpk.app.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.Locale

class AppStateTest : StringSpec({
    fun format(id: String, kind: FormatKind) = FormatEntry(
        formatId = id,
        ext = "mp4",
        resolution = null,
        fps = null,
        vcodec = if (kind == FormatKind.VIDEO_AUDIO || kind == FormatKind.VIDEO_ONLY) "avc1.640028" else null,
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

    "selected video/audio helpers resolve by dedicated ids" {
        val state = AppState(
            formats = allFormats,
            selectedVideoOnlyFormatId = "137",
            selectedAudioOnlyFormatId = "140"
        )

        state.selectedVideoOnlyFormat?.formatId shouldBe "137"
        state.selectedAudioOnlyFormat?.formatId shouldBe "140"
    }

    "filteredFormats returns only matching tab entries" {
        AppState(formats = allFormats, selectedFormatTab = FormatKind.VIDEO_AUDIO)
            .filteredFormats
            .map { it.formatId } shouldContainExactly listOf("18", "137")

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

    "filters video formats by configured codec preference" {
        val h264 = format("137", FormatKind.VIDEO_ONLY).copy(vcodec = "avc1.640028")
        val vp9 = format("248", FormatKind.VIDEO_ONLY).copy(vcodec = "vp9")
        val audio = format("140", FormatKind.AUDIO_ONLY)

        val filtered = AppState(
            formats = listOf(h264, vp9, audio),
            selectedFormatTab = FormatKind.VIDEO_ONLY,
            settings = AppSettings(videoCodecPreference = VideoCodecPreference.H264)
        ).filteredFormats.map { it.formatId }

        filtered shouldContainExactly listOf("137")
    }

    "falls back to all video formats when codec preference has no matches" {
        val generic = format("http-720", FormatKind.VIDEO_ONLY).copy(vcodec = null, rawText = "720p mp4")
        val audio = format("140", FormatKind.AUDIO_ONLY)

        val filtered = AppState(
            formats = listOf(generic, audio),
            selectedFormatTab = FormatKind.VIDEO_AUDIO,
            settings = AppSettings(videoCodecPreference = VideoCodecPreference.H264)
        ).filteredFormats.map { it.formatId }

        filtered shouldContainExactly listOf("http-720")
    }

    "defaults video codec preference to h264" {
        AppSettings().videoCodecPreference shouldBe VideoCodecPreference.H264
    }

    "system language resolves supported locales and falls back to english" {
        systemAppLanguage(Locale.JAPANESE) shouldBe AppLanguage.JAPANESE
        systemAppLanguage(Locale.KOREAN) shouldBe AppLanguage.KOREAN
        systemAppLanguage(Locale.FRENCH) shouldBe AppLanguage.ENGLISH
        AppLanguage.SYSTEM.resolve(Locale.KOREAN) shouldBe AppLanguage.KOREAN
    }
})
