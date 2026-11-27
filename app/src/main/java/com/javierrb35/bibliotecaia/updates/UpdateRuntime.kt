package com.javierrb35.bibliotecaia.updates

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.javierrb35.bibliotecaia.BuildConfig
import com.javierrb35.bibliotecaia.ui.SplashActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

/**
 * Immediate on app resume, then periodic checks across ALL foreground screens.
 * Poll at most every 2 minutes to respect anonymous GitHub API rate limits.
 * Background WorkManager is opportunistic and has Android's >=15 minute floor.
 */
internal object UpdateRuntime : Application.ActivityLifecycleCallbacks {
    private const val FOREGROUND_INTERVAL_MS = 120_000L
    private const val BACKGROUND_WORK_ID = "bibliotecaia_signed_release_watch"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var active: WeakReference<Activity>? = null
    private var lastCheckUptime: Long = -FOREGROUND_INTERVAL_MS
    private var cachedRelease: UpdateChecker.ReleaseInfo? = null

    fun initialize(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED).build()
        val work = PeriodicWorkRequestBuilder<UpdateBackgroundWorker>(
            15, TimeUnit.MINUTES
        ).setConstraints(constraints).build()
        WorkManager.getInstance(application).enqueueUniquePeriodicWork(
            BACKGROUND_WORK_ID, ExistingPeriodicWorkPolicy.KEEP, work
        )
    }

    override fun onActivityResumed(activity: Activity) {
        // Splash lasts only two seconds. Do not start a request there which
        // would be cancelled during navigation and consume the polling interval.
        if (activity is SplashActivity) return
        UpdateInstaller.resumeAfterPermission(activity)
        var forcePrompt = activity.intent?.getBooleanExtra(UpdateNotice.EXTRA_SHOW_UPDATE, false) == true
        if (forcePrompt) {
            activity.intent?.removeExtra(UpdateNotice.EXTRA_SHOW_UPDATE)
            lastCheckUptime = -FOREGROUND_INTERVAL_MS
        }
        active = WeakReference(activity)
        job?.cancel()
        job = scope.launch {
            while (isActive && active?.get() === activity &&
                !activity.isFinishing && !activity.isDestroyed) {
                val elapsed = SystemClock.elapsedRealtime() - lastCheckUptime
                if (elapsed >= FOREGROUND_INTERVAL_MS) {
                    val result = withContext(Dispatchers.IO) {
                        UpdateChecker.check(BuildConfig.VERSION_NAME)
                    }
                    // A cancelled request must not delay the next resumed screen.
                    lastCheckUptime = SystemClock.elapsedRealtime()
                    when (result) {
                        is UpdateChecker.CheckResult.Available -> cachedRelease = result.release
                        UpdateChecker.CheckResult.Current -> cachedRelease = null
                        is UpdateChecker.CheckResult.Unavailable -> Unit
                    }
                }
                val release = cachedRelease
                if (release != null && isActive && active?.get() === activity &&
                    activity.hasWindowFocus()) {
                    UpdateNotice.showIfNew(activity, release, force = forcePrompt)
                    forcePrompt = false
                }
                // Deliver cached results when focus returns, without another HTTP request.
                delay(if (release != null && !activity.hasWindowFocus()) 500L
                    else (FOREGROUND_INTERVAL_MS -
                        (SystemClock.elapsedRealtime() - lastCheckUptime)).coerceAtLeast(500L))
            }
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (active?.get() === activity) {
            job?.cancel()
            job = null
            active = null
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
