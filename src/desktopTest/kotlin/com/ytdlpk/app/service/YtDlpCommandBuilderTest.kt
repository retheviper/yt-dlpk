package com.ytdlpk.app.service

import com.ytdlpk.app.model.DownloadOptions
import com.ytdlpk.app.model.FormatEntry
import com.ytdlpk.app.model.FormatKind
import com.ytdlpk.app.model.PlaylistMode
import com.ytdlpk.app.model.VideoCodecPreference
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

    "separates a URL from options and ignores external CLI configuration" {
        val command = builder.build("yt-dlp", "ffmpeg", options().copy(url = "--version"))
        command.takeLast(2) shouldBe listOf("--", "--version")
        command shouldContain "--ignore-config"
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
        command[fIndex + 1] shouldBe "137+bestaudio/bestvideo+bestaudio/best"
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
        command[fIndex + 1] shouldBe "137/bestvideo"
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
        command[fIndex + 1] shouldBe "137+251/bestvideo+bestaudio/best"
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
        command[fIndex + 1] shouldBe "251/bestaudio/best"
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

    "uses quick format selector when provided" {
        val command = builder.build(
            "yt-dlp",
            "ffmpeg",
            options().copy(quickFormatSelector = "bestvideo[height<=1080]+bestaudio/best")
        )

        val fIndex = command.indexOf("-f")
        command[fIndex + 1] shouldBe "bestvideo[height<=1080]+bestaudio/best"
    }

    "quick format selector overrides selected format" {
        val command = builder.build(
            "yt-dlp",
            "ffmpeg",
            options(
                selectedFormatTab = FormatKind.VIDEO_AUDIO,
                selectedFormat = format("137", FormatKind.VIDEO_ONLY)
            ).copy(quickFormatSelector = "bestaudio/best")
        )

        val fIndex = command.indexOf("-f")
        command[fIndex + 1] shouldBe "bestaudio/best"
    }

    "adds codec filter to selected video format" {
        val command = builder.build(
            "yt-dlp",
            "ffmpeg",
            options(
                selectedFormatTab = FormatKind.VIDEO_AUDIO,
                selectedFormat = format("137", FormatKind.VIDEO_ONLY)
            ).copy(videoCodecPreference = VideoCodecPreference.H264)
        )

        val fIndex = command.indexOf("-f")
        command[fIndex + 1] shouldBe "137[vcodec^=avc1]+bestaudio/bestvideo+bestaudio/best"
    }

    "does not add codec filter when selected format has no matching codec metadata" {
        val command = builder.build(
            "yt-dlp",
            "ffmpeg",
            options(
                selectedFormatTab = FormatKind.VIDEO_AUDIO,
                selectedFormat = format("http-720", FormatKind.VIDEO_ONLY).copy(vcodec = null, rawText = "720p mp4")
            ).copy(videoCodecPreference = VideoCodecPreference.H264)
        )

        val fIndex = command.indexOf("-f")
        command[fIndex + 1] shouldBe "http-720+bestaudio/bestvideo+bestaudio/best"
    }

    "does not add codec filter to paired video outside audio-only tab when metadata does not match" {
        val video = format("http-720", FormatKind.VIDEO_ONLY).copy(vcodec = null, rawText = "720p mp4")
        val audio = format("audio", FormatKind.AUDIO_ONLY)
        val command = builder.build(
            "yt-dlp",
            "ffmpeg",
            options(
                selectedFormatTab = FormatKind.VIDEO_AUDIO,
                selectedFormat = audio
            ).copy(
                selectedVideoOnlyFormat = video,
                videoCodecPreference = VideoCodecPreference.H264
            )
        )

        val fIndex = command.indexOf("-f")
        command[fIndex + 1] shouldBe "http-720+audio/bestvideo+bestaudio/best"
    }

    "does not force mp4 merge output for quick selector" {
        val command = builder.build(
            "yt-dlp",
            "ffmpeg",
            options().copy(quickFormatSelector = "bestvideo[vcodec^=avc1]+bestaudio/bestvideo+bestaudio/best")
        )

        command.contains("--merge-output-format") shouldBe false
    }

    "forces mp4 merge output only for compatible explicit video and audio selections" {
        val video = format("137", FormatKind.VIDEO_ONLY)
        val audio = format("140", FormatKind.AUDIO_ONLY).copy(ext = "m4a")
        val command = builder.build(
            "yt-dlp",
            "ffmpeg",
            options(selectedFormat = video).copy(
                selectedVideoOnlyFormat = video,
                selectedAudioOnlyFormat = audio,
                videoCodecPreference = VideoCodecPreference.H264
            )
        )

        command shouldContainAll listOf("--merge-output-format", "mp4")
    }

    "does not force mp4 merge output for generic non-matching video selection" {
        val command = builder.build(
            "yt-dlp",
            "ffmpeg",
            options(
                selectedFormatTab = FormatKind.VIDEO_AUDIO,
                selectedFormat = format("http-720", FormatKind.VIDEO_ONLY).copy(vcodec = null, rawText = "720p webm", ext = "webm")
            )
        )

        command.contains("--merge-output-format") shouldBe false
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
