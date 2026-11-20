package com.javierrb35.bibliotecaia

import android.content.Intent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.ui.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launches the real production entry activity under Android instrumentation,
 * rather than merely compiling the APK. Regression guard for Samsung P0 #16.
 *
 * The device-specific fatal crash still requires its actual Android logcat
 * stacktrace; passing on an emulator does not close the issue.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityLaunchTest {
    @Test
    fun libraryColdStartsAndDisplaysItsControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val activity = instrumentation.startActivitySync(intent) as MainActivity
        try {
            instrumentation.waitForIdleSync()
            // Verify actual inflation and startup wiring, not just JVM class loading.
            assertNotNull(activity.findViewById<View>(R.id.recyclerBooks))
            assertNotNull(activity.findViewById<View>(R.id.txtSearch))
            assertNotNull(activity.findViewById<View>(R.id.btnImport))
            assertNotNull(activity.findViewById<View>(R.id.mainNavigation))
            assertFalse(activity.isFinishing)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
