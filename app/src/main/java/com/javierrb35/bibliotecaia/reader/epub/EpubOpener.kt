package com.javierrb35.bibliotecaia.reader.epub

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpStreamResponse
import org.readium.r2.shared.util.http.HttpTry
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.TransformingContainer
import org.readium.r2.shared.util.resource.map
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File
import java.io.IOException

object EpubOpener {
    suspend fun open(context: Context, file: File): Publication {
        // Close a just-opened resource if cancellation happens during dispatcher hand-off.
        var opened: Publication? = null
        try { return withContext(Dispatchers.IO) {
        EpubSafety.validate(file)
        val offline = object : HttpClient {
            override suspend fun stream(request: HttpRequest): HttpTry<HttpStreamResponse> =
                Try.failure(HttpError.IO(IOException("La lectura no permite solicitudes remotas.")))
        }
        val retriever = AssetRetriever(context.contentResolver, offline)
        val asset = retriever.retrieve(file.toUrl()).getOrElse {
            throw IOException("No se ha podido abrir el archivo EPUB.")
        }
        val opener = PublicationOpener(DefaultPublicationParser(context, offline, retriever, pdfFactory = null))
        opener.open(asset, allowUserInteraction = false, onCreatePublication = {
            val bookManifest = manifest
            container = TransformingContainer(container) { url, resource ->
                val path = url.toString()
                val mediaType = bookManifest.linkWithHref(url)?.mediaType
                val svg = mediaType == MediaType.SVG || path.endsWith(".svg", true)
                if (EpubSafety.isMarkup(path) || mediaType?.isHtml == true || svg) resource.map { bytes ->
                    Try.success(EpubSafety.sanitize(bytes, svg))
                } else resource
            }
        }).getOrElse {
            asset.close()
            throw IOException("EPUB no compatible o protegido. El archivo original se conserva.")
        }.also { opened = it }
        } } catch (failure: Exception) {
            opened?.close()
            throw failure
        }
    }
}
