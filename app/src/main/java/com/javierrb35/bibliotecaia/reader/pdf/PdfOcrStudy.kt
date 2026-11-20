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
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.javierrb35.bibliotecaia.ai.AiChatActivity
import com.javierrb35.bibliotecaia.ai.AiKeyStore
import com.javierrb35.bibliotecaia.data.ReadingRepository
import com.javierrb35.bibliotecaia.study.DefinitionUi
import com.javierrb35.bibliotecaia.ui.AnnotationEditor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

/**
 * Optional, bundled, on-device OCR of the current rendered PDF page.
 * Does not transmit the PDF and never pretends that the raster PDF has a
 * selectable native text layer. Selection and visible underlines are provided
 * in a separate, accessible transcription panel.
 */
class PdfOcrStudy(
    private val activity: AppCompatActivity,
    private val bookId: String,
    private val repository: ReadingRepository
) {
    companion object {
        private const val MAX_OCR_CHARS = 16_000
        private const val MAX_CACHED_BYTES = 64_000L
    }

    fun open(page: Int, bitmap: Bitmap, status: (String) -> Unit, done: () -> Unit) {
        val file = File(activity.filesDir, "ocr/$bookId/page-" + (page + 1) + ".txt")
        val cached = runCatching {
            if (file.isFile && file.length() in 1..MAX_CACHED_BYTES) file.readText() else null
        }.getOrNull()
        if (!cached.isNullOrBlank()) {
            showText(page, cached)
            done()
            return
        }

        status("Reconociendo texto localmente · página " + (page + 1) + "…")
        try {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { recognized ->
                    if (activity.isDestroyed || activity.isFinishing) return@addOnSuccessListener
                    val plain = recognized.text.trim().take(MAX_OCR_CHARS)
                    if (plain.isBlank()) {
                        status("No se ha detectado texto en esta página.")
                        return@addOnSuccessListener
                    }
                    // Keep the exact OCR transcript to avoid binding a repeated
                    // quote to a different position on subsequent openings.
                    val saved = runCatching {
                        file.parentFile?.mkdirs()
                        file.writeText(plain, Charsets.UTF_8)
                    }.isSuccess
                    if (saved) {
                        status("Texto local disponible · página " + (page + 1))
                        showText(page, plain)
                    } else {
                        status("Sin espacio para guardar el texto OCR. No se crearán anclajes inestables.")
                    }
                }
                .addOnFailureListener {
                    if (!activity.isDestroyed) status("No se pudo reconocer texto de esta página.")
                }
                .addOnCompleteListener {
                    recognizer.close()
                    if (!activity.isDestroyed) done()
                }
        } catch (_: Exception) {
            status("El reconocimiento de texto no está disponible.")
            done()
        }
    }

    private fun showText(page: Int, text: String) {
        if (activity.isDestroyed || activity.isFinishing) return
        val d = activity.resources.displayMetrics.density
        fun dp(value: Int) = (d * value).toInt()
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(12))
            setBackgroundColor(Color.BLACK)
        }
        panel.addView(TextView(activity).apply {
            this.text = "Texto reconocido en el dispositivo. Puede contener errores de OCR. " +
                "Selecciona para definir, anotar, subrayar o interpretar con IA."
            setTextColor(0xffe8dfce.toInt())
            textSize = 13f
        })
        val selection = TextView(activity).apply {
            setText(text)
            setTextColor(0xffe8dfce.toInt())
            textSize = 18f
            setLineSpacing(dp(4).toFloat(), 1.12f)
            setPadding(0, dp(12), 0, dp(24))
            setTextIsSelectable(true)
        }
        panel.addView(selection)
        val scroll = ScrollView(activity).apply {
            addView(panel)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (activity.resources.displayMetrics.heightPixels * 0.70f).toInt()
            )
        }
        var selectedOffset = 0
        val delegate = object : DefinitionUi.Delegate {
            override fun openExternal(url: String) {
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: Exception) {
                    toast("No se pudo abrir la fuente externa.")
                }
            }
            override fun explainWithGemini(word: String) {
                AiChatActivity.start(activity, bookId, word, PdfOcrAnchors.locator(page, selectedOffset))
            }
            override fun showError(message: String) = toast(message)
        }
        val configured = AiKeyStore.providers().any { AiKeyStore.getKey(activity, it) != null }
        DefinitionUi.attach(activity, selection, configured, delegate,
            DefinitionUi.SelectionHandler { quote, start, _ ->
                AnnotationEditor.show(activity, bookId, PdfOcrAnchors.locator(page, start), quote, "NOTE")
            },
            DefinitionUi.UnderlineHandler { quote, start, _ ->
                activity.lifecycleScope.launch {
                    try {
                        repository.addAnnotation(bookId, PdfOcrAnchors.locator(page, start),
                            quote, "", "HIGHLIGHT")
                        toast("Texto OCR subrayado.")
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { toast("No se pudo guardar el subrayado.") }
                }
            },
            DefinitionUi.FragmentHandler { quote, start, _ ->
                if (quote.length > 1200) toast("Selecciona un máximo de 1.200 caracteres.")
                else AiChatActivity.start(activity, bookId, quote, PdfOcrAnchors.locator(page, start))
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
                val spans = SpannableString(text)
                for (mark in marks) {
                    val range = PdfOcrAnchors.range(mark.locator, page, text, mark.quote) ?: continue
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
