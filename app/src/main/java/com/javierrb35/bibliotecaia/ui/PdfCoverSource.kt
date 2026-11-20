package com.javierrb35.bibliotecaia.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/**
 * Portada de PDFs locales: primera página renderizada con el motor de plataforma.
 * Sin dependencias nuevas; sin escritura en disco ni accesos remotos.
 * Devuelve null si el archivo no es un PDF legible, no tiene páginas o excede los límites.
 */
object PdfCoverSource {

    fun read(file: File, maxWidth: Int = 360, maxHeight: Int = 540): Bitmap? = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount < 1) return null
                renderer.openPage(0).use { page ->
                    // Límites defensivos análogos a EpubCoverSource/decode(): nada de dimensiones absurdas
                    if (page.width <= 0 || page.height <= 0 ||
                        page.width > 20000 || page.height > 20000
                    ) return null
                    var sample = 1
                    while (page.width / sample > maxWidth || page.height / sample > maxHeight) sample *= 2
                    val width = (page.width / sample).coerceAtLeast(1)
                    val height = (page.height / sample).coerceAtLeast(1)
                    // PdfRenderer exige ARGB_8888 («Unsupported pixel format» con RGB_565)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    // Los PDF con fondo transparente se apoyan en blanco: aspecto real de la página
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    } catch (e: Exception) {
        Log.w("PdfCoverSource", "no se pudo renderizar la portada de ${file.name}", e)
        null // PDF corrupto o ilegible: la biblioteca muestra la ficha tipográfica
    }
}
