package com.javierrb35.bibliotecaia.updates

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.javierrb35.bibliotecaia.R
import com.javierrb35.bibliotecaia.ui.MainActivity

/**
 * Update delivery is an opt-in Android installation: no self-update without
 * user consent, no modification of offline books, annotations or app storage.
 */
internal object UpdateNotice {
    private const val PREFS = "biblioteca_update_notifications"
    private const val LAST_SHOWN = "last_foreground_tag"
    private const val LAST_NOTIFIED = "last_background_tag"
    private const val CHANNEL = "bibliotecaia_updates"
    private const val NOTIFICATION_ID = 4102

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun showIfNew(activity: Activity, release: UpdateChecker.ReleaseInfo) {
        if (activity.isFinishing || activity.isDestroyed) return
        val prefs = preferences(activity)
        if (prefs.getString(LAST_SHOWN, null) == release.tagName) return

        // Persist only when actually surfaced to the user; never consume a new
        // version on network errors or before the Activity is visible.
        val message = "Biblioteca IA ${release.versionName} ya está disponible." +
            if (release.notes.isBlank()) "" else "\n\n${release.notes}"
        try {
            if (activity is MainActivity) {
                AlertDialog.Builder(activity)
                    .setTitle("Actualización disponible")
                    .setMessage(message)
                    .setPositiveButton("Descargar APK") { _, _ ->
                        openOfficialAsset(activity, release.apkUrl)
                    }
                    .setNeutralButton("Ver novedades") { _, _ ->
                        openOfficialAsset(activity, release.htmlUrl)
                    }
                    .setNegativeButton("Más tarde", null)
                    .show()
            } else {
                val root = activity.findViewById<android.view.View>(android.R.id.content)
                Snackbar.make(root, "Biblioteca IA ${release.versionName} disponible",
                    Snackbar.LENGTH_LONG)
                    .setAction("Descargar") { openOfficialAsset(activity, release.apkUrl) }
                    .show()
            }
            prefs.edit().putString(LAST_SHOWN, release.tagName).apply()
        } catch (_: RuntimeException) {
            // A destroyed foreground screen should never crash reading.
        }
    }

    /** Only URLs parsed and pinned to the official GitHub repo reach this point. */
    private fun openOfficialAsset(context: Context, url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun notifyIfNew(context: Context, release: UpdateChecker.ReleaseInfo) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return

        val prefs = preferences(context)
        if (prefs.getString(LAST_NOTIFIED, null) == release.tagName) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Actualizaciones de Biblioteca IA",
                NotificationManager.IMPORTANCE_DEFAULT)
        )
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(context, release.versionName.hashCode(),
            intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Nueva versión: Biblioteca IA ${release.versionName}")
            .setContentText("Pulsa para descargar la actualización firmada.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
            prefs.edit().putString(LAST_NOTIFIED, release.tagName).apply()
        } catch (_: SecurityException) {
            // Notifications are optional; the in-app checker still works.
        }
    }
}
