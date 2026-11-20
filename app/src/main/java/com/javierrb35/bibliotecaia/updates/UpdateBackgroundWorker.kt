package com.javierrb35.bibliotecaia.updates

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.javierrb35.bibliotecaia.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android determines exact timing; background checks cannot be guaranteed
 * instant without a push service. Never downloads or installs in background.
 */
class UpdateBackgroundWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val available = withContext(Dispatchers.IO) {
            UpdateChecker.latestRelease(BuildConfig.VERSION_NAME)
        }
        if (available != null) UpdateNotice.notifyIfNew(applicationContext, available)
        return Result.success()
    }
}
