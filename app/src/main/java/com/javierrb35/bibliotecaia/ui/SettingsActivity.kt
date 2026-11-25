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
import com.javierrb35.bibliotecaia.sync.SharedLibrary
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
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
    private lateinit var setupWizard: StorageSetupWizard
    private var backupBusy = false
    private var sharedBusy = false
    private lateinit var sharedStatus: TextView
    private lateinit var sharedPrimary: MaterialButton
    private lateinit var sharedLink: MaterialButton
    private lateinit var sharedSync: MaterialButton
    private lateinit var sharedOptions: MaterialButton
    private lateinit var sharedGuide: TextView
    private var ownName: String = ""
    private var nameUri: Uri? = null
    private val sharedFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) sharedAction {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            com.javierrb35.bibliotecaia.sync.SharedLibrary.connect(applicationContext, db, uri)
        }
    }
    private val sharedFileOwn = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) sharedAction {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            com.javierrb35.bibliotecaia.sync.SharedLibrary.connectFile(applicationContext, db, uri, newDocument = true)
        }
    }
    private val sharedFileExisting = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.let { data ->
            data.data?.let { uri -> sharedAction {
                val grant = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                require(grant == (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
                    "El proveedor no permite escribir en este contenedor."
                }
                contentResolver.takePersistableUriPermission(uri, grant)
                com.javierrb35.bibliotecaia.sync.SharedLibrary.connectFile(applicationContext, db, uri)
            } }
        }
    }
    private fun chooseSharedConnection(action: () -> Unit) {
        if (sharedBusy) return
        if (!com.javierrb35.bibliotecaia.sync.SharedLibrary.configured(applicationContext)) {
            action()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Cambiar conexión de biblioteca")
            .setMessage("Se conservarán los libros descargados, notas, posiciones, el ZIP y los archivos de la biblioteca anterior. Los libros solo disponibles en la conexión anterior seguirán allí; podrás volver a esa conexión desde Ajustes. No se trasladan automáticamente sus originales al nuevo destino.")
            .setPositiveButton("Continuar") { _, _ -> action() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
    private val sharedFilePeer = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sharedAction {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val result = SharedLibrary.addFilePeer(applicationContext, db, uri)
            if (result.errors.isEmpty()) {
                Toast.makeText(this@SettingsActivity,
                    "Dispositivo vinculado y biblioteca comprobada.", Toast.LENGTH_LONG).show()
            }
            result
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
            sharedStatus.text = "Comprobando la conexión…"
            refreshSharedButtons()
            try {
                val result = operation()
                if (result is SharedLibrary.Result && result.errors.isNotEmpty()) {
                    androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("Conexión con avisos")
                        .setMessage(result.errors.joinToString("\n").take(1800))
                        .setPositiveButton("Entendido", null).show()
                }
            }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                com.javierrb35.bibliotecaia.sync.SharedLibrary.recordFailure(applicationContext, e)
                androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("No se pudo completar la conexión")
                    .setMessage((e.message ?: "No se pudo acceder al archivo.") +
                        "\n\nPuedes volver a intentarlo o cambiar la ubicación desde las opciones. No borres los datos de la aplicación.")
                    .setPositiveButton("Entendido", null).show()
            } finally {
                sharedBusy = false
                refreshSharedPanel()
            }
        }
    }

    private fun createOwnLibrary() = chooseSharedConnection {
        val model = Build.MODEL.replace(Regex("[^a-zA-Z0-9_-]+"), "-").trim('-').take(28).ifBlank { "Android" }
        val suffix = if (SharedLibrary.configured(applicationContext))
            "-" + java.util.UUID.randomUUID().toString().take(8) else ""
        sharedFileOwn.launch("BibliotecaIA-" + model + "-" +
            ReadingPositionSafSync.deviceId(applicationContext).take(8) + suffix + ".zip")
    }
    private fun repairOwnLibrary() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Reparar biblioteca sin perder datos")
            .setMessage("El archivo guardado no coincide con la identidad de este dispositivo. Crea un archivo nuevo en la misma carpeta de Drive. El anterior no se borrará ni se sobrescribirá; si es válido se conservará como fuente de lectura. Después vincula el nuevo archivo en el otro dispositivo.")
            .setPositiveButton("Crear archivo nuevo") { _, _ -> createOwnLibrary() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun openOwnLibrary() = chooseSharedConnection {
        sharedFileExisting.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        })
    }
    private fun linkDevice() {
        if (!sharedBusy) androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Añadir otro dispositivo")
            .setMessage("1. En el otro móvil o tablet, abre Ajustes → Biblioteca compartida y configura su archivo en Drive, en la misma carpeta.\n\n2. Aquí selecciona el archivo creado por el otro dispositivo.\n\n3. En el otro equipo, añade el archivo de este dispositivo" +
                (if (ownName.isBlank()) "." else ":\n" + ownName) +
                "\n\nSolo hay que hacerlo una vez por dispositivo.")
            .setPositiveButton("Seleccionar archivo del otro dispositivo") { _, _ ->
                sharedFilePeer.launch(arrayOf("application/zip", "application/octet-stream"))
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun showSetup() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("¿Dónde quieres guardar tu biblioteca?")
            .setItems(arrayOf("Google Drive u otra nube (recomendado)", "Abrir mi archivo de biblioteca existente", "Carpeta compartida compatible (avanzado)")) { _, which ->
                when (which) {
                    0 -> createOwnLibrary()
                    1 -> openOwnLibrary()
                    else -> chooseSharedConnection {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Carpeta compartida")
                            .setMessage("Elige una carpeta que todos tus dispositivos puedan abrir. Si Google Drive no aparece en este selector, vuelve atrás y elige Google Drive u otra nube.")
                            .setPositiveButton("Seleccionar carpeta") { _, _ -> sharedFolder.launch(null) }
                            .setNegativeButton(android.R.string.cancel, null).show()
                    }
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun restorePreviousConnection() {
        val saved = SharedLibrary.previousConnections(applicationContext)
        if (saved.isEmpty()) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Conexiones anteriores")
            .setItems(saved.map { it.label }.toTypedArray()) { _, which ->
                chooseSharedConnection {
                    sharedAction { SharedLibrary.restoreConnection(applicationContext, db, saved[which]) }
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun showSharedOptions() {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        fun option(label: String, action: () -> Unit) { labels.add(label); actions.add(action) }
        option("Cambiar ubicación o reparar permisos") { showSetup() }
        if (SharedLibrary.fileMode(applicationContext)) {
            option("Abrir de nuevo mi archivo") { openOwnLibrary() }
            if (SharedLibrary.peerCount(applicationContext) > 0) option("Gestionar dispositivos vinculados") {
                val peers = SharedLibrary.linkedFiles(applicationContext)
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Dispositivos vinculados")
                    .setItems(peers.mapIndexed { index, uri -> "Dispositivo ${index + 1} · " + (uri.lastPathSegment ?: "").takeLast(45) }.toTypedArray()) { _, which ->
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Desvincular dispositivo")
                            .setMessage("Se dejará de sincronizar con este archivo. No se borrará ningún dato, pero sus libros que no estén en otra ubicación podrán dejar de estar disponibles aquí.")
                            .setPositiveButton("Desvincular") { _, _ ->
                                sharedAction { SharedLibrary.removeFilePeer(applicationContext, peers[which]) }
                            }.setNegativeButton(android.R.string.cancel, null).show()
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }
        }
        if (SharedLibrary.configured(applicationContext)) option("Incorporar una copia de seguridad anterior") {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Incorporar libros de un respaldo")
                .setMessage("Selecciona un ZIP de recuperación anterior. Los originales se añadirán a la biblioteca sin borrar la copia.")
                .setPositiveButton("Seleccionar copia") { _, _ ->
                    sharedMigration.launch(arrayOf("application/zip", "application/octet-stream"))
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
        if (SharedLibrary.previousConnections(applicationContext).isNotEmpty())
            option("Recuperar conexión anterior") { restorePreviousConnection() }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Opciones de biblioteca")
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun refreshSharedButtons() {
        if (!::sharedPrimary.isInitialized) return
        val app = applicationContext
        val configured = SharedLibrary.configured(app)
        val files = SharedLibrary.fileMode(app)
        val linked = SharedLibrary.peerCount(app)
        val access = SharedLibrary.hasAccess(app)
        val repair = SharedLibrary.needsRepair(app)
        sharedPrimary.text = when {
            !configured -> "Configurar biblioteca"
            repair || !access -> "Reparar conexión"
            files && linked == 0 -> "Añadir otro dispositivo"
            else -> "Sincronizar ahora"
        }
        sharedPrimary.isEnabled = !sharedBusy
        sharedLink.visibility = if (configured && files && linked > 0 && access && !repair) View.VISIBLE else View.GONE
        sharedLink.isEnabled = !sharedBusy
        sharedSync.visibility = if (configured && files && linked == 0 && access && !repair) View.VISIBLE else View.GONE
        sharedSync.isEnabled = !sharedBusy
        sharedOptions.isEnabled = !sharedBusy
        sharedOptions.visibility = if (configured || SharedLibrary.previousConnections(app).isNotEmpty()) View.VISIBLE else View.GONE
        sharedGuide.text = when {
            !configured -> "Guarda los libros en Drive y continúa leyendo desde otro dispositivo. Sin crear una cuenta nueva."
            repair -> "El archivo no coincide con la identidad de este dispositivo. Repara la conexión; no elimines ningún ZIP."
            !access -> "Falta acceso al archivo o carpeta. Repara la conexión sin borrar los datos."
            files && linked == 0 -> "Archivo de este dispositivo preparado. Añade el otro móvil o tablet para compartir libros y posiciones."
            files -> "Dispositivos vinculados: $linked. Comprueba que también han vinculado el archivo de este dispositivo."
            else -> "En los demás dispositivos, selecciona la misma carpeta compartida."
        }
    }
    private fun refreshSharedPanel() {
        if (!::sharedStatus.isInitialized) return
        refreshSharedButtons()
        if (sharedBusy) return
        val app = applicationContext
        val selected = SharedLibrary.uri(app)
        val success = SharedLibrary.lastSuccess(app)
        val published = SharedLibrary.lastPublication(app)
        sharedStatus.text = buildString {
            append(SharedLibrary.status(app))
            if (selected != null) append("\nID de este dispositivo: ").append(ReadingPositionSafSync.deviceId(app).take(8))
            if (selected != null && nameUri == selected && ownName.isNotBlank()) append("\nArchivo o carpeta: ").append(ownName)
            if (success > 0) append("\nÚltima comprobación: ").append(DateFormat.getDateTimeInstance().format(Date(success)))
            if (published > 0) append("\nÚltimo envío comprobado: ").append(DateFormat.getDateTimeInstance().format(Date(published)))
        }
        if (selected == null) { nameUri = null; ownName = "" }
        if (selected != null && selected != nameUri) {
            nameUri = selected
            ownName = ""
            lifecycleScope.launch {
                val name = withContext(Dispatchers.IO) { SharedLibrary.connectedName(app) }
                if (SharedLibrary.uri(app) == selected && nameUri == selected) {
                    ownName = name
                    refreshSharedPanel()
                }
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
                        val ok = ReadingPositionSafSync.refreshForRead(applicationContext, db)
                        ReadingPositionSafSync.requestPublish(applicationContext)
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
        setupWizard = StorageSetupWizard(this, db) {
            refreshSharedPanel()
            if (::backupStatus.isInitialized) refreshBackupStatus()
            refreshPositionSyncStatus()
        }
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
        section(layout, "Configuración guiada")
        val setupCard = card(layout)
        hint(setupCard, "Configura la biblioteca, la copia de seguridad y la lectura en varios dispositivos en un único asistente. Conservamos lo que ya funciona.")
        action(setupCard, "Iniciar asistente", primary = true) { setupWizard.start() }
        section(layout, "Biblioteca compartida")
        val sharedCard = card(layout)
        sharedStatus = hint(sharedCard, "")
        sharedGuide = hint(sharedCard, "")
        sharedPrimary = action(sharedCard, "Configurar biblioteca", primary = true) {
            when {
                SharedLibrary.needsRepair(applicationContext) -> repairOwnLibrary()
                !SharedLibrary.configured(applicationContext) || !SharedLibrary.hasAccess(applicationContext) -> setupWizard.start()
                SharedLibrary.fileMode(applicationContext) && SharedLibrary.peerCount(applicationContext) == 0 -> setupWizard.start()
                else -> sharedAction { SharedLibrary.sync(applicationContext, db) }
            }
        }
        sharedLink = action(sharedCard, "Añadir otro dispositivo") { linkDevice() }
        sharedSync = action(sharedCard, "Sincronizar ahora") {
            sharedAction { SharedLibrary.sync(applicationContext, db) }
        }
        sharedOptions = action(sharedCard, "Opciones de biblioteca") { showSharedOptions() }
        refreshSharedPanel()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SharedLibrary.changes.collect { refreshSharedPanel() }
            }
        }

        section(layout, "Sincronización rápida de posiciones")
        val positionCard = card(layout)
        hint(positionCard, "Tu última página se guarda automáticamente. Usa el asistente para conectar otro equipo.")
        positionSyncStatus = hint(positionCard, "")
        action(positionCard, "Configurar lectura en otros dispositivos") { setupWizard.start() }
        val advancedPositions = action(positionCard, "Opciones avanzadas de sincronización") {}
        val syncCard = card(positionCard)
        syncCard.visibility = View.GONE
        advancedPositions.setOnClickListener {
            syncCard.visibility = if (syncCard.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        action(syncCard, "Crear JSON de este dispositivo") {
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
        action(syncCard, "Vincular JSON de otro dispositivo") {
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
                val success = ReadingPositionSafSync.refreshForRead(applicationContext, db)
                ReadingPositionSafSync.requestPublish(applicationContext)
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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ReadingPositionSafSync.changes.collect { refreshPositionSyncStatus() }
            }
        }

        section(layout, getString(R.string.settings_backup))

        val backupCard = card(layout)
        hint(backupCard, getString(R.string.settings_backup_intro))
        hint(backupCard, "La copia de seguridad permite recuperar tus datos. No es necesaria para conectar otro dispositivo. Crea un archivo distinto del de la biblioteca compartida.")
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
        setupWizard.restore(savedInstanceState)
        if (savedInstanceState == null && intent.getBooleanExtra(StorageSetupWizard.EXTRA_OPEN, false))
            window.decorView.post { setupWizard.start() }
        refreshBackupStatus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::setupWizard.isInitialized) setupWizard.save(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::sharedStatus.isInitialized) refreshSharedPanel()
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
            append(if (ReadingPositionSafSync.fastReady(this@SettingsActivity))
                "\nCanal JSON configurado." else "\nCompleta los enlaces JSON; se mantiene el canal anterior durante la transición.")
            if (sync.lastPublished > 0) {
                append("\nÚltimo envío JSON comprobado: ")
                append(DateFormat.getDateTimeInstance().format(Date(sync.lastPublished)))
            }
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
            state.missingTitles.isNotEmpty() -> "Copia verificada con originales ausentes: " + state.missingTitles.size +
                "\n" + state.missingTitles.joinToString("\n").take(800)
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
                val result = BackupRoutine.save(applicationContext, db, BuildConfig.VERSION_NAME,
                    force = true, chosenUri = uri, replaceExisting = replaceExisting)
                refreshBackupStatus()
                androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.backup_export_done_title)
                    .setMessage(if (result == BackupRoutine.Result.PARTIAL)
                        "Copia guardada y verificada, pero incompleta. Se conservan los datos, notas y posiciones de todos los libros. No se han encontrado los originales de:\n\n" +
                            BackupRoutine.status(this@SettingsActivity).missingTitles.joinToString("\n") +
                            "\n\nPuedes reincorporarlos más adelante."
                        else getString(R.string.settings_backup_verified_message))
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
                        result.report.versionsRestored, result.report.positionsUpdated) +
                        if (result.missingTitles.isEmpty()) "" else "\n\nOriginales no incluidos en la copia (sus datos se han conservado):\n" +
                            result.missingTitles.joinToString("\n"))
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
        if (BuildConfig.QA_BUILD) {
            Toast.makeText(this, "Compilación QA aislada. No se instalarán actualizaciones de producción.", Toast.LENGTH_LONG).show()
            return
        }
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
