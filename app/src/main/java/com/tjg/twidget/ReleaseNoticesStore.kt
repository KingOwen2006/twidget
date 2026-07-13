package com.tjg.twidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CachedReleaseNotices(
    val notices: List<ReleaseNotice>,
    val cachedAt: Long,
)

object ReleaseNoticesStore {
    private const val PREFS = "twidget_release_notices"
    private const val KEY_NOTICES = "notices"
    private const val KEY_CACHED_AT = "cached_at"

    fun cached(context: Context): CachedReleaseNotices {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_NOTICES, null) ?: return CachedReleaseNotices(emptyList(), 0L)
        val notices = runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                ReleaseNotice(
                    tag = item.getString("tag"),
                    title = item.getString("title"),
                    body = item.optString("body"),
                    url = item.getString("url"),
                    prerelease = item.optBoolean("prerelease"),
                    publishedAt = item.optString("publishedAt"),
                )
            }
        }.getOrDefault(emptyList())
        return CachedReleaseNotices(notices, prefs.getLong(KEY_CACHED_AT, 0L))
    }

    fun save(context: Context, notices: List<ReleaseNotice>, now: Long = System.currentTimeMillis()) {
        val encoded = JSONArray(notices.map { notice ->
            JSONObject()
                .put("tag", notice.tag)
                .put("title", notice.title)
                .put("body", notice.body)
                .put("url", notice.url)
                .put("prerelease", notice.prerelease)
                .put("publishedAt", notice.publishedAt)
        }).toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_NOTICES, encoded)
            .putLong(KEY_CACHED_AT, now)
            .apply()
    }
}

object ReleaseNoticeText {
    fun plainText(markdown: String): String = markdown
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("!\\[([^]]*)]\\([^)]+\\)"), "$1")
        .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
        .replace(Regex("[*_]{1,2}([^*_]+)[*_]{1,2}"), "$1")
        .replace(Regex("(?m)^\\s*[-*+]\\s+"), "• ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
