package com.javierrb35.bibliotecaia

import android.content.Intent
import android.app.Activity
import android.app.Instrumentation
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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
    @Test fun importPickerAllowsMultipleDocumentsAndCancelKeepsLibraryOpen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val captured = AtomicReference<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_OPEN_DOCUMENT) return null
                captured.set(Intent(intent))
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        instrumentation.addMonitor(monitor)
        try {
            instrumentation.runOnMainSync { activity.findViewById<View>(R.id.btnImport).performClick() }
            instrumentation.waitForIdleSync()
            val outgoing = captured.get()
            assertNotNull("Expected Android document picker", outgoing)
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, outgoing.action)
            assertTrue(outgoing.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
            assertTrue(activity.findViewById<View>(R.id.btnImport).isEnabled)
            assertFalse(activity.isFinishing)
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
