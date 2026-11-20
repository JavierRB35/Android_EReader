package com.javierrb35.bibliotecaia.reader.pdf

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.*
import kotlin.math.sqrt

/** Native PDF parsing runs in an isolated process; only the selected read-only fd is passed in. */
class PdfRenderService : Service() {
    companion object {
        const val OPEN = 1
        const val RENDER = 2
        const val ERROR = 3
    }
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private var renderer: PdfRenderer? = null

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("pdf-render").also { it.start() }
        handler = Handler(worker.looper) { request ->
            try {
                when (request.what) {
                    OPEN -> open(request)
                    RENDER -> render(request)
                }
            } catch (_: SecurityException) {
                reply(request, ERROR, Bundle().apply { putString("message", "PDF protegido o cifrado no compatible.") })
            } catch (_: OutOfMemoryError) {
                reply(request, ERROR, Bundle().apply { putString("message", "No hay memoria suficiente para esta página.") })
            } catch (_: Exception) {
                reply(request, ERROR, Bundle().apply { putString("message", "No se pudo leer el PDF. Puede estar dañado o no ser compatible.") })
            }
            true
        }
    }
    @Suppress("DEPRECATION")
    private fun open(request: Message) {
        renderer?.close(); renderer = null
        val descriptor = request.data.getParcelable<ParcelFileDescriptor>("file") ?: error("Missing file")
        try { renderer = PdfRenderer(descriptor) }
        catch (error: Throwable) { descriptor.close(); throw error }
        reply(request, OPEN, Bundle().apply { putInt("count", renderer!!.pageCount) })
    }
    private fun render(request: Message) {
        val document = renderer ?: error("Not open")
        val index = request.data.getInt("page")
        require(index in 0 until document.pageCount)
        document.openPage(index).use { page ->
            require(page.width > 0 && page.height > 0)
            val requestedWidth = request.data.getInt("width", 1200).coerceIn(320, 1800)
            val scale = minOf(requestedWidth.toDouble() / page.width,
                sqrt(3_000_000.0 / (page.width.toDouble() * page.height)),
                4096.0 / maxOf(page.width, page.height))
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE) // Never invert figures or alter document colours.
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val pipe = ParcelFileDescriptor.createPipe()
                try {
                    reply(request, RENDER, Bundle().apply {
                        putParcelable("image", pipe[0]); putInt("page", index)
                    })
                    pipe[0].close()
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                    }
                } finally { pipe.forEach { runCatching { it.close() } } }
            } finally { bitmap.recycle() }
        }
    }
    private fun reply(request: Message, code: Int, payload: Bundle) {
        request.replyTo?.send(Message.obtain(null, code).apply {
            arg1 = request.arg1; data = payload
        })
    }
    override fun onBind(intent: Intent): IBinder = Messenger(handler).binder
    override fun onDestroy() {
        handler.post { renderer?.close(); renderer = null; worker.quitSafely() }
        super.onDestroy()
    }
}
