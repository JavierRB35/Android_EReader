package com.javierrb35.bibliotecaia.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.javierrb35.bibliotecaia.R
import com.javierrb35.bibliotecaia.data.BookEntity

/** Adaptador del catálogo. Bibliotecas personales pequeñas: reposición simple sin DiffUtil. */
class BookAdapter(private val onClick: (BookEntity) -> Unit) :
    RecyclerView.Adapter<BookAdapter.Holder>() {

    private val items = mutableListOf<BookEntity>()

    fun submit(books: List<BookEntity>) {
        items.clear()
        items.addAll(books)
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.txtBookTitle)
        val authors: TextView = view.findViewById(R.id.txtBookAuthors)
        val format: TextView = view.findViewById(R.id.txtBookFormat)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val book = items[position]
        holder.title.text = book.title
        holder.authors.text = book.authors ?: ""
        holder.format.text = if (book.favorite) book.format.name + " ★" else book.format.name
        holder.itemView.setOnClickListener { onClick(book) }
    }
}
