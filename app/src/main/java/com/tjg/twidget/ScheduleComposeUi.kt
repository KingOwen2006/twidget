package com.tjg.twidget

import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

internal class ScheduleComposeUi(
    private val activity: ScheduleActivity,
    root: View,
) {
    private val closeButton: ImageButton = root.findViewById(R.id.schedule_compose_close)
    private val draftsButton: TextView = root.findViewById(R.id.schedule_compose_drafts)
    private val submitButton: TextView = root.findViewById(R.id.schedule_compose_submit)
    private val avatarView: ImageView = root.findViewById(R.id.schedule_compose_avatar)
    private val mainInput: EditText = root.findViewById(R.id.schedule_compose_input)
    private val mediaStrip: HorizontalScrollView = root.findViewById(R.id.schedule_compose_media_strip)
    private val mediaContainer: LinearLayout = root.findViewById(R.id.schedule_compose_media_container)
    private val timeSummary: TextView = root.findViewById(R.id.schedule_compose_time_summary)
    private val attachMediaButton: ImageButton = root.findViewById(R.id.schedule_compose_attach_media)
    private val downloadMediaButton: ImageButton = root.findViewById(R.id.schedule_compose_download_media)
    private val pickTimeButton: ImageButton = root.findViewById(R.id.schedule_compose_pick_time)

    private var suppressTextEvents = false

    fun bind() {
        loadAvatar()
        closeButton.setOnClickListener { activity.onComposeCloseRequested() }
        draftsButton.setOnClickListener { activity.onComposeSaveDraftRequested() }
        submitButton.setOnClickListener { activity.onComposeSubmitRequested() }
        attachMediaButton.setOnClickListener { activity.onComposeAttachMedia() }
        downloadMediaButton.setOnClickListener { activity.onComposeDownloadMedia() }
        pickTimeButton.setOnClickListener { activity.onComposePickTimeRequested() }

        bindMainInput()
        refreshFromEditor()
    }

    fun refreshFromEditor() {
        suppressTextEvents = true
        mainInput.setText(activity.composeItemText())
        suppressTextEvents = false
        refreshMediaStrip(scrollToMedia = false)
        refreshTimeSummary()
        refreshDownloadButton()
        refreshSubmitState()
    }

    fun refreshTimeSummary() {
        timeSummary.text = activity.composeTimeSummaryText()
        timeSummary.visibility = View.VISIBLE
    }

    fun refreshSubmitState() {
        val enabled = !activity.composeIsBusy() && activity.composeHasContent()
        submitButton.isEnabled = enabled
        submitButton.alpha = if (enabled) 1f else 0.45f
    }

    fun setBusy(busy: Boolean) {
        closeButton.isEnabled = !busy
        draftsButton.isEnabled = !busy
        attachMediaButton.isEnabled = !busy
        downloadMediaButton.isEnabled = !busy
        pickTimeButton.isEnabled = !busy
        refreshSubmitState()
    }

    private fun loadAvatar() {
        val username = activity.composeAvatarUsername()
        val stats = if (username.isBlank()) {
            TwidgetStore.currentStats(activity)
        } else {
            TwidgetStore.currentStats(activity, username)
        }
        ProfileImageLoader.loadInto(activity, avatarView, stats.profileImage)
    }

    private fun bindMainInput() {
        mainInput.filters = arrayOf(InputFilter.LengthFilter(SchedulePolicy.MAX_TEXT_LENGTH))
        mainInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (suppressTextEvents) return
                activity.composeUpdateItemText(s?.toString().orEmpty())
                refreshSubmitState()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    fun refreshMediaForActiveItem() {
        refreshMediaStrip(scrollToMedia = activity.composeItemMedia().isNotEmpty())
        refreshDownloadButton()
    }

    private fun refreshDownloadButton() {
        downloadMediaButton.visibility = if (activity.composeItemMedia().isNotEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun refreshMediaStrip(scrollToMedia: Boolean) {
        mediaContainer.removeAllViews()
        val media = activity.composeItemMedia()
        if (media.isEmpty()) {
            mediaStrip.visibility = View.GONE
            return
        }
        mediaStrip.visibility = View.VISIBLE
        media.forEachIndexed { mediaIndex, source ->
            val preview = LayoutInflater.from(activity)
                .inflate(R.layout.item_schedule_media_preview, mediaContainer, false)
            val image = preview.findViewById<ImageView>(R.id.schedule_media_preview_image)
            val remove = preview.findViewById<ImageButton>(R.id.schedule_media_preview_remove)
            val download = preview.findViewById<ImageButton>(R.id.schedule_media_preview_download)
            bindMediaPreview(image, source)
            remove.setOnClickListener {
                activity.composeRemoveMedia(mediaIndex)
                refreshFromEditor()
            }
            download.setOnClickListener {
                activity.onComposeDownloadMedia(mediaIndex)
            }
            mediaContainer.addView(preview)
        }
        if (scrollToMedia) {
            mediaStrip.post {
                mediaStrip.requestRectangleOnScreen(android.graphics.Rect(), true)
            }
        }
    }

    private fun bindMediaPreview(image: ImageView, source: ScheduleMediaSource) {
        when (source) {
            is LocalUriMedia -> {
                image.scaleType = ImageView.ScaleType.CENTER_CROP
                image.setImageURI(android.net.Uri.parse(source.uri))
            }
            is PublicUrlMedia -> {
                ProfileImageLoader.loadMediaInto(activity, image, source.url, activity.composeDp(12))
            }
            is PostponeLibraryMedia -> {
                val url = source.url.orEmpty()
                if (url.isBlank()) {
                    image.setImageResource(R.drawable.ic_schedule_media)
                } else {
                    ProfileImageLoader.loadMediaInto(activity, image, url, activity.composeDp(12))
                }
            }
        }
    }
}
