package com.ytdlpk.app.service

import com.ytdlpk.app.model.ProgressInfo

class ProgressParser {
    fun parse(line: String, current: ProgressInfo): ProgressInfo {
        val itemMatch = ITEM_REGEX.find(line)
        if (itemMatch != null) {
            return current.copy(
                itemIndex = itemMatch.groupValues[1].toIntOrNull(),
                itemTotal = itemMatch.groupValues[2].toIntOrNull()
            )
        }

        val destinationMatch = DEST_REGEX.find(line)
        if (destinationMatch != null) {
            return current.copy(currentFile = destinationMatch.groupValues[1])
        }

        val progressMatch = DOWNLOAD_REGEX.find(line)
        if (progressMatch != null) {
            return current.copy(
                percent = progressMatch.groupValues[1].toDoubleOrNull(),
                speed = progressMatch.groupValues[2].ifBlank { null },
                eta = progressMatch.groupValues[3].ifBlank { null }
            )
        }

        return current
    }

    companion object {
        private val ITEM_REGEX = Regex("Downloading item (\\d+) of (\\d+)")
        private val DEST_REGEX = Regex("Destination:\\s+(.+)$")
        private val DOWNLOAD_REGEX = Regex("\\[download]\\s+(\\d+(?:\\.\\d+)?)%.*?at\\s+([^\\s]+).*?ETA\\s+([^\\s]+)")
    }
}
