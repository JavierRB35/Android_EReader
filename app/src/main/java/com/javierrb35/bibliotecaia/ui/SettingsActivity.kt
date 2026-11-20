package com.javierrb35.bibliotecaia.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.javierrb35.bibliotecaia.BuildConfig
import com.javierrb35.bibliotecaia.R
import com.javierrb35.bibliotecaia.backup.CompleteBackupManager
import com.javierrb35.bibliotecaia.backup.BackupManager
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.updates.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ajustes: biblioteca local, copia completa voluntaria vía SAF y actualizaciones.
 * Sin registro de cuenta, OAuth ni acceso directo a Drive. El usuario elige proveedor.
 */
class SettingsActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.get(this) }
    private var backupBusy = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Toast.makeText(this,
            if (granted) "Avisos automáticos activados."
            else "Seguirás viendo actualizaciones al abrir la aplicación.",
            Toast.LENGTH_LONG).show()
    }

    private val exportBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? -> if (uri != null) exportTo(uri) }

    private val importBackup = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) confirmRestore(uri) }

    private val importLegacyJson = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) confirmLegacyRestore(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val root = ScrollView(this).apply { addView(layout) }
        setContentView(root)

        header(layout, getString(R.string.settings_title))

        section(layout, getString(R.string.settings_backup))
        action(layout, getString(R.string.settings_backup_export)) {
            if (!backupBusy) exportBackup.launch(backupFileName())
        }
        action(layout, getString(R.string.settings_backup_restore)) {
            if (!backupBusy) importBackup.launch(arrayOf("application/zip", "application/x-zip-compressed"))
        }
        hint(layout, getString(R.string.settings_backup_note))
        action(layout, getString(R.string.settings_backup_legacy)) {
            if (!backupBusy) importLegacyJson.launch(arrayOf("application/json"))
        }

        section(layout, getString(R.string.settings_updates))
        action(layout, getString(R.string.settings_updates_check)) { checkUpdates() }
        action(layout, "Activar avisos automáticos de actualización") {
            enableBackgroundNotifications()
        }
        hint(layout, "La app comprueba nuevas versiones al abrirse y cada dos minutos de uso. " +
            "Android programa las comprobaciones en segundo plano aproximadamente cada 15 minutos o más, " +
            "según ahorro de batería y conectividad. La instalación siempre necesita tu autorización.")

        section(layout, getString(R.string.settings_about))
        hint(layout, getString(R.string.settings_about_text, BuildConfig.VERSION_NAME))
    }

    private fun backupFileName(): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT)
            .format(java.util.Date())
        return "BibliotecaIA-completa-$stamp.zip"
    }

    private fun exportTo(uri: Uri) {
        if (backupBusy) return
        backupBusy = true
        lifecycleScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    CompleteBackupManager(applicationContext, db, BuildConfig.VERSION_NAME)
                        .exportTo(uri)
                }
                androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.backup_export_done_title)
                    .setMessage(getString(R.string.backup_export_done_full, report.books))
                    .setPositiveButton(R.string.dialog_ok, null).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, getString(R.string.backup_error,
                    e.message ?: getString(R.string.backup_error_generic)), Toast.LENGTH_LONG).show()
            } finally {
                backupBusy = false
            }
        }
    }

    private fun confirmRestore(uri: Uri) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings_backup_restore)
            .setMessage(R.string.backup_restore_confirm)
            .setPositiveButton(R.string.dialog_ok) { _, _ -> restoreFrom(uri) }
            .setNegativeButton(R.string.dialog_cancel, null).show()
    }

    private fun restoreFrom(uri: Uri) {
        if (backupBusy) return
        backupBusy = true
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    CompleteBackupManager(applicationContext, db, BuildConfig.VERSION_NAME)
                        .restoreFrom(uri)
                }
                androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.backup_restore_done_title)
                    .setMessage(getString(R.string.backup_restore_done_full,
                        result.originals, result.report.annotationsRestored,
                        result.report.versionsRestored, result.report.positionsUpdated))
                    .setPositiveButton(R.string.dialog_ok, null).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, getString(R.string.backup_error,
                    e.message ?: getString(R.string.backup_error_generic)), Toast.LENGTH_LONG).show()
            } finally {
                backupBusy = false
            }
        }
    }

    private fun confirmLegacyRestore(uri: Uri) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings_backup_legacy)
            .setMessage(R.string.backup_legacy_warning)
            .setPositiveButton(R.string.dialog_ok) { _, _ -> restoreLegacy(uri) }
            .setNegativeButton(R.string.dialog_cancel, null).show()
    }

    private fun restoreLegacy(uri: Uri) {
        if (backupBusy) return
        backupBusy = true
        lifecycleScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                        it.readText()
                    } ?: throw IllegalArgumentException("Archivo no legible.")
                    BackupManager(db, BuildConfig.VERSION_NAME).restoreJson(text)
                }
                androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.backup_restore_done_title)
                    .setMessage(getString(R.string.backup_legacy_result, report.booksRestored))
                    .setPositiveButton(R.string.dialog_ok, null).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, getString(R.string.backup_error,
                    e.message ?: getString(R.string.backup_error_generic)), Toast.LENGTH_LONG).show()
            } finally {
                backupBusy = false
            }
        }
    }

    private fun enableBackgroundNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            Toast.makeText(this, "Los avisos automáticos ya están disponibles.",
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkUpdates() {
        lifecycleScope.launch {
            val release = withContext(Dispatchers.IO) {
                UpdateChecker.latestRelease(BuildConfig.VERSION_NAME)
            }
            if (release == null) {
                Toast.makeText(this@SettingsActivity, R.string.updates_none, Toast.LENGTH_SHORT).show()
                return@launch
            }
            androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                .setTitle(getString(R.string.updates_available, release.versionName))
                .setMessage(release.notes.ifBlank { getString(R.string.updates_notes_missing) })
                .setPositiveButton("Descargar APK") { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl)))
                }
                .setNeutralButton("Ver novedades") { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl)))
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    // ---------- construcción de vistas (tokens del sistema de diseño) ----------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun header(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            setText(text)
            textSize = 30f
            setTypeface(Typeface.SERIF)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.bia_text))
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        })
    }

    private fun section(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            setText(text)
            textSize = 14f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.bia_accent))
            setPadding(0, dp(28), 0, dp(4))
        })
    }

    private fun action(parent: LinearLayout, text: String, onClick: () -> Unit) {
        parent.addView(Button(this).apply {
            setText(text)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.bia_accent))
            background = null
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { onClick() }
        })
    }

    private fun hint(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            setText(text)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.bia_text_secondary))
            setPadding(0, dp(8), 0, dp(8))
        })
    }
}
