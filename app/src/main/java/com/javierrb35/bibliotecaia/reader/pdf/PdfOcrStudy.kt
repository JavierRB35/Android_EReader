package com.javierrb35.bibliotecaia.reader.pdf

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.UnderlineSpan
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.javierrb35.bibliotecaia.ai.AiChatActivity
import com.javierrb35.bibliotecaia.ai.AiKeyStore
import com.javierrb35.bibliotecaia.data.ReadingRepository
import com.javierrb35.bibliotecaia.study.DefinitionUi
import com.javierrb35.bibliotecaia.ui.AnnotationEditor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The selected OCR transcript is local and is a companion to the PDF bitmap.
 * Word rectangles are saved with the annotation; the PDF itself is unchanged.
 */
class PdfOcrStudy(
    private val activity: AppCompatActivity,
    private val bookId: String,
    private val repository: ReadingRepository
) {
    fun open(
        page: Int, bitmap: Bitmap, contentHash: String,
        status: (String) -> Unit, done: () -> Unit,
        onSnapshot: (PdfOcrSnapshot) -> Unit
    ) {
        activity.lifecycleScope.launch {
            val cached = withContext(Dispatchers.IO) {
                PdfOcrStore.load(activity, bookId, page, contentHash)
            }
            if (cached != null) {
                if (!activity.isDestroyed) {
                    onSnapshot(cached)
                    showText(page, cached)
                }
                done()
                return@launch
            }

            status("Reconociendo texto localmente · página " + (page + 1) + "…")
            try {
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { recognized ->
                        activity.lifecycleScope.launch {
                            try {
                                val snapshot = fromRecognition(recognized, bitmap, contentHash)
                                if (snapshot == null) {
                                    status("No se encontró texto seleccionable en esta página.")
                                    return@launch
                                }
                                val saved = withContext(Dispatchers.IO) {
                                    PdfOcrStore.save(activity, bookId, page, snapshot)
                                }
                                if (saved && !activity.isDestroyed && !activity.isFinishing) {
                                    status("Texto reconocido localmente · página " + (page + 1))
                                    onSnapshot(snapshot)
                                    showText(page, snapshot)
                                } else {
                                    status("No se pudo guardar el OCR. No se crearán anclajes inestables.")
                                }
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (_: Exception) { status("Error al preparar la transcripción OCR.") }
                            finally { done() }
                        }
                    }
                    .addOnFailureListener {
                        if (!activity.isDestroyed) {
                            status("El reconocimiento de texto local ha fallado.")
                            done()
                        }
                    }
                    .addOnCompleteListener { recognizer.close() }
            } catch (_: Exception) {
                status("El reconocimiento de texto no está disponible.")
                done()
            }
        }
    }

    /** ML Kit returns coordinates in input bitmap pixels; keep normalized boxes. */
    internal fun fromRecognition(
        result: Text, bitmap: Bitmap, hash: String
    ): PdfOcrSnapshot? {
        if (bitmap.width < 1 || bitmap.height < 1) return null
        val lines = result.textBlocks.flatMap { it.lines }.map { line ->
            line.elements.mapNotNull { element ->
                val r = element.boundingBox ?: return@mapNotNull null
                val box = PdfOcrRect(
                    (r.left.toFloat() / bitmap.width).coerceIn(0f, 1f),
                    (r.top.toFloat() / bitmap.height).coerceIn(0f, 1f),
                    (r.right.toFloat() / bitmap.width).coerceIn(0f, 1f),
                    (r.bottom.toFloat() / bitmap.height).coerceIn(0f, 1f)
                )
                if (box.valid()) element.text to box else null
            }
        }
        return PdfOcrGeometry.fromLines(lines, hash)
    }

    private fun showText(page: Int, snapshot: PdfOcrSnapshot) {
        if (activity.isDestroyed || activity.isFinishing) return
        val content = snapshot.text
        val dpScale = activity.resources.displayMetrics.density
        fun dp(value: Int) = (dpScale * value).toInt()
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(12))
            setBackgroundColor(Color.BLACK)
        }
        panel.addView(TextView(activity).apply {
            text = "Transcripción OCR local: puede contener errores. " +
                "Los subrayados se mostrarán también sobre la página PDF."
            setTextColor(0xffe8dfce.toInt())
            textSize = 13f
        })
        val selection = TextView(activity).apply {
            text = content
            setTextColor(0xffe8dfce.toInt())
            textSize = 18f
            setLineSpacing(dp(4).toFloat(), 1.12f)
            setPadding(0, dp(12), 0, dp(24))
            setTextIsSelectable(true)
        }
        panel.addView(selection)
        val scroll = ScrollView(activity).apply {
            addView(panel)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (activity.resources.displayMetrics.heightPixels * 0.70f).toInt())
        }

        var selectedOffset = 0
        fun anchor(start: Int, quote: String): String? =
            PdfOcrAnchors.anchoredLocator(page, start, quote, snapshot)
        fun invalidSelection() =
            toast("No se han podido localizar las coordenadas exactas de este fragmento.")

        val delegate = object : DefinitionUi.Delegate {
            override fun openExternal(url: String) {
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: Exception) { toast("No se pudo abrir la fuente externa.") }
            }
            override fun explainWithGemini(word: String) {
                val locator = anchor(selectedOffset, word)
                if (locator == null) invalidSelection()
                else AiChatActivity.start(activity, bookId, word, locator)
            }
            override fun showError(message: String) = toast(message)
        }
        val configured = AiKeyStore.providers().any {
            AiKeyStore.getKey(activity, it) != null
        }
        DefinitionUi.attach(activity, selection, configured, delegate,
            DefinitionUi.SelectionHandler { quote, start, _ ->
                val locator = anchor(start, quote)
                if (locator == null) invalidSelection()
                else AnnotationEditor.show(activity, bookId, locator, quote, "NOTE")
            },
            DefinitionUi.UnderlineHandler { quote, start, _ ->
                val locator = anchor(start, quote)
                if (locator == null) invalidSelection()
                else activity.lifecycleScope.launch {
                    try {
                        repository.addAnnotation(bookId, locator, quote, "", "HIGHLIGHT")
                        toast("Subrayado guardado sobre PDF.")
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { toast("No se pudo guardar el subrayado.") }
                }
            },
            DefinitionUi.FragmentHandler { quote, start, _ ->
                if (quote.length > 1200) toast("Selecciona un máximo de 1.200 caracteres.")
                else {
                    val locator = anchor(start, quote)
                    if (locator == null) invalidSelection()
                    else AiChatActivity.start(activity, bookId, quote, locator)
                }
            },
            object : DefinitionUi.SelectionLifecycle {
                override fun onSelectionChanged(start: Int, end: Int) {
                    if (start >= 0 && end > start) selectedOffset = start
                }
                override fun onSelectionFinished() = Unit
            }
        )

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Texto OCR · página " + (page + 1))
            .setView(scroll)
            .setNegativeButton("Cerrar", null)
            .create()
        dialog.show()
        val observe = activity.lifecycleScope.launch {
            repository.observeAnnotations(bookId).collect { marks ->
                if (!dialog.isShowing) return@collect
                val spans = SpannableString(content)
                for (mark in marks) {
                    if (PdfOcrAnchors.overlayBoxes(mark.locator, page, mark.quote,
                            snapshot.contentHash, snapshot).isEmpty()) continue
                    val range = PdfOcrAnchors.range(mark.locator, page, content, mark.quote)
                        ?: continue
                    if (mark.kind == "HIGHLIGHT") {
                        spans.setSpan(UnderlineSpan(), range.first, range.last + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else if (mark.kind == "NOTE") {
                        spans.setSpan(BackgroundColorSpan(0xffb59b62.toInt()),
                            range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                selection.setText(spans, TextView.BufferType.SPANNABLE)
            }
        }
        dialog.setOnDismissListener { observe.cancel() }
    }

    private fun toast(message: String) =
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
}
