package com.javierrb35.bibliotecaia.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.View
import com.google.android.material.button.MaterialButton
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
import com.javierrb35.bibliotecaia.backup.BackupRoutine
import com.javierrb35.bibliotecaia.backup.BackupManager
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.updates.UpdateChecker
import com.javierrb35.bibliotecaia.updates.UpdateInstaller
import kotlinx.coroutines.Dispatchers
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ajustes: biblioteca local, copia completa voluntaria vía SAF y actualizaciones.
 * Sin registro de cuenta, OAuth ni acceso directo a Drive. El usuario elige proveedor.
 */
class SettingsActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.get(this) }
    private var backupBusy = false
    private lateinit var backupStatus: TextView
    private lateinit var backupError: TextView
    private lateinit var backupRecovery: MaterialButton
    private lateinit var backupPrimary: MaterialButton

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Toast.makeText(this,
            if (granted) "Avisos automáticos activados."
            else "Seguirás viendo actualizaciones al abrir la aplicación.",
            Toast.LENGTH_LONG).show()
    }

    private val createBackup = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.let { data ->
                data.data?.let { uri ->
                    if (persistBackupPermission(uri, data.flags)) {
                        exportTo(uri, replaceExisting = false)
                    }
                }
            }
        }
    }

    private val selectExistingBackup = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.let { data ->
                data.data?.let { uri ->
                    if (persistBackupPermission(uri, data.flags)) {
                        exportTo(uri, replaceExisting = true)
                    }
                }
            }
        }
    }

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
            setPadding(dp(22), dp(22), dp(22), dp(26))
        }
        val root = ScrollView(this).apply {
            fillViewport = true
            setBackgroundColor(ContextCompat.getColor(this@SettingsActivity, R.color.bia_background))
            addView(layout)
        }
        setContentView(root)

        header(layout, getString(R.string.settings_title))
        hint(layout, getString(R.string.settings_subtitle))
        section(layout, getString(R.string.settings_backup))

        val backupCard = card(layout)
        hint(backupCard, getString(R.string.settings_backup_intro))
        backupStatus = hint(backupCard, "")
        backupError = hint(backupCard, "")

        backupPrimary = action(backupCard, getString(R.string.settings_backup_create_now), primary = true) {
            if (backupBusy) return@action
            val destination = BackupRoutine.status(this).uri
            if (destination == null) chooseBackupDestination()
            else exportTo(destination, replaceExisting = true)
        }
        action(backupCard, getString(R.string.settings_backup_pick_destination)) {
            if (!backupBusy) chooseBackupDestination()
        }
        action(backupCard, getString(R.string.settings_backup_restore_action)) {
            if (!backupBusy) {
                importBackup.launch(arrayOf("application/zip", "application/x-zip-compressed"))
            }
        }

        backupRecovery = action(backupCard, getString(R.string.settings_backup_recover_previous)) {
            if (!backupBusy) recoverPreviousBackup()
        }
        hint(backupCard, getString(R.string.settings_backup_privacy_note))

        action(layout, getString(R.string.settings_backup_legacy_short)) {
            if (!backupBusy) importLegacyJson.launch(arrayOf("application/json"))
        }

        section(layout, getString(R.string.settings_updates))
        val updatesCard = card(layout)
        action(updatesCard, getString(R.string.settings_updates_check)) { checkUpdates() }
        action(updatesCard, getString(R.string.settings_updates_notifications)) {
            enableBackgroundNotifications()
        }
        hint(updatesCard, getString(R.string.settings_updates_short_note))

        section(layout, getString(R.string.settings_about))
        hint(layout, getString(R.string.settings_about_text, BuildConfig.VERSION_NAME))
        refreshBackupStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::backupStatus.isInitialized) refreshBackupStatus()
    }

    private fun refreshBackupStatus() {
        val state = BackupRoutine.status(this)
        backupStatus.text = when {
            state.recoveryPending -> getString(R.string.settings_backup_recovery_required)
            state.uri == null -> getString(R.string.settings_backup_not_configured)
            state.lastSuccess == 0L -> getString(R.string.settings_backup_not_verified)
            else -> getString(R.string.settings_backup_last_verified,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(state.lastSuccess)))
        }
        backupStatus.setTextColor(ContextCompat.getColor(this,
            if (state.recoveryPending || state.uri == null) R.color.bia_accent
            else R.color.bia_text))
        backupError.visibility = if (state.lastError.isBlank()) View.GONE else View.VISIBLE
        backupError.text = if (state.lastError.isBlank()) "" else
            getString(R.string.settings_backup_last_error, state.lastError)
        backupRecovery.visibility = if (state.recoveryPending) View.VISIBLE else View.GONE
        backupPrimary.text = getString(R.string.settings_backup_create_now)
        backupPrimary.isEnabled = !backupBusy && !state.recoveryPending
    }

    private fun chooseBackupDestination() {
        // Scriptura's two explicit SAF choices: create once, or reuse an
        // existing file. CreateDocument alone cannot overwrite an existing ZIP.
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings_backup_pick_destination)
            .setItems(arrayOf(
                getString(R.string.settings_backup_choose_new),
                getString(R.string.settings_backup_choose_existing)
            )) { _, option ->
                val create = option == 0
                val intent = Intent(
                    if (create) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT
                ).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/zip"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    if (create) putExtra(Intent.EXTRA_TITLE, "BibliotecaIA-completa.zip")
                }
                if (create) createBackup.launch(intent) else selectExistingBackup.launch(intent)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun persistBackupPermission(uri: Uri, resultFlags: Int): Boolean {
        return try {
            val mask = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            require(resultFlags and mask == mask) {
                getString(R.string.settings_backup_permission_error)
            }
            contentResolver.takePersistableUriPermission(uri, mask)
            true
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.settings_backup_permission_error),
                Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun exportTo(uri: Uri, replaceExisting: Boolean) {
        if (backupBusy) return
        backupBusy = true
        refreshBackupStatus()
        backupStatus.text = getString(R.string.settings_backup_in_progress)
        lifecycleScope.launch {
            try {
                BackupRoutine.save(applicationContext, db, BuildConfig.VERSION_NAME,
                    force = true, chosenUri = uri, replaceExisting = replaceExisting)
                refreshBackupStatus()
                androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.backup_export_done_title)
                    .setMessage(R.string.settings_backup_verified_message)
                    .setPositiveButton(R.string.dialog_ok, null).show()
            } catch (error: Exception) {
                refreshBackupStatus()
                androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.settings_backup_error_title)
                    .setMessage(error.message ?: getString(R.string.backup_error_generic))
                    .setPositiveButton(R.string.dialog_ok, null).show()
            } finally {
                backupBusy = false
                refreshBackupStatus()
            }
        }
    }

    private fun recoverPreviousBackup() {
        if (backupBusy) return
        backupBusy = true
        lifecycleScope.launch {
            try {
                BackupRoutine.recover(applicationContext, db, BuildConfig.VERSION_NAME)
                androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.settings_backup_recover_previous)
                    .setMessage(R.string.settings_backup_recovered)
                    .setPositiveButton(R.string.dialog_ok, null).show()
            } catch (error: Exception) {
                androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.settings_backup_error_title)
                    .setMessage(error.message ?: getString(R.string.backup_error_generic))
                    .setPositiveButton(R.string.dialog_ok, null).show()
            } finally {
                backupBusy = false
                refreshBackupStatus()
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
                    BackupRoutine.restore(applicationContext, db, BuildConfig.VERSION_NAME, uri)
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
                .setPositiveButton("Instalar actualización") { _, _ ->
                    UpdateInstaller.start(this@SettingsActivity, release)
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
