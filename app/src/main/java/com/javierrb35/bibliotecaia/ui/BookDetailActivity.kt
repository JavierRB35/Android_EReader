package com.javierrb35.bibliotecaia.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.javierrb35.bibliotecaia.R
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.ReadingStatus
import kotlinx.coroutines.launch
import java.util.Date

/** Ficha básica del libro (Incremento 1). Edición manual completa de metadatos: pendiente. */
class BookDetailActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_BOOK_ID = "book_id"

        fun start(context: Context, bookId: String) {
            context.startActivity(
                Intent(context, BookDetailActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)
            )
        }
    }

    private val bookDao by lazy { AppDatabase.get(this).bookDao() }
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

        findViewById<View>(R.id.btnCycleStatus).setOnClickListener { cycleStatus() }
    }

    private fun load(bookId: String) {
        lifecycleScope.launch {
            val loaded = bookDao.findById(bookId)
            if (loaded == null) {
                Toast.makeText(this@BookDetailActivity, R.string.book_not_found, Toast.LENGTH_SHORT).show()
                finish()
            } else {
                book = loaded
                show(loaded)
            }
        }
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
        findViewById<TextView>(R.id.txtDetailFile).text = b.fileName
        findViewById<TextView>(R.id.txtDetailStatus).text = statusLabel(b.readingStatus)
    }

    /** Ciclo simple de estados; favoritos accesibles desde biblioteca en una rebanada posterior. */
    private fun cycleStatus() {
        val current = book ?: return
        val next = when (current.readingStatus) {
            ReadingStatus.PENDING -> ReadingStatus.READING
            ReadingStatus.READING -> ReadingStatus.FINISHED
            ReadingStatus.FINISHED -> ReadingStatus.ABANDONED
            ReadingStatus.ABANDONED, ReadingStatus.FAVORITE -> ReadingStatus.PENDING
        }
        lifecycleScope.launch {
            bookDao.update(current.copy(readingStatus = next))
            load(current.id)
        }
    }

    private fun statusLabel(status: ReadingStatus): String = getString(
        when (status) {
            ReadingStatus.PENDING -> R.string.status_pending
            ReadingStatus.READING -> R.string.status_reading
            ReadingStatus.FAVORITE -> R.string.status_favorite
            ReadingStatus.FINISHED -> R.string.status_finished
            ReadingStatus.ABANDONED -> R.string.status_abandoned
        }
    )
}
