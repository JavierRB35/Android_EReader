package com.javierrb35.bibliotecaia.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.javierrb35.bibliotecaia.BuildConfig
import com.javierrb35.bibliotecaia.backup.BackupRoutine
import com.javierrb35.bibliotecaia.backup.CentralCatalogue
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.sync.NamedFolderSetupFailure
import com.javierrb35.bibliotecaia.sync.ReadingPositionSafSync
import com.javierrb35.bibliotecaia.sync.SharedLibrary
import com.javierrb35.bibliotecaia.sync.StorageGrants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One resumable setup. A picker grants access; it never asks the reader to
 * construct, name, edit or understand a JSON or ZIP. */
internal class StorageSetupWizard(
    private val activity: AppCompatActivity, private val db: AppDatabase,
    private val recoverBackup: () -> Unit,
    private val changed: () -> Unit
) {
    companion object {
        private const val PREFS = "storage_setup_wizard"
        const val EXTRA_OPEN = "storage_setup_wizard_open"
        fun pending(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return !prefs.getBoolean("dismissed", false) &&
                (!SharedLibrary.namedFolderMode(context) ||
                    SharedLibrary.writeAccess(context) != com.javierrb35.bibliotecaia.sync.SharedWriteAccess.READY)
        }
    }
    private val app get() = activity.applicationContext
    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var active = false
    private var waiting = false
    private var several = true
    private var busy = false
    private var skipBackup = false
    private var targetPeers = 1
    private var folder: Uri? = null
    private var prompt: AlertDialog? = null
    private var unified = false
    private var currentPhase = StorageSetupOutcome.Issue.LIBRARY_SYNC
    private var seekingPeer = false
    private var choosingLocation = false
    private var locatingDrive = false

    // A document picker can expose a provider that is absent from the tree
    // picker. Its result is a navigation hint ONLY, never a library grant.
    private val locateDrive = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        waiting = false
        if (uri == null || uri.scheme != "content") {
            locatingDrive = false
            showLocationChoice()
        } else {
            locatingDrive = true
            show("Autorizar la carpeta completa",
                "El selector de archivos está disponible. Ahora selecciona la carpeta completa en el selector de carpetas. " +
                    "El archivo elegido no se ha abierto ni se ha guardado como biblioteca.",
                "Seleccionar carpeta" to { launchPicker { pickFolder.launch(uri) } },
                "Volver" to { locatingDrive = false; showLocationChoice() })
        }
    }

    private val pickFolder = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        waiting = false
        if (uri == null) {
            if (locatingDrive) {
                locatingDrive = false
                showLocationChoice("No se ha autorizado una carpeta. Si Drive aparece al buscar archivos pero no al seleccionar carpetas, " +
                    "puede que su proveedor no admita el acceso completo. Puedes volver a intentarlo o elegir otra carpeta compartida compatible.")
            } else if (choosingLocation || unified) showLocationChoice() else advance()
            return@registerForActivityResult
        }
        locatingDrive = false
        runStep {
            currentPhase = StorageSetupOutcome.Issue.LIBRARY_ACCESS
            require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) {
                "La ubicación devuelta no es un permiso de carpeta."
            }
            val resolver = activity.contentResolver
            val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
            val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val prior = resolver.persistedUriPermissions.firstOrNull { it.uri == uri }
            val oldFlags = (if (prior?.isReadPermission == true) read else 0) or
                (if (prior?.isWritePermission == true) write else 0)
            val newFlags = (read or write) and oldFlags.inv()
            var activated = false
            try {
                resolver.takePersistableUriPermission(uri, read or write)
                require(resolver.persistedUriPermissions.any {
                    it.uri == uri && it.isReadPermission && it.isWritePermission
                }) { "Faltan permisos persistentes de lectura y escritura sobre la carpeta." }
                folder = uri
                prepareAll(uri)
                activated = true
                choosingLocation = false
            } finally {
                // Failed selection must not revoke a previously granted permission
                // or replace the last valid library. Retry asks for a fresh tree.
                if (!activated) {
                    folder = null
                    if (newFlags != 0 && SharedLibrary.uri(app) != uri)
                        runCatching { resolver.releasePersistableUriPermission(uri, newFlags) }
                }
            }
        }
    }
    private val libraryFile = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        waiting = false
        if (uri == null) {
            if (choosingLocation) showLocationChoice() else advance()
            return@registerForActivityResult
        }
        runStep {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            currentPhase = StorageSetupOutcome.Issue.LIBRARY_SYNC
            val result = SharedLibrary.connectFile(app, db, uri, newDocument = true)
            require(result.errors.isEmpty()) { result.errors.joinToString(" · ") }
            choosingLocation = false
        }
    }
    private val repairLibrary = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        waiting = false
        val data = result.data
        val uri = data?.data
        if (result.resultCode != android.app.Activity.RESULT_OK || uri == null) { advance(); return@registerForActivityResult }
        runStep {
            require(uri == SharedLibrary.uri(app)) { "Selecciona el archivo de biblioteca que ya tenías configurado." }
            val mask = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            require((data.flags and mask) == mask) { "La ubicación no permite escribir." }
            activity.contentResolver.takePersistableUriPermission(uri, mask)
            val result = SharedLibrary.connectFile(app, db, uri)
            require(result.errors.isEmpty()) { result.errors.joinToString(" · ") }
        }
    }
    private val repairBackup = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        waiting = false
        val data = result.data
        val uri = data?.data
        if (result.resultCode != android.app.Activity.RESULT_OK || uri == null) { advance(); return@registerForActivityResult }
        runStep {
            require(uri == BackupRoutine.savedDestination(app)) { "Selecciona la misma copia que ya tenías configurada." }
            val mask = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            require((data.flags and mask) == mask) { "La ubicación no permite escribir." }
            activity.contentResolver.takePersistableUriPermission(uri, mask)
            currentPhase = StorageSetupOutcome.Issue.BACKUP_WRITE
            BackupRoutine.save(app, db, BuildConfig.VERSION_NAME, true, uri, replaceExisting = true)
        }
    }
    private fun openWritable() = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "application/zip"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }
    private val backupFile = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        waiting = false
        if (uri == null) { advance(); return@registerForActivityResult }
        runStep {
            currentPhase = StorageSetupOutcome.Issue.BACKUP_WRITE
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            BackupRoutine.save(app, db, BuildConfig.VERSION_NAME, true, uri, replaceExisting = false)
        }
    }
    private val positionFile = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        waiting = false
        if (uri == null) { advance(); return@registerForActivityResult }
        runStep {
            currentPhase = StorageSetupOutcome.Issue.PROGRESS_WRITE
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            ReadingPositionSafSync.setOwn(app, db, uri)
            ReadingPositionSafSync.requestPublish(app)
        }
    }
    private val libraryPeer = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        waiting = false
        if (uri == null) { advance(); return@registerForActivityResult }
        runStep {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            currentPhase = StorageSetupOutcome.Issue.LIBRARY_SYNC
            val result = SharedLibrary.addFilePeer(app, db, uri)
            require(result.errors.isEmpty()) { result.errors.joinToString(" · ") }
        }
    }
    private val positionPeer = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        waiting = false
        if (uri == null) { advance(); return@registerForActivityResult }
        runStep {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            currentPhase = StorageSetupOutcome.Issue.PROGRESS_READ
            ReadingPositionSafSync.addPeer(app, uri)
            ReadingPositionSafSync.refreshForRead(app, db)
        }
    }
    fun restore(state: Bundle?) {
        choosingLocation = state?.getBoolean("wizard_choosing_location") == true
        locatingDrive = state?.getBoolean("wizard_locating_drive") == true
        active = state?.getBoolean("wizard_active") == true
        unified = true
        waiting = state?.getBoolean("wizard_waiting") == true
        several = state?.getBoolean("wizard_several", true) ?: true
        skipBackup = state?.getBoolean("wizard_skip_backup") == true
        seekingPeer = state?.getBoolean("wizard_seeking_peer") == true
        targetPeers = state?.getInt("wizard_target_peers", 1) ?: 1
        folder = state?.getString("wizard_folder")?.let(Uri::parse) ?: SharedLibrary.uri(app)?.takeIf { SharedLibrary.namedFolderMode(app) }
        if (active && !waiting) activity.window.decorView.post {
            if (choosingLocation) showLocationChoice() else advance()
        }
    }
    fun save(state: Bundle) {
        state.putBoolean("wizard_choosing_location", choosingLocation)
        state.putBoolean("wizard_locating_drive", locatingDrive)
        state.putBoolean("wizard_active", active)
        state.putBoolean("wizard_unified", unified)
        state.putBoolean("wizard_waiting", waiting)
        state.putBoolean("wizard_several", several)
        state.putBoolean("wizard_skip_backup", skipBackup)
        state.putBoolean("wizard_seeking_peer", seekingPeer)
        state.putInt("wizard_target_peers", targetPeers)
        state.putString("wizard_folder", folder?.toString())
    }

    fun start(addDevice: Boolean = false) {
        if (busy || waiting || prompt?.isShowing == true) return
        active = true
        unified = true
        several = true
        skipBackup = false
        seekingPeer = false
        choosingLocation = false
        locatingDrive = false
        prefs.edit().putBoolean("dismissed", false).apply()
        val current = SharedLibrary.uri(app)?.takeIf {
            !SharedLibrary.fileMode(app) && StorageGrants.allows(app, it, true)
        }
        folder = current
        if (current != null && SharedLibrary.namedFolderMode(app)) {
            show("Tu biblioteca única", "La carpeta compartida guarda tus libros y datos de lectura. La copia de seguridad es independiente y opcional.",
                "Comprobar" to { runStep { prepareAll(current) } },
                "Cambiar ubicación" to { showLocationChoice() })
        } else showLocationChoice()
    }

    private fun showLocationChoice(detail: String? = null) {
        choosingLocation = true
        locatingDrive = false
        show("Configura tu biblioteca única",
            (detail?.let { "$it\n\n" } ?: "") +
                "Selecciona la misma carpeta compartida en todos tus dispositivos. Debe permitir lectura y escritura. " +
                "Si Google Drive no aparece, pulsa Buscar Drive y elige cualquier archivo de la carpeta deseada para intentar abrirla en el selector de carpetas. " +
                "No se creará ningún ZIP ni se cambiará tu conexión hasta comprobar la carpeta.",
            "Seleccionar carpeta" to {
                unified = true
                folder = null
                launchPicker { pickFolder.launch(null) }
            },
            "Buscar Drive" to {
                unified = true
                folder = null
                launchPicker { locateDrive.launch(arrayOf("*/*")) }
            })
    }

    private suspend fun prepareAll(tree: Uri) {
        unified = true
        currentPhase = StorageSetupOutcome.Issue.LIBRARY_SYNC
        val lib = SharedLibrary.connectNamedFolder(app, db, tree) { message ->
            activity.runOnUiThread {
                if (busy && !activity.isFinishing && !activity.isDestroyed) prompt?.setMessage(message)
            }
        }
        require(lib.errors.isEmpty()) { lib.errors.joinToString(" · ") }
        prefs.edit().putBoolean("several", true).putString("unified_location", tree.toString()).apply()
    }

    private fun showUnifiedResult() {
        show("Biblioteca preparada en este dispositivo",
            "Originales y datos de lectura comprobados. En el otro dispositivo, selecciona esta misma carpeta. " +
                "No es necesario crear archivos de progreso ni copias por dispositivo. La comprobación de acceso desde el otro equipo sigue pendiente.",
            "Terminar" to { pause() }, closeLabel = null)
    }

    private fun chooseMode(value: Boolean) {
        several = value
        prefs.edit().putBoolean("several", value).apply()
        advance()
    }
    private fun pause() {
        choosingLocation = false
        locatingDrive = false
        active = false
        prefs.edit().putBoolean("dismissed", true).apply()
        changed()
    }
    private fun show(title: String, message: String, primary: Pair<String, () -> Unit>,
                     secondary: Pair<String, () -> Unit>? = null, closeLabel: String? = "Continuar más tarde") {
        if (!active || activity.isFinishing || activity.isDestroyed) return
        prompt?.dismiss()
        val builder = AlertDialog.Builder(activity).setTitle(title).setMessage(message)
            .setPositiveButton(primary.first) { _, _ -> primary.second() }
            .setOnCancelListener { pause() }
        if (closeLabel != null) builder.setNegativeButton(closeLabel) { _, _ -> pause() }
        if (secondary != null) builder.setNeutralButton(secondary.first) { _, _ -> secondary.second() }
        prompt = builder.show()
    }
    private fun launchPicker(action: () -> Unit) {
        waiting = true
        try { action() } catch (e: Exception) {
            waiting = false
            show("No se pudo abrir la ubicación", e.message ?: "Prueba otra ubicación.",
                "Volver al asistente" to { if (choosingLocation) showLocationChoice() else advance() })
        }
    }
    private fun runStep(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        prompt?.dismiss()
        prompt = AlertDialog.Builder(activity).setTitle("Preparando tu biblioteca")
            .setMessage("Comprobando permisos y guardando los datos…").setCancelable(false).show()
        activity.lifecycleScope.launch {
            try {
                block()
                busy = false
                prompt?.dismiss()
                changed()
                if (unified) showUnifiedResult() else advance()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                busy = false
                prompt?.dismiss()
                changed()
                if (unified) showUnifiedFailure(e)
                else showIssue(currentIssue(currentPhase), e.message.orEmpty())
            }
        }
    }

    private fun showUnifiedFailure(error: Exception) {
        val diagnosed = error as? NamedFolderSetupFailure
        val access = diagnosed?.stage == NamedFolderSetupFailure.Stage.FOLDER_ACCESS ||
            (diagnosed == null && currentPhase == StorageSetupOutcome.Issue.LIBRARY_ACCESS)
        val message = diagnosed?.userMessage() ?: (
            (if (access) "No se han podido conservar los permisos de la carpeta."
            else "No se ha completado la comprobación de la biblioteca.") +
                "\n\nDetalle: " + (error.message?.take(600)?.takeIf { it.isNotBlank() }
                    ?: error.javaClass.simpleName))
        show(if (access) "No se ha comprobado el acceso a la carpeta" else "No se ha completado la biblioteca",
            message + "\n\nNo borres los libros ni la ubicación anterior.",
            "Reintentar" to { retryCheck() },
            "Cambiar ubicación" to { showLocationChoice() })
    }

    private fun advance() {
        if (unified) {
            val selected = folder
            if (selected != null && StorageGrants.allows(app, selected, true)) runStep { prepareAll(selected) }
            else showLocationChoice()
            return
        }
        if (!active || busy || waiting || activity.isFinishing || activity.isDestroyed) return
        val libraryReady = SharedLibrary.writeAccess(app) == com.javierrb35.bibliotecaia.sync.SharedWriteAccess.READY
        val backup = BackupRoutine.status(app)
        val position = ReadingPositionSafSync.status(app)
        // Verify this installation before asking the reader to configure
        // another device. Missing peers do not block the local backup check.
        val step = StorageSetupPlan.next(StorageSetupPlan.State(
            libraryReady, skipBackup || (backup.uri != null && !backup.recoveryPending),
            position.ownConfigured, several,
            !seekingPeer || !SharedLibrary.fileMode(app) || SharedLibrary.peerCount(app) >= targetPeers,
            !seekingPeer || (ReadingPositionSafSync.fastReady(app) && position.peerCount >= targetPeers)))
        when (step) {
            StorageSetupPlan.Step.LIBRARY -> {
                val newFile = "BibliotecaIA-" + ReadingPositionSafSync.deviceId(app).take(8) + "-" +
                    java.util.UUID.randomUUID().toString().take(8) + ".zip"
                if (SharedLibrary.fileMode(app)) show("Paso 1 · Recuperar acceso",
                    "Ya existe una biblioteca. Selecciona de nuevo tu archivo para autorizar el acceso, o crea uno nuevo si necesitas reparar la conexión. El anterior no se borrará.",
                    "Volver a seleccionar" to { launchPicker { repairLibrary.launch(openWritable()) } },
                    "Crear archivo nuevo" to { launchPicker { libraryFile.launch(newFile) } })
                else show("Paso 1 · Ubicación de tu biblioteca",
                    "Elige una ubicación común a tus dispositivos. Si Drive no permite elegir una carpeta, usa la opción Guardar en Drive. La aplicación prepara el archivo con su nombre.",
                    "Elegir carpeta" to { launchPicker { pickFolder.launch(null) } },
                    "Guardar en Drive" to { launchPicker { libraryFile.launch(newFile) } })
            }
            StorageSetupPlan.Step.BACKUP -> {
                if (backup.recoveryPending) {
                    show("Paso 2 · Copia de seguridad", "Hay una recuperación pendiente. No sustituiremos la copia. Utiliza Recuperar copia anterior en Ajustes.",
                        "Continuar sin cambiarla" to { skipBackup = true; advance() })
                } else if (BackupRoutine.savedDestination(app) != null) {
                    show("Paso 2 · Recuperar acceso a la copia", "Ya existe una copia. Vuelve a autorizar el mismo archivo para conservar la configuración.",
                        "Autorizar copia existente" to { launchPicker { repairBackup.launch(openWritable()) } },
                        "Ahora no" to { skipBackup = true; advance() })
                } else {
                    val tree = folder?.takeIf { StorageGrants.allows(app, it, true) }
                    show("Paso 2 · Protege tus libros", "Guardaremos una copia completa que podrás abrir también con otros lectores.",
                        "Guardar copia" to {
                            if (tree != null) runStep {
                                val uri = withContext(Dispatchers.IO) { StorageGrants.create(app, tree, "application/zip",
                                    "BibliotecaIA-copia-" + ReadingPositionSafSync.deviceId(app).take(8) + ".zip") }
                                BackupRoutine.save(app, db, BuildConfig.VERSION_NAME, true, uri, replaceExisting = false)
                            } else launchPicker { backupFile.launch("BibliotecaIA-copia-" + ReadingPositionSafSync.deviceId(app).take(8) + ".zip") }
                        }, "Ahora no" to { skipBackup = true; advance() })
                }
            }
            StorageSetupPlan.Step.POSITION -> {
                val tree = folder?.takeIf { StorageGrants.allows(app, it, true) }
                if (tree != null) runStep { ReadingPositionSafSync.prepareFolder(app, db, tree) }
                else show("Paso 3 · Continúa donde lo dejaste",
                    "Elige la misma carpeta de Drive. Prepararemos automáticamente el pequeño archivo que guarda tu página; solo confirma la ubicación.",
                    "Guardar progreso" to { launchPicker { positionFile.launch(ReadingPositionSafSync.suggestedName(app)) } },
                    "Autorizar carpeta" to { launchPicker { pickFolder.launch(null) } })
            }
            StorageSetupPlan.Step.LIBRARY_PEER -> show("Paso 4 · Conecta otro dispositivo",
                "Abre este asistente en tu otro móvil o tablet. Cuando haya preparado su biblioteca, selecciona el archivo BibliotecaIA de ese equipo.",
                "Seleccionar otro dispositivo" to { launchPicker { libraryPeer.launch(arrayOf("application/zip", "application/octet-stream")) } },
                "Lo haré más tarde" to { pause() })
            StorageSetupPlan.Step.POSITION_PEER -> {
                val tree = folder ?: ReadingPositionSafSync.folder(app)
                show("Paso 5 · Compartir avances", "En el otro equipo termina el asistente. " +
                    if (tree != null) "Después podemos detectar automáticamente su progreso en la carpeta." else
                    "Selecciona su archivo de progreso (BibliotecaIA-lectura). La aplicación ya lo ha preparado.",
                    (if (tree != null) "Buscar otro dispositivo" to { runStep { ReadingPositionSafSync.prepareFolder(app, db, tree) } }
                    else "Seleccionar progreso del otro equipo" to { launchPicker { positionPeer.launch(arrayOf("*/*")) } }),
                    if (tree != null) "Seleccionar archivo" to { launchPicker { positionPeer.launch(arrayOf("*/*")) } }
                    else "Lo haré más tarde" to { pause() })
            }
            StorageSetupPlan.Step.VERIFY -> verifyExisting()
        }
    }

    private fun currentIssue(fallback: StorageSetupOutcome.Issue): StorageSetupOutcome.Issue {
        val copy = BackupRoutine.status(app)
        return when {
            !SharedLibrary.hasAccess(app) -> StorageSetupOutcome.Issue.LIBRARY_ACCESS
            SharedLibrary.needsRepair(app) -> StorageSetupOutcome.Issue.LIBRARY_OWNER
            fallback == StorageSetupOutcome.Issue.BACKUP_WRITE && copy.recoveryPending -> StorageSetupOutcome.Issue.BACKUP_RECOVERY
            fallback == StorageSetupOutcome.Issue.BACKUP_WRITE && copy.uri == null && BackupRoutine.savedDestination(app) != null -> StorageSetupOutcome.Issue.BACKUP_ACCESS
            fallback == StorageSetupOutcome.Issue.BACKUP_WRITE && copy.missingTitles.isNotEmpty() -> StorageSetupOutcome.Issue.BACKUP_MISSING
            else -> fallback
        }
    }

    private fun verifyExisting() {
        if (busy || !active) return
        busy = true
        prompt?.dismiss()
        prompt = AlertDialog.Builder(activity).setTitle("Comprobando tu biblioteca")
            .setMessage("Estamos verificando el acceso y guardando tus datos.").setCancelable(false).show()
        activity.lifecycleScope.launch {
            try {
                currentPhase = StorageSetupOutcome.Issue.LIBRARY_SYNC
                val lib = SharedLibrary.sync(app, db)
                if (lib.errors.isNotEmpty()) {
                    busy = false
                    showIssue(currentIssue(currentPhase), lib.errors.joinToString(" · "))
                    return@launch
                }
                currentPhase = StorageSetupOutcome.Issue.BACKUP_WRITE
                val copy = BackupRoutine.status(app)
                if (copy.recoveryPending) {
                    busy = false
                    showIssue(StorageSetupOutcome.Issue.BACKUP_RECOVERY)
                    return@launch
                }
                if (copy.uri == null) {
                    busy = false
                    showIssue(StorageSetupOutcome.Issue.BACKUP_ACCESS)
                    return@launch
                }
                // Do not trust an old success flag; actually refresh and verify the copy.
                val backed = BackupRoutine.save(app, db, BuildConfig.VERSION_NAME, true)
                if (backed == BackupRoutine.Result.PARTIAL) {
                    busy = false
                    showIssue(StorageSetupOutcome.Issue.BACKUP_MISSING)
                    return@launch
                }
                currentPhase = StorageSetupOutcome.Issue.PROGRESS_WRITE
                val written = ReadingPositionSafSync.hasOwn(app) && ReadingPositionSafSync.publish(app, db)
                currentPhase = StorageSetupOutcome.Issue.PROGRESS_READ
                val read = written && ReadingPositionSafSync.refresh(app, db)
                val pos = ReadingPositionSafSync.status(app)
                val issue = StorageSetupOutcome.first(StorageSetupOutcome.State(
                    SharedLibrary.hasAccess(app), !SharedLibrary.needsRepair(app), true,
                    false, true, false, true, written,
                    pos.peerCount >= maxOf(1, SharedLibrary.peerCount(app)), read))
                busy = false
                if (issue == StorageSetupOutcome.Issue.NONE) {
                    prefs.edit().remove("target_peers").apply()
                    show("Todo preparado", "Tu biblioteca y tu copia de seguridad están comprobadas. Se han guardado y leído los avances de los dispositivos conectados.",
                        "Terminar" to { pause() }, closeLabel = null)
                } else showIssue(issue, pos.lastError)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                busy = false
                showIssue(currentIssue(currentPhase), e.message.orEmpty())
            } finally {
                changed()
            }
        }
    }

    private fun retryCheck() {
        if (unified) {
            val tree = folder
            if (tree != null) runStep { prepareAll(tree) } else showLocationChoice()
        } else verifyExisting()
    }

    private fun showIssue(issue: StorageSetupOutcome.Issue, details: String = "") {
        // Preserve the exact provider failure for support, but don't put it in the main explanation.
        if (details.isNotBlank()) android.util.Log.w("BibliotecaSetup", issue.name + ": " + details.take(1500))
        val copy = BackupRoutine.status(app)
        when (issue) {
            StorageSetupOutcome.Issue.LIBRARY_ACCESS, StorageSetupOutcome.Issue.LIBRARY_OWNER ->
                show("Necesitamos volver a acceder a tu biblioteca",
                    "No hemos podido abrir la biblioteca guardada. Tus libros del dispositivo siguen aquí. Autoriza otra vez la ubicación o selecciona una nueva.",
                    "Elegir ubicación" to { showLocationChoice() },
                    "Reintentar" to { retryCheck() })
            StorageSetupOutcome.Issue.LIBRARY_SYNC ->
                show("No hemos podido comprobar tu biblioteca",
                    "No hemos podido leer o actualizar todos los libros guardados. Comprueba la conexión a internet y vuelve a intentarlo. Si persiste, elige otra ubicación; no borraremos la anterior.",
                    "Reintentar" to { retryCheck() }, "Cambiar ubicación" to { showLocationChoice() })
            StorageSetupOutcome.Issue.BACKUP_RECOVERY ->
                show("Hay una copia anterior que recuperar",
                    "La última copia no pudo completarse. Conservamos la copia anterior para proteger tus libros. Recupérala antes de volver a guardar.",
                    "Recuperar copia anterior" to { pause(); recoverBackup() })
            StorageSetupOutcome.Issue.BACKUP_ACCESS ->
                show("Falta acceso a tu copia de seguridad",
                    "La biblioteca puede seguir utilizándose, pero todavía no podemos guardar la copia. Vuelve a autorizar su ubicación.",
                    "Autorizar copia" to {
                        if (unified) showLocationChoice()
                        else if (BackupRoutine.savedDestination(app) != null) launchPicker { repairBackup.launch(openWritable()) }
                        else launchPicker { backupFile.launch("BibliotecaIA-copia-" + ReadingPositionSafSync.deviceId(app).take(8) + ".zip") }
                    })
            StorageSetupOutcome.Issue.BACKUP_MISSING ->
                show("Faltan libros en la copia",
                    (if (copy.missingTitles.isEmpty()) "No hemos podido localizar todos los archivos originales."
                    else "No encontramos estos libros:\n" + copy.missingTitles.take(5).joinToString("\n") +
                        if (copy.missingTitles.size > 5) "\nY " + (copy.missingTitles.size - 5) + " más." else "") +
                        "\n\nAbre tu biblioteca: descarga o recupera los libros que quieras conservar. Si ya no los quieres, elimínalos de la biblioteca desde su ficha. Después vuelve a comprobar.",
                    "Ir a mi biblioteca" to { pause(); activity.finish() },
                    "Reintentar" to { retryCheck() })
            StorageSetupOutcome.Issue.BACKUP_WRITE ->
                show("No hemos podido guardar la copia",
                    "Tus libros siguen disponibles. Comprueba que la ubicación tenga espacio y permita guardar archivos. No consideraremos completa la copia hasta verificarla.",
                    "Reintentar" to { retryCheck() }, "Cambiar ubicación" to { showLocationChoice() })
            StorageSetupOutcome.Issue.PROGRESS_WRITE ->
                show("No hemos podido compartir tu página",
                    "Tu avance está guardado en este dispositivo. Falta guardarlo en la ubicación compartida.",
                    "Guardar mi progreso" to {
                        if (unified) retryCheck()
                        else if (!ReadingPositionSafSync.hasOwn(app)) launchPicker {
                            positionFile.launch(ReadingPositionSafSync.suggestedName(app))
                        } else retryCheck()
                    }, "Cambiar ubicación" to { showLocationChoice() })
            StorageSetupOutcome.Issue.OTHER_DEVICE ->
                show("Este dispositivo ya está preparado",
                    "La biblioteca y la copia se han comprobado y tu página se guarda. Todavía no hemos encontrado el progreso del otro equipo. Abre BibliotecaAI allí y completa el mismo asistente.",
                    "Buscar otro dispositivo" to {
                        if (unified) retryCheck()
                        else {
                            seekingPeer = true
                            targetPeers = maxOf(1, SharedLibrary.peerCount(app))
                            advance()
                        }
                    }, closeLabel = "Cerrar")
            StorageSetupOutcome.Issue.PROGRESS_READ ->
                show("No podemos comprobar la página del otro equipo",
                    "Tu avance local se conserva, pero no hemos podido leer todos los avances compartidos. Comprueba internet y vuelve a intentarlo.",
                    "Reintentar" to { retryCheck() }, "Cambiar ubicación" to { showLocationChoice() })
            StorageSetupOutcome.Issue.NONE -> showUnifiedResult()
        }
    }
}
