package com.javierrb35.bibliotecaia

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.ui.SplashActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Ensures the actual launcher does not wait for ZIP completion. */
@RunWith(AndroidJUnit4::class)
class SplashActivityLaunchTest {
    @Test
    fun splashDisplaysBackupStateAndLeavesAfterTwoSeconds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = Intent(context, SplashActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val activity = instrumentation.startActivitySync(intent) as SplashActivity
        try {
            instrumentation.waitForIdleSync()
            assertNotNull(activity.findViewById<View>(R.id.splashBackupProgress))
            assertTrue(activity.findViewById<TextView>(R.id.splashBackupStatus).text.isNotBlank())
            assertTrue(!activity.isFinishing)
            SystemClock.sleep(2_450L)
            instrumentation.waitForIdleSync()
            assertTrue(activity.isFinishing || activity.isDestroyed)
        } finally {
            instrumentation.runOnMainSync { if (!activity.isDestroyed) activity.finish() }
        }
    }
}
