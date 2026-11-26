package com.javierrb35.bibliotecaia.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
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
        if (payloads.isNotEmpty() && payloads.all { it == SELECTION_PAYLOAD })
            bindSelection(holder, items[position])
        else super.onBindViewHolder(holder, position, payloads)
    }

    private companion object { const val SELECTION_PAYLOAD = "book_selection" }

    var grid: Boolean = true
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }

    fun submit(books: List<BookEntity>) {
        items.clear()
        items.addAll(books)
        notifyDataSetChanged()
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
    }

    override fun getItemViewType(position: Int) = if (grid) 1 else 0
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(if (viewType == 1) R.layout.item_book else R.layout.item_book_list, parent, false),
        parent.findViewTreeLifecycleOwner()?.lifecycleScope
    )
    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val book = items[position]
        val context = holder.itemView.context
        holder.job?.cancel()
        holder.boundId = book.id
        holder.title.text = book.title
        holder.authors.text = book.authors ?: ""
        holder.format.text = (if (book.favorite) "${book.format.name}  ★" else book.format.name) +
            if (book.localPath.isBlank() || !java.io.File(book.localPath).isFile) " · " + context.getString(R.string.central_not_downloaded) else ""
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
        holder.job = holder.scope?.launch {
            val bitmap = BookCoverLoader.load(context, book)
            if (holder.boundId == book.id && bitmap != null) {
                holder.cover.setImageBitmap(bitmap)
                holder.fallback.visibility = View.GONE
                holder.itemView.contentDescription = "${book.title}, ${book.authors.orEmpty()}, ${holder.format.text}, ${holder.progressText.text}"
            }
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.job?.cancel()
        holder.boundId = null
        holder.cover.setImageDrawable(null)
        super.onViewRecycled(holder)
    }
}
