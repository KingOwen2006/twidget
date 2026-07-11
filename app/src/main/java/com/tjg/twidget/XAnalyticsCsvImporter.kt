package com.tjg.twidget

import java.io.Reader
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

data class XAnalyticsImport(
    val samples: List<HistorySample>,
    val firstDate: LocalDate,
    val lastDate: LocalDate,
)

/**
 * Parses X Premium's Account overview CSV export. The export does not identify
 * its account and contains daily follow/unfollow movements rather than follower
 * totals, so the caller must explicitly choose an account and supply its live
 * follower count as the trusted anchor.
 */
object XAnalyticsCsvImporter {
    private const val MAX_CSV_CHARS = 1_000_000
    private val dateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d, uuuu", Locale.US)
        .withResolverStyle(ResolverStyle.STRICT)

    fun parse(
        reader: Reader,
        anchorFollowers: Long,
        today: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): XAnalyticsImport {
        require(anchorFollowers >= 0L) { "The selected account has no usable follower count yet." }
        val records = parseCsv(reader)
        require(records.size >= 2) { "The file does not contain any analytics rows." }

        val header = records.first().mapIndexed { index, value ->
            value.removePrefix("\uFEFF").trim().lowercase(Locale.US) to index
        }.toMap()
        val dateIndex = header["date"] ?: throw IllegalArgumentException("The Date column is missing.")
        val followsIndex = header["new follows"]
            ?: throw IllegalArgumentException("The New follows column is missing.")
        val unfollowsIndex = header["unfollows"]
            ?: throw IllegalArgumentException("The Unfollows column is missing.")
        val requiredWidth = maxOf(dateIndex, followsIndex, unfollowsIndex) + 1

        val days = records.drop(1).filterNot { row -> row.all { it.isBlank() } }.mapIndexed { offset, row ->
            require(row.size >= requiredWidth) { "Row ${offset + 2} is incomplete." }
            DailyMovement(
                date = runCatching { LocalDate.parse(row[dateIndex].trim(), dateFormatter) }
                    .getOrElse { throw IllegalArgumentException("Row ${offset + 2} has an invalid date.") },
                newFollows = unsignedLong(row[followsIndex], offset + 2, "New follows"),
                unfollows = unsignedLong(row[unfollowsIndex], offset + 2, "Unfollows"),
            )
        }.sortedByDescending { it.date }

        require(days.isNotEmpty()) { "The file does not contain any analytics rows." }
        require(days.size <= 366) { "The export contains more than one year of data." }
        require(days.map { it.date }.distinct().size == days.size) { "The export contains duplicate dates." }
        require(days.first().date == today) {
            "The newest row must be today so the live follower count can anchor the import."
        }
        days.zipWithNext().forEach { (newer, older) ->
            require(older.date == newer.date.minusDays(1)) {
                "The export has a gap between ${older.date} and ${newer.date}."
            }
        }

        var followers = anchorFollowers
        val samples = days.map { day ->
            require(followers >= 0L) { "The follow/unfollow totals produce an impossible follower count." }
            val sample = HistorySample(
                dayLabel = day.date.format(DateTimeFormatter.ofPattern("MMM d", Locale.US)),
                followers = followers,
                following = 0L,
                posts = 0L,
                likes = 0L,
                timestamp = day.date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                followersKnown = true,
                followingKnown = false,
                postsKnown = false,
                likesKnown = false,
                imported = true,
            )
            followers -= day.newFollows - day.unfollows
            sample
        }.asReversed()

        return XAnalyticsImport(
            samples = samples,
            firstDate = days.last().date,
            lastDate = days.first().date,
        )
    }

    private fun unsignedLong(value: String, row: Int, column: String): Long {
        val clean = value.trim()
        require(clean.matches(Regex("[0-9]+"))) { "Row $row has an invalid $column value." }
        return clean.toLongOrNull() ?: throw IllegalArgumentException("Row $row has an invalid $column value.")
    }

    private fun parseCsv(reader: Reader): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        val text = buildString {
            val buffer = CharArray(4_096)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                require(length + count <= MAX_CSV_CHARS) { "The analytics file is too large." }
                append(buffer, 0, count)
            }
        }
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                quoted && char == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                !quoted && char == ',' -> {
                    row += field.toString()
                    field.setLength(0)
                }
                !quoted && (char == '\n' || char == '\r') -> {
                    row += field.toString()
                    field.setLength(0)
                    if (row.any { it.isNotEmpty() }) rows += row.toList()
                    row.clear()
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                }
                else -> field.append(char)
            }
            index++
        }
        require(!quoted) { "The file contains an unterminated quoted value." }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            if (row.any { it.isNotEmpty() }) rows += row.toList()
        }
        return rows
    }

    private data class DailyMovement(
        val date: LocalDate,
        val newFollows: Long,
        val unfollows: Long,
    )
}
