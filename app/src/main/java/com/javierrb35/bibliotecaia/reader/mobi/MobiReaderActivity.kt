package com.javierrb35.bibliotecaia.reader.mobi

import com.javierrb35.bibliotecaia.R

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.text.style.BackgroundColorSpan
import android.net.Uri
import android.view.View
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.ReadingRepository
import com.javierrb35.bibliotecaia.data.AnnotationEntity
import com.javierrb35.bibliotecaia.reader.annotations.AnnotationHitSpan
import com.javierrb35.bibliotecaia.reader.annotations.MarkedTextTap
import com.javierrb35.bibliotecaia.reader.annotations.SavedAnnotationSheet
import com.javierrb35.bibliotecaia.reader.speech.ReaderSpeech
import com.javierrb35.bibliotecaia.ai.AiChatActivity
import com.javierrb35.bibliotecaia.ai.AiKeyStore
import com.javierrb35.bibliotecaia.study.DefinitionUi
import com.javierrb35.bibliotecaia.study.SelectionAnchors
import com.javierrb35.bibliotecaia.ui.AnnotationEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Native, network-free text view for the supported MOBI 6 subset. */
class MobiReaderActivity : AppCompatActivity() {
    companion object {
        fun start(context: Context, bookId: String, locatorJson: String? = null) {
            context.startActivity(Intent(context, MobiReaderActivity::class.java)
                .putExtra("book_id", bookId).putExtra("locator", locatorJson))
        }
        private const val CHUNK = 12000
        // The last reading position must survive Activity destruction. The
        // lifecycle-bound UI scope is cancelled when the screen is closed.
        private val positionWrites = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val positionMutex = Mutex()
    }
    private lateinit var bookId: String
    private lateinit var root: LinearLayout
    private lateinit var content: TextView
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var previous: Button
    private lateinit var next: Button
    private var text = ""
    private var offset = 0
    private var endOffset = 0
    private var loaded = false
    private val speech = ReaderSpeech()
    private lateinit var speechButton: com.google.android.material.button.MaterialButton
    private var automaticSpeechAdvance = false
    private val speechListener = object : ReaderSpeech.Listener {
        override fun onSpeaking(active: Boolean) {
            if (::speechButton.isInitialized) speechButton.text = if (active) "Detener" else "Escuchar"
        }
        override fun onError(message: String) {
            Toast.makeText(this@MobiReaderActivity, message, Toast.LENGTH_LONG).show()
        }
        override fun onFinished() {
            if (loaded && endOffset < text.length) {
                automaticSpeechAdvance = true
                showChunk(endOffset)
                automaticSpeechAdvance = false
                speech.continueWith(content.text.toString())
            } else speech.stop()
        }
    }
    private var annotations = emptyList<AnnotationEntity>()
    private var selectedAbsoluteOffset = 0
    private var pendingPosition: Job? = null
    @Volatile private var positionRevision = 0L
    private var touchX = 0f
    private var touchY = 0f
    private val repository by lazy { ReadingRepository(this) }
    private val preferences by lazy { getSharedPreferences("mobi_reader", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = intent.getStringExtra("book_id") ?: run { finish(); return }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = LinearLayout(this)
        toolbar.addView(button("Volver") { finish() })
        toolbar.addView(button("Buscar") { search() })
        toolbar.addView(button("Aa") { settings() })
        speechButton = button("Escuchar") { toggleSpeech() }
        toolbar.addView(speechButton)
        root.addView(toolbar)
        status = TextView(this).apply { text = "Abriendo MOBI…"; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        root.addView(status)
        content = TextView(this).apply {
            setPadding(dp(24), dp(16), dp(24), dp(32))
            typeface = Typeface.create("serif", Typeface.NORMAL)
            setLineSpacing(dp(5).toFloat(), 1.15f)
            setTextIsSelectable(true)

        }
        // Scriptura's native menu preserves Copy / Share / Select all.
        DefinitionUi.attach(this, content, aiConfigured(), object : DefinitionUi.Delegate {
            override fun openExternal(url: String) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: Exception) {
                    Toast.makeText(this@MobiReaderActivity,
                        "No se pudo abrir la fuente externa.", Toast.LENGTH_LONG).show()
                }
            }
            override fun explainWithGemini(word: String) {
                AiChatActivity.start(this@MobiReaderActivity, bookId, word, locator(selectedAbsoluteOffset))
            }
            override fun showError(message: String) {
                Toast.makeText(this@MobiReaderActivity, message, Toast.LENGTH_LONG).show()
            }
        }, DefinitionUi.SelectionHandler { quote, start, _ ->
            AnnotationEditor.show(this, bookId, locator(offset + start), quote, "NOTE")
        }, DefinitionUi.UnderlineHandler { quote, start, _ ->
            lifecycleScope.launch {
                try {
                    repository.addAnnotation(bookId, locator(offset + start), quote, "", "HIGHLIGHT")
                    Toast.makeText(this@MobiReaderActivity,
                        "Texto subrayado.", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(this@MobiReaderActivity,
                        "No se pudo guardar el subrayado.", Toast.LENGTH_LONG).show()
                }
            }
        }, DefinitionUi.FragmentHandler { quote, start, _ ->
            if (quote.length > 1200) {
                Toast.makeText(this, "Selecciona un máximo de 1.200 caracteres.",
                    Toast.LENGTH_LONG).show()
            } else AiChatActivity.start(this, bookId, quote, locator(offset + start))
        }, object : DefinitionUi.SelectionLifecycle {
            override fun onSelectionChanged(start: Int, end: Int) {
                if (start >= 0 && end > start) selectedAbsoluteOffset = offset + start
            }
            override fun onSelectionFinished() = Unit
        }, object : DefinitionUi.SavedMarkHandler {
            override fun hasSavedMark(start: Int, end: Int) =
                savedMarksWithin(start, end).isNotEmpty()

            override fun onShowSavedMark(start: Int, end: Int) {
                SavedAnnotationSheet.show(this@MobiReaderActivity, repository,
                    savedMarksWithin(start, end))
            }
        })
        MarkedTextTap.attach(content, marksAt = { textOffset ->
            // Only spans actually painted in this MOBI chunk can open a note.
            val ids = MarkedTextTap.idsAt(content.text, textOffset).toSet()
            annotations.filter { saved ->
                saved.id in ids && saved.kind in setOf("NOTE", "HIGHLIGHT")
            }
        }, onMarks = { overlapping ->
            SavedAnnotationSheet.show(this, repository, overlapping)
        })
        scroll = ScrollView(this).apply { addView(content) }
        scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            if (loaded) {
                pendingPosition?.cancel()
                pendingPosition = lifecycleScope.launch {
                    delay(850)
                    savePosition(visibleOffset())
                }
            }
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val navigation = LinearLayout(this)
        previous = button("Anterior") { showChunk((offset - CHUNK).coerceAtLeast(0)) }
        next = button("Siguiente") { showChunk(endOffset) }
        navigation.addView(previous, LinearLayout.LayoutParams(0, dp(56), 1f))
        navigation.addView(next, LinearLayout.LayoutParams(0, dp(56), 1f))
        navigation.addView(button("Nota") {
            if (loaded) AnnotationEditor.show(this, bookId, locator(visibleOffset()), "", "NOTE")
        })
        root.addView(navigation)
        setContentView(root)
        applySettings()
        previous.isEnabled = false; next.isEnabled = false
        lifecycleScope.launch {
            try {
                val book = AppDatabase.get(this@MobiReaderActivity).bookDao().findById(bookId)
                    ?: error("El libro ya no está en la biblioteca.")
                val saved = savedInstanceState?.getInt("offset") ?: parseOffset(
                    intent.getStringExtra("locator") ?: repository.position(bookId)?.locator)
                text = withContext(Dispatchers.Default) {
                    // Html's parser creates no browser and cannot execute scripts or fetch images.
                    Html.fromHtml(MobiDocument.readHtml(File(book.localPath)), Html.FROM_HTML_MODE_LEGACY)
                        .toString().trim()
                }
                if (text.isBlank()) error("No se encontró texto legible en este MOBI.")
                title = book.title
                loaded = true
                showChunk(saved.coerceIn(0, text.lastIndex))
                launch {
                    repository.observeAnnotations(bookId).collect { notes ->
                        annotations = notes
                        if (loaded) paintMarks()
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                status.text = error.message ?: "No se pudo abrir el MOBI."
            }
        }
    }

    /** The contextual Ver anotación action uses the same painted ranges as tapping. */
    private fun savedMarksWithin(start: Int, end: Int): List<AnnotationEntity> {
        val ids = MarkedTextTap.idsWithin(content.text, start, end).toSet()
        return annotations.filter {
            it.id in ids && it.deletedAt == null && it.kind in setOf("NOTE", "HIGHLIGHT")
        }
    }

    private fun toggleSpeech() {
        if (speech.active()) { speech.stop(); return }
        if (!loaded) {
            Toast.makeText(this, "El texto MOBI todavía se está preparando.", Toast.LENGTH_SHORT).show()
            return
        }
        speech.toggle(this, content.text.toString(), speechListener)
    }

    private fun showChunk(at: Int) {
        if (!loaded) return
        if (speech.active() && !automaticSpeechAdvance) speech.stop()
        offset = at.coerceIn(0, text.lastIndex)
        // Never split a UTF-16 surrogate pair between views or saved locators.
        if (offset > 0 && Character.isLowSurrogate(text[offset])) offset--
        endOffset = (offset + CHUNK).coerceAtMost(text.length)
        if (endOffset < text.length && Character.isLowSurrogate(text[endOffset])) endOffset--
        content.text = text.substring(offset, endOffset)
        scroll.scrollTo(0, 0)
        paintMarks()
        status.text = "${title} · MOBI texto · ${(offset * 100L / text.length)} %"
        previous.isEnabled = offset > 0
        next.isEnabled = endOffset < text.length
        savePosition(offset)
    }

    private fun aiConfigured() = AiKeyStore.providers().any { AiKeyStore.getKey(this, it) != null }

    /** Restore the underline at the EXACT absolute offset, never at another matching quote. */
    private fun paintMarks() {
        if (!loaded || endOffset <= offset || endOffset > text.length) return
        val rendered = SpannableString(text.substring(offset, endOffset))
        for (mark in annotations) {
            if (mark.quote.isBlank() || mark.locator == null) continue
            val at = validatedMarkOffset(mark) ?: continue
            val range = SelectionAnchors.visibleRange(text, at, mark.quote, offset, endOffset)
                ?: continue
            val start = range.first
            val end = range.last + 1
            if (mark.kind == "HIGHLIGHT") {
                rendered.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (mark.kind == "NOTE") {
                rendered.setSpan(BackgroundColorSpan(0x66b59b62),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                rendered.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else continue
            rendered.setSpan(AnnotationHitSpan(mark.id), start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val position = scroll.scrollY
        content.setText(rendered, TextView.BufferType.SPANNABLE)
        if (position != 0) scroll.post { scroll.scrollTo(0, position) }
    }

    private fun validatedMarkOffset(mark: AnnotationEntity): Int? {
        if (mark.quote.isBlank() || mark.locator == null) return null
        val at = runCatching {
            val locator = JSONObject(mark.locator)
            if (locator.optString("format") != "MOBI" || locator.optInt("textVersion", 1) != 1)
                return@runCatching null
            locator.optInt("offset", -1)
        }.getOrNull() ?: return null
        return at.takeIf { SelectionAnchors.exactMatch(text, it, mark.quote) }
    }

    private fun visibleOffset(): Int {
        val layout = content.layout ?: return offset
        val line = layout.getLineForVertical((scroll.scrollY - content.paddingTop).coerceAtLeast(0))
        return (offset + layout.getLineStart(line)).coerceAtMost(text.length)
    }
    private fun locator(at: Int) = JSONObject().put("format", "MOBI")
        .put("offset", at).put("textVersion", 1).toString()
    private fun parseOffset(json: String?): Int = runCatching {
        val obj = JSONObject(json ?: "{}")
        if (obj.optString("format") == "MOBI") obj.optInt("offset", 0) else 0
    }.getOrDefault(0)
    private fun savePosition(at: Int) {
        if (!loaded || text.isEmpty()) return
        val id = bookId
        val bounded = at.coerceIn(0, text.length)
        val location = locator(bounded)
        val progress = bounded.toFloat() / text.length
        val revision = ++positionRevision
        val repo = ReadingRepository(applicationContext)
        // UNDISPATCHED schedules this write before the Activity can finish;
        // serialized writes prevent an old chunk-start from replacing a later
        // precise scroll position.
        positionWrites.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                positionMutex.withLock {
                    if (revision == positionRevision) {
                        repo.savePosition(id, location, progress)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                android.util.Log.e("BibliotecaMobiPosition", "Unable to persist reading position", failure)
            }
        }
    }
    override fun onPause() {
        speech.stop()
        pendingPosition?.cancel()
        if (loaded) savePosition(visibleOffset())
        super.onPause()
    }
    override fun onDestroy() {
        speech.shutdown()
        pendingPosition?.cancel()
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("offset", visibleOffset()); super.onSaveInstanceState(outState)
    }
    private fun search() {
        if (!loaded) return
        val query = EditText(this).apply { hint = "Texto del libro"; setSingleLine() }
        MaterialAlertDialogBuilder(this).setTitle("Buscar en MOBI").setView(query)
            .setPositiveButton("Buscar") { _, _ ->
                val term = query.text.toString().trim()
                if (term.isEmpty()) return@setPositiveButton
                val index = text.indexOf(term, (visibleOffset() + 1).coerceAtMost(text.length), true)
                    .takeIf { it >= 0 } ?: text.indexOf(term, 0, true)
                if (index >= 0) showChunk(index)
                else Toast.makeText(this, "No se encontró el texto.", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancelar", null).show()
    }
    private fun settings() {
        val options = arrayOf("Letra más grande", "Letra más pequeña", "OLED", "Oscuro", "Sepia", "Claro")
        MaterialAlertDialogBuilder(this).setTitle("Lectura").setItems(options) { _, which ->
            val at = visibleOffset()
            if (which < 2) preferences.edit().putFloat("size",
                (preferences.getFloat("size", 20f) + if (which == 0) 2 else -2).coerceIn(14f, 36f)).apply()
            else preferences.edit().putInt("theme", which - 2).apply()
            applySettings()
            if (loaded) showChunk(at)
        }.show()
    }
    private fun applySettings() {
        val theme = preferences.getInt("theme", 0).coerceIn(0, 3)
        val backgrounds = intArrayOf(Color.BLACK, 0xff191b1d.toInt(), 0xfff4e7ce.toInt(), 0xfffaf9f5.toInt())
        val foreground = if (theme < 2) 0xffe8dfce.toInt() else 0xff302a24.toInt()
        root.setBackgroundColor(backgrounds[theme]); content.setTextColor(foreground); status.setTextColor(foreground)
        content.setHighlightColor(if (theme < 2) 0x66D4B483 else 0x55C7A264)
        content.textSize = preferences.getFloat("size", 20f)
        window.statusBarColor = backgrounds[theme]; window.navigationBarColor = backgrounds[theme]
        window.decorView.systemUiVisibility = if (theme >= 2) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
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
