package com.javierrb35.bibliotecaia.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Typeface
import android.view.Gravity
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
import com.javierrb35.bibliotecaia.sync.ReadingPositionSafSync
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
    private var sharedBusy = false
    private lateinit var sharedStatus: TextView
    private val sharedFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) sharedAction {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            com.javierrb35.bibliotecaia.sync.SharedLibrary.connect(applicationContext, db, uri)
        }
    }
    private val sharedMigration = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sharedAction {
            com.javierrb35.bibliotecaia.sync.SharedLibrary.migrateZip(applicationContext, db, uri)
        }
    }
    private fun sharedAction(operation: suspend () -> Any) {
        if (sharedBusy) return
        sharedBusy = true
        lifecycleScope.launch {
            try { operation() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, e.message ?: "No se pudo sincronizar la carpeta.", Toast.LENGTH_LONG).show()
            } finally {
                sharedBusy = false
                sharedStatus.text = com.javierrb35.bibliotecaia.sync.SharedLibrary.status(applicationContext)
            }
        }
    }
    private lateinit var backupStatus: TextView
    private lateinit var backupError: TextView
    private lateinit var backupRecovery: MaterialButton
    private lateinit var backupPrimary: MaterialButton
    private lateinit var positionSyncStatus: TextView

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

    private val createPositionSyncFile = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val uri = data?.data
            if (uri != null && persistSyncPermission(uri, data.flags, write = true)) {
                lifecycleScope.launch {
                    try {
                        ReadingPositionSafSync.setOwn(applicationContext, db, uri)
                        refreshPositionSyncStatus()
                        Toast.makeText(this@SettingsActivity,
                            "Archivo de lectura del dispositivo configurado.", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@SettingsActivity,
                            e.message ?: "No se pudo preparar la sincronización.",
                            Toast.LENGTH_LONG).show()
                        refreshPositionSyncStatus()
                    }
                }
            }
        }
    }

    private val addPositionSyncPeer = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val uri = data?.data
            if (uri != null && persistSyncPermission(uri, data.flags, write = false)) {
                lifecycleScope.launch {
                    try {
                        ReadingPositionSafSync.addPeer(applicationContext, uri)
                        val ok = ReadingPositionSafSync.refresh(applicationContext, db)
                        refreshPositionSyncStatus()
                        Toast.makeText(this@SettingsActivity,
                            if (ok) "Dispositivo vinculado y posiciones comprobadas."
                            else "Vinculado, pero la sincronización necesita revisión.",
                            Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@SettingsActivity,
                            e.message ?: "No se pudo vincular el archivo.",
                            Toast.LENGTH_LONG).show()
                        refreshPositionSyncStatus()
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
            setPadding(dp(20), dp(12), dp(20), dp(28))
        }
        val root = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(ContextCompat.getColor(this@SettingsActivity, R.color.bia_background))
            addView(layout)
        }
        setContentView(root)

        header(layout, getString(R.string.settings_title))
        hint(layout, getString(R.string.settings_subtitle))
        section(layout, "Biblioteca central compartida (experimental)")
        val sharedCard = card(layout)
        hint(sharedCard, "Elige la misma carpeta en cada dispositivo. Debe permitir listar, crear y leer archivos mediante Android. El ZIP anterior se conserva como copia independiente. No todos los proveedores ofrecen acceso a carpetas.")
        sharedStatus = hint(sharedCard, com.javierrb35.bibliotecaia.sync.SharedLibrary.status(applicationContext))
        action(sharedCard, "Conectar carpeta central") { sharedFolder.launch(null) }
        action(sharedCard, "Migrar copia ZIP existente") {
            if (com.javierrb35.bibliotecaia.sync.SharedLibrary.configured(applicationContext))
                sharedMigration.launch(arrayOf("application/zip", "application/octet-stream"))
            else Toast.makeText(this, "Conecta primero una carpeta central.", Toast.LENGTH_LONG).show()
        }
        action(sharedCard, "Sincronizar biblioteca ahora") {
            if (com.javierrb35.bibliotecaia.sync.SharedLibrary.configured(applicationContext))
                sharedAction { com.javierrb35.bibliotecaia.sync.SharedLibrary.sync(applicationContext, db) }
        }
        section(layout, "Sincronización anterior (JSON de posiciones)")
        val syncCard = card(layout)
        hint(syncCard, "Una vez por dispositivo: crea su archivo JSON en Drive. " +
            "Después vincula en este dispositivo, como lectura, los archivos creados " +
            "en tus otros dispositivos. Cada dispositivo escribe solo el suyo. " +
            "No se requiere cuenta ni OAuth en BibliotecaAI.")
        positionSyncStatus = hint(syncCard, "")
        action(syncCard, "Crear archivo de este dispositivo") {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE,
                    ReadingPositionSafSync.suggestedName(this@SettingsActivity))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            createPositionSyncFile.launch(intent)
        }
        action(syncCard, "Vincular archivo de otro dispositivo") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            addPositionSyncPeer.launch(intent)
        }
        action(syncCard, "Sincronizar posiciones ahora") {
            lifecycleScope.launch {
                val success = ReadingPositionSafSync.refresh(applicationContext, db)
                refreshPositionSyncStatus()
                Toast.makeText(this@SettingsActivity,
                    if (success) "Posiciones sincronizadas."
                    else "No se pudo sincronizar. Consulta el estado.",
                    Toast.LENGTH_LONG).show()
            }
        }
        action(syncCard, "Desvincular archivos de otros dispositivos") {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage("Se olvidarán los archivos de los otros dispositivos. " +
                    "No se borrará ningún archivo de Drive ni ningún dato local.")
                .setPositiveButton(R.string.dialog_ok) { _, _ ->
                    ReadingPositionSafSync.unlinkPeers(this)
                    refreshPositionSyncStatus()
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
        refreshPositionSyncStatus()

        section(layout, getString(R.string.settings_backup))

        val backupCard = card(layout)
        hint(backupCard, getString(R.string.settings_backup_intro))
        hint(backupCard, "Atención: el ZIP completo sigue siendo una copia de recuperación, " +
            "no un archivo colaborativo. Si usas varios dispositivos, no sustituyas " +
            "el ZIP compartido desde un dispositivo desactualizado. La " +
            "sincronización segura inicial comprende solo posiciones de lectura.")
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
        if (::positionSyncStatus.isInitialized) refreshPositionSyncStatus()
    }

    private fun refreshPositionSyncStatus() {
        if (!::positionSyncStatus.isInitialized) return
        val sync = ReadingPositionSafSync.status(this)
        positionSyncStatus.text = buildString {
            append(if (sync.ownConfigured) "Archivo propio configurado" else
                "Sin archivo propio. Crea uno para activar la sincronización")
            append(" · Otros dispositivos: ").append(sync.peerCount)
            if (sync.lastSuccess > 0) {
                append("\nÚltima comprobación: ")
                append(DateFormat.getDateTimeInstance().format(Date(sync.lastSuccess)))
            }
            if (sync.lastError.isNotBlank()) {
                append("\nError: ").append(sync.lastError)
            }
        }
    }

    private fun persistSyncPermission(uri: Uri, flags: Int, write: Boolean): Boolean {
        return try {
            val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
            val mask = if (write) read or Intent.FLAG_GRANT_WRITE_URI_PERMISSION else read
            require((flags and mask) == mask) { "Permisos SAF insuficientes." }
            contentResolver.takePersistableUriPermission(uri, mask)
            true
        } catch (_: Exception) {
            Toast.makeText(this, "No se pudo conservar el permiso del archivo.",
                Toast.LENGTH_LONG).show()
            false
        }
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
            require((resultFlags and mask) == mask) {
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

    // ---------- OLED settings: compact premium cards, proper Android touch targets ----------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun header(parent: LinearLayout, value: String) {
        val masthead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        masthead.addView(TextView(this).apply {
            text = "‹"
            textSize = 30f
            gravity = Gravity.CENTER
            contentDescription = "Volver"
            isClickable = true
            isFocusable = true
            setTextColor(getColor(R.color.bia_accent))
            setBackgroundResource(R.drawable.bg_premium_note)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        masthead.addView(TextView(this).apply {
            text = "BibliotecaAI"
            textSize = 20f
            setTypeface(Typeface.SERIF, Typeface.BOLD)
            setTextColor(getColor(R.color.bia_text))
            setPadding(dp(12), 0, 0, 0)
        })
        parent.addView(masthead)
        parent.addView(TextView(this).apply {
            text = value
            textSize = 33f
            typeface = Typeface.SERIF
            setTextColor(getColor(R.color.bia_text))
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
            setPadding(0, dp(14), 0, dp(4))
        })
    }

    private fun section(parent: LinearLayout, value: String) {
        parent.addView(TextView(this).apply {
            text = value
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.bia_accent))
            setPadding(0, dp(21), 0, dp(9))
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        })
    }

    private fun card(parent: LinearLayout): LinearLayout {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(14), dp(17), dp(14))
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(this@SettingsActivity, R.color.bia_surface))
                cornerRadius = dp(17).toFloat()
                setStroke(dp(1), ContextCompat.getColor(this@SettingsActivity, R.color.bia_outline))
            }
        }
        parent.addView(content, LinearLayout.LayoutParams(-1, -2))
        return content
    }

    private fun action(
        parent: LinearLayout,
        label: String,
        primary: Boolean = false,
        onClick: () -> Unit,
    ): MaterialButton {
        val button = MaterialButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            minHeight = dp(48)
            cornerRadius = dp(12)
            insetTop = 0
            insetBottom = 0
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this@SettingsActivity,
                    if (primary) R.color.bia_accent else R.color.bia_surface_high)
            )
            setTextColor(ContextCompat.getColor(this@SettingsActivity,
                if (primary) R.color.bia_background else R.color.bia_text))
            if (!primary) {
                strokeWidth = dp(1)
                strokeColor = ColorStateList.valueOf(
                    ContextCompat.getColor(this@SettingsActivity, R.color.bia_outline))
            }
            setOnClickListener { onClick() }
        }
        val params = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        }
        parent.addView(button, params)
        return button
    }

    private fun hint(parent: LinearLayout, value: String): TextView {
        val view = TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.bia_text_secondary))
            setLineSpacing(dp(2).toFloat(), 1.08f)
            setPadding(0, dp(3), 0, dp(4))
            if (value.length > 210) {
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                contentDescription = "Mostrar información completa: " + value
                isClickable = true
                isFocusable = true
                setOnClickListener { maxLines = if (maxLines == 3) Integer.MAX_VALUE else 3 }
            }
        }
        parent.addView(view)
        return view
    }
}
