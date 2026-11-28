package com.javierrb35.bibliotecaia.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.javierrb35.bibliotecaia.R
import com.javierrb35.bibliotecaia.data.BookEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Real covers loaded off-thread; view recycling cancels pending updates. */
class BookAdapter(
    private val onLongClick: ((BookEntity) -> Unit)? = null,
    private val onClick: (BookEntity) -> Unit
) : RecyclerView.Adapter<BookAdapter.Holder>() {
    private val items = mutableListOf<BookEntity>()
    private var editions: Map<String, List<BookEntity>> = emptyMap()
    private var selectedIds: Set<String> = emptySet()
    fun showSelection(ids: Set<String>) {
        val changed = (selectedIds - ids) + (ids - selectedIds)
        selectedIds = ids.toSet()
        items.forEachIndexed { index, book ->
            if (book.id in changed) notifyItemChanged(index, SELECTION_PAYLOAD)
        }
    }

    private fun bindSelection(holder: Holder, book: BookEntity) {
        val card = holder.itemView as com.google.android.material.card.MaterialCardView
        val context = card.context
        val selected = book.id in selectedIds
        card.isSelected = selected
        val surface = context.getColor(R.color.bia_surface)
        val accent = context.getColor(R.color.bia_accent)
        card.setCardBackgroundColor(if (selected)
            androidx.core.graphics.ColorUtils.blendARGB(surface, accent, .30f) else surface)
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == SELECTION_PAYLOAD || it == COVER_PAYLOAD }) {
            val book = items[position]
            if (SELECTION_PAYLOAD in payloads) bindSelection(holder, book)
            if (COVER_PAYLOAD in payloads && holder.fallback.visibility == View.VISIBLE) {
                if (holder.job?.isActive == true) holder.coverRefreshPending = true
                else loadCover(holder, book, variants(book))
            }
        } else super.onBindViewHolder(holder, position, payloads)
    }

    private companion object {
        const val SELECTION_PAYLOAD = "book_selection"
        const val COVER_PAYLOAD = "book_cover"
    }

    // A cover becoming available must not invalidate every row or restart
    // an active touch gesture. Payloads reuse the existing visible holders.
    fun refreshCovers() {
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size, COVER_PAYLOAD)
    }

    private fun variants(book: BookEntity) = editions[book.id].orEmpty().ifEmpty { listOf(book) }

    internal var mode: LibraryViewMode = LibraryViewMode.GRID
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }
    var grid: Boolean
        get() = mode == LibraryViewMode.GRID
        set(value) { mode = if (value) LibraryViewMode.GRID else LibraryViewMode.CARDS }

    fun submit(books: List<BookEntity>, alternatives: Map<String, List<BookEntity>> = emptyMap()) {
        if (items == books && editions == alternatives) return
        val old = items.toList()
        val oldEditions = editions
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = books.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                old[oldItemPosition].id == books[newItemPosition].id
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val previous = old[oldItemPosition]
                val next = books[newItemPosition]
                return previous == next && oldEditions[previous.id] == alternatives[next.id]
            }
        })
        editions = alternatives.toMap()
        items.clear()
        items.addAll(books)
        diff.dispatchUpdatesTo(this)
    }

    class Holder(view: View, val scope: CoroutineScope?) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.txtBookTitle)
        val authors: TextView = view.findViewById(R.id.txtBookAuthors)
        val format: TextView = view.findViewById(R.id.txtBookFormat)
        val progressText: TextView = view.findViewById(R.id.txtBookProgress)
        val progress: LinearProgressIndicator = view.findViewById(R.id.bookProgress)
        val coverTitle: TextView = view.findViewById(R.id.txtCoverTitle)
        val fallback: View = view.findViewById(R.id.coverFallback)
        val cover: ImageView = view.findViewById(R.id.imgBookCover)
        var job: Job? = null
        var boundId: String? = null
        var coverRefreshPending = false
    }

    override fun getItemViewType(position: Int) = mode.ordinal
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(when (LibraryViewMode.entries[viewType]) {
            LibraryViewMode.GRID -> R.layout.item_book
            LibraryViewMode.CARDS -> R.layout.item_book_list
            LibraryViewMode.COMPACT -> R.layout.item_book_compact
        }, parent, false),
        parent.findViewTreeLifecycleOwner()?.lifecycleScope
    )
    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val book = items[position]
        val context = holder.itemView.context
        holder.job?.cancel()
        holder.coverRefreshPending = false
        holder.boundId = book.id
        holder.title.text = book.title
        holder.authors.text = book.authors ?: ""
        val variants = variants(book)
        val formats = variants.joinToString(" / ") { it.format.name }
        holder.format.text = (if (book.favorite) "$formats  ★" else formats) +
            if (variants.none { com.javierrb35.bibliotecaia.backup.CentralCatalogue.local(it) })
                " · " + context.getString(R.string.central_not_downloaded) else ""
        val percent = (book.progress.coerceIn(0f, 1f) * 100).roundToInt()
        holder.progress.setProgressCompat(percent, false)
        holder.progress.visibility = if (percent > 0) View.VISIBLE else View.GONE
        holder.progressText.text = if (percent > 0) context.getString(R.string.bia_progress, percent) else context.getString(R.string.bia_not_started)
        holder.coverTitle.text = book.title
        holder.cover.setImageDrawable(null)
        holder.fallback.visibility = View.VISIBLE
        holder.itemView.contentDescription = context.getString(R.string.bia_missing_cover_description, book.title) +
            ", ${book.authors.orEmpty()}, ${holder.format.text}, ${holder.progressText.text}"
        bindSelection(holder, book)
        holder.itemView.setOnClickListener { onClick(book) }
        holder.itemView.setOnLongClickListener(if (onLongClick == null) null else View.OnLongClickListener {
            onLongClick.invoke(book)
            true
        })
        loadCover(holder, book, variants)
    }

    private fun loadCover(holder: Holder, book: BookEntity, variants: List<BookEntity>) {
        val context = holder.itemView.context
        holder.job = holder.scope?.launch {
            var bitmap = BookCoverLoader.load(context, book)
            for (alternative in variants.drop(1)) {
                if (bitmap != null) break
                bitmap = BookCoverLoader.load(context, alternative)
            }
            if (holder.boundId == book.id) {
                val retry = holder.coverRefreshPending
                holder.coverRefreshPending = false
                if (bitmap != null) {
                    holder.cover.setImageBitmap(bitmap)
                    holder.fallback.visibility = View.GONE
                    holder.itemView.contentDescription = "${book.title}, ${book.authors.orEmpty()}, ${holder.format.text}, ${holder.progressText.text}"
                } else if (retry) loadCover(holder, book, variants)
            }
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.job?.cancel()
        holder.boundId = null
        holder.coverRefreshPending = false
        holder.cover.setImageDrawable(null)
        super.onViewRecycled(holder)
    }
}
