package com.ytdlpk.app.service

import com.ytdlpk.app.model.ProgressInfo
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ProgressParserTest : StringSpec({
    val parser = ProgressParser()

    "parses playlist item progress" {
        val current = ProgressInfo(null, null, null, null, null, null)

        val parsed = parser.parse("[download] Downloading item 2 of 8", current)

        parsed.itemIndex shouldBe 2
        parsed.itemTotal shouldBe 8
    }

    "parses destination line" {
        val current = ProgressInfo(null, null, null, null, null, null)

        val parsed = parser.parse("[download] Destination: /tmp/video.mp4", current)

        parsed.currentFile shouldBe "/tmp/video.mp4"
    }

    "parses download percentage speed and eta" {
        val current = ProgressInfo(null, null, null, null, null, null)

        val parsed = parser.parse("[download]  54.3% of 10.24MiB at 1.25MiB/s ETA 00:12", current)

        parsed.percent shouldBe 54.3
        parsed.speed shouldBe "1.25MiB/s"
        parsed.eta shouldBe "00:12"
    }

    "parses completed download line without speed or eta" {
        val current = ProgressInfo(98.0, "1.0MiB/s", "00:01", null, null, null)

        val parsed = parser.parse("[download] 100% of 12.3MiB in 00:05", current)

        parsed.percent shouldBe 100.0
        parsed.speed shouldBe null
        parsed.eta shouldBe null
    }

    "returns current state for unrelated lines" {
        val current = ProgressInfo(12.0, "1.0MiB/s", "00:04", "a.mp4", 1, 3)

        val parsed = parser.parse("some unrelated output", current)

        parsed shouldBe current
    }
})
