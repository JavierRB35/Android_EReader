package com.javierrb35.bibliotecaia.sync.coordinator.onboarding

import android.content.Context
import android.os.Build
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.javierrb35.bibliotecaia.R

/**
 * First-screen presentation only. Host calls render on main thread and runs
 * callbacks off it. Never use a legacy SAF writer from these callbacks.
 * The host supplies bootstrap authorization for create and persists secrets
 * through the coordinator adapter; no credentials belong in saved view state.
 */
internal class UniqueLibraryOnboardingView(
    context: Context,
    private val actions: Actions
) : ScrollView(context) {
    interface Actions {
        fun create()
        fun link(libraryId: String, pairingCode: String)
        fun retry()
        fun check()
        fun continueToInventory()
    }

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val margin = (20 * resources.displayMetrics.density).toInt()
        setPadding(margin, margin, margin, margin)
    }
    private val heading = text(R.string.bia_unique_setup_title, 24f)
    private val status = text(R.string.bia_unique_setup_choose, 16f)
    private val progress = ProgressBar(context).apply { visibility = View.GONE }
    private val create = button(R.string.bia_unique_setup_create, "bia_onboarding_create") {
        if (canChoose()) { beginBusy(); actions.create() }
    }
    private val showLink = button(R.string.bia_unique_setup_link, "bia_onboarding_link") {
        if (canChoose()) { form.visibility = View.VISIBLE; library.requestFocus() }
    }
    private val form = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val library = EditText(context).apply {
        id = View.generateViewId()
        tag = "bia_onboarding_library"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setSingleLine(true)
        hint = context.getString(R.string.bia_unique_setup_library_id)
        isSaveEnabled = false
        if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }
    private val code = EditText(context).apply {
        id = View.generateViewId()
        tag = "bia_onboarding_code"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setSingleLine(true)
        hint = context.getString(R.string.bia_unique_setup_code)
        isSaveEnabled = false
        if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }
    private val confirmLink = button(R.string.bia_unique_setup_confirm_link, "bia_onboarding_confirm_link") {
        if (canChoose()) {
            val id = library.text.toString().trim()
            val secret = code.text.toString().trim()
            if (PairingInput.parse(id, secret) == null) {
                status.setText(R.string.bia_unique_setup_invalid)
            } else {
                code.text.clear()
                beginBusy()
                actions.link(id, secret)
            }
        }
    }
    private val retry = button(R.string.bia_unique_setup_retry, "bia_onboarding_retry") {
        if (!busy && displayed is OnboardingState.Pending) { beginBusy(); actions.retry() }
    }
    private val check = button(R.string.bia_unique_setup_check, "bia_onboarding_check") {
        if (!busy && displayed == OnboardingState.Unavailable) { beginBusy(); actions.check() }
    }
    private val next = button(R.string.bia_unique_setup_inventory, "bia_onboarding_inventory") {
        if (!busy && displayed is OnboardingState.Linked) { beginBusy(); actions.continueToInventory() }
    }
    private var displayed: OnboardingState = OnboardingState.Unavailable
    private var busy = false

    init {
        isSaveEnabled = false
        // Prevent restoration of transient inputs anywhere in this subtree.
        isSaveFromParentEnabled = false
        addView(content)
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        listOf(heading, status, progress, create, showLink, form, retry, check, next)
            .forEach { content.addView(it) }
        form.addView(text(R.string.bia_unique_setup_link_help, 14f))
        form.addView(text(R.string.bia_unique_setup_library_id, 14f).apply { labelFor = library.id })
        form.addView(library)
        form.addView(text(R.string.bia_unique_setup_code, 14f).apply { labelFor = code.id })
        form.addView(code)
        form.addView(confirmLink)
        render(OnboardingResult(OnboardingState.Unavailable, OnboardingFeedback.NONE))
    }

    private fun text(value: Int, size: Float) = TextView(context).apply {
        setText(value)
        textSize = size
        val pad = (8 * resources.displayMetrics.density).toInt()
        setPadding(0, pad, 0, pad)
    }
    private fun button(value: Int, name: String, onClick: () -> Unit) = Button(context).apply {
        setText(value)
        tag = name
        setOnClickListener { onClick() }
    }
    private fun canChoose() = !busy && displayed == OnboardingState.Choose
    private fun beginBusy() {
        busy = true
        progress.visibility = View.VISIBLE
        status.setText(R.string.bia_unique_setup_working)
        listOf(create, showLink, confirmLink, retry, check, next, library, code)
            .forEach { it.isEnabled = false }
    }

    /** Render only durable state reported by the adapter, never a presumed network success. */
    fun render(result: OnboardingResult) {
        displayed = result.state
        busy = false
        code.text.clear()
        form.visibility = View.GONE
        progress.visibility = View.GONE
        listOf(create, showLink, confirmLink, retry, check, next, library, code)
            .forEach { it.isEnabled = true }
        create.visibility = if (displayed == OnboardingState.Choose) View.VISIBLE else View.GONE
        showLink.visibility = create.visibility
        retry.visibility = if (displayed is OnboardingState.Pending) View.VISIBLE else View.GONE
        check.visibility = if (displayed == OnboardingState.Unavailable) View.VISIBLE else View.GONE
        next.visibility = if (displayed is OnboardingState.Linked) View.VISIBLE else View.GONE
        library.text.clear()
        val message = when (val current = displayed) {
            OnboardingState.Choose -> when (result.feedback) {
                OnboardingFeedback.INVALID_PAIRING -> R.string.bia_unique_setup_invalid
                OnboardingFeedback.CHECK_CONNECTION -> R.string.bia_unique_setup_try_again
                else -> R.string.bia_unique_setup_choose
            }
            OnboardingState.Unavailable -> R.string.bia_unique_setup_unavailable
            is OnboardingState.Pending -> if (current.mode == OnboardingMode.CREATE)
                R.string.bia_unique_setup_pending_create else R.string.bia_unique_setup_pending_join
            is OnboardingState.Linked -> {
                status.text = context.getString(R.string.bia_unique_setup_linked, current.libraryId.toString())
                return
            }
        }
        status.setText(message)
    }

    override fun onDetachedFromWindow() {
        code.text.clear()
        library.text.clear()
        super.onDetachedFromWindow()
    }
}
