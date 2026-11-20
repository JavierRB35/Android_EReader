package com.javierrb35.bibliotecaia.ai

import android.content.Intent
import android.net.Uri
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.javierrb35.bibliotecaia.R
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Asistente de configuración IA (BYOK, Prompt Maestro §13): explica cómo obtener la
 * clave, abre la página oficial del proveedor, la guarda cifrada en Keystore, la
 * PRUEBA realmente contra el servicio y permite eliminarla. Errores diferenciados.
 */
class AiSetupActivity : AppCompatActivity() {

    private var provider: String = AiClients.GEMINI

    private lateinit var status: TextView
    private lateinit var keyInput: TextInputEditText
    private lateinit var keyLayout: TextInputLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val root = ScrollView(this).apply { addView(layout) }
        setContentView(root)

        header(layout, getString(R.string.ai_setup_title))
        hint(layout, getString(R.string.ai_setup_intro))

        section(layout, getString(R.string.ai_provider))
        val providerButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        layout.addView(providerButtons)
        listOf(AiClients.GEMINI to getString(R.string.ai_provider_gemini),
               AiClients.GROQ to getString(R.string.ai_provider_groq)).forEach { (id, label) ->
            providerButtons.addView(Button(this).apply {
                text = label
                setOnClickListener { selectProvider(id) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        section(layout, getString(R.string.ai_key_section))
        hint(layout, getString(R.string.ai_key_howto_gemini))
        hint(layout, getString(R.string.ai_key_howto_groq))
        action(layout, getString(R.string.ai_open_gemini_keys)) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AiClients.GEMINI_KEYS_URL)))
        }
        action(layout, getString(R.string.ai_open_groq_keys)) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AiClients.GROQ_KEYS_URL)))
        }

        keyLayout = TextInputLayout(this).apply {
            hint = getString(R.string.ai_key_hint)
            isPasswordVisibilityToggleEnabled = true
        }
        keyLayout.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        keyInput = TextInputEditText(this).apply {
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            setTextColor(ContextCompat.getColor(this@AiSetupActivity, R.color.bia_text))
        }
        keyLayout.addView(keyInput)
        layout.addView(keyLayout)

        action(layout, getString(R.string.ai_save_and_test)) { saveAndTest() }
        action(layout, getString(R.string.ai_delete_key)) { deleteKey() }

        section(layout, getString(R.string.ai_status_section))
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@AiSetupActivity, R.color.bia_text_secondary))
            setPadding(0, dp(12), 0, dp(12))
        }
        layout.addView(status)
        hint(layout, getString(R.string.ai_privacy_note))

        selectProvider(AiClients.GEMINI)
    }

    private fun selectProvider(id: String) {
        provider = id
        refreshStatus()
    }

    private fun refreshStatus() {
        val stored = AiKeyStore.getKey(this, provider) != null
        status.text = getString(
            R.string.ai_status,
            providerName(provider),
            if (stored) getString(R.string.ai_stored) else getString(R.string.ai_not_stored),
        )
    }

    private fun saveAndTest() {
        val key = keyInput.text?.toString()?.trim().orEmpty()
        if (key.isEmpty()) {
            Toast.makeText(this, R.string.ai_key_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (!AiKeyStore.saveKey(this, provider, key)) {
            Toast.makeText(this, R.string.ai_keystore_error, Toast.LENGTH_LONG).show()
            return
        }
        status.text = getString(R.string.ai_testing)
        lifecycleScope.launch {
            try {
                val answer = withContext(Dispatchers.IO) {
                    AiClients.ask(provider, key, "Responde únicamente: OK",
                        fragments = listOf("Prueba de funcionamiento."), history = emptyList())
                }
                status.text = getString(R.string.ai_test_ok, answer.model)
                Toast.makeText(this@AiSetupActivity, R.string.ai_test_ok_toast, Toast.LENGTH_SHORT).show()
            } catch (e: AiClients.AiException) {
                status.text = getString(R.string.ai_test_failed, e.message ?: "")
            } catch (e: Exception) {
                status.text = getString(R.string.ai_test_failed, e.message ?: "")
            }
            refreshStatus()
        }
    }

    private fun deleteKey() {
        AiKeyStore.deleteKey(this, provider)
        refreshStatus()
        Toast.makeText(this, R.string.ai_deleted, Toast.LENGTH_SHORT).show()
    }

    private fun providerName(id: String): String =
        if (id == AiClients.GEMINI) getString(R.string.ai_provider_gemini) else getString(R.string.ai_provider_groq)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun header(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            setText(text)
            textSize = 30f
            setTypeface(Typeface.SERIF)
            setTextColor(ContextCompat.getColor(this@AiSetupActivity, R.color.bia_text))
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        })
    }

    private fun section(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            setText(text)
            textSize = 14f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(ContextCompat.getColor(this@AiSetupActivity, R.color.bia_accent))
            setPadding(0, dp(28), 0, dp(4))
        })
    }

    private fun action(parent: LinearLayout, text: String, onClick: () -> Unit) {
        parent.addView(Button(this).apply {
            setText(text)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setTextColor(ContextCompat.getColor(this@AiSetupActivity, R.color.bia_accent))
            background = null
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { onClick() }
        })
    }

    private fun hint(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            setText(text)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@AiSetupActivity, R.color.bia_text_secondary))
            setPadding(0, dp(8), 0, dp(8))
        })
    }
}
