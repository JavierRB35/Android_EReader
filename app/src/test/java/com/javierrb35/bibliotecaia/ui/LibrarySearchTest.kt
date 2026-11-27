package com.javierrb35.bibliotecaia.ui

import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import com.javierrb35.bibliotecaia.data.CatalogSearchDocument
import com.javierrb35.bibliotecaia.data.ReadingStatus
import org.junit.Assert.*
import org.junit.Test

class LibrarySearchTest {
    private fun entry(id: String, title: String, author: String = "", category: String? = null,
        tag: String? = null, synopsis: String? = null, local: Boolean = false,
        format: BookFormat = BookFormat.EPUB, favorite: Boolean = false,
        status: ReadingStatus = ReadingStatus.PENDING, recent: Long? = null,
        file: String = "$id.epub", collection: String? = null): LibrarySearch.Entry {
        val book = BookEntity(id,title,author,format,file,"",id,1,1,
            lastOpenedAt=recent,readingStatus=status,favorite=favorite)
        return LibrarySearch.index(CatalogSearchDocument(book,category,tag,collection,
            if(collection==null) null else "collection-id",synopsis),local)
    }
    private val marquez = entry("a","Cien años de soledad","Gabriel García Márquez", "Literatura")
    private val saramago = entry("b","Ensayo sobre la ceguera","José Saramago",local=true)
    private val finance = entry("c","Planificación financiera","Autora", "Finanzas","Control de gestión",
        "Rentabilidad y presupuestos empresariales",true,BookFormat.MOBI,true,
        ReadingStatus.READING,42,"manual-economico.mobi","Estudio")
    private val books = listOf(marquez,saramago,finance)
    private fun find(q: String, entries: List<LibrarySearch.Entry> = books,
        filters: LibrarySearch.Filters = LibrarySearch.Filters(), central: Boolean = true,
        status: String = "ALL", favorite: Boolean = false, collectionId: String = "") =
        LibrarySearch.search(entries,q,status,favorite,collectionId,central,
            entries.map { it.book.contentHash }.toSet(),filters)
    @Test fun accentsPrefixTyposAndReorderedTokens() {
        for(q in listOf("garcia marquez","marquez gabriel","GARCÍA MÁRQUEZ","cien anos","marqu"))
            assertEquals(listOf("a"),find(q).books.map { it.id })
        assertEquals(listOf("b"),find("saramgo").books.map { it.id })
        assertEquals(listOf("b"),find("saramgao").books.map { it.id })
        assertEquals(listOf("c"),find("control gestion").books.map { it.id })
        assertEquals(listOf("c"),find("rentabilidad presupuestos").books.map { it.id })
        assertEquals(listOf("c"),find("manual economico").books.map { it.id })
        assertTrue(find("inexistente xyz").books.isEmpty())
    }
    @Test fun relevanceBeatsRecencyAndSuggestionsAreTyped() {
        val weak=entry("d","Introducción","Otro",synopsis="Planificación financiera",recent=999)
        assertEquals("c",find("planificacion financiera",books+weak).books.first().id)
        assertTrue(find("finan").suggestions.any { it.label=="Categoría · Finanzas" })
    }
    @Test fun combinedFiltersAndScopeKeepExactOriginalIdentity() {
        val filters=LibrarySearch.Filters(category="Finanzas",tag="Control de gestión",
            collection="Estudio",format="MOBI",availability="local",favorite=true,sort="title")
        assertEquals(listOf("c"),find("",filters=filters,status="READING",collectionId="collection-id").books.map{it.id})
        assertTrue(find("",filters=filters.copy(availability="remote")).books.isEmpty())
        assertEquals(setOf("b","c"),find("",central=false).books.map{it.id}.toSet())
        assertTrue(LibrarySearch.search(books,"","ALL",false,"",true,setOf("b"),
            LibrarySearch.Filters()).books.all { it.id=="b" })
        assertEquals(listOf("a"),find("",filters=LibrarySearch.Filters(availability="remote")).books.map{it.id})
    }
    @Test fun emptyQueryRetainsRecencyAndSortOverridesIt() {
        assertEquals("c",find("").books.first().id)
        assertEquals(listOf("a","b","c"),find("",filters=LibrarySearch.Filters(sort="title")).books.map{it.id})
    }
    @Test fun largeCatalogueFindsRemoteMetadataWithoutOpeningOriginals() {
        val entries=(1..1500).map { entry("id$it","Volumen $it","Autor $it") } + finance
        assertEquals(listOf("c"),find("rentabilidad presupuestos",entries).books.map { it.id })
    }
}
