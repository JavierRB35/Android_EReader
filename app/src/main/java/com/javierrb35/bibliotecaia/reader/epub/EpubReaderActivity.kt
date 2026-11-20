@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.javierrb35.bibliotecaia.reader.epub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.text.style.BackgroundColorSpan
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import android.os.Bundle
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.javierrb35.bibliotecaia.R
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.AnnotationEntity
import com.javierrb35.bibliotecaia.data.ReadingRepository
import com.javierrb35.bibliotecaia.ai.AiChatActivity
import com.javierrb35.bibliotecaia.ai.AiKeyStore
import com.javierrb35.bibliotecaia.study.DefinitionUi
import com.javierrb35.bibliotecaia.study.WordMeaning
import com.javierrb35.bibliotecaia.ui.AnnotationEditor
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.Color as ReaderColor
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.getOrElse
import java.io.File

/** Offline EPUB reading using Readium; original files are never rewritten. */
class EpubReaderActivity : AppCompatActivity(), EpubNavigatorFragment.Listener {
    companion object {
        private const val BOOK = "book_id"
        private const val LOCATION = "locator"
        private val saves = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val saveMutex = Mutex()
        fun start(context: Context, bookId: String, locatorJson: String? = null) {
            context.startActivity(Intent(context, EpubReaderActivity::class.java)
                .putExtra(BOOK, bookId).putExtra(LOCATION, locatorJson))
        }
    }

    private lateinit var bookId: String
    private val repository by lazy { ReadingRepository(applicationContext) }
    private val preferences by lazy { getSharedPreferences("epub_reader", MODE_PRIVATE) }
    private var publication: Publication? = null
    private var navigator: EpubNavigatorFragment? = null
    private var pendingSave: Job? = null
    private var searchJob: Job? = null
    private var latest: Locator? = null
    private var annotations = emptyList<AnnotationEntity>()
    private lateinit var root: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var footer: LinearLayout
    private lateinit var positionLabel: TextView
    private lateinit var loading: TextView
    private lateinit var webFrame: FrameLayout
    private lateinit var nativeScroll: ScrollView
    private lateinit var nativeText: TextView
    private lateinit var nativeToggle: MaterialButton
    private var nativeMode = true
    private var nativeChapter: NativeEpubText.Chapter? = null
    private var nativeIndex = 0
    private var nativeLoading: Job? = null
    private var nativeSave: Job? = null
    private var nativeTouchX = 0f
    private var nativeTouchY = 0f
    private var nativeGeneration = 0
    private var themeIndex = 0
    private var fontScale = 1.1f
    private var scroll = false
    private var serif = true

    override fun onCreate(savedInstanceState: Bundle?) {
        // Publication opens asynchronously. Do not ask FragmentManager to restore a navigator
        // without its publication factory after process death; restore our persisted Locator instead.
        super.onCreate(null)
        bookId = intent.getStringExtra(BOOK) ?: run { finish(); return }
        themeIndex = preferences.getInt("theme", 0).coerceIn(0, 3)
        fontScale = preferences.getFloat("font", 1.1f).coerceIn(.75f, 2f)
        scroll = preferences.getBoolean("scroll", false)
        serif = preferences.getBoolean("serif", true)
        nativeMode = preferences.getBoolean("native_selection", true)
        createLayout()
        val restored = savedInstanceState?.getString(LOCATION) ?: intent.getStringExtra(LOCATION)
        lifecycleScope.launch {
            try {
                val book = AppDatabase.get(this@EpubReaderActivity).bookDao().findById(bookId)
                    ?: error("El libro ya no está en la biblioteca.")
                val savedLocator = restored ?: repository.position(bookId)?.locator
                val initial = parseLocator(savedLocator)
                val opened = EpubOpener.open(applicationContext, File(book.localPath))
                publication = opened
                if (opened.isRestricted) error("Este EPUB está protegido y no se puede abrir.")
                val fragment = lifecycle.withResumed {
                    supportFragmentManager.fragmentFactory = EpubNavigatorFactory(opened).createFragmentFactory(
                        initialLocator = initial, initialPreferences = readerPreferences(), listener = this@EpubReaderActivity,
                        configuration = EpubNavigatorFragment.Configuration(selectionActionModeCallback = selectionCallback)
                    )
                    val created = supportFragmentManager.fragmentFactory.instantiate(classLoader, EpubNavigatorFragment::class.java.name)
                        as EpubNavigatorFragment
                    supportFragmentManager.beginTransaction().replace(R.id.epub_navigator, created).commitNow()
                    created
                }
                navigator = fragment
                // Decorations are interactive: tapping a saved NOTE opens
                // the existing edit/delete dialog instead of leaving an
                // unresponsive coloured fragment in the Readium WebView.
                fragment.addDecorationListener("annotations",
                    object : DecorableNavigator.Listener {
                        override fun onDecorationActivated(
                            event: DecorableNavigator.OnActivatedEvent
                        ): Boolean {
                            val mark = annotations.firstOrNull {
                                it.id == event.decoration.id
                            } ?: return false
                            showSavedTextMark(mark)
                            return true
                        }
                    })
                loading.visibility = View.GONE
                if (nativeMode) {
                    val initialIndex = opened.readingOrder.indexOfFirst {
                        initial?.href?.toString() == it.url().toString()
                    }.coerceAtLeast(0)
                    val initialOffset = NativeEpubAnchors.point(savedLocator)?.offset ?: 0
                    showNativeChapter(initialIndex, initialOffset)
                } else {
                    showReadingSurface()
                }
                fragment.addInputListener(DirectionalNavigationAdapter(fragment))
                fragment.addInputListener(object : InputListener {
                    override fun onTap(event: TapEvent): Boolean {
                        val visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                        controls.visibility = visibility
                        footer.visibility = visibility
                        return true
                    }
                })
                launch {
                    fragment.currentLocator.collect { locator ->
                        if (!nativeMode) {
                            latest = locator
                            positionLabel.text = "${((locator.locations.totalProgression ?: 0.0) * 100).toInt()} %"
                            pendingSave?.cancel()
                            pendingSave = lifecycleScope.launch {
                                delay(900)
                                try { persist(locator) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { toast("No se pudo guardar la posición. Comprueba el espacio disponible.") }
                            }
                        }
                    }
                }
                launch {
                    repository.observeAnnotations(bookId).collect { saved ->
                        annotations = saved
                        if (nativeMode && nativeChapter != null) paintNativeMarks()
                        // Readium needs a visual decoration for NOTE as well as
                        // HIGHLIGHT. Notes from the native EPUB text surface are
                        // kept separate because their offsets refer to normalized text.
                        fragment.applyDecorations(EpubAnnotationMarks.forReadium(saved), "annotations")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                loading.visibility = View.VISIBLE
                loading.text = failure.message ?: "No se ha podido abrir este EPUB. El original se conserva."
            }
        }
    }

    private fun createLayout() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val heading = LinearLayout(this)
        heading.addView(button("Volver") { finish() })
        heading.addView(TextView(this).apply {
            text = "LEER"; textSize = 14f; setTextColor(Color.parseColor("#D4B483"))
            gravity = android.view.Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, 52.dp, 1f))
        controls.addView(heading)
        val actions = LinearLayout(this)
        actions.addView(button(getString(R.string.epub_contents)) { showContents() })
        actions.addView(button(getString(R.string.epub_search)) { search() })
        actions.addView(button(getString(R.string.epub_settings)) { settings() })
        actions.addView(button("Notas") { showAnnotations() })
        nativeToggle = button(if (nativeMode) "Maquetación" else "Selección precisa") {
            setNativeMode(!nativeMode)
        }
        actions.addView(nativeToggle)
        controls.addView(HorizontalScrollView(this).apply { addView(actions); isHorizontalScrollBarEnabled = false })
        root.addView(controls)
        val frame = FrameLayout(this)
        webFrame = FrameLayout(this).apply { id = R.id.epub_navigator }
        frame.addView(webFrame, FrameLayout.LayoutParams(-1, -1))
        nativeText = TextView(this).apply {
            id = R.id.epub_native_text
            setPadding(24.dp, 24.dp, 24.dp, 40.dp)
            setTextIsSelectable(true)
            isLongClickable = true
            setHighlightColor(0x66D4B483)
            typeface = Typeface.SERIF
            setLineSpacing(6.dp.toFloat(), 1.1f)
            textSize = 22f
        }
        nativeScroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            visibility = View.GONE
            addView(nativeText)
        }
        frame.addView(nativeScroll, FrameLayout.LayoutParams(-1, -1))
        attachNativeSelection()
        loading = TextView(this).apply {
            text = getString(R.string.epub_opening); textSize = 18f; gravity = android.view.Gravity.CENTER
            setPadding(24.dp, 24.dp, 24.dp, 24.dp)
        }
        frame.addView(loading, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        footer = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        footer.addView(button("‹") {
            if (nativeMode) showNativeChapter(nativeIndex - 1) else navigator?.goBackward()
        }.apply { contentDescription = getString(R.string.epub_previous) })
        positionLabel = TextView(this).apply { text = "0 %"; gravity = android.view.Gravity.CENTER }
        footer.addView(positionLabel, LinearLayout.LayoutParams(0, 48.dp, 1f))
        footer.addView(button(getString(R.string.epub_bookmark)) {
            val locator = if (nativeMode) nativeLocator(visibleNativeOffset(), "")
                else latest?.toJSON()?.toString()
            locator?.let { AnnotationEditor.show(this, bookId, it, "", "BOOKMARK") }
        })
        footer.addView(button("›") {
            if (nativeMode) showNativeChapter(nativeIndex + 1) else navigator?.goForward()
        }.apply { contentDescription = getString(R.string.epub_next) })
        root.addView(footer)
        setContentView(root)
        applyTheme()
    }


    /** Scriptura's exact native TextView selection mechanism, not Readium WebView handles. */
    private fun attachNativeSelection() {
        DefinitionUi.attach(this, nativeText, aiConfigured(), object : DefinitionUi.Delegate {
            override fun openExternal(url: String) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: Exception) { toast("No se pudo abrir la fuente externa.") }
            }
            override fun explainWithGemini(word: String) {
                val locator = nativeLocator(lastNativeSelectionStart, word)
                AiChatActivity.start(this@EpubReaderActivity, bookId, word, locator)
            }
            override fun showError(message: String) = toast(message)
        }, DefinitionUi.SelectionHandler { quote, start, _ ->
            val locator = nativeLocator(start, quote)
            if (locator != null) AnnotationEditor.show(this, bookId, locator, quote, "NOTE")
            else toast("No hay un capítulo de lectura disponible.")
        }, DefinitionUi.UnderlineHandler { quote, start, _ ->
            val locator = nativeLocator(start, quote)
            if (locator == null) toast("No se ha podido anclar el subrayado.")
            else lifecycleScope.launch {
                try {
                    repository.addAnnotation(bookId, locator, quote, "", "HIGHLIGHT")
                    toast("Texto subrayado.")
                } catch (_: Exception) { toast("No se pudo guardar el subrayado.") }
            }
        }, DefinitionUi.FragmentHandler { quote, start, _ ->
            if (quote.length > 1200) toast("Selecciona un máximo de 1.200 caracteres.")
            else {
                val locator = nativeLocator(start, quote)
                if (locator != null) AiChatActivity.start(this, bookId, quote, locator)
            }
        }, object : DefinitionUi.SelectionLifecycle {
            override fun onSelectionChanged(start: Int, end: Int) {
                if (start >= 0 && end > start) lastNativeSelectionStart = start
            }
            override fun onSelectionFinished() = Unit
        })

        // Short taps on a saved underline open its detail, but never consume
        // long-press, drag or the native text selection handles.
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        nativeText.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    nativeTouchX = event.x
                    nativeTouchY = event.y
                    false
                }
                MotionEvent.ACTION_UP -> {
                    val quickTap = event.eventTime - event.downTime <
                        ViewConfiguration.getLongPressTimeout() / 2 &&
                        abs(event.x - nativeTouchX) <= slop &&
                        abs(event.y - nativeTouchY) <= slop
                    if (!quickTap) false else {
                        val chapter = nativeChapter
                        val at = nativeText.getOffsetForPosition(event.x, event.y)
                        val mark = if (chapter == null) null else annotations.firstOrNull {
                            val range = NativeEpubAnchors.validatedRange(
                                it.locator, it.quote, chapter.href, chapter.text)
                            range != null && at in range
                        }
                        if (mark == null) false else {
                            showSavedTextMark(mark)
                            true
                        }
                    }
                }
                else -> false
            }
        }
        nativeScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            if (nativeMode && nativeChapter != null) {
                updateNativeProgress()
                scheduleNativeSave()
            }
        }
    }

    private var lastNativeSelectionStart = 0

    /** Toggle between precise native text selection and Readium's rich EPUB layout. */
    private fun setNativeMode(enabled: Boolean) {
        if (nativeMode == enabled || publication == null) return
        if (!enabled) {
            nativeSave?.cancel()
            saveNativePosition(visibleNativeOffset())
        }
        nativeMode = enabled
        preferences.edit().putBoolean("native_selection", enabled).apply()
        nativeToggle.text = if (enabled) "Maquetación" else "Selección precisa"
        if (enabled) {
            val pub = publication ?: return
            val index = pub.readingOrder.indexOfFirst {
                latest?.href?.toString() == it.url().toString()
            }
            showNativeChapter(if (index >= 0) index else nativeIndex,
                if (index < 0) visibleNativeOffset() else 0)
        } else {
            nativeLoading?.cancel()
            showReadingSurface()
            val chapter = nativeChapter
            if (chapter != null) {
                // Navigate to the same chapter, not a false CFI derived from
                // TextView offsets (the DOM is different after sanitization).
                val link = publication?.readingOrder?.getOrNull(nativeIndex)
                if (link != null) navigator?.go(link)
            }
        }
    }

    private fun showReadingSurface() {
        webFrame.visibility = if (nativeMode) View.GONE else View.VISIBLE
        nativeScroll.visibility = if (nativeMode) View.VISIBLE else View.GONE
        nativeToggle.text = if (nativeMode) "Maquetación" else "Selección precisa"
    }

    private fun showNativeChapter(index: Int, requestedOffset: Int = 0, findQuote: String? = null) {
        val pub = publication ?: return
        val link = pub.readingOrder.getOrNull(index) ?: return
        nativeLoading?.cancel()
        val generation = ++nativeGeneration
        nativeLoading = lifecycleScope.launch {
            loading.visibility = View.VISIBLE
            loading.text = "Preparando capítulo para selección precisa…"
            try {
                val chapter = NativeEpubText.load(pub, link)
                if (!isActive || generation != nativeGeneration || !nativeMode) return@launch
                nativeIndex = index
                nativeChapter = chapter
                paintNativeMarks()
                val searchOffset = findQuote?.let {
                    chapter.text.indexOf(it, ignoreCase = true)
                }?.takeIf { it >= 0 }
                val offset = (searchOffset ?: requestedOffset).coerceIn(0, chapter.text.length)
                nativeScroll.post {
                    val layout = nativeText.layout
                    if (layout != null) {
                        nativeScroll.scrollTo(0,
                            (layout.getLineTop(layout.getLineForOffset(offset)) -
                                nativeText.paddingTop).coerceAtLeast(0))
                    }
                    updateNativeProgress()
                }
                showReadingSurface()
                loading.visibility = View.GONE
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation != nativeGeneration) return@launch
                // A chapter consisting only of images/complex content must
                // remain viewable in Readium without mutating the EPUB.
                nativeMode = false
                preferences.edit().putBoolean("native_selection", false).apply()
                showReadingSurface()
                loading.visibility = View.GONE
                navigator?.go(link)
                toast("Este capítulo requiere la maquetación original.")
            }
        }
    }

    private fun nativeProgress(offset: Int): Double {
        val size = publication?.readingOrder?.size ?: return 0.0
        if (size == 0) return 0.0
        val length = nativeChapter?.text?.length ?: 0
        return ((nativeIndex + if (length > 0) offset.coerceIn(0, length).toDouble() / length
            else 0.0) / size).coerceIn(0.0, 1.0)
    }

    private fun nativeLocator(offset: Int, quote: String): String? {
        val chapter = nativeChapter ?: return null
        if (offset !in 0..chapter.text.length ||
            offset + quote.length > chapter.text.length ||
            (quote.isNotEmpty() && !chapter.text.regionMatches(offset, quote, 0, quote.length))
        ) return null
        return NativeEpubAnchors.locator(chapter.href, chapter.title,
            offset, chapter.text.length, nativeProgress(offset), quote)
    }

    private fun visibleNativeOffset(): Int {
        val chapter = nativeChapter ?: return 0
        val layout = nativeText.layout ?: return 0
        val line = layout.getLineForVertical(
            (nativeScroll.scrollY - nativeText.paddingTop).coerceAtLeast(0))
        return layout.getLineStart(line).coerceIn(0, chapter.text.length)
    }

    private fun updateNativeProgress() {
        val chapter = nativeChapter ?: return
        val indexLabel = "${nativeIndex + 1}/${publication?.readingOrder?.size ?: 1}"
        val percent = (nativeProgress(visibleNativeOffset()) * 100).toInt()
        positionLabel.text = "${chapter.title.take(42)} · $indexLabel · $percent %"
    }

    private fun scheduleNativeSave() {
        nativeSave?.cancel()
        nativeSave = lifecycleScope.launch {
            delay(850)
            saveNativePosition(visibleNativeOffset())
        }
    }

    private fun saveNativePosition(offset: Int) {
        val locator = nativeLocator(offset, "") ?: return
        val progress = nativeProgress(offset).toFloat()
        val id = bookId
        saves.launch {
            try { saveMutex.withLock { repository.savePosition(id, locator, progress) } }
            catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    toast("No se pudo guardar la posición.")
                }
            }
        }
    }

    private fun paintNativeMarks() {
        val chapter = nativeChapter ?: return
        val text = SpannableString(chapter.text)
        for (note in annotations) {
            val range = NativeEpubAnchors.validatedRange(
                note.locator, note.quote, chapter.href, chapter.text) ?: continue
            if (note.kind == "HIGHLIGHT") {
                text.setSpan(UnderlineSpan(), range.first, range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (note.kind == "NOTE") {
                text.setSpan(BackgroundColorSpan(0x66B59B62), range.first,
                    range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(UnderlineSpan(), range.first, range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        nativeText.setText(text, TextView.BufferType.SPANNABLE)
    }

    private fun showSavedTextMark(note: AnnotationEntity) {
        val choices = if (note.kind == "HIGHLIGHT")
            arrayOf("Quitar subrayado", "Cerrar")
        else arrayOf("Editar", "Eliminar", "Cerrar")
        MaterialAlertDialogBuilder(this)
            .setTitle(if (note.kind == "HIGHLIGHT") "Subrayado" else "Anotación")
            .setMessage(note.body.ifBlank { note.quote }.take(1200))
            .setItems(choices) { _, index ->
                when {
                    note.kind == "HIGHLIGHT" && index == 0 -> lifecycleScope.launch {
                        try { repository.setDeleted(note.id, true) }
                        catch (_: Exception) { toast("No se pudo quitar el subrayado.") }
                    }
                    note.kind != "HIGHLIGHT" && index == 0 -> AnnotationEditor.edit(this, note)
                    note.kind != "HIGHLIGHT" && index == 1 ->
                        MaterialAlertDialogBuilder(this)
                            .setMessage("¿Eliminar esta anotación?")
                            .setNegativeButton("Cancelar", null)
                            .setPositiveButton("Eliminar") { _, _ ->
                                lifecycleScope.launch {
                                    try { repository.setDeleted(note.id, true) }
                                    catch (_: Exception) { toast("No se pudo eliminar.") }
                                }
                            }.show()
                }
            }.show()
    }

    private fun button(label: String, action: () -> Unit) = MaterialButton(this, null,
        com.google.android.material.R.attr.borderlessButtonStyle).apply {
        text = label; isAllCaps = false; minWidth = 48.dp; minimumWidth = 48.dp; minHeight = 48.dp
        setTextColor(Color.parseColor("#D4B483")); setOnClickListener { action() }
    }

    private fun palette(): Pair<Int, Int> = when (themeIndex) {
        1 -> Color.parseColor("#181A1B") to Color.parseColor("#E8DFCE")
        2 -> Color.parseColor("#F1E5CC") to Color.parseColor("#332B20")
        3 -> Color.parseColor("#FAF8F2") to Color.parseColor("#24221F")
        else -> Color.BLACK to Color.parseColor("#E8DFCE")
    }

    private fun readerPreferences(): EpubPreferences {
        val (background, text) = palette()
        return EpubPreferences(backgroundColor = ReaderColor(background), textColor = ReaderColor(text),
            theme = if (themeIndex <= 1) Theme.DARK else if (themeIndex == 2) Theme.SEPIA else Theme.LIGHT,
            fontSize = fontScale.toDouble(), fontFamily = FontFamily(if (serif) "serif" else "sans-serif"),
            publisherStyles = false, lineHeight = 1.55, pageMargins = 1.2, scroll = scroll)
    }

    private fun applyTheme() {
        val (background, text) = palette()
        root.setBackgroundColor(background); loading.setTextColor(text); positionLabel.setTextColor(text)
        nativeScroll.setBackgroundColor(background)
        nativeText.setBackgroundColor(background)
        nativeText.setTextColor(text)
        nativeText.setHighlightColor(if (themeIndex <= 1) 0x66D4B483 else 0x55C7A264)
        nativeText.textSize = 20f * fontScale
        nativeText.typeface = if (serif) Typeface.SERIF else Typeface.SANS_SERIF
        controls.setBackgroundColor(Color.parseColor("#17191C")); footer.setBackgroundColor(Color.parseColor("#17191C"))
        positionLabel.setTextColor(Color.parseColor("#E8DFCE"))
        window.statusBarColor = background; window.navigationBarColor = Color.BLACK
        navigator?.submitPreferences(readerPreferences())
    }

    private fun settings() {
        MaterialAlertDialogBuilder(this).setTitle("Tu lectura").setItems(arrayOf(
            "Tema: ${arrayOf("OLED", "Oscuro", "Sepia", "Claro")[themeIndex]}",
            "Letra más grande", "Letra más pequeña", if (serif) "Tipografía sin remates" else "Tipografía con remates",
            if (scroll) "Cambiar a páginas" else "Desplazamiento vertical"
        )) { _, which ->
            when (which) {
                0 -> { MaterialAlertDialogBuilder(this).setTitle("Tema de lectura")
                    .setSingleChoiceItems(arrayOf("OLED · negro puro", "Oscuro suave", "Sepia", "Claro"), themeIndex) { dialog, index ->
                        themeIndex = index; savePreferences(); dialog.dismiss()
                    }.show(); return@setItems }
                1 -> fontScale = (fontScale + .1f).coerceAtMost(2f)
                2 -> fontScale = (fontScale - .1f).coerceAtLeast(.75f)
                3 -> serif = !serif
                4 -> scroll = !scroll
            }
            savePreferences()
        }.show()
    }

    private fun savePreferences() {
        preferences.edit().putInt("theme", themeIndex).putFloat("font", fontScale)
            .putBoolean("scroll", scroll).putBoolean("serif", serif).apply()
        applyTheme()
    }

    private fun showContents() {
        val pub = publication ?: return
        val links = mutableListOf<Pair<Link, String>>()
        fun add(items: List<Link>, depth: Int = 0) {
            if (depth > 20 || links.size >= 2000) return
            items.take(2000 - links.size).forEach { link ->
                links += link to ("  ".repeat(depth) + (link.title ?: "Capítulo ${links.size + 1}"))
                add(link.children, depth + 1)
            }
        }
        add(pub.tableOfContents.ifEmpty { pub.readingOrder })
        MaterialAlertDialogBuilder(this).setTitle(R.string.epub_contents).setItems(links.map { it.second }.toTypedArray()) {
            _, index ->
            val selected = links[index].first
            val selectedHref = selected.url().toString().substringBefore('#')
            val chapter = pub.readingOrder.indexOfFirst {
                it.url().toString().substringBefore('#') == selectedHref
            }
            if (nativeMode && chapter >= 0) showNativeChapter(chapter)
            else navigator?.go(selected)
        }.setNegativeButton("Cerrar", null).show()
    }

    private fun showAnnotations() {
        if (annotations.isEmpty()) { toast("Aún no tienes notas ni marcadores en este libro."); return }
        MaterialAlertDialogBuilder(this).setTitle("Mis subrayados y anotaciones")
            .setItems(annotations.map { note ->
                val kind = when (note.kind) {
                    "HIGHLIGHT" -> "Subrayado"
                    "BOOKMARK" -> "Marcador"
                    else -> "Nota"
                }
                kind + " · " + (note.quote.ifBlank { note.body.ifBlank { "Punto de lectura" } }).take(100)
            }.toTypedArray()) { _, index ->
                val note = annotations[index]
                val choices = if (note.kind == "HIGHLIGHT")
                    arrayOf("Ir al texto", "Quitar subrayado")
                else arrayOf("Ir al texto", "Editar nota", "Eliminar anotación")
                MaterialAlertDialogBuilder(this).setTitle(note.quote.ifBlank { "Anotación" })
                    .setItems(choices) { _, selected ->
                        when (selected) {
                            0 -> {
                                val point = NativeEpubAnchors.point(note.locator)
                                val pub = publication
                                val chapterIndex = pub?.readingOrder?.indexOfFirst {
                                    it.url().toString() == point?.href
                                } ?: -1
                                if (nativeMode && point != null && chapterIndex >= 0)
                                    showNativeChapter(chapterIndex, point.offset)
                                else parseLocator(note.locator)?.let { navigator?.go(it) }
                            }
                            1 -> if (note.kind == "HIGHLIGHT") {
                                lifecycleScope.launch {
                                    try { repository.setDeleted(note.id, true) }
                                    catch (_: Exception) { toast("No se pudo quitar el subrayado.") }
                                }
                            } else AnnotationEditor.edit(this, note)
                            2 -> MaterialAlertDialogBuilder(this)
                                .setTitle("Eliminar anotación")
                                .setMessage("¿Quieres eliminar esta anotación? El libro original no cambiará.")
                                .setNegativeButton("Cancelar", null)
                                .setPositiveButton("Eliminar") { _, _ ->
                                    lifecycleScope.launch {
                                        try { repository.setDeleted(note.id, true) }
                                        catch (_: Exception) { toast("No se pudo eliminar la anotación.") }
                                    }
                                }.show()
                        }
                    }.setNegativeButton("Cerrar", null).show()
            }.setNegativeButton("Cerrar", null).show()
    }

    private fun search() {
        val pub = publication ?: return
        val input = EditText(this).apply { hint = "Palabra o frase"; maxLines = 2 }
        MaterialAlertDialogBuilder(this).setTitle("Buscar en el libro").setView(input)
            .setPositiveButton("Buscar") { _, _ ->
                val query = input.text.toString().trim().take(200)
                if (query.isEmpty()) return@setPositiveButton
                searchJob?.cancel()
                val progress = MaterialAlertDialogBuilder(this).setMessage("Buscando…")
                    .setNegativeButton("Cancelar") { _, _ -> searchJob?.cancel() }.create()
                progress.setOnCancelListener { searchJob?.cancel() }; progress.show()
                searchJob = lifecycleScope.launch {
                    try {
                        val results = withContext(Dispatchers.Default) {
                            val iterator = pub.search(query) ?: error("Este libro no permite búsquedas.")
                            val found = mutableListOf<Locator>()
                            try {
                                while (found.size < 100) {
                                    ensureActive()
                                    val page = iterator.next().getOrElse { error("No se ha podido completar la búsqueda.") } ?: break
                                    found += page.locators.take(100 - found.size)
                                }
                            } finally { iterator.close() }
                            found
                        }
                        progress.dismiss()
                        if (results.isEmpty()) toast("No se han encontrado coincidencias.")
                        else MaterialAlertDialogBuilder(this@EpubReaderActivity).setTitle("Resultados (máximo 100)")
                            .setItems(results.map { (it.text.highlight ?: it.title ?: "Coincidencia").take(120) }.toTypedArray()) { _, i ->
                                val result = results[i]
                                val chapter = pub.readingOrder.indexOfFirst {
                                    it.url().toString().substringBefore('#') ==
                                        result.href.toString().substringBefore('#')
                                }
                                if (nativeMode && chapter >= 0)
                                    showNativeChapter(chapter, findQuote = result.text.highlight)
                                else navigator?.go(result)
                            }.setNegativeButton("Cerrar", null).show()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { toast(error.message ?: "La búsqueda no se ha podido completar.") }
                    finally { progress.dismiss() }
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun aiConfigured() = AiKeyStore.providers().any { AiKeyStore.getKey(this, it) != null }

    private fun definitionDelegate(locator: String): DefinitionUi.Delegate =
        object : DefinitionUi.Delegate {
            override fun openExternal(url: String) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: Exception) {
                    toast("No se pudo abrir la fuente externa.")
                }
            }

            override fun explainWithGemini(word: String) {
                AiChatActivity.start(this@EpubReaderActivity, bookId, word, locator)
            }

            override fun showError(message: String) = toast(message)
        }

    /** The native copy/share/select-all actions are intentionally preserved. */
    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            DefinitionUi.populateSelectionMenu(menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            DefinitionUi.populateSelectionMenu(menu)
            return true
        }

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            if (item == null || !DefinitionUi.isStudyAction(item.itemId)) return false
            lifecycleScope.launch {
                try {
                    // Capture the Readium selection BEFORE closing its ActionMode.
                    val selection = navigator?.currentSelection()
                    val quote = selection?.locator?.text?.highlight.orEmpty()
                    val locator = selection?.locator?.toJSON()?.toString()
                    if (locator == null || quote.isBlank()) {
                        toast("Selecciona un fragmento válido.")
                        return@launch
                    }
                    when {
                        DefinitionUi.isDefinition(item.itemId) -> {
                            if (!WordMeaning.oneWord(quote)) {
                                toast("Selecciona una sola palabra para consultar su significado.")
                            } else DefinitionUi.show(this@EpubReaderActivity, quote,
                                aiConfigured(), definitionDelegate(locator))
                        }
                        DefinitionUi.isAnnotate(item.itemId) ->
                            AnnotationEditor.show(this@EpubReaderActivity, bookId, locator,
                                quote, "NOTE")
                        DefinitionUi.isUnderline(item.itemId) -> {
                            repository.addAnnotation(bookId, locator, quote, "", "HIGHLIGHT")
                            toast("Texto subrayado.")
                        }
                        DefinitionUi.isExplain(item.itemId) -> {
                            if (quote.length > 1200) toast("Selecciona un máximo de 1.200 caracteres.")
                            else AiChatActivity.start(this@EpubReaderActivity, bookId, quote, locator)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    toast("No se pudo completar la acción sobre el texto.")
                } finally {
                    mode?.finish()
                }
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode?) {}
    }

    override fun onExternalLinkActivated(url: AbsoluteUrl) { toast("Los enlaces externos están desactivados durante la lectura.") }
    override fun onSaveInstanceState(outState: Bundle) {
        val locator = if (nativeMode) nativeLocator(visibleNativeOffset(), "")
            else latest?.toJSON()?.toString()
        locator?.let { outState.putString(LOCATION, it) }
        super.onSaveInstanceState(outState)
    }
    override fun onStop() {
        pendingSave?.cancel()
        nativeSave?.cancel()
        if (nativeMode) {
            saveNativePosition(visibleNativeOffset())
            super.onStop()
            return
        }
        val locator = latest
        if (locator != null) {
            val repo = repository; val id = bookId; val json = locator.toJSON().toString()
            val progression = (locator.locations.totalProgression ?: 0.0).toFloat()
            val app = applicationContext
            saves.launch {
                try { saveMutex.withLock { repo.savePosition(id, json, progression) } }
                catch (_: Exception) { withContext(Dispatchers.Main) {
                    Toast.makeText(app, "No se pudo guardar la posición de lectura.", Toast.LENGTH_LONG).show()
                } }
            }
        }
        super.onStop()
    }
    private suspend fun persist(locator: Locator) {
        saveMutex.withLock {
            repository.savePosition(bookId, locator.toJSON().toString(), (locator.locations.totalProgression ?: 0.0).toFloat())
        }
    }
    override fun onDestroy() {
        nativeLoading?.cancel()
        nativeSave?.cancel()
        super.onDestroy()
        publication?.close(); publication = null
    }
    private fun parseLocator(json: String?): Locator? = json?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
