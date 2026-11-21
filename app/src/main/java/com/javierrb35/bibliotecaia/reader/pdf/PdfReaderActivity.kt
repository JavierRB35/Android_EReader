package com.javierrb35.bibliotecaia.reader.pdf

import com.javierrb35.bibliotecaia.R

import android.content.*
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.*
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.ReadingRepository
import com.javierrb35.bibliotecaia.data.AnnotationEntity
import com.javierrb35.bibliotecaia.reader.annotations.SavedAnnotationSheet
import com.javierrb35.bibliotecaia.reader.speech.ReaderSpeech
import kotlinx.coroutines.flow.collect
import com.javierrb35.bibliotecaia.ui.AnnotationEditor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class PdfReaderActivity : AppCompatActivity() {
    companion object {
        fun start(context: Context, bookId: String, locatorJson: String? = null) {
            context.startActivity(Intent(context, PdfReaderActivity::class.java)
                .putExtra("book_id", bookId).putExtra("locator", locatorJson))
        }
    }
    private lateinit var bookId: String
    private lateinit var image: PdfPageView
    private lateinit var status: TextView
    private lateinit var previous: Button
    private lateinit var next: Button
    private lateinit var pageButton: Button
    private lateinit var note: Button
    private lateinit var ocr: Button
    private lateinit var listen: Button
    private val speech = ReaderSpeech()
    private val speechListener = object : ReaderSpeech.Listener {
        override fun onSpeaking(active: Boolean) {
            if (::listen.isInitialized) listen.text = if (active) "Detener" else "Escuchar"
        }
        override fun onError(message: String) {
            Toast.makeText(this@PdfReaderActivity, message, Toast.LENGTH_LONG).show()
        }
        override fun onFinished() { speech.stop() }
    }
    private var service: Messenger? = null
    private var bound = false
    private var page = 0
    private var count = 0
    private var generation = 0
    private var renderedPage = -1
    private var pendingRestore = 0
    private var bookTitle = "PDF"
    private var contentHash = ""
    private var marks: List<AnnotationEntity> = emptyList()
    private var ocrSnapshot: PdfOcrSnapshot? = null
    private val repository by lazy { ReadingRepository(this) }
    private val replies by lazy { Messenger(Handler(Looper.getMainLooper()) { message -> receive(message); true }) }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            openDocument()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            failure("El lector PDF se ha detenido. Vuelve a abrir el libro para reintentarlo.")
        }
        override fun onNullBinding(name: ComponentName) { failure("No se pudo iniciar el lector PDF.") }
        override fun onBindingDied(name: ComponentName) { service = null; failure("Se perdió el lector PDF. Vuelve a abrir el libro.") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = intent.getStringExtra("book_id") ?: run { finish(); return }
        pendingRestore = savedInstanceState?.getInt("page", -1) ?: -1
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val toolbar = LinearLayout(this)
        toolbar.addView(button("Volver") { finish() })
        toolbar.addView(button("−") { image.changeZoom(0.8f) }.apply { contentDescription = "Reducir página" })
        toolbar.addView(button("+") { image.changeZoom(1.25f) }.apply { contentDescription = "Ampliar página" })
        toolbar.addView(button("Anchura") { image.fitWidth() })
        root.addView(toolbar)
        status = TextView(this).apply {
            text = "Abriendo PDF…"; setTextColor(0xffe8dfce.toInt()); setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        root.addView(status)
        image = PdfPageView(this)
        image.onMarkedTap { ids ->
            val selected = ids.mapNotNull { id -> marks.firstOrNull { it.id == id } }
            SavedAnnotationSheet.show(this, repository, selected)
        }
        root.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this)
        previous = button("Anterior") { render(page - 1) }
        next = button("Siguiente") { render(page + 1) }
        pageButton = button("Página") { goToPage() }
        note = button("Nota") {
            if (renderedPage == page) AnnotationEditor.show(this, bookId, locator(page), "", "NOTE")
        }
        nav.addView(previous, LinearLayout.LayoutParams(0, dp(56), 1f))
        nav.addView(pageButton, LinearLayout.LayoutParams(0, dp(56), 1f))
        nav.addView(next, LinearLayout.LayoutParams(0, dp(56), 1f))
        ocr = button("Texto OCR · Seleccionar") {
            if (renderedPage != page) return@button
            val bitmap = image.currentBitmap() ?: return@button
            controls(false)
            val selectedPage = page
            PdfOcrStudy(this, bookId, repository).open(selectedPage, bitmap, contentHash,
                status = { message -> if (selectedPage == page) status.text = message },
                done = { if (!isDestroyed && !isFinishing && selectedPage == page) controls(true) },
                onSnapshot = { snapshot ->
                    if (selectedPage == page && renderedPage == page) {
                        ocrSnapshot = snapshot
                        refreshHighlights()
                    }
                }
            )
        }.apply { contentDescription = "Reconocer texto local de la página y seleccionar para estudio" }
        listen = button("Escuchar") { toggleSpeech() }.apply {
            contentDescription = "Escuchar la transcripción local OCR de esta página"
        }
        val studyActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        note.text = "Nota"
        ocr.text = "Texto OCR"
        studyActions.addView(note, LinearLayout.LayoutParams(0, dp(54), 1f))
        studyActions.addView(ocr, LinearLayout.LayoutParams(0, dp(54), 1.5f))
        studyActions.addView(listen, LinearLayout.LayoutParams(0, dp(54), 1.3f))
        root.addView(nav); root.addView(studyActions)
        setContentView(root)
        window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.BLACK
        controls(false)
        lifecycleScope.launch {
            repository.observeAnnotations(bookId).collect { notes ->
                marks = notes
                refreshHighlights()
            }
        }
        bound = bindService(Intent(this, PdfRenderService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) failure("No se pudo iniciar el lector PDF.")
    }

    private fun openDocument() {
        lifecycleScope.launch {
            try {
                val book = AppDatabase.get(this@PdfReaderActivity).bookDao().findById(bookId)
                    ?: error("El libro ya no está en la biblioteca.")
                bookTitle = book.title
                contentHash = book.contentHash
                if (pendingRestore < 0) pendingRestore = parsePage(intent.getStringExtra("locator")
                    ?: repository.position(bookId)?.locator)
                withContext(Dispatchers.IO) {
                    ParcelFileDescriptor.open(File(book.localPath), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        service?.send(Message.obtain(null, PdfRenderService.OPEN).apply {
                            replyTo = replies; data = Bundle().apply { putParcelable("file", descriptor) }
                        }) ?: error("Lector no disponible.")
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { failure(error.message ?: "No se pudo abrir el archivo.") }
        }
    }
    @Suppress("DEPRECATION")
    private fun receive(message: Message) {
        when (message.what) {
            PdfRenderService.OPEN -> {
                if (isDestroyed || isFinishing) return
                count = message.data.getInt("count")
                if (count <= 0) failure("Este PDF no contiene páginas.")
                else render(pendingRestore.coerceIn(0, count - 1))
            }
            PdfRenderService.RENDER -> {
                val descriptor = message.data.getParcelable<ParcelFileDescriptor>("image") ?: return
                val requestGeneration = message.arg1
                val requestedPage = message.data.getInt("page")
                if (isDestroyed || isFinishing || requestGeneration != generation) { descriptor.close(); return }
                lifecycleScope.launch {
                    var decoded: android.graphics.Bitmap? = null
                    try {
                        withContext(Dispatchers.IO) {
                            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
                                decoded = BitmapFactory.decodeStream(it)
                            }
                        }
                        val bitmap = decoded ?: error("No se pudo mostrar la página.")
                        if (requestGeneration == generation && !isFinishing) {
                            image.display(bitmap); decoded = null
                            page = requestedPage; renderedPage = page
                            ocrSnapshot = null
                            refreshHighlights()
                            lifecycleScope.launch {
                                val savedPage = requestedPage
                                val cached = withContext(Dispatchers.IO) {
                                    PdfOcrStore.load(this@PdfReaderActivity, bookId,
                                        savedPage, contentHash)
                                }
                                if (renderedPage == savedPage && !isDestroyed) {
                                    ocrSnapshot = cached
                                    refreshHighlights()
                                }
                            }
                            status.text = "$bookTitle · ${page + 1} / $count"
                            controls(true)
                            repository.savePosition(bookId, locator(page), (page + 1).toFloat() / count)
                        }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: OutOfMemoryError) { failure("No hay memoria suficiente para mostrar esta página.") }
                    catch (error: Exception) { failure(error.message ?: "No se pudo mostrar la página.") }
                    finally { decoded?.recycle(); runCatching { descriptor.close() } }
                }
            }
            PdfRenderService.ERROR -> {
                if (message.arg1 == generation || message.arg1 == 0)
                    failure(message.data.getString("message") ?: "No se pudo leer el PDF.")
            }
        }
    }
    private fun render(index: Int) {
        if (index !in 0 until count) return
        speech.stop()
        generation++
        image.showMarks(emptyList())
        ocrSnapshot = null
        controls(false)
        status.text = "Cargando página ${index + 1}…"
        try {
            service?.send(Message.obtain(null, PdfRenderService.RENDER).apply {
                arg1 = generation; replyTo = replies
                data = Bundle().apply { putInt("page", index); putInt("width", (resources.displayMetrics.widthPixels * 1.5f).toInt()) }
            }) ?: failure("El lector no está disponible. Vuelve a abrir el libro.")
        } catch (_: RemoteException) { failure("Se perdió la conexión con el lector PDF.") }
    }

    private fun toggleSpeech() {
        if (speech.active()) { speech.stop(); return }
        val transcript = ocrSnapshot?.text?.takeIf { it.isNotBlank() }
        if (transcript == null) {
            Toast.makeText(this,
                "Selecciona «Texto OCR» para reconocer primero el texto de esta página.",
                Toast.LENGTH_LONG).show()
            return
        }
        speech.toggle(this, "Página " + (page + 1) + ". " + transcript, speechListener)
    }

    /** Reconstruct from Room; deleted annotations are excluded by the DAO. */
    private fun refreshHighlights() {
        if (!::image.isInitialized || renderedPage < 0 || contentHash.isBlank()) return
        image.showMarks(PdfOcrAnnotationMarks.forPage(
            marks, renderedPage, contentHash, ocrSnapshot
        ))
    }

    private fun controls(ready: Boolean) {
        previous.isEnabled = ready && page > 0; next.isEnabled = ready && page + 1 < count
        pageButton.isEnabled = ready; note.isEnabled = ready && renderedPage == page
        ocr.isEnabled = ready && renderedPage == page
    }
    private fun failure(message: String) {
        if (!isDestroyed) { status.text = message; controls(false) }
    }
    private fun locator(index: Int) = JSONObject().put("format", "PDF").put("page", index).toString()
    private fun parsePage(json: String?): Int = runCatching {
        val obj = JSONObject(json ?: "{}")
        if (obj.optString("format") == "PDF") obj.optInt("page", 0) else 0
    }.getOrDefault(0)
    private fun goToPage() {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "1–$count" }
        MaterialAlertDialogBuilder(this).setTitle("Ir a página").setView(input)
            .setPositiveButton("Ir") { _, _ ->
                val selected = input.text.toString().toIntOrNull()
                if (selected != null && selected in 1..count) render(selected - 1)
                else Toast.makeText(this, "Introduce una página entre 1 y $count.", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancelar", null).show()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("page", page); super.onSaveInstanceState(outState) }
    override fun onStop() {
        speech.stop()
        super.onStop()
    }
    override fun onDestroy() {
        speech.shutdown()
        generation++
        if (bound) { unbindService(connection); bound = false }
        service = null; image.clear(); super.onDestroy()
    }
    private fun button(label: String, action: () -> Unit) =
        com.google.android.material.button.MaterialButton(this).apply {
            text = label
            isAllCaps = false
            minHeight = dp(48)
            cornerRadius = dp(14)
            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.bia_surface_high))
            setTextColor(getColor(R.color.bia_text))
            strokeWidth = dp(1)
            strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.bia_accent_low))
            setOnClickListener { action() }
        }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
