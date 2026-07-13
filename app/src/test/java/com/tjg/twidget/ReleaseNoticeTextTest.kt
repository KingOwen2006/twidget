package com.tjg.twidget

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNoticeTextTest {
    @Test
    fun releaseMarkdownBecomesReadableCardText() {
        val markdown = """
            ## Highlights
            - **Better analytics** with [range filters](https://example.com)
            - Calendar view
        """.trimIndent()

        assertEquals(
            "Highlights\n• Better analytics with range filters\n• Calendar view",
            ReleaseNoticeText.plainText(markdown),
        )
    }
}
