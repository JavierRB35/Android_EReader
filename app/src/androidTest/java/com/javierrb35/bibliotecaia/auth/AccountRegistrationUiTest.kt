package com.javierrb35.bibliotecaia.auth

import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.R
import com.javierrb35.bibliotecaia.ui.AccountActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Activity smoke test; never sends email or attempts to create a user. */
@RunWith(AndroidJUnit4::class)
class AccountRegistrationUiTest {
    @Test fun gmailAddressRegistrationIsAccessibleWithoutGoogleOauthConfiguration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = Intent(context, AccountActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val activity = instrumentation.startActivitySync(intent) as AccountActivity
        try {
            instrumentation.waitForIdleSync()
            val root = activity.findViewById<View>(android.R.id.content)
            val buttons = mutableListOf<Button>()
            val inputs = mutableListOf<EditText>()
            fun visit(view: View) {
                if (view is Button) buttons.add(view)
                if (view is EditText) inputs.add(view)
                if (view is android.view.ViewGroup) {
                    for (i in 0 until view.childCount) visit(view.getChildAt(i))
                }
            }
            visit(root)
            val register = buttons.firstOrNull {
                it.text == context.getString(R.string.account_register_email)
            }
            val login = buttons.firstOrNull {
                it.text == context.getString(R.string.account_login_email)
            }
            val google = buttons.firstOrNull {
                it.text == context.getString(R.string.account_sign_in)
            }
            assertNotNull("Registration button is missing", register)
            assertNotNull("Email sign-in button is missing", login)
            assertNotNull("Existing native Google button is missing", google)
            assertTrue("Expected email and password inputs", inputs.size >= 2)
            assertFalse("The Account screen unexpectedly closed", activity.isFinishing)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
