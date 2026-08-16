package com.userexec.soneme.recorder

import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID

object RecorderNames {
    private val locale = Locale.US
    val filenameFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy, HH.mm.ss", locale)
    val fullUiFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy hh:mm a", locale)
    val dateUiFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy", locale)
    val dateWithWeekdayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", locale)
    val tdrcFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", locale)

    private val invalidChars = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
    private val uuidRegex = Regex("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    fun validateSeries(name: String): String? {
        if (name.isBlank()) return "Series title is required."
        return validateComponent(name)
    }

    fun validateTitle(title: String): String? {
        if (title.isBlank()) return null
        return validateComponent(title)
    }

    private fun validateComponent(value: String): String? {
        if (value != value.trim()) return "Leading or trailing spaces are not allowed."
        if (value == "." || value == "..") return "That name is reserved."
        if (value.endsWith('.')) return "Names may not end with a period."
        if (" - " in value) return "Names may not contain \" - \"."
        if (value.any { Character.isISOControl(it) || it in invalidChars }) {
            return "Name contains a character that cannot be used."
        }
        if (utf8Bytes(value) > 255) return "Name is too long."
        return null
    }

    fun titleForSave(raw: String): String = raw.ifBlank { "Untitled" }

    fun finalName(series: String, start: LocalDateTime, title: String): String =
        "$series - ${filenameFormatter.format(start)} - $title.mp3"

    fun tempName(series: String, start: LocalDateTime): String =
        "TEMP _ $series _ ${filenameFormatter.format(start)}.mp3"

    fun stagingName(): String = "SAVING _ ${UUID.randomUUID()}.mp3"

    fun isFinalNameWithinLimit(series: String, start: LocalDateTime, title: String): Boolean =
        utf8Bytes(finalName(series, start, titleForSave(title))) <= 255

    fun parseFinished(name: String): ParsedFinished? {
        if (!name.endsWith(".mp3", ignoreCase = true)) return null
        val stem = name.dropLast(4)
        val pieces = stem.split(" - ")
        if (pieces.size != 3) return null
        val series = pieces[0]
        val ts = pieces[1]
        val title = pieces[2]
        if (series.isBlank() || title.isBlank()) return null
        val timestamp = tryParseTimestamp(ts) ?: return null
        return ParsedFinished(series, timestamp, title)
    }

    fun parseTemp(name: String): ParsedTemp? {
        if (!name.endsWith(".mp3", ignoreCase = true)) return null
        if (!name.startsWith("TEMP _ ")) return null
        val stem = name.dropLast(4)
        val last = stem.lastIndexOf(" _ ")
        if (last <= "TEMP _ ".length) return null
        val seriesText = stem.substring("TEMP _ ".length, last)
        val timestampText = stem.substring(last + 3)
        if (seriesText.isBlank()) return null
        val timestamp = tryParseTimestamp(timestampText) ?: return null
        return ParsedTemp(seriesText, timestamp)
    }

    fun isStaging(name: String): Boolean {
        if (!name.startsWith("SAVING _ ") || !name.endsWith(".mp3", ignoreCase = true)) return false
        val uuid = name.substring("SAVING _ ".length, name.length - 4)
        return uuidRegex.matches(uuid)
    }

    private fun tryParseTimestamp(text: String): LocalDateTime? = try {
        LocalDateTime.parse(text, filenameFormatter)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun utf8Bytes(value: String): Int = value.toByteArray(StandardCharsets.UTF_8).size

    data class ParsedFinished(val seriesText: String, val timestamp: LocalDateTime, val title: String)
    data class ParsedTemp(val seriesText: String, val timestamp: LocalDateTime)
}
