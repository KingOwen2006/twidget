package com.tjg.twidget

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.view.SemBlurCompat
import androidx.core.view.SemBlurCompat.BLUR_MODE_WINDOW_CAPTURED
import androidx.core.view.SemBlurCompat.BLUR_UI_HIGH_ULTRA_THICK_DARK
import androidx.core.view.SemBlurCompat.BLUR_UI_HIGH_ULTRA_THICK_LIGHT
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.shape.RelativeCornerSize
import com.google.android.material.shape.RoundedCornerTreatment
import dev.oneuiproject.oneui.widget.RoundedNestedScrollView
import dev.oneuiproject.oneui.widget.ScrollAwareFloatingActionButton
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class NoticeDetailActivity : FoldablePopOverActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notice_detail)
        val root = findViewById<FrameLayout>(R.id.notice_detail_root)
        val scroll = findViewById<RoundedNestedScrollView>(R.id.notice_detail_scroll)
        val back = findViewById<ScrollAwareFloatingActionButton>(R.id.notice_detail_back)
        back.shapeAppearanceModel = back.shapeAppearanceModel.toBuilder()
            .setAllCorners(RoundedCornerTreatment())
            .setAllCornerSizes(RelativeCornerSize(0.5f))
            .build()
        back.rippleColor = Color.TRANSPARENT
        back.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        back.post { applyCircularBackgroundBlur(back) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            back.layoutParams = (back.layoutParams as FrameLayout.LayoutParams).apply {
                marginStart = safe.left + dp(18)
                topMargin = safe.top + dp(18)
            }
            scroll.setPadding(safe.left, 0, safe.right, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        val tag = intent.getStringExtra(EXTRA_NOTICE_TAG)
        val notice = ReleaseNoticesStore.cached(this).notices
            .firstOrNull { it.tag == tag }
        if (notice == null) {
            finish()
            return
        }

        findViewById<TextView>(R.id.notice_detail_title).text = notice.title
        findViewById<TextView>(R.id.notice_detail_meta).text = releaseMeta(notice)
        findViewById<TextView>(R.id.notice_detail_beta).visibility =
            if (notice.prerelease) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.notice_detail_body).text =
            ReleaseNoticeText.plainText(notice.body)
                .ifBlank { getString(R.string.notices_no_details) }
        findViewById<AppCompatButton>(R.id.notice_detail_release_button).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(notice.url)))
        }
        findViewById<AppCompatButton>(R.id.notice_detail_update_button).setOnClickListener {
            startLeftSidePopOverActivity(Intent(this, AboutActivity::class.java))
        }
    }

    private fun releaseMeta(notice: ReleaseNotice): String {
        val date = runCatching {
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .format(Instant.parse(notice.publishedAt).atZone(ZoneId.systemDefault()))
        }.getOrNull()
        return listOfNotNull(notice.tag, date).joinToString(" · ")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun applyCircularBackgroundBlur(back: ScrollAwareFloatingActionButton) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM ||
            !SemBlurCompat.isBlurEffectPresetSupport()
        ) return

        val isDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val preset = if (isDark) {
            BLUR_UI_HIGH_ULTRA_THICK_DARK
        } else {
            BLUR_UI_HIGH_ULTRA_THICK_LIGHT
        }
        val applied = SemBlurCompat.setBlurEffectPreset(
            back,
            BLUR_MODE_WINDOW_CAPTURED,
            preset,
            null,
            back.width / 2f,
        )
        if (applied) {
            // The Samsung blur layer supplies the surface. Leaving the FAB's own material
            // fill/elevation visible creates the smaller inner shape and obscures the blur.
            back.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            back.compatElevation = 0f
            back.compatHoveredFocusedTranslationZ = 0f
            back.compatPressedTranslationZ = 0f
        }
    }

    companion object {
        const val EXTRA_NOTICE_TAG = "notice_tag"
    }
}
