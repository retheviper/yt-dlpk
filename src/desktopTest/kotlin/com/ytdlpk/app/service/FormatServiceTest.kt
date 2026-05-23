package com.ytdlpk.app.service

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

class FormatServiceTest : StringSpec({
    "parses bitrate without confusing KiB or kHz" {
        val service = FormatService(ProcessRunner())
        val lines = listOf(
            "[info] Available formats for test:",
            "ID  EXT   RESOLUTION FPS |   FILESIZE   TBR PROTO | VCODEC        ACODEC",
            "--------------------------------------------------------------------------------",
            "137 mp4   1920x1080  30  |  1.20MiB   4100k https | avc1.640028   mp4a.40.2",
            "140 m4a   audio only      |  2.01MiB    128k https | audio only    mp4a.40.2",
            "249 webm  audio only      |  1.10MiB         https | audio only    opus @ 48kHz"
        )

        val entries = service.parseFormatTable(lines)

        entries.size shouldBe 3
        entries[0].tbrKbps shouldBe (4100.0 plusOrMinus 0.001)
        entries[0].vcodec shouldBe "avc1.640028"
        entries[1].tbrKbps shouldBe (128.0 plusOrMinus 0.001)
        entries[2].tbrKbps shouldBe null
    }

    "does not parse abr from audio notes when only kHz exists" {
        val service = FormatService(ProcessRunner())
        val lines = listOf(
            "ID  EXT  RESOLUTION | NOTE",
            "249 webm audio only | audio only opus @ 48kHz"
        )

        val entries = service.parseFormatTable(lines)

        entries.size shouldBe 1
        entries[0].abrKbps shouldBe null
    }

    "parses formats from yt-dlp json" {
        val service = FormatService(ProcessRunner())
        val formats = Json.parseToJsonElement(
            """
            [
              {"format_id":"137","ext":"mp4","width":1920,"height":1080,"fps":30,"vcodec":"avc1.640028","acodec":"none","tbr":4100.0},
              {"format_id":"140","ext":"m4a","resolution":"audio only","vcodec":"none","acodec":"mp4a.40.2","abr":128.0}
            ]
            """.trimIndent()
        ).jsonArray

        val entries = service.parseFormatJson(formats)

        entries.size shouldBe 2
        entries[0].formatId shouldBe "137"
        entries[0].resolution shouldBe "1920x1080"
        entries[0].kind shouldBe com.ytdlpk.app.model.FormatKind.VIDEO_ONLY
        entries[1].kind shouldBe com.ytdlpk.app.model.FormatKind.AUDIO_ONLY
    }
})
