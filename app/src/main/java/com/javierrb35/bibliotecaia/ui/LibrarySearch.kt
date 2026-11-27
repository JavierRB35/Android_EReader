package com.javierrb35.bibliotecaia.ui

import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.CatalogSearchDocument
import java.text.Normalizer
import java.util.Locale

/** Rebuildable, read-only metadata index. No original streams or remote calls. */
internal object LibrarySearch {
    private val marks = Regex("\\p{M}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    fun normalize(value: String): String = separators.replace(
        marks.replace(Normalizer.normalize(value, Normalizer.Form.NFD), "")
            .lowercase(Locale.ROOT), " ").trim()
    private fun words(value: String) = value.split(' ').filter { it.isNotEmpty() }
    private fun names(value: String?) = value.orEmpty().split('\n').filter { it.isNotBlank() }
    data class Filters(
        val category: String = "", val tag: String = "", val collection: String = "",
        val format: String = "", val availability: String = "",
        val favorite: Boolean = false, val sort: String = "relevance"
    ) {
        val count: Int get() = listOf(category, tag, collection, format, availability).count { it.isNotEmpty() } +
            (if (favorite) 1 else 0) + (if (sort != "relevance") 1 else 0)
    }
    data class Field(val value: String, val tokens: List<String>, val weight: Int, val fuzzy: Boolean)
    data class Entry(val book: BookEntity, val categories: List<String>, val tags: List<String>,
        val collections: List<String>, val collectionIds: Set<String>, val fields: List<Field>,
        val local: Boolean)
    data class Suggestion(val label: String, val value: String)
    data class Results(val books: List<BookEntity>, val suggestions: List<Suggestion>)
    fun index(doc: CatalogSearchDocument, local: Boolean): Entry {
        val b = doc.book
        val cats = names(doc.categories)
        val tags = names(doc.tags)
        val cols = names(doc.collections)
        fun field(value: String, weight: Int, fuzzy: Boolean = true): Field {
            val text = normalize(value)
            return Field(text, words(text).distinct(), weight, fuzzy)
        }
        return Entry(b, cats, tags, cols, names(doc.collectionIds).toSet(),
            listOf(field(b.title, 100), field(b.authors.orEmpty(), 85), field(b.fileName, 70)) +
                cats.map { field(it, 60) } + tags.map { field(it, 55) } +
                cols.map { field(it, 45) } + field(doc.synopsis.orEmpty(), 30, false), local)
    }
    /** Bounded edit distance with adjacent transposition; no fuzzy matching short words/numbers. */
    private fun near(a: String, b: String): Boolean {
        val limit = if (a.length >= 8) 2 else 1
        if (a.length !in 4..40 || b.length > 42 || kotlin.math.abs(a.length-b.length) > limit ||
            a.any { it.isDigit() } || b.any { it.isDigit() }) return false
        var prevPrev = IntArray(b.length+1)
        var prev = IntArray(b.length+1) { it }
        for (i in 1..a.length) {
            val curr = IntArray(b.length+1)
            curr[0] = i
            for (j in 1..b.length) {
                curr[j] = minOf(prev[j]+1, curr[j-1]+1, prev[j-1]+if(a[i-1]==b[j-1]) 0 else 1)
                if (i>1 && j>1 && a[i-1]==b[j-2] && a[i-2]==b[j-1])
                    curr[j] = minOf(curr[j],prevPrev[j-2]+1)
            }
            prevPrev=prev; prev=curr
        }
        return prev[b.length] <= limit
    }
    private fun score(entry: Entry, query: String, terms: List<String>): Int? {
        if (terms.isEmpty()) return 0
        var score = 0
        for (term in terms) {
            var best = 0
            for (f in entry.fields) {
                val match = when {
                    term in f.tokens -> f.weight*10
                    f.tokens.any { it.startsWith(term) } -> f.weight*7
                    // Preserve interior substring search from the old LIKE query.
                    term.length >= 2 && f.value.contains(term) -> f.weight*5
                    else -> 0
                }
                best=maxOf(best,match)
            }
            if (best==0) for (f in entry.fields) {
                if (f.fuzzy && f.tokens.any { near(term,it) }) best=maxOf(best,f.weight*2)
            }
            if(best==0) return null
            score+=best
        }
        val phraseBonus = entry.fields.maxOfOrNull { f ->
            when {
                f.value == query -> f.weight*8
                f.value.startsWith(query) -> f.weight*4
                f.value.contains(query) -> f.weight*2
                else -> 0
            }
        } ?: 0
        return score+phraseBonus
    }
    fun search(entries: List<Entry>, raw: String, status: String, favoriteOnly: Boolean,
        collectionId: String, central: Boolean, hashes: Set<String>?, filters: Filters): Results {
        val query=normalize(raw)
        val terms=words(query).distinct()
        val scope=entries.filter { e ->
            val b=e.book
            (if(central) b.contentHash in hashes.orEmpty() else e.local) &&
            (status=="ALL" || b.readingStatus.name==status) &&
            (!(favoriteOnly || filters.favorite) || b.favorite) &&
            (collectionId.isEmpty() || collectionId in e.collectionIds) &&
            (filters.category.isEmpty() || filters.category in e.categories) &&
            (filters.tag.isEmpty() || filters.tag in e.tags) &&
            (filters.collection.isEmpty() || filters.collection in e.collections) &&
            (filters.format.isEmpty() || filters.format==b.format.name) &&
            (filters.availability.isEmpty() || (filters.availability=="local" && e.local) ||
                (filters.availability=="remote" && !e.local))
        }
        val recent=compareByDescending<Pair<Entry,Int>> { it.first.book.lastOpenedAt ?: Long.MIN_VALUE }
            .thenByDescending { it.first.book.addedAt }.thenBy { it.first.book.id }
        val cmp=when(filters.sort) {
            "title" -> compareBy<Pair<Entry,Int>> { normalize(it.first.book.title) }.then(recent)
            "author" -> compareBy<Pair<Entry,Int>> { normalize(it.first.book.authors.orEmpty()) }.then(recent)
            "added" -> compareByDescending<Pair<Entry,Int>> { it.first.book.addedAt }.then(recent)
            "recent" -> recent
            else -> compareByDescending<Pair<Entry,Int>> { it.second }.then(recent)
        }
        val matches=scope.mapNotNull { e -> score(e,query,terms)?.let { e to it } }.sortedWith(cmp)
        val suggestions=if(query.length<2) emptyList() else {
            val candidates=linkedMapOf<String,Suggestion>()
            fun add(kind: String,value: String) {
                val key=normalize(value)
                if(key.isNotEmpty() && key!=query && (key.startsWith(query) ||
                    words(key).any { it.startsWith(query) } || near(query,key)))
                    candidates.putIfAbsent(key,Suggestion("$kind · $value",value))
            }
            for ((e,_) in matches) {
                e.categories.forEach { add("Categoría",it) }; e.tags.forEach { add("Etiqueta",it) }
                e.collections.forEach { add("Colección",it) }; add("Autor",e.book.authors.orEmpty())
                add("Libro",e.book.title)
                if(candidates.size>=12) break
            }
            candidates.values.take(5)
        }
        return Results(matches.map { it.first.book },suggestions)
    }
}
