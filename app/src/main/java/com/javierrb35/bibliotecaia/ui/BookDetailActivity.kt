package com.javierrb35.bibliotecaia.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.javierrb35.bibliotecaia.R
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookCategoryCrossRef
import com.javierrb35.bibliotecaia.data.BookTagCrossRef
import com.javierrb35.bibliotecaia.data.CollectionBookCrossRef
import com.javierrb35.bibliotecaia.data.ReadingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * Ficha del libro (§7.2): metadatos, edición manual básica, favorito ortogonal y
 * asignaciones a categorías/etiquetas/colecciones.
 */
class BookDetailActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_BOOK_ID = "book_id"

        fun start(context: Context, bookId: String) {
            context.startActivity(
                Intent(context, BookDetailActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)
            )
        }
    }

    private val db by lazy { AppDatabase.get(this) }
    private val linker = com.javierrb35.bibliotecaia.backup.OriginalLinker(java.io.File(filesDir, "books"))
    private val linkLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) linkOriginal(uri)
    }
    private var book: BookEntity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book_detail)

        val bookId = intent.getStringExtra(EXTRA_BOOK_ID)
        if (bookId == null) {
            finish()
            return
        }
        load(bookId)

        findViewById<View>(R.id.btnReadBook).setOnClickListener { ReaderRouter.open(this, bookId) }

        findViewById<View>(R.id.btnCycleStatus).setOnClickListener { cycleStatus() }
        findViewById<View>(R.id.btnAi).setOnClickListener { openAi() }
        findViewById<View>(R.id.btnFavorite).setOnClickListener { toggleFavorite() }
        findViewById<View>(R.id.btnLink).setOnClickListener { linkLauncher.launch(arrayOf("*/*")) }
        findViewById<View>(R.id.btnEditMetadata).setOnClickListener { promptEditMetadata() }
        findViewById<View>(R.id.btnEditCategories).setOnClickListener { promptAssignCategories() }
        findViewById<View>(R.id.btnEditTags).setOnClickListener { promptAssignTags() }
        findViewById<View>(R.id.btnEditCollections).setOnClickListener { promptAssignCollections() }
    }

    private fun openAi() {
        val current = book ?: return
        val keyStored = com.javierrb35.bibliotecaia.ai.AiKeyStore.providers()
            .any { com.javierrb35.bibliotecaia.ai.AiKeyStore.getKey(this, it) != null }
        if (keyStored) com.javierrb35.bibliotecaia.ai.AiChatActivity.start(this, current.id)
        else startActivity(Intent(this, com.javierrb35.bibliotecaia.ai.AiSetupActivity::class.java))
    }

    /** Revinculación por hash (ADR-008): nunca por nombre; sin duplicar fichas. */
    private fun linkOriginal(uri: android.net.Uri) {
        val current = book ?: return
        lifecycleScope.launch {
            try {
                val temp = java.io.File(cacheDir, "link_${'$'}{System.currentTimeMillis()}.tmp")
                contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Sin lectura")
                val linked: com.javierrb35.bibliotecaia.data.BookEntity? =
                    withContext(Dispatchers.IO) { linker.link(temp, listOf(current)) }
                temp.delete()
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
                show(loaded)
                showAssignments(loaded.id)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        intent.getStringExtra(EXTRA_BOOK_ID)?.let { load(it) }
    }

    private fun show(b: BookEntity) {
        findViewById<TextView>(R.id.txtDetailTitle).text = b.title
        findViewById<TextView>(R.id.txtDetailAuthors).text =
            b.authors ?: getString(R.string.detail_no_authors)
        findViewById<TextView>(R.id.txtDetailFormat).text = b.format.name
        findViewById<TextView>(R.id.txtDetailSize).text =
            Formatter.formatShortFileSize(this, b.sizeBytes)
        findViewById<TextView>(R.id.txtDetailAdded).text =
            DateFormat.getDateFormat(this).format(Date(b.addedAt))
        findViewById<TextView>(R.id.txtDetailStatus).text = statusLabel(b.readingStatus)
        val fileAvailable = b.localPath.isNotBlank() && java.io.File(b.localPath).exists()
        findViewById<TextView>(R.id.txtDetailFile).text =
            if (fileAvailable) b.fileName else getString(R.string.detail_file_missing)
        findViewById<View>(R.id.btnLink).visibility =
            if (fileAvailable) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.btnFavorite).setText(
            if (b.favorite) R.string.btn_unfavorite else R.string.btn_favorite
        )
    }

    private suspend fun showAssignments(bookId: String) {
        val refs = db.refsDao()
        findViewById<TextView>(R.id.txtDetailCategories).text =
            refs.categoryNamesForBook(bookId).joinToString(", ").ifEmpty { getString(R.string.detail_none) }
        findViewById<TextView>(R.id.txtDetailTags).text =
            refs.tagNamesForBook(bookId).joinToString(", ").ifEmpty { getString(R.string.detail_none) }
        findViewById<TextView>(R.id.txtDetailCollections).text =
            refs.collectionNamesForBook(bookId).joinToString(", ").ifEmpty { getString(R.string.detail_none) }
    }

    private fun cycleStatus() {
        val current = book ?: return
        val next = when (current.readingStatus) {
            ReadingStatus.PENDING -> ReadingStatus.READING
            ReadingStatus.READING -> ReadingStatus.FINISHED
            ReadingStatus.FINISHED -> ReadingStatus.ABANDONED
            ReadingStatus.ABANDONED -> ReadingStatus.PENDING
        }
        lifecycleScope.launch {
            db.bookDao().update(current.copy(readingStatus = next))
            load(current.id)
        }
    }

    private fun toggleFavorite() {
        val current = book ?: return
        lifecycleScope.launch {
            db.bookDao().update(current.copy(favorite = !current.favorite))
            load(current.id)
        }
    }

    /** Edición manual básica de metadatos (§7.2). El resto de campos, en rebanadas posteriores. */
    private fun promptEditMetadata() {
        val current = book ?: return
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val titleInput = EditText(this).apply { setText(current.title); hint = getString(R.string.detail_title_hint) }
        val authorsInput = EditText(this).apply { setText(current.authors ?: ""); hint = getString(R.string.detail_authors_hint) }
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

    private fun promptAssignCategories() {
        val current = book ?: return
        lifecycleScope.launch {
            val all = db.categoryDao().allOnce()
            val selected = db.refsDao().categoryIdsForBook(current.id).toSet()
            multiSelect(
                title = getString(R.string.detail_assign_categories),
                ids = all.map { it.id },
                labels = all.map { it.name },
                selected = selected
            ) { newSelection ->
                lifecycleScope.launch {
                    applySelection(
                        current.id, selected, newSelection,
                        add = { db.refsDao().addBookCategory(BookCategoryCrossRef(current.id, it)) },
                        remove = { db.refsDao().removeBookCategory(current.id, it) }
                    )
                    load(current.id)
                }
            }
        }
    }

    private fun promptAssignTags() {
        val current = book ?: return
        lifecycleScope.launch {
            val all = db.tagDao().allOnce()
            val selected = db.refsDao().tagIdsForBook(current.id).toSet()
            multiSelect(
                title = getString(R.string.detail_assign_tags),
                ids = all.map { it.id },
                labels = all.map { it.name },
                selected = selected
            ) { newSelection ->
                lifecycleScope.launch {
                    applySelection(
                        current.id, selected, newSelection,
                        add = { db.refsDao().addBookTag(BookTagCrossRef(current.id, it)) },
                        remove = { db.refsDao().removeBookTag(current.id, it) }
                    )
                    load(current.id)
                }
            }
        }
    }

    private fun promptAssignCollections() {
        val current = book ?: return
        lifecycleScope.launch {
            val all = db.collectionDao().allOnce()
            val selected = db.refsDao().collectionIdsForBook(current.id).toSet()
            multiSelect(
                title = getString(R.string.detail_assign_collections),
                ids = all.map { it.id },
                labels = all.map { it.name },
                selected = selected
            ) { newSelection ->
                lifecycleScope.launch {
                    applySelection(
                        current.id, selected, newSelection,
                        add = { db.refsDao().addCollectionBook(CollectionBookCrossRef(it, current.id)) },
                        remove = { db.refsDao().removeCollectionBook(current.id, it) }
                    )
                    load(current.id)
                }
            }
        }
    }

    private fun multiSelect(
        title: String,
        ids: List<String>,
        labels: List<String>,
        selected: Set<String>,
        onResult: (Set<String>) -> Unit
    ) {
        if (ids.isEmpty()) {
            Toast.makeText(this, R.string.detail_no_items_to_assign, Toast.LENGTH_SHORT).show()
            return
        }
        val checked = BooleanArray(ids.size) { ids[it] in selected }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                onResult(ids.filterIndexed { i, _ -> checked[i] }.toSet())
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private suspend fun applySelection(
        bookId: String,
        oldSelection: Set<String>,
        newSelection: Set<String>,
        add: suspend (String) -> Unit,
        remove: suspend (String) -> Unit
    ) {
        (newSelection - oldSelection).forEach { add(it) }
        (oldSelection - newSelection).forEach { remove(it) }
    }

    private fun statusLabel(status: ReadingStatus): String = getString(
        when (status) {
            ReadingStatus.PENDING -> R.string.status_pending
            ReadingStatus.READING -> R.string.status_reading
            ReadingStatus.FINISHED -> R.string.status_finished
            ReadingStatus.ABANDONED -> R.string.status_abandoned
        }
    )
}
