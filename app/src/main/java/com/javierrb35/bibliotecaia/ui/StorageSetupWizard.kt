package com.javierrb35.bibliotecaia.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.javierrb35.bibliotecaia.BuildConfig
import com.javierrb35.bibliotecaia.backup.BackupRoutine
import com.javierrb35.bibliotecaia.data.AppDatabase
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
    private val changed: () -> Unit
) {
    companion object {
        private const val PREFS = "storage_setup_wizard"
        const val EXTRA_OPEN = "storage_setup_wizard_open"
        fun pending(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val shared = SharedLibrary.configured(context)
            val several = prefs.getBoolean("several", shared && (SharedLibrary.peerCount(context) > 0 || !SharedLibrary.fileMode(context)))
            return !prefs.getBoolean("dismissed", false) && (!shared || !SharedLibrary.hasAccess(context) ||
                BackupRoutine.status(context).uri == null || (several && !ReadingPositionSafSync.fastReady(context)))
        }
    }
    private val app get() = activity.applicationContext
    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var active = false
    private var waiting = false
    private var several = true
    private var busy = false
    private var skipBackup = false
    private var folder: Uri? = null
    private var prompt: AlertDialog? = null

    private val pickFolder = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        waiting = false
        if (uri == null) { advance(); return@registerForActivityResult }
        runStep {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            folder = uri
            if (!SharedLibrary.configured(app)) {
                val result = SharedLibrary.connect(app, db, uri)
                require(result.errors.isEmpty()) { result.errors.joinToString(" · ") }
            }
            if (several) ReadingPositionSafSync.prepareFolder(app, db, uri)
        }
    }
    private val libraryFile = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        waiting = false
        if (uri == null) { advance(); return@registerForActivityResult }
        runStep {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val result = SharedLibrary.connectFile(app, db, uri, newDocument = true)
            require(result.errors.isEmpty()) { result.errors.joinToString(" · ") }
        }
    }
    private val backupFile = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        waiting = false
        if (uri == null) { advance(); return@registerForActivityResult }
        runStep {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            BackupRoutine.save(app, db, BuildConfig.VERSION_NAME, true, uri, replaceExisting = false)
        }
    }
    private val positionFile = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        waiting = false
        if (uri == null) { advance(); return@registerForActivityResult }
        runStep {
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
            val result = SharedLibrary.addFilePeer(app, db, uri)
            require(result.errors.isEmpty()) { result.errors.joinToString(" · ") }
        }
    }
    private val positionPeer = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        waiting = false
        if (uri == null) { advance(); return@registerForActivityResult }
        runStep {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ReadingPositionSafSync.addPeer(app, uri)
            ReadingPositionSafSync.refreshForRead(app, db)
        }
    }
    fun restore(state: Bundle?) {
        active = state?.getBoolean("wizard_active") == true
        waiting = state?.getBoolean("wizard_waiting") == true
        several = state?.getBoolean("wizard_several", true) ?: true
        skipBackup = state?.getBoolean("wizard_skip_backup") == true
        folder = state?.getString("wizard_folder")?.let(Uri::parse) ?: ReadingPositionSafSync.folder(app)
        if (active && !waiting) activity.window.decorView.post { advance() }
    }
    fun save(state: Bundle) {
        state.putBoolean("wizard_active", active)
        state.putBoolean("wizard_waiting", waiting)
        state.putBoolean("wizard_several", several)
        state.putBoolean("wizard_skip_backup", skipBackup)
        state.putString("wizard_folder", folder?.toString())
    }

    fun start() {
        if (busy || waiting || prompt?.isShowing == true) return
        folder = ReadingPositionSafSync.folder(app) ?: SharedLibrary.uri(app)
            ?.takeIf { !SharedLibrary.fileMode(app) && StorageGrants.allows(app, it, true) }
        active = true
        skipBackup = false
        prefs.edit().putBoolean("dismissed", false).apply()
        val existingPeers = SharedLibrary.peerCount(app) > 0 || ReadingPositionSafSync.status(app).peerCount > 0
        if (existingPeers || prefs.contains("several")) {
            several = existingPeers || prefs.getBoolean("several", true)
            advance()
        } else {
            show("Configura tu biblioteca", "¿Dónde vas a leer? La aplicación preparará los archivos y guardará tus avances automáticamente.",
                "En varios dispositivos" to { chooseMode(true) },
                "Solo en este dispositivo" to { chooseMode(false) })
        }
    }
    private fun chooseMode(value: Boolean) {
        several = value
        prefs.edit().putBoolean("several", value).apply()
        advance()
    }
    private fun pause() {
        active = false
        prefs.edit().putBoolean("dismissed", true).apply()
        changed()
    }
    private fun show(title: String, message: String, primary: Pair<String, () -> Unit>,
                     secondary: Pair<String, () -> Unit>? = null, extra: Pair<String, () -> Unit>? = null) {
        if (!active || activity.isFinishing || activity.isDestroyed) return
        prompt?.dismiss()
        val builder = AlertDialog.Builder(activity).setTitle(title).setMessage(message)
            .setPositiveButton(primary.first) { _, _ -> primary.second() }
            .setNegativeButton("Continuar más tarde") { _, _ -> pause() }
            .setOnCancelListener { pause() }
        if (secondary != null) builder.setNeutralButton(secondary.first) { _, _ -> secondary.second() }
        // A fourth action is never necessary; recovery remains in the Settings panel.
        prompt = builder.show()
    }
    private fun launchPicker(action: () -> Unit) {
        waiting = true
        try { action() } catch (e: Exception) {
            waiting = false
            show("No se pudo abrir la ubicación", e.message ?: "Prueba otra ubicación.",
                "Volver al asistente" to { advance() })
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
                advance()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                busy = false
                prompt?.dismiss()
                changed()
                show("No se pudo completar este paso", (e.message ?: "No se pudo acceder a la ubicación.") +
                    "\n\nLos datos existentes se conservan.", "Reintentar" to { advance() })
            }
        }
    }
    private fun advance() {
        if (!active || busy || waiting || activity.isFinishing || activity.isDestroyed) return
        val libraryReady = SharedLibrary.configured(app) && SharedLibrary.hasAccess(app) && !SharedLibrary.needsRepair(app)
        val backup = BackupRoutine.status(app)
        val position = ReadingPositionSafSync.status(app)
        val step = StorageSetupPlan.next(StorageSetupPlan.State(
            libraryReady, skipBackup || (backup.uri != null && !backup.recoveryPending),
            position.ownConfigured, several,
            !SharedLibrary.fileMode(app) || SharedLibrary.peerCount(app) > 0,
            ReadingPositionSafSync.fastReady(app)))
        when (step) {
            StorageSetupPlan.Step.LIBRARY -> show("Paso 1 · Ubicación de tu biblioteca",
                "Elige una ubicación común a tus dispositivos. Si Drive no permite elegir una carpeta, usa la opción Guardar en Drive. La aplicación prepara el archivo con su nombre.",
                "Elegir carpeta" to { launchPicker { pickFolder.launch(null) } },
                "Guardar en Drive" to { launchPicker { libraryFile.launch("BibliotecaIA-" + ReadingPositionSafSync.deviceId(app).take(8) + ".zip") } })
            StorageSetupPlan.Step.BACKUP -> {
                if (backup.recoveryPending || BackupRoutine.savedDestination(app) != null) {
                    show("Paso 2 · Copia de seguridad", "Ya existe una ubicación de copia o una recuperación pendiente. No la sustituiremos. Puedes repararla en Ajustes sin perder datos.",
                        "Continuar sin cambiarla" to { skipBackup = true; advance() })
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
                    "Selecciona su archivo de progreso (BibliotecaIA-lectura). No tienes que crearlo ni editarlo a mano.",
                    (if (tree != null) "Buscar otro dispositivo" to { runStep { ReadingPositionSafSync.prepareFolder(app, db, tree) } }
                    else "Seleccionar progreso del otro equipo" to { launchPicker { positionPeer.launch(arrayOf("*/*")) } }),
                    if (tree != null) "Seleccionar archivo" to { launchPicker { positionPeer.launch(arrayOf("*/*")) } }
                    else "Lo haré más tarde" to { pause() })
            }
            StorageSetupPlan.Step.VERIFY -> {
                busy = true
                prompt = AlertDialog.Builder(activity).setTitle("Comprobación final").setMessage("Verificando la configuración…").setCancelable(false).show()
                activity.lifecycleScope.launch {
                    try {
                        val lib = SharedLibrary.sync(app, db)
                        val positions = !several || (ReadingPositionSafSync.fastReady(app) &&
                            ReadingPositionSafSync.refreshForRead(app, db) && ReadingPositionSafSync.publish(app, db))
                        val copy = BackupRoutine.status(app)
                        val complete = lib.errors.isEmpty() && positions && copy.uri != null &&
                            !copy.recoveryPending && copy.lastSuccess > 0 && copy.missingTitles.isEmpty()
                        prompt?.dismiss()
                        busy = false
                        show(if (complete) "Configuración completada" else "Configuración con tareas pendientes",
                            "Biblioteca: " + (if (lib.errors.isEmpty()) "comprobada" else "requiere revisión") +
                            "\nCopia de seguridad: " + (if (copy.uri != null && copy.lastSuccess > 0 && !copy.recoveryPending && copy.missingTitles.isEmpty()) "guardada" else "pendiente o incompleta") +
                            "\nAvances: " + (if (!several) "lectura local" else if (positions) "sincronización comprobada" else "pendiente") +
                            if (several) "\n\nRepite el asistente en el otro dispositivo. No necesitas guardar la página manualmente." else "",
                            "Terminar" to { pause() })
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        prompt?.dismiss()
                        busy = false
                        show("Comprobación pendiente", e.message ?: "Vuelve a intentarlo cuando haya conexión.", "Reintentar" to { advance() })
                    } finally { changed() }
                }
            }
        }
    }
}
