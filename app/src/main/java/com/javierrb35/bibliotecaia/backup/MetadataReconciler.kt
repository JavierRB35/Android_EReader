package com.javierrb35.bibliotecaia.backup

import com.javierrb35.bibliotecaia.data.BookEntity
import java.util.Locale

/**
 * Issue #155: el migrador Windows bautizaba carpetas completas con el nombre de
 * la carpeta y de su carpeta superior, de modo que 483 EPUB importados llevan
 * title="epub" y authors="Desktop". Estos placeholders se corrigen solo mientras
 * el valor existente siga siendo genérico: un título o autor editado por el
 * usuario (o ya reparado) nunca se sobrescribe (#142), la corrección no
 * reintroduce datos incorrectos y converge de forma idempotente en cada
 * dispositivo que la recibe por sincronización.
 */
internal object BookMetadataPlaceholders {

    private val GENERIC_TITLES = setOf(
        "epub", "mobi", "pdf", "libro", "book", "untitled",
        "unknown", "desconocido", "sin título", "documento", "nuevo documento",
    )
    private val GENERIC_AUTHORS = setOf(
        "desktop", "unknown", "desconocido", "desconocida", "autor",
        "autor desconocido", "n/a", "na", "null", "varios autores",
    )

    private fun normalize(value: String) = value.trim().lowercase(Locale.ROOT)

    /** Genérico, vacío o simplemente el nombre del archivo (fallback de importación). */
    fun isPlaceholderTitle(title: String, fileName: String): Boolean {
        val value = normalize(title)
        if (value.isEmpty() || value in GENERIC_TITLES) return true
        val stem = fileName.substringBeforeLast('.', fileName).trim()
        return stem.isNotEmpty() && normalize(stem) == value
    }

    fun isPlaceholderAuthors(authors: String?): Boolean {
        val value = authors?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return value.isEmpty() || value in GENERIC_AUTHORS
    }
}

/** Campos concretos que una copia o sincronización puede reparar de una ficha existente. */
internal data class MetadataRepairPlan(val title: String? = null, val authors: String? = null) {
    val hasChanges: Boolean get() = title != null || authors != null
}

internal object MetadataReconciler {

    /** Proveedor de argumentos importados del catálogo Calibre: nunca reemplaza texto del usuario. */
    const val IMPORTED_SYNOPSIS_PROVIDER = "calibre"

    /**
     * Reparación de una ficha existente que ya contiene el mismo original
     * (mismo SHA-256). Solo propone cambios; jamás borra ni degrada valores
     * reales, preserva identificadores y no toca datos de lectura.
     */
    fun plan(existing: BookEntity, incoming: BookEntity): MetadataRepairPlan {
        if (existing.contentHash != incoming.contentHash) return MetadataRepairPlan()
        val title = incoming.title.trim().takeIf {
            it.isNotEmpty() && it != existing.title &&
                BookMetadataPlaceholders.isPlaceholderTitle(existing.title, existing.fileName)
        }
        val authors = incoming.authors?.trim().takeIf {
            !it.isNullOrEmpty() && it != existing.authors &&
                BookMetadataPlaceholders.isPlaceholderAuthors(existing.authors)
        }
        return MetadataRepairPlan(title, authors)
    }

    fun apply(existing: BookEntity, plan: MetadataRepairPlan): BookEntity = existing.copy(
        title = plan.title ?: existing.title,
        authors = plan.authors ?: existing.authors,
    )

    /**
     * Un argumento importado del catálogo (Calibre) completa huecos, pero nunca
     * reemplaza una sinopsis existente del usuario (#155): la restauración
     * aditiva ya la inserta solo si falta, y la sincronización debe respetar
     * ese mismo contrato.
     */
    fun importedSynopsisMayReplace(existingText: String?): Boolean = existingText.isNullOrBlank()
}
