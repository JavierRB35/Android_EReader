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
import kotlinx.coroutines.CancellationException
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
        active = WeakReference(activity)
        job?.cancel()
        job = scope.launch {
            while (isActive && active?.get() === activity &&
                !activity.isFinishing && !activity.isDestroyed) {
                val elapsed = SystemClock.elapsedRealtime() - lastCheckUptime
                if (elapsed < FOREGROUND_INTERVAL_MS) {
                    delay(FOREGROUND_INTERVAL_MS - elapsed)
                    continue
                }
                lastCheckUptime = SystemClock.elapsedRealtime()
                val release = try {
                    withContext(Dispatchers.IO) {
                        UpdateChecker.latestRelease(BuildConfig.VERSION_NAME)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (release != null && isActive && active?.get() === activity)
                    UpdateNotice.showIfNew(activity, release)
                delay(FOREGROUND_INTERVAL_MS)
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
