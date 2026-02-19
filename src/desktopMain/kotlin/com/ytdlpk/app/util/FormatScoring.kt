package com.ytdlpk.app.util

import com.ytdlpk.app.model.FormatEntry

private val RESOLUTION_DIMENSION_REGEX = Regex("""(\d{3,4})x(\d{3,4})""")
private val RESOLUTION_P_REGEX = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)

fun formatSortScore(entry: FormatEntry): Long {
    val resolutionScore = parseResolutionScore(entry.resolution)
    val bitrateScore = ((entry.tbrKbps ?: entry.abrKbps ?: 0.0) * 100).toLong()
    val fpsScore = ((entry.fps ?: 0) * 1_000L)
    return resolutionScore * 1_000_000L + fpsScore + bitrateScore
}

fun parseResolutionScore(resolution: String?): Long {
    if (resolution.isNullOrBlank()) return 0L
    val dim = RESOLUTION_DIMENSION_REGEX.find(resolution)
    if (dim != null) {
        val w = dim.groupValues[1].toLongOrNull() ?: 0L
        val h = dim.groupValues[2].toLongOrNull() ?: 0L
        return w * h
    }
    val p = RESOLUTION_P_REGEX.find(resolution)
    if (p != null) {
        val h = p.groupValues[1].toLongOrNull() ?: 0L
        return h * h * 16 / 9
    }
    return 0L
}

fun resolutionMaxSide(resolution: String?): Long {
    val dim = RESOLUTION_DIMENSION_REGEX.find(resolution.orEmpty()) ?: return 0L
    val a = dim.groupValues[1].toLongOrNull() ?: 0L
    val b = dim.groupValues[2].toLongOrNull() ?: 0L
    return maxOf(a, b)
}

fun resolutionMinSide(resolution: String?): Long {
    val dim = RESOLUTION_DIMENSION_REGEX.find(resolution.orEmpty()) ?: return 0L
    val a = dim.groupValues[1].toLongOrNull() ?: 0L
    val b = dim.groupValues[2].toLongOrNull() ?: 0L
    return minOf(a, b)
}
