package com.javierrb35.bibliotecaia

import android.app.Application
import android.os.Build
import com.javierrb35.bibliotecaia.updates.UpdateRuntime
import java.io.File

/**
 * Initialize the updater only in the app's main process.
 *
 * PdfRenderService runs in an Android isolated process. WorkManager startup is
 * not available there, so calling UpdateRuntime.initialize() in every process
 * can crash the PDF service before it even binds to the reader Activity.
 */
class BibliotecaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (isMainProcess() && !BuildConfig.QA_BUILD) UpdateRuntime.initialize(this)
    }

    private fun isMainProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            // Android 26/27: the process name is provided by the kernel; read
            // only this local process metadata, never private book content.
            runCatching {
                val bytes = File("/proc/self/cmdline").readBytes()
                bytes.copyOfRange(0, bytes.indexOf(0).takeIf { it >= 0 } ?: bytes.size)
                    .toString(Charsets.UTF_8)
            }.getOrNull()
        }
        return name == packageName
    }
}
