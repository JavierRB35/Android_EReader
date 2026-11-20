package com.javierrb35.bibliotecaia.reader.pdf

import com.javierrb35.bibliotecaia.data.AnnotationEntity

/**
 * Converts the exact persisted OCR geometry of every linked NOTE or HIGHLIGHT
 * into marks on the real PDF page. No fuzzy quote search or arbitrary page mark.
 */
internal object PdfOcrAnnotationMarks {
    fun forPage(
        saved: List<AnnotationEntity>, page: Int,
        editionHash: String, snapshot: PdfOcrSnapshot?
    ): List<PdfPaintedMark> =
        saved.asSequence().filter {
            it.deletedAt == null &&
                (it.kind == "NOTE" || it.kind == "HIGHLIGHT") &&
                it.quote.isNotBlank()
        }.flatMap { mark ->
            PdfOcrAnchors.overlayBoxes(
                mark.locator, page, mark.quote, editionHash, snapshot
            ).asSequence().map { rect ->
                PdfPaintedMark(mark.id, rect, mark.kind)
            }
        }.toList()
}
