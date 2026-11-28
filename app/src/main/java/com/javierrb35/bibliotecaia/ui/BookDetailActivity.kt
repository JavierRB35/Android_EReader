package com.javierrb35.bibliotecaia.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.javierrb35.bibliotecaia.R
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookSynopsisEntity
import com.javierrb35.bibliotecaia.data.ReadingStatus
import com.javierrb35.bibliotecaia.backup.BackupRoutine
import com.javierrb35.bibliotecaia.BuildConfig
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * One-screen book details: reading actions and taxonomy assignments are
 * available without visiting another screen. Large fonts may still scroll
 * rather than losing controls outside the viewport.
 */
class BookDetailActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_BOOK_ID = "book_id"
        private const val COLLAPSED_SYNOPSIS_LINES = 6
        const val RESULT_LOCAL_REMOVED = android.app.Activity.RESULT_FIRST_USER + 21

        fun start(context: Context, bookId: String) {
            context.startActivity(
                Intent(context, BookDetailActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)
            )
        }
    }

    private val db by lazy { AppDatabase.get(this) }
    private val organizer by lazy { BookOrganizationEditor(db) }
    // The Context must be attached before accessing filesDir (Issue #16).
    private val linker by lazy {
        com.javierrb35.bibliotecaia.backup.OriginalLinker(filesDir)
    }
    private val linkLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) linkOriginal(uri)
    }
    private var book: BookEntity? = null
    private var savedSynopsis: BookSynopsisEntity? = null
    private var synopsisExpanded = false
    private var classificationWork: java.util.UUID? = null
    private var classificationEnqueuing = false
    private var pendingSynopsisExport: String? = null
    private val exportSynopsisLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val payload = pendingSynopsisExport
        pendingSynopsisExport = null
        if (uri != null && payload != null) {
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val stream = contentResolver.openOutputStream(uri, "wt")
                            ?: error("No se puede escribir en el destino elegido.")
                        stream.bufferedWriter(Charsets.UTF_8).use { it.write(payload) }
                    }
                    Toast.makeText(this@BookDetailActivity,
                        R.string.detail_synopsis_export_success, Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                    Toast.makeText(this@BookDetailActivity,
                        R.string.detail_synopsis_export_error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book_detail)
        pendingSynopsisExport = savedInstanceState?.getString("synopsis_export_pending")

        val bookId = intent.getStringExtra(EXTRA_BOOK_ID)
        if (bookId == null) {
            finish()
            return
        }

        findViewById<View>(R.id.btnReadBook).setOnClickListener { ReaderRouter.open(this, bookId) }
        findViewById<View>(R.id.btnCycleStatus).setOnClickListener { promptReadingStatus() }
        findViewById<View>(R.id.btnAi).setOnClickListener { openAi() }
        findViewById<View>(R.id.btnSynopsisBody).setOnClickListener { toggleSynopsisExpanded() }
        findViewById<View>(R.id.btnSynopsisExpand).setOnClickListener { toggleSynopsisExpanded() }
        findViewById<View>(R.id.btnSynopsisObtain).setOnClickListener { openSynopsisAction() }
        findViewById<View>(R.id.btnSynopsisEdit).setOnClickListener { editSynopsis() }
        findViewById<View>(R.id.btnSynopsisDelete).setOnClickListener { confirmDeleteSynopsis() }
        findViewById<View>(R.id.btnSynopsisMore).setOnClickListener { anchor ->
            androidx.appcompat.widget.PopupMenu(this, anchor).apply {
                menu.add(R.string.detail_synopsis_copy)
                menu.add(R.string.detail_synopsis_export)
                setOnMenuItemClickListener { item ->
                    val saved = savedSynopsis?.takeIf { it.bookId == bookId && it.text.isNotBlank() }
                    if (item.title == getString(R.string.detail_synopsis_copy) && saved != null) {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("Argumento · " + (book?.title ?: ""), saved.text))
                    } else if (item.title == getString(R.string.detail_synopsis_export) && saved != null) {
                        exportSynopsis(saved)
                    }
                    true
                }
            }.show()
        }
        findViewById<View>(R.id.btnFavorite).setOnClickListener { toggleFavorite() }
        findViewById<View>(R.id.btnLink).setOnClickListener { linkLauncher.launch(arrayOf("*/*")) }
        findViewById<View>(R.id.btnRemoveLocalCopy).setOnClickListener { confirmRemoveLocalCopy() }
        findViewById<View>(R.id.btnDeleteEverywhere).setOnClickListener { anchor ->
            androidx.appcompat.widget.PopupMenu(this, anchor).apply {
                menu.add("Eliminar de la biblioteca")
                setOnMenuItemClickListener {
                    confirmDeleteEverywhere()
                    true
                }
            }.show()
        }
        findViewById<View>(R.id.btnDownloadCentral).setOnClickListener { downloadCentral() }
        findViewById<View>(R.id.btnEditMetadata).setOnClickListener { promptEditMetadata() }
        findViewById<View>(R.id.btnClassifyBook).setOnClickListener { classifyBook() }
        savedInstanceState?.getString("classification_work")?.let { raw ->
            runCatching { java.util.UUID.fromString(raw) }.getOrNull()?.let { observeClassification(it) }
        }
        findViewById<View>(R.id.btnEditCategories).setOnClickListener {
            promptAssignment(BookOrganizationEditor.Kind.CATEGORY)
        }
        findViewById<View>(R.id.btnEditTags).setOnClickListener {
            promptAssignment(BookOrganizationEditor.Kind.TAG)
        }
        findViewById<View>(R.id.btnEditCollections).setOnClickListener {
            promptAssignment(BookOrganizationEditor.Kind.COLLECTION)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingSynopsisExport?.let { outState.putString("synopsis_export_pending", it) }
        classificationWork?.let { outState.putString("classification_work", it.toString()) }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        intent.getStringExtra(EXTRA_BOOK_ID)?.let { id ->
            load(id)
            if (classificationWork == null && !classificationEnqueuing) {
                lifecycleScope.launch {
                    val active = withContext(Dispatchers.IO) {
                        runCatching {
                            androidx.work.WorkManager.getInstance(applicationContext)
                                .getWorkInfosByTag(com.javierrb35.bibliotecaia.catalog.AutoCatalogQueue.explicitTag(id))
                                .get().firstOrNull { !it.state.isFinished }
                        }.getOrNull()
                    }
                    if (active != null && classificationWork == null && !classificationEnqueuing) {
                        observeClassification(active.id)
                    }
                }
            }
        }
    }

    /** The same persistent catalogue engine as bulk classification, limited to this book. */
    private fun classifyBook() {
        val current = book ?: return
        if (classificationEnqueuing || classificationWork != null) return
        val configured = com.javierrb35.bibliotecaia.ai.AiKeyStore.providers().any {
            com.javierrb35.bibliotecaia.ai.AiKeyStore.getKey(this, it) != null
        }
        if (!configured) {
            Toast.makeText(this, R.string.detail_classify_no_key, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, com.javierrb35.bibliotecaia.ai.AiSetupActivity::class.java))
            return
        }
        classificationEnqueuing = true
        renderClassificationButton()
        lifecycleScope.launch {
            try {
                val work = com.javierrb35.bibliotecaia.catalog.AutoCatalogQueue.enqueue(
                    applicationContext, all = false, ids = listOf(current.id), explicit = true)
                observeClassification(work)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(this@BookDetailActivity, R.string.detail_classify_failed, Toast.LENGTH_LONG).show()
            } finally {
                classificationEnqueuing = false
                renderClassificationButton()
            }
        }
    }

    private fun renderClassificationButton() {
        findViewById<TextView>(R.id.btnClassifyBook).apply {
            val busy = classificationEnqueuing || classificationWork != null
            isEnabled = !busy
            setText(if (busy) R.string.detail_classify_working else R.string.detail_classify_ai)
        }
    }

    private fun observeClassification(work: java.util.UUID) {
        classificationWork = work
        renderClassificationButton()
        val live = androidx.work.WorkManager.getInstance(applicationContext).getWorkInfoByIdLiveData(work)
        live.observe(this) { info ->
            if (info == null || !info.state.isFinished || classificationWork != work) return@observe
            live.removeObservers(this)
            classificationWork = null
            renderClassificationButton()
            val message = when (info.outputData.getString("classification_result")) {
                com.javierrb35.bibliotecaia.data.BookClassificationEntity.STATUS_CLASSIFIED -> R.string.detail_classify_success
                com.javierrb35.bibliotecaia.data.BookClassificationEntity.STATUS_REVIEW -> R.string.detail_classification_review
                com.javierrb35.bibliotecaia.data.BookClassificationEntity.STATUS_PENDING -> R.string.detail_classify_insufficient
                else -> R.string.detail_classify_failed
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            intent.getStringExtra(EXTRA_BOOK_ID)?.let { load(it) }
        }
    }

    private fun openAi() {
        val current = book ?: return
        val keyStored = com.javierrb35.bibliotecaia.ai.AiKeyStore.providers()
            .any { com.javierrb35.bibliotecaia.ai.AiKeyStore.getKey(this, it) != null }
        if (keyStored) com.javierrb35.bibliotecaia.ai.AiChatActivity.start(this, current.id)
        else startActivity(Intent(this, com.javierrb35.bibliotecaia.ai.AiSetupActivity::class.java))
    }

    /**
     * El argumento ya está visible en la ficha (#142): esta acción solo obtiene
     * uno cuando falta. Con copia local se genera con IA desde fragmentos; sin
     * copia se encola la catalogación automática (recupera la descripción de
     * los metadatos del original sin instalar el libro); en PDF, edición manual.
     */
    private fun openSynopsisAction() {
        val current = book ?: return
        val existing = savedSynopsis?.takeIf {
            it.bookId == current.id && it.text.isNotBlank()
        }
        if (existing != null) return
        if (current.format.name == "PDF") {
            editSynopsis()
        } else if (current.localPath.isNotBlank() && java.io.File(current.localPath).isFile) {
            com.javierrb35.bibliotecaia.ai.AiChatActivity.startSynopsis(this, current.id)
        } else {
            lifecycleScope.launch {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.javierrb35.bibliotecaia.catalog.AutoCatalogQueue.enqueue(
                            applicationContext, all = false, ids = listOf(current.id))
                    }
                    Toast.makeText(this@BookDetailActivity,
                        R.string.catalog_auto_queued_single, Toast.LENGTH_LONG).show()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Toast.makeText(this@BookDetailActivity,
                        R.string.catalog_auto_enqueue_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Argumento siempre visible bajo su encabezado; ampliable, editable y borrable. */
    private fun renderSynopsisSection(bookId: String) {
        val saved = savedSynopsis?.takeIf { it.bookId == bookId && it.text.isNotBlank() }
        val body = findViewById<TextView>(R.id.btnSynopsisBody)
        val expand = findViewById<TextView>(R.id.btnSynopsisExpand)
        val obtain = findViewById<View>(R.id.btnSynopsisObtain)
        val edit = findViewById<View>(R.id.btnSynopsisEdit)
        val delete = findViewById<View>(R.id.btnSynopsisDelete)
        val more = findViewById<View>(R.id.btnSynopsisMore)
        synopsisExpanded = if (saved == null) false else synopsisExpanded
        if (saved == null) {
            // Sin argumento: una fila compacta con el encabezado y la acción de
            // obtención; la ficha sigue cabiendo sin desplazamiento (#142).
            body.visibility = View.GONE
            obtain.visibility = View.VISIBLE
            edit.visibility = View.GONE
            delete.visibility = View.GONE
            more.visibility = View.GONE
            expand.visibility = View.GONE
        } else {
            body.visibility = View.VISIBLE
            body.text = saved.text
            body.alpha = 1f
            obtain.visibility = View.GONE
            edit.visibility = View.VISIBLE
            delete.visibility = View.VISIBLE
            more.visibility = View.VISIBLE
            body.maxLines = if (synopsisExpanded) Int.MAX_VALUE else COLLAPSED_SYNOPSIS_LINES
            expand.visibility = if (synopsisExpanded) View.VISIBLE else View.GONE
            expand.setText(if (synopsisExpanded) R.string.detail_synopsis_collapse
                else R.string.detail_synopsis_expand)
            // El desplegable solo aparece si el texto realmente no cabe.
            body.post {
                if (saved.text.isNotBlank() && body.lineCount > COLLAPSED_SYNOPSIS_LINES &&
                    !synopsisExpanded) expand.visibility = View.VISIBLE
            }
        }
    }

    private fun toggleSynopsisExpanded() {
        if (savedSynopsis?.text.isNullOrBlank()) return
        synopsisExpanded = !synopsisExpanded
        book?.let { renderSynopsisSection(it.id) }
    }

    /** Estado de la catalogación automática junto al argumento (#142). */
    private fun renderSynopsisStatus(bookId: String) {
        lifecycleScope.launch {
            val row = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                db.classificationDao().byBook(bookId)
            }
            val label = when (row?.status) {
                com.javierrb35.bibliotecaia.data.BookClassificationEntity.STATUS_REVIEW ->
                    getString(R.string.detail_classification_review)
                com.javierrb35.bibliotecaia.data.BookClassificationEntity.STATUS_PENDING ->
                    getString(R.string.detail_classification_pending)
                else -> null
            }
            findViewById<TextView>(R.id.txtSynopsisStatus).text = label.orEmpty()
            findViewById<TextView>(R.id.txtSynopsisStatus).visibility =
                if (label == null) View.GONE else View.VISIBLE
        }
    }

    private fun confirmDeleteSynopsis() {
        val current = book ?: return
        val saved = savedSynopsis ?: return
        if (saved.bookId != current.id || saved.text.isBlank()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.detail_synopsis_delete_title)
            .setMessage(R.string.detail_synopsis_delete_message)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.detail_synopsis_delete) { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            db.synopsisDao().deleteByBook(current.id)
                        }
                        savedSynopsis = null
                        renderSynopsisSection(current.id)
                        Toast.makeText(this@BookDetailActivity,
                            R.string.detail_synopsis_deleted, Toast.LENGTH_SHORT).show()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        Toast.makeText(this@BookDetailActivity,
                            R.string.detail_synopsis_delete_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    /** Exportación SAF del argumento, idéntica a la del lector de argumentos. */
    private fun exportSynopsis(saved: BookSynopsisEntity) {
        val current = book ?: return
        pendingSynopsisExport = "Argumento de: " + current.title + "\n" +
            "Autor: " + (current.authors ?: "No indicado") + "\n\n" +
            saved.text + "\n\n" + getString(
                if (saved.provider == "manual") R.string.detail_synopsis_manual_source
                else R.string.detail_synopsis_ai_source
            )
        val safeTitle = current.title
            .replace(Regex("""[^\p{L}\p{N}_\- ]"""), "")
            .trim().take(60).ifBlank { "Libro" }
        exportSynopsisLauncher.launch("Argumento - " + safeTitle + ".txt")
    }

    /**
     * Write or edit the same Room synopsis used by AI, export and backup.
     * A manual edit changes provenance and cannot be claimed spoiler-safe.
     */
    private fun editSynopsis(existing: BookSynopsisEntity? = savedSynopsis) {
        val current = book ?: return
        val old = existing?.takeIf { it.bookId == current.id && it.text.isNotBlank() }
        val editor = EditText(this).apply {
            id = View.generateViewId()
            hint = getString(R.string.detail_synopsis_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 6
            maxLines = 12
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setTextColor(getColor(R.color.bia_text))
            setHintTextColor(getColor(R.color.bia_text_secondary))
            filters = arrayOf(InputFilter.LengthFilter(100_000))
            setText(old?.text.orEmpty())
            setSelection(text.length)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 8.dp, 16.dp, 0)
            addView(editor, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (old == null) R.string.detail_synopsis_add else R.string.detail_synopsis_edit)
            .setView(layout)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.detail_synopsis_save, null)
            .create()
        dialog.setOnShowListener {
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                val value = editor.text.toString().trim()
                if (value.isEmpty()) {
                    editor.error = getString(R.string.detail_synopsis_required)
                    return@setOnClickListener
                }
                save.isEnabled = false
                lifecycleScope.launch {
                    try {
                        val changed = BookSynopsisEntity(
                            bookId = current.id,
                            text = value,
                            provider = "manual",
                            generatedAt = System.currentTimeMillis(),
                            spoilerSafe = false
                        )
                        withContext(Dispatchers.IO) {
                            requireNotNull(db.bookDao().findById(current.id))
                            db.synopsisDao().save(changed)
                        }
                        savedSynopsis = changed
                        renderSynopsisSection(current.id)
                        dialog.dismiss()
                        Toast.makeText(this@BookDetailActivity,
                            R.string.detail_synopsis_saved, Toast.LENGTH_SHORT).show()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        save.isEnabled = true
                        Toast.makeText(this@BookDetailActivity,
                            R.string.detail_synopsis_save_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    /** Revinculación by hash, never by filename. Preserve the user's metadata. */
    private fun linkOriginal(uri: android.net.Uri) {
        val current = book ?: return
        lifecycleScope.launch {
            val temp = java.io.File.createTempFile("link_", ".tmp", cacheDir)
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Sin lectura")
                val linked: BookEntity? =
                    withContext(Dispatchers.IO) { linker.link(temp, listOf(current)) }
                if (linked == null) {
                    Toast.makeText(this@BookDetailActivity,
                        R.string.detail_link_nomatch, Toast.LENGTH_LONG).show()
                } else {
                    db.bookDao().update(linked)
                    Toast.makeText(this@BookDetailActivity,
                        R.string.detail_link_ok, Toast.LENGTH_SHORT).show()
                    load(current.id)
                }
            } catch (e: Exception) {
                Toast.makeText(this@BookDetailActivity,
                    getString(R.string.detail_link_error, e.message ?: ""), Toast.LENGTH_LONG).show()
            } finally {
                temp.delete()
            }
        }
    }

    /**
     * Only the private, imported copy is deleted. The BookEntity, its annotations,
     * reading positions, organization and any document outside the app survive.
     */
    private fun confirmRemoveLocalCopy() {
        val current = book ?: return
        if (current.localPath.isBlank() || !java.io.File(current.localPath).isFile) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.detail_remove_local_title)
            .setMessage(getString(R.string.detail_remove_local_confirm, current.title))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.detail_remove_local_button) { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            BackupRoutine.evictBook(applicationContext, db, BuildConfig.VERSION_NAME, current.id)
                        }
                        Toast.makeText(this@BookDetailActivity,
                            R.string.detail_remove_local_done, Toast.LENGTH_LONG).show()
                        setResult(RESULT_LOCAL_REMOVED)
                        finish()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        load(current.id)
                        Toast.makeText(this@BookDetailActivity,
                            getString(R.string.detail_remove_local_failed) + " " + (e.message ?: ""), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun confirmDeleteEverywhere() {
        val current = book ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Eliminar de la biblioteca")
            .setMessage("¿Eliminar definitivamente «" + current.title + "» de la biblioteca y de los dispositivos vinculados al sincronizar? Se eliminarán el original de Drive (si está en la biblioteca compartida), las notas, marcadores y posiciones. Las copias históricas externas no se modificarán.")
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton("Eliminar definitivamente") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val app = applicationContext
                        if (com.javierrb35.bibliotecaia.sync.SharedLibrary.namedFolderMode(app)) {
                            val uri = requireNotNull(com.javierrb35.bibliotecaia.sync.SharedLibrary.uri(app))
                            LibraryBatchQueue.enqueue(app, LibraryBatchAction.DELETE, uri, listOf(current))
                            Toast.makeText(this@BookDetailActivity, "Eliminación en cola. Consulta su progreso en la biblioteca.", Toast.LENGTH_LONG).show()
                            finish()
                        } else {
                            require(!com.javierrb35.bibliotecaia.sync.SharedLibrary.configured(app)) {
                                "La eliminación definitiva compartida requiere la carpeta de biblioteca única."
                            }
                            com.javierrb35.bibliotecaia.sync.SharedLibrary.deleteEverywhere(app, db, current.id)
                            BackupRoutine.refreshOnLibraryOpen(app, BuildConfig.VERSION_NAME)
                            setResult(RESULT_LOCAL_REMOVED)
                            finish()
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) {
                        Toast.makeText(this@BookDetailActivity, e.message ?: "No se pudo eliminar.", Toast.LENGTH_LONG).show()
                    }
                }
            }.show()
    }

    private fun downloadCentral() {
        val current = book ?: return
        val button = findViewById<TextView>(R.id.btnDownloadCentral)
        if (!button.isEnabled) return
        button.isEnabled = false
        val previous = button.text
        button.text = "Descargando…"
        var job: kotlinx.coroutines.Job? = null
        val progress = AlertDialog.Builder(this)
            .setTitle("Descargando libro")
            .setMessage("Conectando con la biblioteca y verificando el original…")
            .setNegativeButton("Cancelar") { _, _ ->
                button.text = "Cancelando…"
                job?.cancel()
            }
            .setCancelable(false).create()
        progress.show()
        job = lifecycleScope.launch {
            try {
                BackupRoutine.downloadBook(applicationContext, db, BuildConfig.VERSION_NAME, current.contentHash)
                val downloaded = requireNotNull(db.bookDao().findById(current.id))
                check(com.javierrb35.bibliotecaia.backup.CentralCatalogue.local(downloaded)) {
                    "No se ha confirmado la copia local. Puedes volver a intentarlo."
                }
                progress.dismiss()
                load(current.id)
                AlertDialog.Builder(this@BookDetailActivity)
                    .setTitle("Libro descargado")
                    .setMessage("El original está disponible en este dispositivo, también sin conexión.")
                    .setPositiveButton(android.R.string.ok, null).show()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                progress.dismiss()
                findViewById<View>(R.id.btnLink).visibility = View.VISIBLE
                AlertDialog.Builder(this@BookDetailActivity)
                    .setTitle("No se pudo descargar")
                    .setMessage(e.message ?: "Comprueba la conexión y vuelve a intentarlo.")
                    .setPositiveButton(android.R.string.ok, null).show()
            } finally {
                progress.dismiss()
                button.text = previous
                button.isEnabled = true
            }
        }
    }

    private fun load(bookId: String) {
        lifecycleScope.launch {
            val loaded = db.bookDao().findById(bookId)
            if (loaded == null) {
                Toast.makeText(this@BookDetailActivity, R.string.book_not_found, Toast.LENGTH_SHORT).show()
                finish()
            } else {
                book = loaded
                savedSynopsis = db.synopsisDao().byBook(loaded.id)
                show(loaded)
                renderSynopsisSection(loaded.id)
                renderSynopsisStatus(loaded.id)
                showAssignments(loaded.id)
            }
        }
    }

    private fun show(b: BookEntity) {
        findViewById<TextView>(R.id.txtDetailTitle).text = b.title
        findViewById<TextView>(R.id.txtDetailAuthors).text =
            b.authors ?: getString(R.string.detail_no_authors)
        findViewById<TextView>(R.id.txtDetailFormat).text = if (b.format.name == "PDF") "PDF · histórico, sin lector" else b.format.name

        val size = Formatter.formatShortFileSize(this, b.sizeBytes)
        findViewById<TextView>(R.id.txtDetailSize).apply {
            text = size
            contentDescription = getString(R.string.detail_size_content, size)
        }
        val added = DateFormat.getDateFormat(this).format(Date(b.addedAt))
        findViewById<TextView>(R.id.txtDetailAdded).apply {
            text = added
            contentDescription = getString(R.string.detail_added_content, added)
        }
        val status = statusLabel(b.readingStatus)
        findViewById<TextView>(R.id.txtDetailStatus).text = status
        findViewById<View>(R.id.btnCycleStatus).contentDescription =
            getString(R.string.detail_status_content, status)

        val fileAvailable = b.localPath.isNotBlank() && java.io.File(b.localPath).isFile
        val centralHashes = BackupRoutine.browseHashes(applicationContext)
        val inCentral = centralHashes?.contains(b.contentHash) == true
        val centralUnknown = centralHashes == null && BackupRoutine.centralUri(applicationContext) != null
        findViewById<TextView>(R.id.txtDetailFile).apply {
            text = if (fileAvailable)
                getString(R.string.detail_file_compact, b.fileName)
            else getString(if (inCentral) R.string.central_not_downloaded else R.string.central_original_missing)
            contentDescription = if (fileAvailable)
                getString(R.string.detail_file_compact, b.fileName)
            else text
        }
        findViewById<View>(R.id.btnDownloadCentral).visibility =
            if (b.format.name != "PDF" && !fileAvailable && (inCentral || centralUnknown)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnLink).visibility =
            if (b.format.name != "PDF" && !fileAvailable && !inCentral) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnRemoveLocalCopy).visibility =
            if (fileAvailable) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnReadBook).isEnabled = fileAvailable && b.format.name != "PDF"
        findViewById<View>(R.id.btnAi).isEnabled = b.format.name != "PDF"
        findViewById<TextView>(R.id.btnFavorite).apply {
            setText(if (b.favorite) R.string.detail_favorite_selected else R.string.detail_favorite_short)
            contentDescription = getString(if (b.favorite) R.string.btn_unfavorite else R.string.btn_favorite)
        }
    }

    private suspend fun showAssignments(bookId: String) {
        val refs = db.refsDao()
        renderAssignment(R.id.txtDetailCategories, R.id.btnEditCategories,
            getString(R.string.detail_categories), refs.categoryNamesForBook(bookId))
        renderAssignment(R.id.txtDetailTags, R.id.btnEditTags,
            getString(R.string.detail_tags), refs.tagNamesForBook(bookId))
        renderAssignment(R.id.txtDetailCollections, R.id.btnEditCollections,
            getString(R.string.detail_collections), refs.collectionNamesForBook(bookId))
    }

    private fun renderAssignment(
        textId: Int, rowId: Int, title: String, values: List<String>
    ) {
        val selected = values.joinToString(", ")
        findViewById<TextView>(textId).text =
            selected.ifEmpty { getString(R.string.detail_assignment_empty) }
        findViewById<View>(rowId).contentDescription =
            getString(R.string.detail_assignment_content, title,
                selected.ifEmpty { getString(R.string.detail_none) })
    }

    private fun promptReadingStatus() {
        val current = book ?: return
        val options = ReadingStatus.values()
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_cycle_status)
            .setSingleChoiceItems(options.map(::statusLabel).toTypedArray(),
                options.indexOf(current.readingStatus)) { dialog, which ->
                lifecycleScope.launch {
                    try {
                        db.bookDao().update(current.copy(readingStatus = options[which]))
                        load(current.id)
                    } catch (_: Exception) {
                        Toast.makeText(this@BookDetailActivity,
                            R.string.detail_status_update_failed, Toast.LENGTH_LONG).show()
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun toggleFavorite() {
        val current = book ?: return
        lifecycleScope.launch {
            db.bookDao().update(current.copy(favorite = !current.favorite))
            load(current.id)
        }
    }

    private fun promptEditMetadata() {
        val current = book ?: return
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 12.dp, 20.dp, 0)
        }
        val titleInput = EditText(this).apply {
            setText(current.title); hint = getString(R.string.detail_title_hint)
        }
        val authorsInput = EditText(this).apply {
            setText(current.authors ?: ""); hint = getString(R.string.detail_authors_hint)
        }
        container.addView(titleInput)
        container.addView(authorsInput)
        AlertDialog.Builder(this)
            .setTitle(R.string.detail_edit_title)
            .setView(container)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val newTitle = titleInput.text.toString().trim()
                if (newTitle.isEmpty()) return@setPositiveButton
                val newAuthors = authorsInput.text.toString().trim().ifEmpty { null }
                lifecycleScope.launch {
                    db.bookDao().update(current.copy(title = newTitle, authors = newAuthors))
                    load(current.id)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * Each organization row opens a compact in-place editor. It can select
     * existing items, remove links, and create-and-assign a new item in one save.
     * No detour to OrganizeActivity and no destructive delete of library items.
     */
    private fun promptAssignment(kind: BookOrganizationEditor.Kind) {
        val current = book ?: return
        lifecycleScope.launch {
            try {
                showAssignmentDialog(current.id, kind, organizer.snapshot(current.id, kind))
            } catch (_: Exception) {
                Toast.makeText(this@BookDetailActivity,
                    R.string.detail_assignment_load_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showAssignmentDialog(
        bookId: String, kind: BookOrganizationEditor.Kind,
        snapshot: BookOrganizationEditor.Snapshot
    ) {
        val title = when (kind) {
            BookOrganizationEditor.Kind.CATEGORY -> R.string.detail_assign_categories
            BookOrganizationEditor.Kind.TAG -> R.string.detail_assign_tags
            BookOrganizationEditor.Kind.COLLECTION -> R.string.detail_assign_collections
        }
        val newItemHint = when (kind) {
            BookOrganizationEditor.Kind.CATEGORY -> R.string.detail_assignment_new_category
            BookOrganizationEditor.Kind.TAG -> R.string.detail_assignment_new_tag
            BookOrganizationEditor.Kind.COLLECTION -> R.string.detail_assignment_new_collection
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 4.dp, 20.dp, 0)
        }
        val options = snapshot.options
        val checklist = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            adapter = ArrayAdapter(this@BookDetailActivity,
                android.R.layout.simple_list_item_multiple_choice, options.map { it.name })
        }
        if (options.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.detail_assignment_existing)
                setTextColor(0xffb7aa9b.toInt())
                textSize = 13f
                setPadding(0, 8.dp, 0, 3.dp)
            })
            container.addView(checklist, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (48 * options.size.coerceAtMost(5)).dp))
            options.forEachIndexed { i, option ->
                checklist.setItemChecked(i, option.id in snapshot.selected)
            }
        } else {
            container.addView(TextView(this).apply {
                text = getString(R.string.detail_assignment_none_available)
                setTextColor(0xffb7aa9b.toInt())
                textSize = 13f
                setPadding(0, 8.dp, 0, 12.dp)
            })
        }

        val input = EditText(this).apply {
            hint = getString(newItemHint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(64))
            maxLines = 1
            setSingleLine(true)
        }
        container.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.detail_assignment_save, null)
            .create()
        dialog.setOnShowListener {
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                val chosen = options.filterIndexed { i, _ ->
                    checklist.isItemChecked(i)
                }.map { it.id }.toSet()
                save.isEnabled = false
                lifecycleScope.launch {
                    try {
                        organizer.save(bookId, kind, chosen, input.text.toString())
                        dialog.dismiss()
                        load(bookId)
                    } catch (e: Exception) {
                        save.isEnabled = true
                        Toast.makeText(this@BookDetailActivity,
                            getString(R.string.detail_assignment_failed,
                                e.message ?: getString(R.string.ai_generic_error)),
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun statusLabel(status: ReadingStatus): String = getString(
        when (status) {
            ReadingStatus.PENDING -> R.string.status_pending
            ReadingStatus.READING -> R.string.status_reading
            ReadingStatus.FINISHED -> R.string.status_finished
            ReadingStatus.ABANDONED -> R.string.status_abandoned
        }
    )

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
