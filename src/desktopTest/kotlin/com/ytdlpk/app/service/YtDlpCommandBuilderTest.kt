package com.ytdlpk.app.service

import com.ytdlpk.app.model.DownloadOptions
import com.ytdlpk.app.model.FormatEntry
import com.ytdlpk.app.model.FormatKind
import com.ytdlpk.app.model.PlaylistMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

class YtDlpCommandBuilderTest : StringSpec({
    val builder = YtDlpCommandBuilder(FormatService(ProcessRunner()))

    fun options(
        playlistMode: PlaylistMode = PlaylistMode.PLAYLIST,
        selectedFormatTab: FormatKind = FormatKind.VIDEO_AUDIO,
        selectedFormat: FormatEntry? = null,
        includeAutoSubs: Boolean = false,
        extractAudio: Boolean = false,
        outputDirectory: String = "/downloads",
        fileNameTemplate: String = "%(title)s.%(ext)s"
    ) = DownloadOptions(
        url = "https://example.com/watch?v=abc",
        playlistMode = playlistMode,
        selectedFormatTab = selectedFormatTab,
        outputDirectory = outputDirectory,
        fileNameTemplate = fileNameTemplate,
        selectedFormat = selectedFormat,
        selectedVideoOnlyFormat = null,
        selectedAudioOnlyFormat = null,
        includeAutoSubs = includeAutoSubs,
        subLang = "en.*",
        extractAudio = extractAudio,
        audioFormat = "mp3",
        mergeOutputFormat = "mp4"
    )

    fun format(id: String, kind: FormatKind) = FormatEntry(
        formatId = id,
        ext = "mp4",
        resolution = "1920x1080",
        fps = 30,
        vcodec = "avc1",
        acodec = "mp4a",
        tbrKbps = 2000.0,
        abrKbps = null,
        note = "",
        kind = kind,
        rawText = ""
    )

    "adds no-playlist flag in single mode" {
        val command = builder.build("yt-dlp", "ffmpeg", options(playlistMode = PlaylistMode.SINGLE))

        command shouldContain "--no-playlist"
    }

    "builds selector with bestaudio merge for video-only format" {
        val command = builder.build(
            "yt-dlp",
            "ffmpeg",
            options(
                selectedFormatTab = FormatKind.VIDEO_AUDIO,
                selectedFormat = format("137", FormatKind.VIDEO_ONLY)
            )
        )

        val fIndex = command.indexOf("-f")
        command[fIndex + 1] shouldBe "137+bestaudio/best"
    }

    "builds selector without audio merge in video-only tab" {
        val command = builder.build(
            "yt-dlp",
            "ffmpeg",
            options(
                selectedFormatTab = FormatKind.VIDEO_ONLY,
                selectedFormat = format("137", FormatKind.VIDEO_ONLY)
            )
        )

        val fIndex = command.indexOf("-f")
        command[fIndex + 1] shouldBe "137"
    }

    "builds selector with explicit video+audio pairing when both are selected" {
        val video = format("137", FormatKind.VIDEO_ONLY)
        val audio = format("251", FormatKind.AUDIO_ONLY)
        val command = builder.build(
            "yt-dlp",
            "ffmpeg",
            options(selectedFormat = video).copy(
                selectedVideoOnlyFormat = video,
                selectedAudioOnlyFormat = audio
            )
        )

        val fIndex = command.indexOf("-f")
        command[fIndex + 1] shouldBe "137+251"
    }

    "builds selector without video merge in audio-only tab" {
        val video = format("137", FormatKind.VIDEO_ONLY)
        val audio = format("251", FormatKind.AUDIO_ONLY)
        val command = builder.build(
            "yt-dlp",
            "ffmpeg",
            options(
                selectedFormatTab = FormatKind.AUDIO_ONLY,
                selectedFormat = audio
            ).copy(selectedVideoOnlyFormat = video)
        )

        val fIndex = command.indexOf("-f")
        command[fIndex + 1] shouldBe "251"
    }

    "adds extraction and subtitle flags when enabled" {
        val command = builder.build(
            "yt-dlp",
            "ffmpeg",
            options(
                selectedFormat = format("140", FormatKind.AUDIO_ONLY),
                includeAutoSubs = true,
                extractAudio = true
            )
        )

        command shouldContainAll listOf(
            "-x",
            "--audio-format", "mp3",
            "--write-auto-subs", "--write-subs", "--sub-lang", "en.*", "--convert-subs", "srt"
        )
    }

    "rejects blank output directory" {
        shouldThrow<IllegalArgumentException> {
            builder.build("yt-dlp", "ffmpeg", options(outputDirectory = " "))
        }
    }

    "rejects shell-like template tokens" {
        shouldThrow<IllegalArgumentException> {
            builder.build("yt-dlp", "ffmpeg", options(fileNameTemplate = "$(whoami).%(ext)s"))
        }
    }
})
