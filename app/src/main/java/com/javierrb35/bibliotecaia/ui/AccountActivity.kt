package com.javierrb35.bibliotecaia.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.ClearCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.javierrb35.bibliotecaia.R
import com.javierrb35.bibliotecaia.auth.AccountFailure
import com.javierrb35.bibliotecaia.auth.AccountRepository
import com.javierrb35.bibliotecaia.auth.AccountSession
import com.javierrb35.bibliotecaia.auth.GoogleAccountSignIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Account status is separate from linking or syncing local books. */
class AccountActivity : AppCompatActivity() {
    private val repository by lazy { AccountRepository(this) }
    private val google by lazy { GoogleAccountSignIn(this) }
    private lateinit var status: TextView
    private lateinit var signIn: MaterialButton
    private lateinit var signOut: MaterialButton
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var registerEmail: MaterialButton
    private lateinit var loginEmail: MaterialButton
    private lateinit var forget: MaterialButton
    private lateinit var progress: ProgressBar
    private var session: AccountSession? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(column) })
        column.addView(text(R.string.account_title, 32f))
        column.addView(text(R.string.account_local_title, 20f))
        column.addView(text(R.string.account_local_detail, 16f))
        status = text(R.string.account_ready, 16f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        column.addView(status)
        progress = ProgressBar(this).apply { visibility = View.GONE }
        column.addView(progress)
        signIn = button(R.string.account_sign_in) { authenticate() }
        signOut = button(R.string.account_sign_out) { logout() }
        forget = button(R.string.account_forget) { confirmForget() }
        column.addView(signIn)
        column.addView(text(R.string.account_email_detail, 15f))
        email = EditText(this).apply {
            setHint(R.string.account_email_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
            setTextColor(0xFFE8DFCE.toInt())
            setHintTextColor(0xFFA9A49C.toInt())
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        }
        password = EditText(this).apply {
            setHint(R.string.account_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setTextColor(0xFFE8DFCE.toInt())
            setHintTextColor(0xFFA9A49C.toInt())
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        }
        column.addView(email)
        column.addView(password)
        registerEmail = button(R.string.account_register_email) { registerWithEmail() }
        loginEmail = button(R.string.account_login_email) { loginWithEmail() }
        column.addView(registerEmail)
        column.addView(loginEmail)
        column.addView(signOut)
        column.addView(forget)
        column.addView(button(R.string.account_back) { finish() })
        lifecycleScope.launch {
            try { session = repository.savedSession(); render() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                render()
                status.setText(R.string.account_storage_error)
                forget.visibility = View.VISIBLE
            }
        }
    }

    private fun authenticate() {
        if (busy) return
        if (!repository.configuration.googleConfigured) {
            status.setText(R.string.account_google_setup_needed)
            return
        }
        busy = true; render()
        lifecycleScope.launch {
            try {
                session = if (session == null) google.signIn(repository) else repository.requireOnlineSession()
                status.text = getString(R.string.account_verified, session?.email.orEmpty())
            } catch (e: CancellationException) { throw e }
            catch (_: GetCredentialCancellationException) { status.setText(R.string.account_cancelled) }
            catch (_: GetCredentialException) { status.setText(R.string.account_provider_error) }
            catch (e: AccountFailure) {
                status.setText(message(e))
                if (e.reason == AccountFailure.Reason.REJECTED || e.reason == AccountFailure.Reason.NO_SESSION) session = null
                if (e.reason == AccountFailure.Reason.SECURE_STORAGE) forget.visibility = View.VISIBLE
            } catch (_: Exception) { status.setText(R.string.account_provider_error) }
            finally { busy = false; renderControls() }
        }
    }

    private fun registerWithEmail() {
        if (busy) return
        busy = true; renderControls()
        lifecycleScope.launch {
            try {
                repository.registerWithEmail(email.text?.toString().orEmpty(),
                    password.text?.toString().orEmpty())
                password.text?.clear()
                status.setText(R.string.account_registration_pending)
            } catch (e: CancellationException) { throw e }
            catch (e: AccountFailure) { status.setText(message(e)) }
            catch (_: Exception) { status.setText(R.string.account_service_error) }
            finally { busy = false; renderControls() }
        }
    }

    private fun loginWithEmail() {
        if (busy) return
        busy = true; renderControls()
        lifecycleScope.launch {
            try {
                session = repository.signInWithEmail(email.text?.toString().orEmpty(),
                    password.text?.toString().orEmpty())
                password.text?.clear()
                status.text = getString(R.string.account_verified, session?.email.orEmpty())
            } catch (e: CancellationException) { throw e }
            catch (e: AccountFailure) { status.setText(message(e)) }
            catch (_: Exception) { status.setText(R.string.account_service_error) }
            finally { busy = false; renderControls() }
        }
    }

    private fun logout() {
        if (busy) return
        busy = true; renderControls()
        lifecycleScope.launch {
            try {
                repository.signOut()
                session = null
                try { google.clearProviderState() } catch (_: ClearCredentialException) { /* local tokens already removed */ }
                render()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { status.setText(R.string.account_signout_failed); forget.visibility = View.VISIBLE }
            finally { busy = false; renderControls() }
        }
    }

    private fun confirmForget() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.account_forget).setMessage(R.string.account_forget_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.account_forget_confirm) { _, _ ->
                lifecycleScope.launch {
                    busy = true; renderControls()
                    try {
                        repository.forgetOnThisDevice()
                        session = null
                        try { google.clearProviderState() } catch (_: ClearCredentialException) { }
                        forget.visibility = View.GONE
                        render()
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { status.setText(R.string.account_storage_error) }
                    finally { busy = false; renderControls() }
                }
            }.show()
    }

    private fun render() {
        status.text = when {
            !repository.configuration.backendConfigured -> getString(R.string.account_config_missing)
            session != null -> getString(R.string.account_saved, session?.email.orEmpty())
            !repository.configuration.googleConfigured -> getString(R.string.account_email_ready)
            else -> getString(R.string.account_ready)
        }
        renderControls()
    }

    private fun renderControls() {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        signIn.isEnabled = !busy
        signIn.setText(if (session == null) R.string.account_sign_in else R.string.account_verify)
        registerEmail.isEnabled = !busy && repository.configuration.backendConfigured
        loginEmail.isEnabled = !busy && repository.configuration.backendConfigured
        email.isEnabled = !busy
        password.isEnabled = !busy
        signOut.visibility = if (session != null) View.VISIBLE else View.GONE
        signOut.isEnabled = !busy
        forget.isEnabled = !busy
        if (session != null) forget.visibility = View.VISIBLE
        else if (status.text != getString(R.string.account_storage_error)) forget.visibility = View.GONE
    }

    private fun message(error: AccountFailure) = when (error.reason) {
        AccountFailure.Reason.NOT_CONFIGURED -> R.string.account_config_missing
        AccountFailure.Reason.NO_SESSION, AccountFailure.Reason.REJECTED -> R.string.account_rejected
        AccountFailure.Reason.NETWORK -> R.string.account_network_error
        AccountFailure.Reason.RATE_LIMIT -> R.string.account_rate_limit
        AccountFailure.Reason.SECURE_STORAGE -> R.string.account_storage_error
        AccountFailure.Reason.INVALID_INPUT -> R.string.account_invalid_email_password
        else -> R.string.account_service_error
    }

    private fun text(resource: Int, size: Float) = TextView(this).apply {
        setText(resource); textSize = size
        setTextColor(0xFFE8DFCE.toInt())
        setPadding(0, dp(10), 0, dp(14))
    }
    private fun button(resource: Int, click: () -> Unit) = MaterialButton(this).apply {
        setText(resource); minHeight = dp(52); setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, AccountActivity::class.java))
    }
}
