@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.javierrb35.bibliotecaia.reader.epub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.text.style.BackgroundColorSpan
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.GestureDetector
import com.javierrb35.bibliotecaia.reader.gestures.HorizontalSwipeGesture
import android.os.Bundle
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.javierrb35.bibliotecaia.reader.ReaderPalette
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.javierrb35.bibliotecaia.R
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.AnnotationEntity
import com.javierrb35.bibliotecaia.data.ReadingRepository
import com.javierrb35.bibliotecaia.reader.annotations.AnnotationHitSpan
import com.javierrb35.bibliotecaia.reader.annotations.SavedMarkScrollView
import com.javierrb35.bibliotecaia.reader.annotations.SavedAnnotationSheet
import com.javierrb35.bibliotecaia.reader.speech.ReaderSpeech
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
        private const val VIEW_SAVED_TEXT_ID = 0x5C124
        private const val VERTICAL_GESTURES_MIGRATION = "vertical_gestures_v1"
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
    private lateinit var readerBookTitle: TextView
    private lateinit var footer: LinearLayout
    private lateinit var positionLabel: TextView
    private lateinit var bookmarkButton: MaterialButton
    private var concentration = false
    private var readiumSelectionActive = false
    private var nativeSelectionActive = false
    private lateinit var loading: TextView
    private lateinit var webFrame: FrameLayout
    private lateinit var nativeScroll: SavedMarkScrollView
    private lateinit var nativeText: TextView
    private lateinit var nativeToggle: MaterialButton
    private lateinit var speechButton: MaterialButton
    private val speech = ReaderSpeech()
    private var pendingSpeechStart = false
    private var advanceSpeech = false
    private val speechListener = object : ReaderSpeech.Listener {
        override fun onSpeaking(active: Boolean) {
            if (::speechButton.isInitialized) speechButton.text = if (active) "Detener" else "Escuchar"
        }
        override fun onError(message: String) { toast(message) }
        override fun onFinished() {
            val next = nativeIndex + 1
            if (nativeMode && next < (publication?.readingOrder?.size ?: 0)) {
                advanceSpeech = true
                showNativeChapter(next)
            } else speech.stop()
        }
    }
    private var nativeMode = true
    private var nativeChapter: NativeEpubText.Chapter? = null
    private var nativeIndex = 0
    private var nativeLoading: Job? = null
    private var nativeSave: Job? = null
    private var nativeTouchX = 0f
    private var nativeTouchY = 0f
    private var nativeGeneration = 0
    private var nativeRestoring = false
    private var nativeRestoreTarget: String? = null
    private var nativeMoved = false
    private var readiumMoved = false
    private var readiumNavigationArmed = false
    @Volatile private var lastQueuedLocator: String? = null
    @Volatile private var positionWriteRevision = 0L
    private var lastNativeReadiumKey: String? = null
    private var nativeReadiumRefresh: Job? = null
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
        // Existing paginated installations are migrated once to the safer
        // vertical-scrolling default. Aa still lets the reader opt into pages.
        val migrated = preferences.getBoolean(VERTICAL_GESTURES_MIGRATION, false)
        scroll = if (migrated) preferences.getBoolean("scroll", true) else true
        if (!migrated) preferences.edit().putBoolean("scroll", true)
            .putBoolean(VERTICAL_GESTURES_MIGRATION, true).apply()
        serif = preferences.getBoolean("serif", true)
        nativeMode = preferences.getBoolean("native_selection", true)
        createLayout()
        val restored = savedInstanceState?.getString(LOCATION) ?: intent.getStringExtra(LOCATION)
        lifecycleScope.launch {
            try {
                val book = AppDatabase.get(this@EpubReaderActivity).bookDao().findById(bookId)
                    ?: error("El libro ya no está en la biblioteca.")
                readerBookTitle.text = book.title
                val savedLocator = restored ?: repository.position(bookId)?.locator
                lastQueuedLocator = savedLocator
                val initial = parseLocator(savedLocator)
                val opened = EpubOpener.open(applicationContext, File(book.localPath))
                publication = opened
                if (opened.isRestricted) error("Este EPUB está protegido y no se puede abrir.")
                val fragment = lifecycle.withResumed {
                    supportFragmentManager.fragmentFactory = EpubNavigatorFactory(opened).createFragmentFactory(
                        initialLocator = initial, initialPreferences = readerPreferences(), listener = this@EpubReaderActivity,
                        configuration = EpubNavigatorFragment.Configuration(
                            selectionActionModeCallback = selectionCallback
                        )
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
                            if (mark.deletedAt != null || mark.kind !in setOf("NOTE", "HIGHLIGHT")) return false
                            readiumHitRequest++
                            showSavedTextMark(mark)
                            return true
                        }
                    })
                repository.markOpened(bookId)
                if (nativeMode) {
                    val initialIndex = opened.readingOrder.indexOfFirst {
                        initial?.href?.toString() == it.url().toString()
                    }.coerceAtLeast(0)
                    val initialOffset = NativeEpubAnchors.point(savedLocator)?.offset
                    nativeRestoreTarget = savedLocator
                    showNativeChapter(initialIndex, initialOffset ?: 0,
                        restoreFraction = if (initialOffset == null) initial?.locations?.progression else null,
                        recordPosition = false)
                } else {
                    showReadingSurface()
                    loading.visibility = View.GONE
                }
                // Neither side tap nor keyboard arrows turn a page. Readium
                // handles horizontal swipes in paginated mode; ReadiumTouchFrame
                // owns axis-locked chapter swipes in vertical scroll mode.
                fragment.addInputListener(object : InputListener {
                    override fun onTap(event: TapEvent): Boolean {
                        if (nativeMode) return false
                        // Readium consumes direct decoration activations first.
                        // For a generic tap, try geometry/native bridge before
                        // toggling concentration. event.point is relative to
                        // the publication view in physical pixels.
                        showReadiumMarkedAt(event.point.x, event.point.y, toggleIfMiss = true)
                        return true
                    }
                })
                launch {
                    fragment.currentLocator.collect { locator ->
                        // Keep the WebView resource locator even while its
                        // native TextView surface is visible. Otherwise an
                        // immediate Maquetación toggle on the SAME chapter
                        // has no href for the saved-note bridge until the user
                        // manually navigates to another chapter.
                        val previous = latest
                        latest = locator
                        if (!nativeMode) {
                            refreshNativeReadiumMarks(fragment)
                            positionLabel.text = "${((locator.locations.totalProgression ?: 0.0) * 100).toInt()} %"
                            // Do not manufacture a newer position merely by opening
                            // a book on another screen. Save subsequent navigation.
                            if (readiumNavigationArmed && previous != null && previous != locator) readiumMoved = true
                            if (!readiumMoved) return@collect
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
                        val changed = annotations != saved
                        annotations = saved
                        if (changed && nativeMode && nativeChapter != null) paintNativeMarks()
                        fragment.applyDecorations(EpubAnnotationMarks.forReadium(saved), "annotations")
                        if (!nativeMode) {
                            lastNativeReadiumKey = null
                            refreshNativeReadiumMarks(fragment)
                        }
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
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bia_background))
        }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bia_surface))
        }
        val heading = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp, 8.dp, 16.dp, 3.dp)
        }
        heading.addView(button("‹") { finish() }.apply {
            textSize = 27f
            contentDescription = "Volver a la biblioteca"
            minimumWidth = 46.dp
            minWidth = 46.dp
        }, LinearLayout.LayoutParams(48.dp, 48.dp))
        val identity = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(13.dp, 0, 0, 0)
        }
        identity.addView(TextView(this).apply {
            text = "BIBLIOTECAAI  /  LECTURA"
            textSize = 11f
            letterSpacing = .16f
            setTextColor(getColor(R.color.bia_accent))
            typeface = Typeface.DEFAULT_BOLD
        })
        readerBookTitle = TextView(this).apply {
            text = "Tu lectura"
            textSize = 19f
            typeface = Typeface.SERIF
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(getColor(R.color.bia_text))
        }
        identity.addView(readerBookTitle)
        heading.addView(identity, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(heading)
        val actions = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 3.dp, 12.dp, 10.dp)
        }
        fun addAction(label: String, handler: () -> Unit) {
            actions.addView(button(label, handler), LinearLayout.LayoutParams(-2, 48.dp).apply {
                marginEnd = 6.dp
            })
        }
        addAction(getString(R.string.epub_contents)) { showContents() }
        addAction(getString(R.string.epub_search)) { search() }
        addAction(getString(R.string.epub_settings)) { settings() }
        addAction("Concentración") { toggleReadingControls() }
        addAction("Notas") { showAnnotations() }
        speechButton = button("Escuchar") { toggleSpeech() }
        actions.addView(speechButton, LinearLayout.LayoutParams(-2, 48.dp).apply {
            marginEnd = 6.dp
        })
        nativeToggle = button(if (nativeMode) "Maquetación" else "Selección precisa") {
            setNativeMode(!nativeMode)
        }
        actions.addView(nativeToggle, LinearLayout.LayoutParams(-2, 48.dp))
        controls.addView(HorizontalScrollView(this).apply {
            addView(actions)
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        })
        controls.addView(View(this).apply {
            setBackgroundColor(getColor(R.color.bia_outline))
        }, LinearLayout.LayoutParams(-1, 1.dp))
        root.addView(controls)
        val frame = FrameLayout(this)
        webFrame = ReadiumTouchFrame(this).apply { id = R.id.epub_navigator }
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
        nativeScroll = SavedMarkScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            visibility = View.INVISIBLE
            addView(nativeText)
        }
        frame.addView(nativeScroll, FrameLayout.LayoutParams(-1, -1))
        attachNativeSelection()
        loading = TextView(this).apply {
            text = getString(R.string.epub_opening); textSize = 18f; gravity = android.view.Gravity.CENTER
            isClickable = true
            setPadding(24.dp, 24.dp, 24.dp, 24.dp)
        }
        frame.addView(loading, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        footer = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.bia_surface))
            setPadding(12.dp, 8.dp, 12.dp, 8.dp)
        }
        positionLabel = TextView(this).apply {
            text = "0 %"
            textSize = 16f
            typeface = Typeface.SERIF
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.bia_text))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        footer.addView(positionLabel, LinearLayout.LayoutParams(0, 48.dp, 1f))
        bookmarkButton = button(getString(R.string.epub_bookmark)) {
            val locator = if (nativeMode) nativeLocator(visibleNativeOffset(), "")
                else latest?.toJSON()?.toString()
            locator?.let { AnnotationEditor.show(this, bookId, it, "", "BOOKMARK") }
        }.apply { contentDescription = "Guardar marcador en esta posición" }
        footer.addView(bookmarkButton,
            LinearLayout.LayoutParams(-2, 48.dp).apply { marginEnd = 6.dp })
        root.addView(View(this).apply {
            setBackgroundColor(getColor(R.color.bia_outline))
        }, LinearLayout.LayoutParams(-1, 1.dp))
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
            override fun captureAnnotation(quote: String, start: Int, end: Int):
                com.javierrb35.bibliotecaia.study.DefinitionAnnotation.Target? =
                nativeLocator(start, quote)?.let {
                    com.javierrb35.bibliotecaia.study.DefinitionAnnotation.Target(bookId, it, quote)
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
                nativeSelectionActive = start >= 0 && end > start
                if (nativeSelectionActive) lastNativeSelectionStart = start
            }
            override fun onSelectionFinished() { nativeSelectionActive = false }
        }, object : DefinitionUi.SavedMarkHandler {
            override fun hasSavedMark(start: Int, end: Int) =
                nativeSavedMarksWithin(start, end).isNotEmpty()

            override fun onShowSavedMark(start: Int, end: Int) {
                SavedAnnotationSheet.show(this@EpubReaderActivity, repository,
                    nativeSavedMarksWithin(start, end))
            }
        })

        // Scriptura parity: short tap opens all annotations at the character,
        // long-press/drag remains owned by TextView's native selection mode.
        nativeScroll.attach(nativeText, marksAt = { at ->
            val chapter = nativeChapter
            if (chapter == null) emptyList()
            else {
                // Scriptura's working v0.1.38 path: the validated saved
                // anchor is the source of truth, not a transient CharacterStyle
                // span that TextView may drop when selection/rendering changes.
                // paintNativeMarks() uses this EXACT same validated range.
                annotations.filter { mark ->
                    mark.deletedAt == null && mark.kind in setOf("NOTE", "HIGHLIGHT") &&
                        nativeMarkRange(mark, chapter)?.contains(at) == true
                }
            }
        }, onMarks = { overlapping ->
            SavedAnnotationSheet.show(this, repository, overlapping)
        }, onUnmarkedTap = {
            if (nativeMode) toggleReadingControls()
        }, onMarkedLongPress = { marked ->
            SavedAnnotationSheet.show(this, repository, marked)
        }, onHorizontalSwipe = { direction ->
            if (nativeMode && nativeChapter != null) showNativeChapter(nativeIndex + direction)
        }, maySwipe = { !nativeSelectionActive })
        nativeScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            if (nativeMode && nativeChapter != null && !nativeRestoring) {
                nativeMoved = true
                updateNativeProgress()
                scheduleNativeSave()
            }
        }
    }

    /** Native Android selection acts on verified painted spans, not quote guesses. */
    private fun nativeSavedMarksWithin(start: Int, end: Int): List<AnnotationEntity> {
        if (start < 0 || end <= start) return emptyList()
        val chapter = nativeChapter ?: return emptyList()
        // The same persisted, verified anchors must work for both tapping
        // and a long-press selection, including previously imported notes.
        return annotations.filter { note ->
            val range = nativeMarkRange(note, chapter)
            note.deletedAt == null && note.kind in setOf("NOTE", "HIGHLIGHT") &&
                range != null && range.first < end && start <= range.last
        }
    }

    private var lastNativeSelectionStart = 0

    /** Toggle between precise native text selection and Readium's rich EPUB layout. */
    private fun setNativeMode(enabled: Boolean) {
        if (nativeMode == enabled || publication == null || nativeRestoring) return
        if (!enabled) {
            speech.stop()
            pendingSpeechStart = false
            advanceSpeech = false

            nativeSave?.cancel()
            saveNativePosition(visibleNativeOffset())
        }
        nativeMode = enabled
        readiumNavigationArmed = false
        readiumMoved = false
        pendingSave?.cancel()
        if (!enabled) lastNativeReadiumKey = null
        else nativeReadiumRefresh?.cancel()
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
            navigator?.let { refreshNativeReadiumMarks(it) }
            val chapter = nativeChapter
            if (chapter != null) {
                // Navigate to the same chapter, not a false CFI derived from
                // TextView offsets (the DOM is different after sanitization).
                val link = publication?.readingOrder?.getOrNull(nativeIndex)
                if (link != null) navigator?.go(link)
            }
        }
    }

    private fun toggleSpeech() {
        if (speech.active()) { speech.stop(); return }
        if (publication == null) { toast("El libro todavía se está preparando."); return }
        if (!nativeMode) {
            // The native text is read aloud; avoid pretending the Readium DOM
            // can provide an exact spoken passage without extraction.
            pendingSpeechStart = true
            setNativeMode(true)
            return
        }
        val chapter = nativeChapter
        if (chapter == null) {
            pendingSpeechStart = true
            showNativeChapter(nativeIndex)
            return
        }
        speech.toggle(this, spokenChapter(chapter), speechListener)
    }

    private fun spokenChapter(chapter: NativeEpubText.Chapter): String =
        readerBookTitle.text.toString() + ". " + chapter.title + ". " + chapter.text

    /**
     * Paint only verified native notes in the CURRENT XHTML resource, without
     * fabricating a Readium CFI from a TextView position. Unique DOM matches
     * can be highlighted by CSS; ambiguous ones remain in "Notas".
     */
    private fun refreshNativeReadiumMarks(fragment: EpubNavigatorFragment) {
        if (nativeMode) return
        val href = latest?.href?.toString() ?: return
        val notes = ReadiumNativeNoteBridge.notesForHref(annotations, href)
        val key = href + "|" + notes.joinToString("|") { it.id + ":" + it.updatedAt }
        if (key == lastNativeReadiumKey) return
        lastNativeReadiumKey = key
        nativeReadiumRefresh?.cancel()
        nativeReadiumRefresh = lifecycleScope.launch {
            delay(180)
            if (!nativeMode && latest?.href?.toString() == href &&
                !isFinishing && !isDestroyed) {
                runCatching {
                    fragment.evaluateJavascript(ReadiumNativeNoteBridge.decorateScript(notes))
                }
            }
        }
    }

    private fun showReadingSurface() {
        webFrame.visibility = if (nativeMode) View.GONE else View.VISIBLE
        nativeScroll.visibility = if (nativeMode) View.VISIBLE else View.GONE
        nativeToggle.text = if (nativeMode) "Maquetación" else "Selección precisa"
    }

    private fun showNativeChapter(index: Int, requestedOffset: Int = 0, findQuote: String? = null,
                                  restoreFraction: Double? = null, recordPosition: Boolean = true) {
        val pub = publication ?: return
        val link = pub.readingOrder.getOrNull(index) ?: return
        if (speech.active() && !advanceSpeech) speech.stop()
        nativeLoading?.cancel()
        nativeSave?.cancel()
        val generation = ++nativeGeneration
        nativeRestoring = true
        nativeMoved = false
        // INVISIBLE retains measurement/layout; GONE would prevent positioning.
        // The opaque loading surface stays above both engines until layout is ready.
        nativeScroll.visibility = View.INVISIBLE
        webFrame.visibility = View.INVISIBLE
        loading.visibility = View.VISIBLE
        loading.text = "Preparando capítulo para selección precisa…"
        nativeLoading = lifecycleScope.launch {
            try {
                val chapter = NativeEpubText.load(pub, link)
                if (!isActive || generation != nativeGeneration || !nativeMode) return@launch
                nativeIndex = index
                nativeChapter = chapter
                paintNativeMarks()
                val searchOffset = findQuote?.let {
                    chapter.text.indexOf(it, ignoreCase = true)
                }?.takeIf { it >= 0 }
                val fractionalOffset = restoreFraction?.takeIf { it.isFinite() }?.let {
                    (it.coerceIn(0.0, 1.0) * chapter.text.length).toInt()
                }
                val offset = (searchOffset ?: fractionalOffset ?: requestedOffset).coerceIn(0, chapter.text.length)
                // Wait for the layout of THIS chapter, not a queued Runnable
                // that can run against the previous TextView layout.
                nativeScroll.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if (generation != nativeGeneration || !nativeMode || isDestroyed) {
                            nativeScroll.viewTreeObserver.removeOnPreDrawListener(this)
                            return true
                        }
                        val layout = nativeText.layout ?: return true
                        if (nativeText.isLayoutRequested || nativeScroll.isLayoutRequested ||
                            nativeScroll.height == 0) return true
                        nativeScroll.viewTreeObserver.removeOnPreDrawListener(this)
                        nativeScroll.scrollTo(0,
                            (layout.getLineTop(layout.getLineForOffset(offset)) +
                                nativeText.totalPaddingTop).coerceAtLeast(0))
                        // The first visible frame already contains the target line.
                        showReadingSurface()
                        loading.visibility = View.GONE
                        nativeRestoring = false
                        nativeRestoreTarget = null
                        nativeMoved = recordPosition
                        updateNativeProgress()
                        if (recordPosition) scheduleNativeSave()
                        if (advanceSpeech) {
                            advanceSpeech = false
                            speech.continueWith(spokenChapter(chapter))
                        } else if (pendingSpeechStart) {
                            pendingSpeechStart = false
                            speech.toggle(this@EpubReaderActivity, spokenChapter(chapter), speechListener)
                        }
                        return true
                    }
                })
                nativeScroll.invalidate()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation != nativeGeneration) return@launch
                nativeRestoring = false
                advanceSpeech = false
                pendingSpeechStart = false
                speech.stop()
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
            (nativeScroll.scrollY - nativeText.totalPaddingTop).coerceAtLeast(0))
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
            if (!nativeRestoring) saveNativePosition(visibleNativeOffset())
        }
    }

    private fun saveNativePosition(offset: Int, publishExit: Boolean = false) {
        val locator = nativeLocator(offset, "") ?: return
        val progress = nativeProgress(offset).toFloat()
        val id = bookId
        val changed = lastQueuedLocator != locator
        if (changed) lastQueuedLocator = locator
        val revision = if (changed) ++positionWriteRevision else positionWriteRevision
        saves.launch {
            try {
                saveMutex.withLock {
                    if (changed && revision == positionWriteRevision) repository.savePosition(id, locator, progress)
                }
                if (publishExit) com.javierrb35.bibliotecaia.sync.SharedLibrary.publishReadingExit(applicationContext)
            }
            catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (revision == positionWriteRevision) lastQueuedLocator = null
                    toast("No se pudo guardar la posición.")
                }
            }
        }
    }

    private fun nativeMarkRange(
        note: AnnotationEntity, chapter: NativeEpubText.Chapter
    ): IntRange? = NativeEpubAnchors.validatedRange(
        note.locator, note.quote, chapter.href, chapter.text
    ) ?: NativeEpubAnchors.uniqueLegacyReadiumRange(
        note.locator, note.quote, chapter.href, chapter.text
    )

    private fun paintNativeMarks() {
        val chapter = nativeChapter ?: return
        val text = SpannableString(chapter.text)
        for (note in annotations) {
            val range = nativeMarkRange(note, chapter) ?: continue
            if (note.kind == "HIGHLIGHT") {
                text.setSpan(UnderlineSpan(), range.first, range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (note.kind == "NOTE") {
                text.setSpan(BackgroundColorSpan(0x66B59B62), range.first,
                    range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(UnderlineSpan(), range.first, range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else continue
            // Same precise validated range as the visible underline.
            text.setSpan(AnnotationHitSpan(note.id), range.first,
                range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        nativeText.setText(text, TextView.BufferType.SPANNABLE)
    }

    private fun showSavedTextMark(note: AnnotationEntity) {
        // Readium reports one activated decoration. Include other notes on
        // the exact same DOM locator so a second annotation is not hidden.
        // Avoid searching by quote alone: the same words can occur elsewhere.
        val overlapping = annotations.filter {
            it.kind in setOf("NOTE", "HIGHLIGHT") &&
                it.locator == note.locator && it.quote == note.quote
        }
        SavedAnnotationSheet.show(this, repository, overlapping.ifEmpty { listOf(note) })
    }

    private fun button(label: String, action: () -> Unit) = MaterialButton(this, null,
        com.google.android.material.R.attr.borderlessButtonStyle).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        minWidth = 40.dp
        minimumWidth = 40.dp
        minHeight = 48.dp
        insetTop = 0
        insetBottom = 0
        setPadding(12.dp, 0, 12.dp, 0)
        cornerRadius = 12.dp
        backgroundTintList = ColorStateList.valueOf(getColor(R.color.bia_surface_high))
        setTextColor(getColor(R.color.bia_accent))
        strokeWidth = 1.dp
        strokeColor = ColorStateList.valueOf(getColor(R.color.bia_outline))
        setOnClickListener { action() }
    }

    /** UI visibility is independent of how pages are turned. */
    private fun toggleReadingControls() {
        concentration = !concentration
        controls.visibility = if (concentration) View.GONE else View.VISIBLE
        footer.visibility = if (concentration) View.GONE else View.VISIBLE
    }

    private fun navigateByHorizontalSwipe(direction: Int) {
        if (direction !in -1..1 || direction == 0 || nativeMode || !scroll) return
        val pub = publication ?: return
        val target = EpubChapterNavigation.adjacentIndex(
            pub.readingOrder.map { it.url().toString() },
            latest?.href?.toString(), direction
        ) ?: return
        readiumNavigationArmed = true
        navigator?.go(pub.readingOrder[target])
    }

    /**
     * Dispatch-level observation sees WebView gestures without consuming
     * native text selection. Scroll-mode swipes use a strict directional lock
     * after disabling Readium's less selective chapter turns.
     */
    private inner class ReadiumTouchFrame(context: Context) : FrameLayout(context) {
        private val horizontal = HorizontalSwipeGesture(
            ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
            maxOf(72f * resources.displayMetrics.density, resources.displayMetrics.widthPixels * .17f)
        )
        private val detector = GestureDetector(context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onLongPress(e: MotionEvent) {
                    if (!nativeMode) showReadiumMarkedLongPress(e.x, e.y)
                }
            })
        private var captured = false
        private var firstDownX = 0f
        private var firstDownY = 0f
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (nativeMode || readiumSelectionActive) {
                horizontal.cancel()
                return super.dispatchTouchEvent(event)
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount == 1 &&
                (kotlin.math.abs(event.x - firstDownX) > ViewConfiguration.get(context).scaledTouchSlop ||
                    kotlin.math.abs(event.y - firstDownY) > ViewConfiguration.get(context).scaledTouchSlop)) {
                readiumNavigationArmed = true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    horizontal.down(event.x, event.y)
                    firstDownX = event.x
                    firstDownY = event.y
                    captured = false
                }
                MotionEvent.ACTION_MOVE -> if (scroll &&
                    (captured || horizontal.move(event.x, event.y, event.pointerCount))) {
                    if (!captured) {
                        captured = true
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        try {
                            detector.onTouchEvent(cancel)
                            super.dispatchTouchEvent(cancel)
                        } finally { cancel.recycle() }
                    }
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_CANCEL -> horizontal.cancel()
                MotionEvent.ACTION_UP -> if (scroll && captured) {
                    val turn = horizontal.up(event.x, event.y, event.pointerCount)
                    captured = false
                    if (turn != 0) navigateByHorizontalSwipe(turn)
                    return true
                }
            }
            detector.onTouchEvent(event)
            // Readium 3.0.3 lacks disablePageTurnsWhileScrolling. Keep its
            // vertical scroll, but never let a diagonal scroll masquerade
            // as a horizontal chapter swipe in the inner WebView.
            if (scroll && horizontal.isVerticalLocked() &&
                event.actionMasked in setOf(MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
                val vertical = MotionEvent.obtain(event)
                vertical.offsetLocation(firstDownX - event.x, 0f)
                return try { super.dispatchTouchEvent(vertical) }
                    finally { vertical.recycle() }
            }
            return super.dispatchTouchEvent(event)
        }
    }

    private fun showReadiumMarkedLongPress(x: Float, y: Float) {
        val fragment = navigator ?: return
        val marked = annotations.filter {
            it.deletedAt == null && it.kind in setOf("NOTE", "HIGHLIGHT")
        }
        if (marked.isEmpty()) return // Unmarked text keeps WebView selection.
        val origin = IntArray(2)
        val target = IntArray(2)
        webFrame.getLocationOnScreen(origin)
        fragment.publicationView.getLocationOnScreen(target)
        val hitX = x + origin[0] - target[0]
        val hitY = y + origin[1] - target[1]
        showReadiumMarkedAt(hitX, hitY, toggleIfMiss = false)
    }

    private var readiumHitRequest = 0L
    private fun showReadiumMarkedAt(hitX: Float, hitY: Float, toggleIfMiss: Boolean) {
        val fragment = navigator ?: return
        val marked = annotations.filter {
            it.deletedAt == null && it.kind in setOf("NOTE", "HIGHLIGHT")
        }
        if (marked.isEmpty()) {
            if (toggleIfMiss && !nativeMode && !readiumSelectionActive) toggleReadingControls()
            return
        }
        val request = ++readiumHitRequest
        val href = latest?.href?.toString()
        lifecycleScope.launch {
            val candidates = marked.map { it.id }.toSet()
            val actualId = runCatching {
                ReadiumMarkedTap.verifiedId(
                    fragment.evaluateJavascript(ReadiumMarkedTap.hitTestScript(hitX, hitY)),
                    candidates
                )
            }.getOrNull()
            val nativeNotes = ReadiumNativeNoteBridge.notesForHref(
                marked, latest?.href?.toString())
            val nativeId = if (actualId == null && nativeNotes.isNotEmpty())
                runCatching {
                    ReadiumMarkedTap.verifiedId(
                        fragment.evaluateJavascript(
                            ReadiumNativeNoteBridge.hitTestScript(hitX, hitY, nativeNotes)),
                        nativeNotes.map { it.id }.toSet()
                    )
                }.getOrNull() else null
            if (request != readiumHitRequest || nativeMode || isFinishing ||
                latest?.href?.toString() != href) return@launch
            val matched = annotations.firstOrNull { it.id == (actualId ?: nativeId) && it.deletedAt == null }
            if (matched != null) showSavedTextMark(matched)
            else if (toggleIfMiss && !readiumSelectionActive) toggleReadingControls()
        }
    }

    private fun palette(): Pair<Int, Int> = ReaderPalette.at(themeIndex).let { it.background to it.foreground }

    private fun readerPreferences(): EpubPreferences {
        val (background, text) = palette()
        return EpubPreferences(backgroundColor = ReaderColor(background), textColor = ReaderColor(text),
            theme = if (themeIndex <= 1) Theme.DARK else if (themeIndex == 2) Theme.SEPIA else Theme.LIGHT,
            fontSize = fontScale.toDouble(), fontFamily = FontFamily(if (serif) "serif" else "sans-serif"),
            publisherStyles = false, lineHeight = 1.55, pageMargins = 1.2, scroll = scroll)
    }

    private fun applyTheme() {
        val (background, text) = palette()
        root.setBackgroundColor(background); loading.setBackgroundColor(background)
        loading.setTextColor(text); positionLabel.setTextColor(text)
        nativeScroll.setBackgroundColor(background)
        nativeText.setBackgroundColor(background)
        nativeText.setTextColor(text)
        nativeText.setHighlightColor(if (themeIndex <= 1) 0x66D4B483 else 0x55C7A264)
        nativeText.textSize = 20f * fontScale
        nativeText.typeface = if (serif) Typeface.SERIF else Typeface.SANS_SERIF
        val palette = ReaderPalette.at(themeIndex)
        palette.applyChrome(controls)
        palette.applyChrome(footer)
        palette.applySystemBars(window)
        navigator?.submitPreferences(readerPreferences())
    }

    private fun settings() {
        val anchor = if (nativeMode && !nativeRestoring) visibleNativeOffset() else null
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.reader_font_percent, (fontScale * 100).toInt()))
            .setItems(arrayOf(
                "Tema: ${arrayOf("OLED", "Oscuro", "Sepia", "Claro")[themeIndex]}",
                getString(R.string.reader_font_increase), getString(R.string.reader_font_decrease),
                if (serif) "Tipografía sin remates" else "Tipografía con remates",
                if (scroll) "Cambiar a páginas" else "Desplazamiento vertical"
            ), null)
            .setNegativeButton(R.string.reader_settings_done, null).show()
        // A normal setItems listener dismisses the dialog after every tap.
        // Only the size actions stay open; the remaining menu retains its existing behavior.
        dialog.listView?.setOnItemClickListener { _, _, which, _ ->
            when (which) {
                0 -> {
                    dialog.dismiss()
                    MaterialAlertDialogBuilder(this).setTitle("Tema de lectura")
                        .setSingleChoiceItems(arrayOf("OLED · negro puro", "Oscuro suave", "Sepia", "Claro"), themeIndex) { themeDialog, index ->
                            themeIndex = index; savePreferences(); themeDialog.dismiss()
                        }.show()
                }
                1, 2 -> {
                    val next = (fontScale + if (which == 1) .1f else -.1f).coerceIn(.75f, 2f)
                    if (next != fontScale) {
                        // Reflow native text at the same logical character, not the old pixel offset.
                        if (anchor != null && nativeMode && !nativeRestoring) {
                            nativeRestoring = true
                            nativeSave?.cancel()
                            val generation = nativeGeneration
                            nativeScroll.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                                override fun onPreDraw(): Boolean {
                                    if (generation != nativeGeneration || !nativeMode || isDestroyed) {
                                        nativeScroll.viewTreeObserver.removeOnPreDrawListener(this)
                                        return true
                                    }
                                    val layout = nativeText.layout ?: return true
                                    if (nativeText.isLayoutRequested || nativeScroll.isLayoutRequested) return true
                                    nativeScroll.viewTreeObserver.removeOnPreDrawListener(this)
                                    nativeScroll.scrollTo(0, layout.getLineTop(layout.getLineForOffset(
                                        anchor.coerceIn(0, nativeText.length()))) + nativeText.totalPaddingTop)
                                    nativeRestoring = false
                                    updateNativeProgress()
                                    return true
                                }
                            })
                        }
                        fontScale = next
                        savePreferences()
                    }
                    dialog.setTitle(getString(R.string.reader_font_percent, kotlin.math.round(fontScale * 100).toInt()))
                }
                3 -> { dialog.dismiss(); serif = !serif; savePreferences() }
                4 -> { dialog.dismiss(); scroll = !scroll; savePreferences() }
            }
        }
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
            else {
                readiumNavigationArmed = true
                navigator?.go(selected)
            }
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
                val hasTextMark = note.kind in setOf("NOTE", "HIGHLIGHT")
                val choices = when (note.kind) {
                    "HIGHLIGHT" -> arrayOf("Leer anotación", "Ir al texto", "Quitar subrayado")
                    "NOTE" -> arrayOf("Leer anotación", "Ir al texto", "Editar nota", "Eliminar anotación")
                    else -> arrayOf("Ir al texto", "Editar nota", "Eliminar anotación")
                }
                MaterialAlertDialogBuilder(this).setTitle(note.quote.ifBlank { "Anotación" })
                    .setItems(choices) { _, selected ->
                        // The notebook itself MUST provide an unbreakable
                        // read-only entry point independent of the TouchView
                        // implementation or Samsung's selection menu.
                        if (hasTextMark && selected == 0) {
                            SavedAnnotationSheet.show(this, repository, listOf(note))
                        } else when (if (hasTextMark) selected - 1 else selected) {
                            0 -> {
                                val point = NativeEpubAnchors.point(note.locator)
                                val pub = publication
                                val chapterIndex = pub?.readingOrder?.indexOfFirst {
                                    it.url().toString() == point?.href
                                } ?: -1
                                if (point != null && chapterIndex >= 0) {
                                    // Native offsets cannot be turned into a
                                    // fake Readium CFI. Navigate to the exact
                                    // native anchor even from Maquetación.
                                    if (!nativeMode) setNativeMode(true)
                                    showNativeChapter(chapterIndex, point.offset)
                                } else parseLocator(note.locator)?.let {
                                    readiumNavigationArmed = true
                                    navigator?.go(it)
                                }
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
                                else {
                                    readiumNavigationArmed = true
                                    navigator?.go(result)
                                }
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

    /** Keep the annotation action alongside the native selection controls. */
    private fun addSavedTextAction(menu: Menu?) {
        if (menu == null || menu.findItem(VIEW_SAVED_TEXT_ID) != null) return
        if (annotations.none { it.deletedAt == null && it.kind in setOf("NOTE", "HIGHLIGHT") }) return
        menu.add(Menu.NONE, VIEW_SAVED_TEXT_ID, Menu.CATEGORY_SECONDARY, "Ver anotación")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }

    /** Readium WebView selection: native Copy/Share/Select All plus study actions. */
    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            readiumSelectionActive = true
            DefinitionUi.populateSelectionMenu(menu)
            addSavedTextAction(menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            DefinitionUi.populateSelectionMenu(menu)
            addSavedTextAction(menu)
            return true
        }

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            if (item == null) return false

            // Readium replaces Android's WebView ActionMode callback. Returning
            // false would NOT copy/share/select here: handle the native IDs.
            if (item.itemId == android.R.id.copy || item.itemId == android.R.id.shareText) {
                lifecycleScope.launch {
                    try {
                        val quote = navigator?.currentSelection()?.locator?.text?.highlight.orEmpty()
                        if (quote.isBlank()) {
                            toast("No hay texto seleccionado.")
                        } else if (item.itemId == android.R.id.copy) {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText("Texto seleccionado", quote)
                            )
                        } else {
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, quote)
                            }
                            startActivity(Intent.createChooser(share, "Compartir fragmento"))
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        toast("No se pudo copiar o compartir el fragmento.")
                    } finally {
                        mode?.finish()
                    }
                }
                return true
            }
            if (item.itemId == android.R.id.selectAll) {
                lifecycleScope.launch {
                    try {
                        // Scope is the currently visible EPUB resource, not the
                        // whole library. Keep the ActionMode open for Copy/Share.
                        val selected = navigator?.evaluateJavascript(
                            "(function(){var s=window.getSelection();" +
                                "if(!s||!document.body)return false;" +
                                "var r=document.createRange();" +
                                "r.selectNodeContents(document.body);" +
                                "s.removeAllRanges();s.addRange(r);return true;})()"
                        )
                        if (selected != "true") toast("No se pudo seleccionar todo el texto.")
                        else mode?.invalidate()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        toast("No se pudo seleccionar todo el texto.")
                    }
                }
                return true
            }
            if (item.itemId == VIEW_SAVED_TEXT_ID) {
                lifecycleScope.launch {
                    try {
                        // A Readium selection has a DOM locator; native notes have
                        // offsets in normalized chapter text. Only link the two
                        // when the selected text has ONE verifiable occurrence.
                        val selected = navigator?.currentSelection()?.locator
                        val excerpt = selected?.text?.highlight.orEmpty()
                        val pub = publication
                        val link = pub?.readingOrder?.firstOrNull {
                            it.url().toString() == selected?.href?.toString()
                        }
                        if (excerpt.isBlank() || pub == null || link == null) {
                            toast("Selecciona un fragmento marcado para consultar su nota.")
                            return@launch
                        }
                        val chapter = NativeEpubText.load(pub, link)
                        val matching = EpubSelectionNotes.overlapping(chapter, excerpt, annotations)
                        if (matching.isEmpty()) {
                            toast("No hay una anotación verificada en esta selección.")
                        } else {
                            SavedAnnotationSheet.show(this@EpubReaderActivity, repository, matching)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        toast("No se pudo consultar la anotación seleccionada.")
                    } finally {
                        mode?.finish()
                    }
                }
                return true
            }
            if (!DefinitionUi.isStudyAction(item.itemId)) return false
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
                            if (WordMeaning.searchPhrase(quote) == null) {
                                toast("Selecciona una palabra o expresión breve para consultar su significado.")
                            } else {
                                val location = selection?.locator
                                var passage = com.javierrb35.bibliotecaia.study.ContextualDefinition.fromParts(
                                    location?.text?.before.orEmpty(), quote, location?.text?.after.orEmpty())
                                // Older Readium/WebView versions may omit before/after.
                                // Only use a unique exact match in the CURRENT resource.
                                if (!passage.hasContext) {
                                    val pub = publication
                                    val link = pub?.readingOrder?.firstOrNull {
                                        it.url().toString() == location?.href?.toString()
                                    }
                                    if (pub != null && link != null) {
                                        val chapter = withContext(Dispatchers.IO) {
                                            NativeEpubText.load(pub, link)
                                        }
                                        passage = com.javierrb35.bibliotecaia.study.ContextualDefinition
                                            .fromUniqueOccurrence(chapter.text, quote)
                                    }
                                }
                                mode?.finish()
                                DefinitionUi.show(this@EpubReaderActivity, quote,
                                    aiConfigured(), definitionDelegate(locator), passage,
                                    com.javierrb35.bibliotecaia.study.DefinitionAnnotation.Target(bookId, locator, quote))
                            }
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

        override fun onDestroyActionMode(mode: ActionMode?) {
            readiumSelectionActive = false
        }
    }

    override fun onExternalLinkActivated(url: AbsoluteUrl) { toast("Los enlaces externos están desactivados durante la lectura.") }
    override fun onSaveInstanceState(outState: Bundle) {
        val locator = if (nativeMode) {
            if (nativeRestoring) nativeRestoreTarget ?: lastQueuedLocator
            else nativeLocator(visibleNativeOffset(), "")
        } else if (readiumMoved) latest?.toJSON()?.toString() else lastQueuedLocator
        locator?.let { outState.putString(LOCATION, it) }
        super.onSaveInstanceState(outState)
    }
    override fun onStop() {
        speech.stop()
        pendingSpeechStart = false
        advanceSpeech = false
        pendingSave?.cancel()
        nativeSave?.cancel()
        if (nativeMode) {
            if (!nativeRestoring && nativeMoved) saveNativePosition(visibleNativeOffset(), publishExit = true)
            else saves.launch {
                saveMutex.withLock { }
                com.javierrb35.bibliotecaia.sync.SharedLibrary.publishReadingExit(applicationContext)
            }
            nativeMoved = false
            super.onStop()
            return
        }
        val locator = latest
        if (locator != null && readiumMoved) {
            val repo = repository; val id = bookId; val json = locator.toJSON().toString()
            val progression = (locator.locations.totalProgression ?: 0.0).toFloat()
            val app = applicationContext
            val changed = lastQueuedLocator != json
            if (changed) lastQueuedLocator = json
            val revision = if (changed) ++positionWriteRevision else positionWriteRevision
            saves.launch {
                try {
                    saveMutex.withLock {
                        if (changed && revision == positionWriteRevision) repo.savePosition(id, json, progression)
                    }
                    com.javierrb35.bibliotecaia.sync.SharedLibrary.publishReadingExit(app)
                }
                catch (_: Exception) { withContext(Dispatchers.Main) {
                    if (revision == positionWriteRevision) lastQueuedLocator = null
                    Toast.makeText(app, "No se pudo guardar la posición de lectura.", Toast.LENGTH_LONG).show()
                } }
            }
        } else saves.launch {
            saveMutex.withLock { }
            com.javierrb35.bibliotecaia.sync.SharedLibrary.publishReadingExit(applicationContext)
        }
        readiumMoved = false
        readiumNavigationArmed = false
        super.onStop()
    }
    private suspend fun persist(locator: Locator) {
        val json = locator.toJSON().toString()
        if (lastQueuedLocator == json) return
        val revision = ++positionWriteRevision
        saveMutex.withLock {
            if (revision == positionWriteRevision) {
                repository.savePosition(bookId, json, (locator.locations.totalProgression ?: 0.0).toFloat())
                if (revision == positionWriteRevision) lastQueuedLocator = json
            }
        }
    }
    override fun onDestroy() {
        speech.shutdown()
        nativeLoading?.cancel()
        nativeSave?.cancel()
        super.onDestroy()
        publication?.close(); publication = null
    }
    private fun parseLocator(json: String?): Locator? = json?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
