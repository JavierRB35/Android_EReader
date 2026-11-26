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
import android.widget.ScrollView
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
        findViewById<View>(R.id.btnSynopsis).setOnClickListener { openSynopsisAction() }
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
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        intent.getStringExtra(EXTRA_BOOK_ID)?.let { load(it) }
    }

    private fun openAi() {
        val current = book ?: return
        val keyStored = com.javierrb35.bibliotecaia.ai.AiKeyStore.providers()
            .any { com.javierrb35.bibliotecaia.ai.AiKeyStore.getKey(this, it) != null }
        if (keyStored) com.javierrb35.bibliotecaia.ai.AiChatActivity.start(this, current.id)
        else startActivity(Intent(this, com.javierrb35.bibliotecaia.ai.AiSetupActivity::class.java))
    }

    /** A single book-scoped entry point: add if absent, read if already stored. */
    private fun openSynopsisAction() {
        val current = book ?: return
        val existing = savedSynopsis?.takeIf {
            it.bookId == current.id && it.text.isNotBlank()
        }
        if (existing == null && current.format.name == "PDF") {
            editSynopsis()
        } else if (existing == null) {
            // The CTA requests an AI-generated synopsis automatically; manual editing
            // remains available in the saved-synopsis modal.
            com.javierrb35.bibliotecaia.ai.AiChatActivity.startSynopsis(this, current.id)
        } else showSavedSynopsis()
    }

    private fun renderSynopsisAction(bookId: String) {
        val hasSynopsis = savedSynopsis?.let {
            it.bookId == bookId && it.text.isNotBlank()
        } == true
        val label = if (hasSynopsis) R.string.detail_synopsis_view else R.string.detail_synopsis_add
        findViewById<TextView>(R.id.txtSynopsisAction).setText(label)
        findViewById<View>(R.id.btnSynopsis).apply {
            visibility = View.VISIBLE
            contentDescription = getString(label)
        }
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
                        renderSynopsisAction(current.id)
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

    /** Read the saved book argument instantly without calling an AI provider again. */
    private fun showSavedSynopsis() {
        val current = book ?: return
        val saved = savedSynopsis ?: return
        if (current.id != saved.bookId || saved.text.isBlank()) return
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + .5f).toInt()
        val maxHeight = (resources.displayMetrics.heightPixels * .64f).toInt()
        val modalText = TextView(this).apply {
            text = saved.text
            textSize = 16f
            typeface = android.graphics.Typeface.SERIF
            setLineSpacing(dp(4).toFloat(), 1.1f)
            setTextColor(getColor(R.color.bia_text))
            setTextIsSelectable(true)
            setPadding(dp(20), dp(14), dp(20), dp(18))
        }
        val scroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
            }
        }.apply {
            isFillViewport = false
            addView(modalText)
        }
        val dialogContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val editButton = com.google.android.material.button.MaterialButton(this).apply {
            text = getString(R.string.detail_synopsis_edit)
            isAllCaps = false
            minHeight = dp(44)
            setTextColor(getColor(R.color.bia_text))
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        actions.addView(editButton, LinearLayout.LayoutParams(
            0, dp(48), 1f
        ))
        val deleteButton = com.google.android.material.button.MaterialButton(this).apply {
            text = getString(R.string.detail_synopsis_delete)
            isAllCaps = false
            minHeight = dp(48)
            setTextColor(getColor(R.color.bia_text))
        }
        actions.addView(deleteButton, LinearLayout.LayoutParams(
            0, dp(48), 1f
        ).apply { marginStart = dp(8) })
        dialogContent.addView(actions)
        dialogContent.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_book_overview)
            .setView(dialogContent)
            .setNegativeButton("Cerrar", null)
            .setNeutralButton(R.string.detail_synopsis_copy) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Argumento · " + current.title, saved.text))
            }
            .setPositiveButton(R.string.detail_synopsis_export) { _, _ ->
                // Android's system document picker offers local storage, Drive
                // and any provider selected by the user; no OAuth inside the app.
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
            .show()
        editButton.setOnClickListener { dialog.dismiss(); editSynopsis(saved) }
        deleteButton.setOnClickListener {
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
                            renderSynopsisAction(current.id)
                            dialog.dismiss()
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
            .setMessage("¿Eliminar definitivamente «" + current.title + "» de la biblioteca y de los dispositivos vinculados? Se eliminarán también sus notas y posiciones. Las copias históricas externas no se modificarán.")
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton("Eliminar definitivamente") { _, _ ->
                lifecycleScope.launch {
                    try {
                        com.javierrb35.bibliotecaia.sync.SharedLibrary.deleteEverywhere(applicationContext, db, current.id)
                        BackupRoutine.refreshOnLibraryOpen(applicationContext, BuildConfig.VERSION_NAME)
                        setResult(RESULT_LOCAL_REMOVED)
                        finish()
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) {
                        Toast.makeText(this@BookDetailActivity, e.message ?: "No se pudo eliminar.", Toast.LENGTH_LONG).show()
                    }
                }
            }.show()
    }

    private fun downloadCentral() {
        val current = book ?: return
        val button = findViewById<View>(R.id.btnDownloadCentral)
        button.isEnabled = false
        lifecycleScope.launch {
            try {
                BackupRoutine.downloadBook(applicationContext, db, BuildConfig.VERSION_NAME, current.contentHash)
                load(current.id)
                Toast.makeText(this@BookDetailActivity, R.string.central_downloaded, Toast.LENGTH_SHORT).show()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                findViewById<View>(R.id.btnLink).visibility = View.VISIBLE
                Toast.makeText(this@BookDetailActivity, e.message ?: "No se pudo descargar.", Toast.LENGTH_LONG).show()
            } finally { button.isEnabled = true }
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
                renderSynopsisAction(loaded.id)
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
