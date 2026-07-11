package com.tjg.twidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.time.LocalDate
import java.time.ZoneId

class XAnalyticsCsvImporterTest {
    @Test
    fun `reconstructs follower totals backwards from selected account anchor`() {
        val csv = """
            Date,Impressions,New follows,Unfollows
            "Sat, Jul 11, 2026",100,5,2
            "Fri, Jul 10, 2026",90,4,1
            "Thu, Jul 9, 2026",80,1,3
        """.trimIndent()

        val result = XAnalyticsCsvImporter.parse(
            StringReader(csv),
            anchorFollowers = 1_000,
            today = LocalDate.of(2026, 7, 11),
            zoneId = ZoneId.of("Europe/London"),
        )

        assertEquals(LocalDate.of(2026, 7, 9), result.firstDate)
        assertEquals(LocalDate.of(2026, 7, 11), result.lastDate)
        assertEquals(listOf(994L, 997L, 1_000L), result.samples.map { it.followers })
        assertTrue(result.samples.all { it.imported && it.followersKnown })
        assertTrue(result.samples.all { !it.estimated })
        assertTrue(result.samples.all { !it.followingKnown && !it.postsKnown && !it.likesKnown })
    }

    @Test
    fun `rejects stale exports that cannot be anchored accurately`() {
        val csv = """
            Date,New follows,Unfollows
            "Fri, Jul 10, 2026",1,0
        """.trimIndent()

        val error = runCatching {
            XAnalyticsCsvImporter.parse(
                StringReader(csv),
                anchorFollowers = 100,
                today = LocalDate.of(2026, 7, 11),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("newest row must be today", ignoreCase = true))
    }

    @Test
    fun `rejects missing dates instead of inventing movement`() {
        val csv = """
            Date,New follows,Unfollows
            "Sat, Jul 11, 2026",1,0
            "Thu, Jul 9, 2026",1,0
        """.trimIndent()

        val error = runCatching {
            XAnalyticsCsvImporter.parse(
                StringReader(csv),
                anchorFollowers = 100,
                today = LocalDate.of(2026, 7, 11),
            )
        }.exceptionOrNull()

        assertFalse(error == null)
        assertTrue(error?.message.orEmpty().contains("gap", ignoreCase = true))
    }
}
