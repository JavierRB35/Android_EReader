package com.javierrb35.bibliotecaia.reader.mobi

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Html
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.ReadingRepository
import com.javierrb35.bibliotecaia.ui.AnnotationEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
        root.addView(toolbar)
        status = TextView(this).apply { text = "Abriendo MOBI…"; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        root.addView(status)
        content = TextView(this).apply {
            setPadding(dp(24), dp(16), dp(24), dp(32))
            typeface = Typeface.create("serif", Typeface.NORMAL)
            setLineSpacing(dp(5).toFloat(), 1.15f)
            setTextIsSelectable(true)
            customSelectionActionModeCallback = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    menu.add(0, 1121, 0, "Anotar"); return true
                }
                override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
                override fun onDestroyActionMode(mode: ActionMode) = Unit
                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                    if (item.itemId != 1121) return false
                    val start = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
                    val end = maxOf(selectionStart, selectionEnd).coerceAtMost(length())
                    if (start < end) AnnotationEditor.show(this@MobiReaderActivity, bookId,
                        locator(offset + start), text.subSequence(start, end).toString())
                    mode.finish()
                    return true
                }
            }
        }
        scroll = ScrollView(this).apply { addView(content) }
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
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                status.text = error.message ?: "No se pudo abrir el MOBI."
            }
        }
    }

    private fun showChunk(at: Int) {
        if (!loaded) return
        offset = at.coerceIn(0, text.lastIndex)
        // Never split a UTF-16 surrogate pair between views or saved locators.
        if (offset > 0 && Character.isLowSurrogate(text[offset])) offset--
        endOffset = (offset + CHUNK).coerceAtMost(text.length)
        if (endOffset < text.length && Character.isLowSurrogate(text[endOffset])) endOffset--
        content.text = text.substring(offset, endOffset)
        scroll.scrollTo(0, 0)
        status.text = "${title} · MOBI texto · ${(offset * 100L / text.length)} %"
        previous.isEnabled = offset > 0
        next.isEnabled = endOffset < text.length
        savePosition(offset)
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
        if (loaded) lifecycleScope.launch {
            repository.savePosition(bookId, locator(at), at.toFloat() / text.length)
        }
    }
    override fun onPause() { if (loaded) savePosition(visibleOffset()); super.onPause() }
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
        content.textSize = preferences.getFloat("size", 20f)
        window.statusBarColor = backgrounds[theme]; window.navigationBarColor = backgrounds[theme]
        window.decorView.systemUiVisibility = if (theme >= 2) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; minHeight = dp(48); setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
