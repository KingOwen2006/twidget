package com.tjg.twidget

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatButton
import dev.oneuiproject.oneui.layout.ToolbarLayout
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class AnalyticsDetailsActivity : FoldablePopOverActivity() {
    private lateinit var content: LinearLayout
    private var selectedRange = AnalyticsRange.MONTH
    private var selectedMetric = ImportedAnalyticsMetric.IMPRESSIONS

    private val username: String
        get() = intent.getStringExtra(EXTRA_USERNAME).orEmpty().trim().trimStart('@')

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_analytics_details)
        content = findViewById(R.id.analytics_details_content)
        findViewById<ToolbarLayout>(R.id.analytics_details_root)
            .setNavigationButtonOnClickListener { onBackPressedDispatcher.onBackPressed() }
        applyEdgeToEdgeInsets(findViewById(R.id.analytics_details_root))
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        content.removeAllViews()
        content.addView(sectionTitle(getString(R.string.analytics_for_account, username.ifBlank { "—" })))
        addRangeSelector()

        val allSamples = ImportedAnalyticsStore.all(this, username)
        val samples = AnalyticsInsights.select(allSamples, selectedRange)
        val summary = AnalyticsInsights.summarize(samples)
        if (summary == null) {
            content.addView(emptyState())
            content.addView(importButton())
            TwidgetFonts.applyTo(content)
            return
        }

        if (summary.metric(selectedMetric) == null) {
            selectedMetric = summary.metrics.firstOrNull()?.metric ?: ImportedAnalyticsMetric.NEW_FOLLOWS
        }
        content.addView(coverageCard(summary))
        content.addView(sectionTitle(getString(R.string.analytics_overview)))
        addSummaryPair(
            metricTotal(summary, ImportedAnalyticsMetric.IMPRESSIONS),
            getString(R.string.x_impressions),
            metricTotal(summary, ImportedAnalyticsMetric.ENGAGEMENTS),
            getString(R.string.x_engagements),
        )
        addSummaryPair(
            summary.engagementRate?.let(::percent) ?: "—",
            getString(R.string.engagement_rate),
            signed(summary.netFollows),
            getString(R.string.analytics_net_follows),
        )
        addSummaryPair(
            metricTotal(summary, ImportedAnalyticsMetric.LIKES),
            getString(R.string.x_likes_received),
            metricTotal(summary, ImportedAnalyticsMetric.PROFILE_VISITS),
            getString(R.string.x_profile_visits),
        )

        content.addView(sectionTitle(getString(R.string.analytics_trend)))
        addMetricSelector(summary)
        content.addView(chartCard(samples))

        content.addView(sectionTitle(getString(R.string.analytics_all_metrics)))
        summary.metrics.forEach { content.addView(metricRow(it)) }
        content.addView(importButton())
        TwidgetFonts.applyTo(content)
    }

    private fun addRangeSelector() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(4), dp(10), dp(14))
        }
        AnalyticsRange.entries.forEach { range ->
            row.addView(chip(rangeLabel(range), range == selectedRange) {
                selectedRange = range
                render()
            })
        }
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
    }

    private fun addMetricSelector(summary: ImportedAnalyticsSummary) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), 0, dp(10), dp(12))
        }
        summary.metrics.forEach { metric ->
            row.addView(chip(metricLabel(metric.metric), metric.metric == selectedMetric) {
                selectedMetric = metric.metric
                render()
            })
        }
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
    }

    private fun coverageCard(summary: ImportedAnalyticsSummary): View = card().apply {
        addView(titleText(getString(R.string.analytics_imported_coverage)))
        val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        addView(metaText(getString(
            R.string.analytics_coverage_summary,
            formatter.format(summary.firstDate),
            formatter.format(summary.lastDate),
            summary.importedDays,
        )))
    }

    private fun addSummaryPair(firstValue: String, firstLabel: String, secondValue: String, secondLabel: String) {
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(summaryCard(firstValue, firstLabel), LinearLayout.LayoutParams(0, dp(118), 1f).apply {
                marginEnd = dp(5)
            })
            addView(summaryCard(secondValue, secondLabel), LinearLayout.LayoutParams(0, dp(118), 1f).apply {
                marginStart = dp(5)
            })
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            setMargins(dp(6), 0, dp(6), dp(10))
        })
    }

    private fun summaryCard(value: String, label: String): View = card(withMargins = false).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@AnalyticsDetailsActivity).apply {
            text = value
            includeFontPadding = false
            maxLines = 1
            setTextColor(getColor(R.color.oneui_text_primary))
            textSize = 27f
            typeface = Typeface.create("sec", Typeface.BOLD)
        })
        addView(metaText(label))
    }

    private fun chartCard(samples: List<XAnalyticsMovement>): View = card().apply {
        addView(titleText(metricLabel(selectedMetric)))
        val points = AnalyticsInsights.chartPoints(samples, selectedMetric)
        addView(MetricChartView(this@AnalyticsDetailsActivity).apply {
            contentDescription = getString(R.string.analytics_chart_description, metricLabel(selectedMetric))
            setSeries(points)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(250),
        ).apply { topMargin = dp(10) })
        addView(metaText(getString(R.string.analytics_chart_bucket_notice, points.size)))
    }

    private fun metricRow(metric: ImportedMetricSummary): View = card().apply {
        addView(titleText(metricLabel(metric.metric)))
        addView(metaText(getString(
            R.string.analytics_metric_summary,
            number(metric.total),
            String.format(Locale.US, "%.1f", metric.dailyAverage),
            metric.dataPoints,
        )))
    }

    private fun emptyState(): View = card().apply {
        gravity = Gravity.CENTER
        addView(titleText(getString(R.string.analytics_empty_title)).apply { gravity = Gravity.CENTER })
        addView(metaText(getString(R.string.analytics_empty_summary)).apply { gravity = Gravity.CENTER })
    }

    private fun importButton(): View = AppCompatButton(this).apply {
        setText(R.string.analytics_import_more)
        isAllCaps = false
        setOnClickListener {
            startActivity(
                Intent(this@AnalyticsDetailsActivity, AnalyticsImportActivity::class.java)
                    .putExtra(AnalyticsImportActivity.EXTRA_USERNAME, username)
            )
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52),
        ).apply { setMargins(dp(12), dp(12), dp(12), dp(24)) }
    }

    private fun chip(label: String, selected: Boolean, action: () -> Unit): View = AppCompatButton(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        minHeight = dp(42)
        minWidth = 0
        setPadding(dp(14), 0, dp(14), 0)
        if (selected) {
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.oneui_accent_translucent))
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(6) }
        setOnClickListener { action() }
    }

    private fun card(withMargins: Boolean = true): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = AppCompatResources.getDrawable(context, R.drawable.schedule_card_bg)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        if (withMargins) {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(6), 0, dp(6), dp(10)) }
        }
    }

    private fun sectionTitle(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 19f
        typeface = TwidgetFonts.oneUiSans(context, 700)
        setTextColor(getColor(R.color.oneui_text_primary))
        setPadding(dp(24), dp(14), dp(24), dp(8))
    }

    private fun titleText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 15f
        typeface = TwidgetFonts.oneUiSans(context, 700)
        setTextColor(getColor(R.color.oneui_text_primary))
    }

    private fun metaText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(getColor(R.color.oneui_text_secondary))
        setPadding(0, dp(6), 0, 0)
    }

    private fun metricTotal(summary: ImportedAnalyticsSummary, metric: ImportedAnalyticsMetric): String =
        summary.metric(metric)?.total?.let(::number) ?: "—"

    private fun metricLabel(metric: ImportedAnalyticsMetric): String = getString(when (metric) {
        ImportedAnalyticsMetric.IMPRESSIONS -> R.string.x_impressions
        ImportedAnalyticsMetric.ENGAGEMENTS -> R.string.x_engagements
        ImportedAnalyticsMetric.LIKES -> R.string.x_likes_received
        ImportedAnalyticsMetric.PROFILE_VISITS -> R.string.x_profile_visits
        ImportedAnalyticsMetric.REPLIES -> R.string.analytics_replies
        ImportedAnalyticsMetric.REPOSTS -> R.string.analytics_reposts
        ImportedAnalyticsMetric.SHARES -> R.string.analytics_shares
        ImportedAnalyticsMetric.BOOKMARKS -> R.string.analytics_bookmarks
        ImportedAnalyticsMetric.POSTS_CREATED -> R.string.analytics_posts_created
        ImportedAnalyticsMetric.VIDEO_VIEWS -> R.string.analytics_video_views
        ImportedAnalyticsMetric.MEDIA_VIEWS -> R.string.analytics_media_views
        ImportedAnalyticsMetric.NEW_FOLLOWS -> R.string.analytics_new_follows
        ImportedAnalyticsMetric.UNFOLLOWS -> R.string.analytics_unfollows
    })

    private fun rangeLabel(range: AnalyticsRange): String = getString(when (range) {
        AnalyticsRange.WEEK -> R.string.analytics_range_7d
        AnalyticsRange.MONTH -> R.string.analytics_range_30d
        AnalyticsRange.QUARTER -> R.string.analytics_range_90d
        AnalyticsRange.YEAR -> R.string.analytics_range_1y
    })

    private fun number(value: Long): String = NumberFormat.getIntegerInstance(Locale.US).format(value)
    private fun signed(value: Long): String = if (value > 0) "+${number(value)}" else number(value)
    private fun percent(value: Double): String = String.format(Locale.US, "%.2f%%", value * 100)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_USERNAME = "com.tjg.twidget.extra.ANALYTICS_USERNAME"
    }
}
