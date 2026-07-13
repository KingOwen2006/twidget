package com.tjg.twidget

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import dev.oneuiproject.oneui.layout.ToolbarLayout
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class ScheduleActivity : FoldablePopOverActivity() {
    private lateinit var content: LinearLayout
    private lateinit var primaryButton: AppCompatButton
    private lateinit var queueRoot: View
    private lateinit var composePanel: View
    private var composeUi: ScheduleComposeUi? = null
    private val store by lazy { ScheduleStore(this) }
    private val coordinator by lazy { ScheduleCoordinator(this) }
    private val postponeClient by lazy { PostponeClient(this) }

    private var selectedQueueStatus: ScheduleStatus? = null
    private var selectedQueueView = ScheduleQueueView.LIST
    private var calendarMonth = YearMonth.now()
    private var selectedCalendarDate: LocalDate? = null
    private var editorPost: ScheduledPost? = null
    private var editorProvider = ScheduleProvider.LOCAL_REMINDER
    private var editorAccount = ""
    private var editorTime = Calendar.getInstance().apply {
        add(Calendar.HOUR_OF_DAY, 1)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    private val editorItems = mutableListOf<EditorItem>()
    private var mediaTarget = -1
    private var busy = false
    private var notificationWarningAccepted = false
    private var exactWarningAccepted = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationWarningAccepted = true
            if (!granted) toast(R.string.schedule_notification_permission)
            submitSchedule()
        }

    private val localMediaPicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val target = editorItems.getOrNull(mediaTarget) ?: return@registerForActivityResult
            val room = SchedulePolicy.MAX_MEDIA_PER_ITEM - target.media.size
            uris.take(room.coerceAtLeast(0)).forEach { uri ->
                val persisted = runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }.isSuccess
                if (persisted) {
                    target.media += LocalUriMedia(
                        uri = uri.toString(),
                        displayName = uri.lastPathSegment,
                        mimeType = contentResolver.getType(uri),
                    )
                } else {
                    toast(R.string.schedule_media_permission_failed)
                }
            }
            composeUi?.refreshMediaForActiveItem()
        }

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            onComposeCloseRequested()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule)
        content = findViewById(R.id.schedule_content)
        primaryButton = findViewById(R.id.schedule_primary_button)
        queueRoot = findViewById(R.id.schedule_root)
        composePanel = findViewById(R.id.schedule_compose_panel)
        composeUi = ScheduleComposeUi(this, composePanel)
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
        findViewById<ToolbarLayout>(R.id.schedule_root)
            .setNavigationButtonOnClickListener { onBackPressedDispatcher.onBackPressed() }
        applyEdgeToEdgeInsets(findViewById(R.id.schedule_root)) { inset ->
            primaryButton.updateBottomMargin(dp(20) + inset)
            composePanel.setPadding(0, inset, 0, 0)
        }

        val scheduleId = intent.getStringExtra(ScheduleDeepLink.EXTRA_SCHEDULE_ID)
        val post = scheduleId?.let(store::get)
        when {
            intent.action == ScheduleDeepLink.ACTION_OPEN_CHECKLIST ||
                post?.status == ScheduleStatus.NEEDS_ACTION -> {
                if (post == null) {
                    toast(R.string.schedule_not_found)
                    renderQueue()
                } else {
                    renderChecklist(post)
                }
            }
            post != null -> openEditor(post)
            else -> renderQueue()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun renderQueue() {
        editorPost = null
        showQueueMode()
        content.removeAllViews()
        content.addView(sectionTitle(getString(R.string.schedule_queue_for, queueAccountLabel())))
        addQueueViewFilters()
        addStatusFilters()

        val username = requestedUsername()
        val allPosts = if (username.isBlank()) store.list() else store.listForAccount(username)
        val posts = allPosts.filter { selectedQueueStatus == null || it.status == selectedQueueStatus }
        if (selectedQueueView == ScheduleQueueView.CALENDAR) {
            renderCalendar(posts)
        } else if (posts.isEmpty()) {
            content.addView(emptyState())
        } else {
            posts.forEach { content.addView(queueCard(it)) }
        }
        primaryButton.apply {
            visibility = View.VISIBLE
            isEnabled = !busy
            setText(R.string.schedule_new)
            contentDescription = getString(R.string.schedule_new)
            setOnClickListener { openEditor(null) }
        }
        TwidgetFonts.applyTo(content)
    }

    private fun addQueueViewFilters() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        listOf(
            ScheduleQueueView.LIST to R.string.schedule_view_list,
            ScheduleQueueView.CALENDAR to R.string.schedule_view_calendar,
        ).forEach { (view, label) ->
            row.addView(actionButton(getString(label), view == selectedQueueView) {
                selectedQueueView = view
                selectedCalendarDate = null
                renderQueue()
            })
        }
        content.addView(row)
    }

    private fun renderCalendar(posts: List<ScheduledPost>) {
        val zoneId = ZoneId.systemDefault()
        val monthPosts = ScheduleCalendar.postsInMonth(posts, calendarMonth, zoneId)
        val counts = ScheduleCalendar.countByDate(posts, calendarMonth, zoneId)
        content.addView(calendarHeader())
        content.addView(calendarGrid(counts))

        val selected = selectedCalendarDate?.takeIf { YearMonth.from(it) == calendarMonth }
        if (selected != null) {
            content.addView(sectionTitle(getString(
                R.string.schedule_agenda_for,
                selected.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())),
            )))
            val selectedPosts = ScheduleCalendar.postsOnDate(posts, selected, zoneId)
            if (selectedPosts.isEmpty()) content.addView(calendarEmptyState())
            else selectedPosts.forEach { content.addView(queueCard(it)) }
        } else {
            content.addView(sectionTitle(getString(
                R.string.schedule_month_agenda,
                calendarMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())),
            )))
            if (monthPosts.isEmpty()) {
                content.addView(calendarEmptyState())
            } else {
                monthPosts.groupBy { ScheduleCalendar.dateFor(it, zoneId) }.forEach { (date, datedPosts) ->
                    date?.let {
                        content.addView(metaText(it.format(
                            DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()),
                        )).apply { setPadding(dp(18), dp(10), dp(18), dp(6)) })
                    }
                    datedPosts.forEach { content.addView(queueCard(it)) }
                }
            }
        }

        val undated = posts.filter { it.scheduledAt == null }
        if (undated.isNotEmpty()) {
            content.addView(sectionTitle(getString(R.string.schedule_unscheduled_drafts)))
            undated.forEach { content.addView(queueCard(it)) }
        }
    }

    private fun calendarHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        addView(actionButton("‹") {
            calendarMonth = calendarMonth.minusMonths(1)
            selectedCalendarDate = null
            renderQueue()
        }.apply { contentDescription = getString(R.string.schedule_previous_month) })
        addView(TextView(this@ScheduleActivity).apply {
            text = calendarMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
            gravity = Gravity.CENTER
            textSize = 18f
            typeface = TwidgetFonts.oneUiSans(context, 700)
            setTextColor(ContextCompat.getColor(context, R.color.oneui_text_primary))
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        addView(actionButton("›") {
            calendarMonth = calendarMonth.plusMonths(1)
            selectedCalendarDate = null
            renderQueue()
        }.apply { contentDescription = getString(R.string.schedule_next_month) })
    }

    private fun calendarGrid(counts: Map<LocalDate, Int>): View = GridLayout(this).apply {
        columnCount = 7
        setPadding(dp(8), dp(4), dp(8), dp(12))
        val firstDay = calendarMonth.atDay(1)
        val leadingDays = (firstDay.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
        val today = LocalDate.now()

        DayOfWeek.entries.forEachIndexed { column, day ->
            addView(TextView(this@ScheduleActivity).apply {
                text = day.getDisplayName(TextStyle.NARROW, Locale.getDefault())
                gravity = Gravity.CENTER
                textSize = 12f
                typeface = TwidgetFonts.oneUiSans(context, 700)
                setTextColor(ContextCompat.getColor(context, R.color.oneui_text_secondary))
            }, calendarCellParams(0, column, dp(28)))
        }
        repeat(calendarMonth.lengthOfMonth()) { offset ->
            val date = calendarMonth.atDay(offset + 1)
            val position = leadingDays + offset
            val row = position / 7 + 1
            val column = position % 7
            val count = counts[date] ?: 0
            addView(TextView(this@ScheduleActivity).apply {
                text = if (count > 0) "${date.dayOfMonth}\n$count" else date.dayOfMonth.toString()
                gravity = Gravity.CENTER
                textSize = if (count > 0) 13f else 14f
                typeface = Typeface.create("sec", if (date == today || date == selectedCalendarDate) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(ContextCompat.getColor(
                    context,
                    if (date == selectedCalendarDate) R.color.oneui_card_bg else R.color.oneui_text_primary,
                ))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    when {
                        date == selectedCalendarDate -> setColor(ContextCompat.getColor(context, R.color.oneui_accent))
                        count > 0 -> setColor(ContextCompat.getColor(context, R.color.oneui_accent_translucent))
                        else -> setColor(android.graphics.Color.TRANSPARENT)
                    }
                    if (date == today && date != selectedCalendarDate) {
                        setStroke(dp(1), ContextCompat.getColor(context, R.color.oneui_accent))
                    }
                }
                isClickable = true
                isFocusable = true
                contentDescription = getString(R.string.schedule_day_description, date.toString(), count)
                setOnClickListener {
                    selectedCalendarDate = if (selectedCalendarDate == date) null else date
                    renderQueue()
                }
            }, calendarCellParams(row, column, dp(52)))
        }
    }

    private fun calendarCellParams(row: Int, column: Int, height: Int): GridLayout.LayoutParams =
        GridLayout.LayoutParams(
            GridLayout.spec(row),
            GridLayout.spec(column, 1, 1f),
        ).apply {
            width = 0
            this.height = height
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }

    private fun calendarEmptyState(): View = card().apply {
        gravity = Gravity.CENTER
        addView(metaText(getString(R.string.schedule_calendar_empty)).apply { gravity = Gravity.CENTER })
    }

    private fun addStatusFilters() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(4), dp(10), dp(14))
        }
        val filters = listOf(
            null to R.string.schedule_filter_all,
            ScheduleStatus.SCHEDULED to R.string.schedule_status_scheduled,
            ScheduleStatus.DRAFT to R.string.schedule_status_draft,
            ScheduleStatus.NEEDS_ACTION to R.string.schedule_status_needs_action,
            ScheduleStatus.FAILED to R.string.schedule_status_failed,
            ScheduleStatus.PUBLISHED to R.string.schedule_status_published,
        )
        filters.forEach { (status, label) ->
            row.addView(actionButton(getString(label), selectedQueueStatus == status) {
                selectedQueueStatus = status
                renderQueue()
            })
        }
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
    }

    private fun queueCard(post: ScheduledPost): View = card().apply {
        addView(titleText(post.thread.firstOrNull()?.text?.take(100)?.ifBlank {
            getString(R.string.schedule_media_post)
        } ?: getString(R.string.schedule_empty_post)))
        addView(metaText(getString(
            R.string.schedule_queue_metadata,
            getString(statusLabel(post.status)),
            providerLabel(post.provider),
            formattedTime(post.scheduledAt),
            post.thread.size,
        )))
        post.errorMessage?.takeIf(String::isNotBlank)?.let {
            addView(metaText(getString(R.string.schedule_error_detail, it)).apply {
                setTextColor(ContextCompat.getColor(context, R.color.metric_red))
            })
        }
        addView(actionRow().apply {
            if (post.status != ScheduleStatus.PUBLISHED && post.status != ScheduleStatus.CANCELLED) {
                addView(actionButton(getString(R.string.schedule_edit)) { openEditor(post) })
            }
            addView(actionButton(getString(R.string.schedule_duplicate)) { duplicate(post) })
            if (post.status == ScheduleStatus.SCHEDULED ||
                post.status == ScheduleStatus.NEEDS_ACTION
            ) {
                addView(actionButton(getString(R.string.cancel)) { cancelPost(post) })
            }
            addView(actionButton(getString(R.string.delete)) { deletePost(post) })
        })
        if (post.status == ScheduleStatus.NEEDS_ACTION) {
            setOnClickListener { renderChecklist(post) }
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.schedule_open_checklist_for, post.accountUsername)
        }
    }

    private fun duplicate(post: ScheduledPost) {
        val now = System.currentTimeMillis()
        val duplicate = post.copy(
            id = UUID.randomUUID().toString(),
            status = ScheduleStatus.DRAFT,
            remotePostId = null,
            errorMessage = null,
            createdAt = now,
            updatedAt = now,
            publishedAt = null,
            thread = post.thread.firstOrNull()?.let {
                listOf(it.copy(id = UUID.randomUUID().toString()))
            } ?: emptyList(),
        )
        store.create(duplicate)
        openEditor(duplicate)
    }

    private fun cancelPost(post: ScheduledPost) {
        AlertDialog.Builder(this)
            .setTitle(R.string.schedule_cancel_title)
            .setMessage(R.string.schedule_cancel_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.cancel) { _, _ ->
                if (post.provider == ScheduleProvider.POSTPONE) {
                    runRemote {
                        coordinator.cancel(post.id)
                    }
                } else {
                    showCoordinatorResult(coordinator.cancel(post.id))
                }
            }
            .show()
    }

    private fun deletePost(post: ScheduledPost) {
        AlertDialog.Builder(this)
            .setTitle(R.string.schedule_delete_title)
            .setMessage(R.string.schedule_delete_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val work = {
                    if (post.provider == ScheduleProvider.POSTPONE &&
                        !post.remotePostId.isNullOrBlank() &&
                        post.status != ScheduleStatus.CANCELLED
                    ) {
                        val result = coordinator.cancel(post.id)
                        if (result?.isSuccess == true) store.remove(post.id)
                        result
                    } else {
                        if (post.provider == ScheduleProvider.LOCAL_REMINDER) {
                            LocalReminderScheduler(this@ScheduleActivity).cancel(post.id)
                            ScheduleNotificationHelper.cancel(this@ScheduleActivity, post.id)
                        }
                        store.remove(post.id)
                        null
                    }
                }
                if (post.provider == ScheduleProvider.POSTPONE &&
                    !post.remotePostId.isNullOrBlank() &&
                    post.status != ScheduleStatus.CANCELLED
                ) {
                    runRemote(work)
                } else {
                    work()
                    renderQueue()
                }
            }
            .show()
    }

    private fun openEditor(post: ScheduledPost?) {
        editorPost = post
        editorProvider = post?.provider ?: ScheduleSettingsStore.defaultProvider(this)
        editorAccount = resolveEditorAccount(post)
        editorTime = Calendar.getInstance().apply {
            timeInMillis = post?.scheduledAt?.takeIf { it > System.currentTimeMillis() }
                ?: (System.currentTimeMillis() + 60 * 60 * 1000L)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        editorItems.clear()
        val first = post?.thread?.firstOrNull()
        editorItems += if (first != null) {
            EditorItem(first.id, first.text, first.media.toMutableList())
        } else {
            EditorItem()
        }
        showComposeEditor()
    }

    private fun showComposeEditor() {
        showComposeMode()
        composeUi?.bind()
    }

    private fun showQueueMode() {
        backPressedCallback.isEnabled = false
        queueRoot.visibility = View.VISIBLE
        composePanel.visibility = View.GONE
    }

    private fun showComposeMode() {
        backPressedCallback.isEnabled = true
        queueRoot.visibility = View.GONE
        composePanel.visibility = View.VISIBLE
    }

    internal fun onComposeCloseRequested() {
        if (composeHasContent() && editorPost == null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.schedule_discard_title)
                .setMessage(R.string.schedule_discard_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.schedule_discard) { _, _ -> renderQueue() }
                .show()
            return
        }
        renderQueue()
    }

    internal fun onComposeSaveDraftRequested() = saveDraft()

    internal fun onComposeSubmitRequested() = submitSchedule()

    internal fun onComposeAttachMedia() {
        mediaTarget = 0
        if (editorProvider == ScheduleProvider.LOCAL_REMINDER) {
            localMediaPicker.launch(arrayOf("image/*", "video/*"))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.schedule_add_local_media)
            .setItems(
                arrayOf(
                    getString(R.string.schedule_add_public_url),
                    getString(R.string.schedule_content_library),
                ),
            ) { _, which ->
                when (which) {
                    0 -> showPublicUrlDialog()
                    1 -> browsePostponeLibrary()
                }
            }
            .show()
    }

    internal fun onComposePickTimeRequested() {
        pickDate()
    }

    internal fun onComposeDownloadMedia(mediaIndex: Int? = null) {
        val item = editorItems.firstOrNull() ?: return
        val media = if (mediaIndex == null) {
            item.media
        } else {
            item.media.getOrNull(mediaIndex)?.let { listOf(it) }.orEmpty()
        }
        if (media.isEmpty()) return
        downloadMedia(ScheduleThreadItem(item.id, item.text, media))
    }

    internal fun composeItemText(): String =
        editorItems.firstOrNull()?.text.orEmpty()

    internal fun composeUpdateItemText(value: String) {
        editorItems.firstOrNull()?.text = value
    }

    internal fun composeItemMedia(): List<ScheduleMediaSource> =
        editorItems.firstOrNull()?.media.orEmpty()

    internal fun composeRemoveMedia(mediaIndex: Int) {
        editorItems.firstOrNull()?.media?.removeAt(mediaIndex)
    }

    internal fun composeHasContent(): Boolean {
        val item = editorItems.firstOrNull() ?: return false
        return item.text.isNotBlank() || item.media.isNotEmpty()
    }

    internal fun composeIsBusy(): Boolean = busy

    internal fun composeAvatarUsername(): String =
        requestedUsername().ifBlank { editorAccount }

    internal fun composeTimeSummaryText(): String =
        getString(
            R.string.schedule_compose_time_set,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(editorTime.time),
            TimeZone.getDefault().getDisplayName(false, TimeZone.SHORT),
        )

    internal fun composeDp(value: Int): Int = dp(value)

    private fun showPublicUrlDialog() {
        val index = 0
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = getString(R.string.schedule_public_url_hint)
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.schedule_add_public_url)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.schedule_add) { _, _ ->
                val value = input.text.toString().trim()
                val uri = Uri.parse(value)
                if ((uri.scheme == "https" || uri.scheme == "http") &&
                    editorItems[index].media.size < SchedulePolicy.MAX_MEDIA_PER_ITEM
                ) {
                    editorItems[index].media.removeAll { it is PostponeLibraryMedia }
                    editorItems[index].media += PublicUrlMedia(value)
                    composeUi?.refreshMediaForActiveItem()
                } else {
                    toast(R.string.schedule_invalid_public_url)
                }
            }
            .show()
    }

    private fun browsePostponeLibrary() {
        val index = 0
        setBusy(true)
        AppExecutors.execute(
            onRejected = {
                runOnUiThread {
                    setBusy(false)
                    toast(R.string.schedule_busy)
                }
            },
        ) {
            val result = postponeClient.browseContentLibrary()
            runOnUiThread {
                setBusy(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                val items = result.value?.items.orEmpty()
                if (items.isEmpty()) {
                    showErrors(result.errors.map { it.message }.ifEmpty {
                        listOf(getString(R.string.schedule_library_empty))
                    })
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.schedule_content_library)
                    .setItems(items.map { it.name }.toTypedArray()) { _, which ->
                        val selected = items[which]
                        editorItems[index].media.clear()
                        editorItems[index].media += PostponeLibraryMedia(
                            selected.id,
                            selected.name,
                            selected.url,
                            selected.mimeType,
                        )
                        composeUi?.refreshMediaForActiveItem()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun pickDate() {
        DatePickerDialog(
            this,
            { _, year, month, day ->
                editorTime.set(year, month, day)
                pickTime()
            },
            editorTime.get(Calendar.YEAR),
            editorTime.get(Calendar.MONTH),
            editorTime.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    private fun pickTime() {
        TimePickerDialog(
            this,
            { _, hour, minute ->
                editorTime.set(Calendar.HOUR_OF_DAY, hour)
                editorTime.set(Calendar.MINUTE, minute)
                composeUi?.refreshTimeSummary()
            },
            editorTime.get(Calendar.HOUR_OF_DAY),
            editorTime.get(Calendar.MINUTE),
            android.text.format.DateFormat.is24HourFormat(this),
        ).show()
    }

    private fun buildEditedPost(): ScheduledPost {
        val old = editorPost
        val providerUsername = if (editorProvider == ScheduleProvider.POSTPONE) {
            ScheduleSettingsStore.postponeAccountFor(this, editorAccount).orEmpty()
        } else {
            editorAccount
        }
        return ScheduledPost(
            id = old?.id ?: UUID.randomUUID().toString(),
            provider = editorProvider,
            status = old?.status ?: ScheduleStatus.DRAFT,
            accountId = editorAccount,
            accountUsername = providerUsername,
            scheduledAt = editorTime.timeInMillis,
            thread = editorItems.map { ScheduleThreadItem(it.id, it.text, it.media.toList()) }.take(1),
            remotePostId = old?.remotePostId?.takeIf { old.provider == editorProvider },
            errorMessage = null,
            createdAt = old?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            publishedAt = old?.publishedAt,
        )
    }

    private fun saveDraft() {
        if (editorProvider == ScheduleProvider.POSTPONE && editorAccount.isBlank()) {
            toast(R.string.schedule_postpone_account_required)
            return
        }
        val draft = buildEditedPost()
        val previous = editorPost
        if (previous?.provider == ScheduleProvider.POSTPONE &&
            !previous.remotePostId.isNullOrBlank() &&
            previous.status == ScheduleStatus.SCHEDULED
        ) {
            runRemote {
                val cancelled = coordinator.cancel(previous.id)
                if (cancelled?.isSuccess != true) {
                    cancelled
                } else {
                    ScheduleCoordinatorResult(coordinator.saveDraft(draft.copy(remotePostId = null)))
                }
            }
        } else {
            editorPost = coordinator.saveDraft(draft)
            toast(R.string.schedule_draft_saved)
            renderQueue()
        }
    }

    private fun submitSchedule() {
        if (editorProvider == ScheduleProvider.POSTPONE && editorAccount.isBlank()) {
            toast(R.string.schedule_postpone_account_required)
            return
        }
        if (editorProvider == ScheduleProvider.POSTPONE &&
            ScheduleSettingsStore.postponeAccountFor(this, editorAccount).isNullOrBlank()
        ) {
            showErrors(listOf(getString(R.string.schedule_postpone_mapping_required, editorAccount)))
            return
        }
        val post = buildEditedPost()
        if (post.provider == ScheduleProvider.POSTPONE) {
            runRemote { coordinator.schedule(post) }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !notificationWarningAccepted
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !(getSystemService(ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms() &&
                !exactWarningAccepted
            ) {
                showExactAlarmChoice()
                return
            }
            val previous = editorPost
            if (previous?.provider == ScheduleProvider.POSTPONE &&
                !previous.remotePostId.isNullOrBlank()
            ) {
                runRemote {
                    val cancelled = coordinator.cancel(previous.id)
                    if (cancelled?.isSuccess != true) cancelled else coordinator.schedule(post)
                }
            } else {
                showCoordinatorResult(coordinator.schedule(post))
            }
        }
    }

    private fun showExactAlarmChoice() {
        AlertDialog.Builder(this)
            .setTitle(R.string.schedule_exact_alarm_title)
            .setMessage(R.string.schedule_exact_alarm_message)
            .setNegativeButton(R.string.schedule_use_approximate) { _, _ ->
                exactWarningAccepted = true
                toast(R.string.schedule_approximate_notice)
                submitSchedule()
            }
            .setPositiveButton(R.string.schedule_open_system_settings) { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:$packageName"),
                    )
                )
            }
            .show()
    }

    private fun runRemote(work: () -> ScheduleCoordinatorResult?) {
        setBusy(true)
        AppExecutors.execute(
            onRejected = {
                runOnUiThread {
                    setBusy(false)
                    toast(R.string.schedule_busy)
                }
            },
        ) {
            val result = runCatching(work)
            runOnUiThread {
                setBusy(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = ::showCoordinatorResult,
                    onFailure = { showErrors(listOf(it.message ?: getString(R.string.schedule_unknown_error))) },
                )
            }
        }
    }

    private fun showCoordinatorResult(result: ScheduleCoordinatorResult?) {
        if (result == null) {
            toast(R.string.schedule_not_found)
        } else if (result.isSuccess) {
            toast(R.string.schedule_updated)
        } else {
            showErrors(result.errors)
        }
        renderQueue()
    }

    private fun renderChecklist(post: ScheduledPost) {
        showQueueMode()
        content.removeAllViews()
        content.addView(sectionTitle(getString(R.string.schedule_publish_checklist)))
        content.addView(metaText(getString(
            R.string.schedule_checklist_summary,
            post.thread.size,
        )).apply { setPadding(dp(24), 0, dp(24), dp(16)) })

        val completed = completedItemIds(post.id)
        post.thread.forEachIndexed { index, item ->
            content.addView(checklistCard(post, index, item, item.id in completed))
        }
        primaryButton.apply {
            visibility = View.VISIBLE
            setText(R.string.schedule_back_to_queue)
            setOnClickListener { renderQueue() }
        }
        TwidgetFonts.applyTo(content)
    }

    private fun checklistCard(
        post: ScheduledPost,
        index: Int,
        item: ScheduleThreadItem,
        isDone: Boolean,
    ): View = card().apply {
        alpha = if (isDone) 0.55f else 1f
        addView(titleText(getString(R.string.schedule_thread_item_number, index + 1)))
        addView(TextView(this@ScheduleActivity).apply {
            text = item.text.ifBlank { getString(R.string.schedule_media_only) }
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.oneui_text_primary))
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(8))
        })
        addView(metaText(mediaSummary(item.media)))
        addView(actionRow().apply {
            addView(actionButton(getString(R.string.schedule_copy_text)) {
                showHandoff(XComposeIntents.copyText(this@ScheduleActivity, item.text))
            })
            addView(actionButton(getString(R.string.schedule_open_x)) {
                showHandoff(XComposeIntents.openCompose(this@ScheduleActivity, item.text))
            })
            if (item.media.isNotEmpty()) {
                addView(actionButton(getString(R.string.schedule_download_media)) {
                    downloadMedia(item)
                })
                addView(actionButton(getString(R.string.schedule_share_media)) {
                    showHandoff(XComposeIntents.shareItem(this@ScheduleActivity, item))
                })
            }
            addView(actionButton(getString(
                if (isDone) R.string.schedule_marked_done else R.string.schedule_mark_done,
            )) {
                if (!isDone) markChecklistItemDone(post, item.id)
            }.apply { isEnabled = !isDone })
        })
    }

    private fun markChecklistItemDone(post: ScheduledPost, itemId: String) {
        val completed = ScheduleChecklistProgress.markCompleted(this, post.id, itemId)
        if (post.thread.all { it.id in completed }) {
            val published = post.copy(
                status = ScheduleStatus.PUBLISHED,
                publishedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                errorMessage = null,
            )
            store.upsert(published)
            ScheduleNotificationHelper.cancel(this, post.id)
            toast(R.string.schedule_all_published)
            renderQueue()
        } else {
            renderChecklist(post)
        }
    }

    private fun showHandoff(outcome: XHandoffOutcome) {
        val message = when (outcome.result) {
            XHandoffResult.OPENED -> outcome.limitation ?: getString(R.string.schedule_opened_x)
            XHandoffResult.COPIED -> getString(R.string.schedule_copied)
            XHandoffResult.SHARED -> outcome.limitation ?: getString(R.string.schedule_shared)
            XHandoffResult.NO_HANDLER -> getString(R.string.schedule_no_handler)
            XHandoffResult.INVALID_MEDIA -> outcome.limitation ?: getString(R.string.schedule_invalid_media)
            XHandoffResult.FAILED -> getString(R.string.schedule_handoff_failed)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun downloadMedia(item: ScheduleThreadItem) {
        setBusy(true)
        AppExecutors.execute(
            onRejected = {
                runOnUiThread {
                    setBusy(false)
                    toast(R.string.schedule_busy)
                }
            },
        ) {
            val outcome = ScheduleMediaExporter.downloadItem(this, item)
            runOnUiThread {
                setBusy(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                showExportOutcome(outcome)
            }
        }
    }

    private fun showExportOutcome(outcome: ScheduleMediaExportOutcome) {
        val message = when (outcome.result) {
            ScheduleMediaExportResult.SAVED -> {
                if (outcome.detail.isNullOrBlank()) {
                    getString(R.string.schedule_media_saved, outcome.savedCount)
                } else {
                    getString(R.string.schedule_media_saved_partial, outcome.savedCount, outcome.detail)
                }
            }
            ScheduleMediaExportResult.NOTHING_TO_SAVE -> getString(R.string.schedule_media_nothing_to_save)
            ScheduleMediaExportResult.FAILED -> outcome.detail ?: getString(R.string.schedule_media_save_failed)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun resolveEditorAccount(post: ScheduledPost?): String {
        post?.accountId?.takeIf(String::isNotBlank)?.let { return it }
        post?.accountUsername?.takeIf(String::isNotBlank)?.let { return it }
        requestedUsername().takeIf(String::isNotBlank)?.let { return it }
        if ((post?.provider ?: editorProvider) == ScheduleProvider.POSTPONE) {
            return TwidgetStore.accounts(this).firstOrNull().orEmpty()
        }
        return ""
    }

    private fun completedItemIds(postId: String): Set<String> =
        ScheduleChecklistProgress.completed(this, postId)

    private fun setBusy(value: Boolean) {
        busy = value
        primaryButton.isEnabled = !value
        composeUi?.setBusy(value)
    }

    private fun showErrors(errors: List<String>) {
        AlertDialog.Builder(this)
            .setTitle(R.string.schedule_error_title)
            .setMessage(errors.filter(String::isNotBlank).joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun emptyState(): View = card().apply {
        gravity = Gravity.CENTER
        addView(titleText(getString(R.string.schedule_empty_title)).apply {
            gravity = Gravity.CENTER
        })
        addView(metaText(getString(R.string.schedule_empty_summary)).apply {
            gravity = Gravity.CENTER
        })
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(context, R.drawable.schedule_card_bg)
        setPadding(dp(20), dp(18), dp(20), dp(18))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            setMargins(dp(6), dp(6), dp(6), dp(10))
        }
    }

    private fun actionRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.START
        setPadding(0, dp(10), 0, 0)
    }

    private fun actionButton(
        label: String,
        selected: Boolean = false,
        action: () -> Unit,
    ): Button = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        minHeight = dp(42)
        minWidth = 0
        setPadding(dp(14), 0, dp(14), 0)
        contentDescription = label
        if (selected) {
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.oneui_accent_translucent),
            )
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(6) }
        setOnClickListener { action() }
    }

    private fun sectionTitle(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 19f
        typeface = TwidgetFonts.oneUiSans(context, 700)
        setTextColor(ContextCompat.getColor(context, R.color.oneui_text_primary))
        setPadding(dp(24), dp(14), dp(24), dp(8))
    }

    private fun titleText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 15f
        typeface = TwidgetFonts.oneUiSans(context, 600)
        setTextColor(ContextCompat.getColor(context, R.color.oneui_text_primary))
        maxLines = 4
    }

    private fun metaText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(ContextCompat.getColor(context, R.color.oneui_text_secondary))
        setPadding(0, dp(6), 0, 0)
    }

    private fun mediaSummary(media: List<ScheduleMediaSource>): String {
        if (media.isEmpty()) return getString(R.string.schedule_no_media)
        val names = media.map {
            when (it) {
                is LocalUriMedia -> it.displayName ?: getString(R.string.schedule_local_media)
                is PublicUrlMedia -> it.url
                is PostponeLibraryMedia -> it.name
            }
        }
        return getString(R.string.schedule_media_summary, media.size, names.joinToString(", "))
    }

    private fun requestedUsername(): String =
        intent.getStringExtra(EXTRA_USERNAME).orEmpty().trim().trimStart('@')

    private fun queueAccountLabel(): String =
        requestedUsername().takeIf(String::isNotBlank)?.let { "@$it" }
            ?: getString(R.string.schedule_all_accounts)

    private fun providerLabel(provider: ScheduleProvider): String = getString(
        if (provider == ScheduleProvider.POSTPONE) {
            R.string.schedule_provider_postpone
        } else {
            R.string.schedule_provider_local
        },
    )

    private fun statusLabel(status: ScheduleStatus): Int = when (status) {
        ScheduleStatus.DRAFT -> R.string.schedule_status_draft
        ScheduleStatus.SCHEDULED -> R.string.schedule_status_scheduled
        ScheduleStatus.NEEDS_ACTION -> R.string.schedule_status_needs_action
        ScheduleStatus.PUBLISHED -> R.string.schedule_status_published
        ScheduleStatus.FAILED -> R.string.schedule_status_failed
        ScheduleStatus.CANCELLED -> R.string.schedule_status_cancelled
    }

    private fun formattedTime(value: Long?): String =
        value?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
            ?: getString(R.string.schedule_no_time)

    private fun toast(message: Int) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun View.updateBottomMargin(value: Int) {
        (layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.bottomMargin = value
            layoutParams = it
        }
    }

    private fun <T> MutableList<T>.swap(first: Int, second: Int) {
        val value = this[first]
        this[first] = this[second]
        this[second] = value
    }

    private enum class ScheduleQueueView {
        LIST,
        CALENDAR,
    }

    private data class EditorItem(
        val id: String = UUID.randomUUID().toString(),
        var text: String = "",
        val media: MutableList<ScheduleMediaSource> = mutableListOf(),
    )

    companion object {
        const val EXTRA_USERNAME = "com.tjg.twidget.extra.SCHEDULE_USERNAME"
    }
}
