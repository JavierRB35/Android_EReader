package com.javierrb35.bibliotecaia.ai

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.javierrb35.bibliotecaia.R
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Conversación IA sobre un libro: RAG por capítulos con referencias [n] y anti-spoilers. */
class AiChatActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_BOOK_ID = "book_id"
        fun start(context: Context, bookId: String) {
            context.startActivity(Intent(context, AiChatActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId))
        }
    }

    private val db by lazy { AppDatabase.get(this) }
    private var bookId: String = ""
    private var bookTitle: String = ""
    private var chapters: List<BookTextSource.Fragment> = emptyList()
    private var progress: Float = 0f
    private val history = mutableListOf<Pair<String, String>>()
    private val usedSources = mutableListOf<BookTextSource.Fragment>()
    private lateinit var spoilerSwitch: SwitchMaterial
    private lateinit var conversation: LinearLayout
    private lateinit var questionInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: run { finish(); return }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val root = ScrollView(this).apply { addView(layout) }
        setContentView(root)

        layout.addView(TextView(this).apply {
            text = getString(R.string.ai_chat_title)
            textSize = 26f
            setTypeface(Typeface.SERIF)
            setTextColor(ContextCompat.getColor(this@AiChatActivity, R.color.bia_text))
        })
        conversation = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(conversation)

        val spoilerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        layout.addView(spoilerRow)
        spoilerSwitch = SwitchMaterial(this).apply { isChecked = true }
        spoilerRow.addView(TextView(this).apply {
            text = getString(R.string.ai_spoiler_switch)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@AiChatActivity, R.color.bia_text_secondary))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        spoilerRow.addView(spoilerSwitch)

        questionInput = EditText(this).apply {
            hint = getString(R.string.ai_question_hint)
            setTextColor(ContextCompat.getColor(this@AiChatActivity, R.color.bia_text))
        }
        layout.addView(questionInput)

        layout.addView(Button(this).apply {
            text = getString(R.string.ai_ask)
            setOnClickListener { ask() }
        })

        lifecycleScope.launch {
            val book = db.bookDao().findById(bookId) ?: run { finish(); return@launch }
            bookTitle = book.title
            progress = book.progress
            chapters = withContext(Dispatchers.IO) {
                BookTextSource.chapters(
                    File(book.localPath), book.format.name,
                    if (book.format.name == "PDF") File(filesDir, "ocr/$bookId") else null,
                )
            }
            if (chapters.isEmpty()) {
                addBubble(getString(R.string.ai_no_text, book.format.name))
                questionInput.isEnabled = false
            } else {
                addBubble(getString(R.string.ai_ready, chapters.size, bookTitle))
            }
        }
    }

    private fun ask() {
        val question = questionInput.text?.toString()?.trim().orEmpty()
        if (question.isEmpty()) return
        questionInput.setText("")
        addBubble(question, isUser = true)
        val thinking = addBubble(getString(R.string.ai_thinking))

        val spoilerSafe = spoilerSwitch.isChecked
        lifecycleScope.launch {
            val provider = firstConfiguredProvider()
            val key = provider?.let { AiKeyStore.getKey(this@AiChatActivity, it) }
            if (provider == null || key == null) {
                thinking.bubbleMessage(getString(R.string.ai_needs_key))
                thinking.setOnClickListener {
                    startActivity(Intent(this@AiChatActivity, AiSetupActivity::class.java))
                }
                return@launch
            }
            try {
                val fragments = withContext(Dispatchers.IO) {
                    BookTextSource.retrieve(chapters, question, spoilerSafe, progress)
                }
                usedSources.clear()
                usedSources.addAll(fragments)
                val answer = withContext(Dispatchers.IO) {
                    AiClients.ask(provider, key, question,
                        fragments.map { "[${it.index}] ${it.title}\n${it.text}" }, history.toList())
                }
                history.add("user" to question)
                history.add("assistant" to answer.text)
                saveHistory(question, answer.text, provider)
                thinking.visibility = android.view.View.GONE
                addAnswer(answer.text)
            } catch (e: AiClients.AiException) {
                thinking.bubbleMessage(e.message ?: "")
            } catch (e: Exception) {
                thinking.bubbleMessage(getString(R.string.ai_generic_error))
            }
        }
    }

    private fun firstConfiguredProvider(): String? =
        AiKeyStore.providers().firstOrNull { AiKeyStore.getKey(this, it) != null }

    private fun saveHistory(question: String, answer: String, provider: String) {
        runCatching {
            val dir = File(filesDir, "ai_history/$bookId").apply { mkdirs() }
            val file = File(dir, "historial.json")
            val root = if (file.exists()) org.json.JSONObject(file.readText())
            else org.json.JSONObject().put("bookId", bookId).put("title", bookTitle)
            val messages = root.optJSONArray("messages") ?: org.json.JSONArray()
            messages.put(org.json.JSONObject().put("role", "user").put("content", question))
            messages.put(org.json.JSONObject().put("role", "assistant").put("content", answer)
                .put("provider", provider))
            root.put("messages", messages)
            file.writeText(root.toString(2))
        }
    }

    // ---------- burbujas ----------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun addBubble(text: String, isUser: Boolean = false): LinearLayout {
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(
                this@AiChatActivity,
                if (isUser) R.color.bia_surface_high else R.color.bia_surface,
            )
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val bubbleText = TextView(this).apply {
            setText(text)
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@AiChatActivity, R.color.bia_text))
        }
        bubble.addView(bubbleText)
        bubble.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(8)) })
        conversation.addView(bubble)
        bubble.tag = bubbleText
        return bubble
    }

    private fun addAnswer(text: String) {
        val bubble = addBubble(text)
        val sources = getString(R.string.ai_sources) + " " +
            usedSources.joinToString(" · ") { "[${it.index}] ${it.title}" }
        bubble.addView(TextView(this).apply {
            setText(sources)
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@AiChatActivity, R.color.bia_text_secondary))
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun LinearLayout.bubbleMessage(message: String) {
        (tag as? TextView ?: getChildAt(0) as TextView).setText(message)
    }
}
