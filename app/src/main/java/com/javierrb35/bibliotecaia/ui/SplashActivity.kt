package com.javierrb35.bibliotecaia.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.javierrb35.bibliotecaia.BuildConfig
import com.javierrb35.bibliotecaia.R
import com.javierrb35.bibliotecaia.backup.BackupRoutine

/**
 * Cold-start brand screen. Backup is independent and must never gate navigation.
 * BackupRoutine preserves ZIP content verification, SAF permission checks and mutex.
 */
class SplashActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val openLibrary = Runnable {
        if (!isFinishing && !isDestroyed) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            overridePendingTransition(0, 0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val destinationConfigured = BackupRoutine.status(applicationContext).uri != null
        findViewById<TextView>(R.id.splashBackupStatus).setText(
            if (destinationConfigured) R.string.splash_backup_checking
            else R.string.splash_backup_no_destination
        )
        findViewById<View>(R.id.splashBackupProgress).visibility =
            if (destinationConfigured) View.VISIBLE else View.INVISIBLE

        // Existing routine launches I/O in its own application coroutine scope.
        // MainActivity/shared worker coordinate backup after remote refresh.
        // The backup routine itself also checks multi-device ownership.
        handler.postDelayed(openLibrary, SPLASH_DURATION_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(openLibrary)
        super.onDestroy()
    }

    private companion object {
        const val SPLASH_DURATION_MS = 2_000L
    }
}
