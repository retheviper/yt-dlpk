package com.ytdlpk.app.util

import com.ytdlpk.app.model.FormatEntry
import com.ytdlpk.app.model.FormatKind
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class FormatScoringTest : StringSpec({
    fun entry(
        resolution: String?,
        fps: Int? = null,
        tbr: Double? = null,
        abr: Double? = null
    ) = FormatEntry(
        formatId = "x",
        ext = "mp4",
        resolution = resolution,
        fps = fps,
        vcodec = null,
        acodec = null,
        tbrKbps = tbr,
        abrKbps = abr,
        note = null,
        kind = FormatKind.UNKNOWN,
        rawText = ""
    )

    "parseResolutionScore handles dimension and p notation" {
        parseResolutionScore("1920x1080") shouldBe 2_073_600L
        parseResolutionScore("1080p") shouldBe 2_073_600L
    }

    "parseResolutionScore returns zero for blank or unknown" {
        parseResolutionScore(null) shouldBe 0L
        parseResolutionScore("") shouldBe 0L
        parseResolutionScore("audio only") shouldBe 0L
    }

    "formatSortScore prioritizes resolution then fps and bitrate" {
        val highRes = formatSortScore(entry(resolution = "1920x1080", fps = 30, tbr = 500.0))
        val lowRes = formatSortScore(entry(resolution = "1280x720", fps = 60, tbr = 5000.0))

        (highRes > lowRes) shouldBe true
    }

    "formatSortScore uses abr when tbr is missing" {
        val score = formatSortScore(entry(resolution = "640x360", fps = 30, tbr = null, abr = 128.0))
        val expected = 230_400L * 1_000_000L + 30_000L + 12_800L

        score shouldBe expected
    }

    "resolution side helpers return max and min" {
        resolutionMaxSide("1920x1080") shouldBe 1920L
        resolutionMinSide("1920x1080") shouldBe 1080L
        resolutionMaxSide("unknown") shouldBe 0L
        resolutionMinSide("unknown") shouldBe 0L
    }
})
