package com.javierrb35.bibliotecaia.ai

import android.content.Intent
import android.net.Uri
import android.graphics.Typeface
import android.view.Gravity
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
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
    private val providerButtons = mutableMapOf<String, MaterialButton>()

    private lateinit var status: TextView
    private lateinit var keyInput: TextInputEditText
    private lateinit var keyLayout: TextInputLayout
    private lateinit var groqFallbackToggle: SwitchMaterial
    private lateinit var modelButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }
        val root = ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.bia_background))
            addView(layout)
        }
        setContentView(root)

        header(layout, getString(R.string.ai_setup_title))
        hint(layout, getString(R.string.ai_setup_intro))

        section(layout, getString(R.string.ai_provider))
        val providerButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        layout.addView(providerButtons)
        listOf(AiClients.GEMINI to getString(R.string.ai_provider_gemini),
               AiClients.GROQ to getString(R.string.ai_provider_groq)).forEach { (id, label) ->
            providerButtons.addView(MaterialButton(this).apply {
                text = label
                isAllCaps = false
                minHeight = dp(48)
                cornerRadius = dp(15)
                this@AiSetupActivity.providerButtons[id] = this
                setOnClickListener { selectProvider(id) }
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginEnd = dp(5)
            })
        }

        modelButton = MaterialButton(this).apply {
            text = getString(R.string.ai_gemini_model_label, AiModelSettings.current(this@AiSetupActivity))
            isAllCaps = false
            minHeight = dp(48)
            cornerRadius = dp(15)
            setOnClickListener { chooseGeminiModel() }
        }
        layout.addView(modelButton)

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

        action(layout, getString(R.string.ai_save_and_test), primary = true) { saveAndTest() }
        action(layout, getString(R.string.ai_delete_key)) { deleteKey() }

        section(layout, getString(R.string.ai_status_section))
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@AiSetupActivity, R.color.bia_text_secondary))
            setPadding(0, dp(12), 0, dp(12))
        }
        layout.addView(status)
        hint(layout, getString(R.string.ai_privacy_note))

        // Scriptura parity: separate affirmative consent, not inferred from the API key.
        section(layout, getString(R.string.ai_fallback_section))
        hint(layout, getString(R.string.ai_fallback_description))
        groqFallbackToggle = SwitchMaterial(this).apply {
            text = getString(R.string.ai_fallback_toggle)
            textSize = 14f
            minHeight = dp(52)
            setTextColor(getColor(R.color.bia_text))
        }
        layout.addView(groqFallbackToggle)
        refreshFallbackControl()

        selectProvider(AiClients.GEMINI)
    }

    private fun selectProvider(id: String) {
        provider = id
        providerButtons.forEach { (key, control) ->
            val active = key == provider
            control.backgroundTintList = ColorStateList.valueOf(
                getColor(if (active) R.color.bia_accent else R.color.bia_surface_high))
            control.setTextColor(getColor(if (active) R.color.bia_background else R.color.bia_text))
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        val stored = AiKeyStore.getKey(this, provider) != null
        status.text = getString(
            R.string.ai_status,
            providerName(provider),
            if (stored) getString(R.string.ai_stored) else getString(R.string.ai_not_stored),
        )
        if (::groqFallbackToggle.isInitialized) refreshFallbackControl()
    }

    private fun refreshFallbackControl() {
        groqFallbackToggle.setOnCheckedChangeListener(null)
        groqFallbackToggle.isChecked = AiFallbackConsent.enabled(this)
        groqFallbackToggle.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                AiFallbackConsent.revoke(this)
                refreshFallbackControl()
                return@setOnCheckedChangeListener
            }
            if (AiKeyStore.getKey(this, AiClients.GROQ).isNullOrBlank()) {
                Toast.makeText(this, R.string.ai_fallback_key_missing, Toast.LENGTH_LONG).show()
                refreshFallbackControl()
                return@setOnCheckedChangeListener
            }
            // Never transmit another provider's book fragments based on key setup alone.
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ai_fallback_consent_title)
                .setMessage(R.string.ai_fallback_consent_text)
                .setNegativeButton(android.R.string.cancel) { _, _ -> refreshFallbackControl() }
                .setOnCancelListener { refreshFallbackControl() }
                .setPositiveButton(R.string.ai_fallback_allow) { _, _ ->
                    AiFallbackConsent.setEnabled(this, true)
                    refreshFallbackControl()
                }.show()
            // The visual switch remains OFF until authorization is confirmed.
            groqFallbackToggle.setOnCheckedChangeListener(null)
            groqFallbackToggle.isChecked = false
            groqFallbackToggle.setOnCheckedChangeListener { _, next ->
                if (next) refreshFallbackControl()
            }
        }
    }

    private fun chooseGeminiModel() {
        val input = TextInputEditText(this).apply {
            setSingleLine()
            setText(AiModelSettings.current(this@AiSetupActivity))
            hint = getString(R.string.ai_gemini_model_hint)
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_gemini_model_title)
            .setMessage(R.string.ai_gemini_model_description)
            .setView(wrapper)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ai_gemini_model_save) { _, _ ->
                try {
                    AiModelSettings.save(this, input.text?.toString().orEmpty())
                    modelButton.text = getString(R.string.ai_gemini_model_label,
                        AiModelSettings.current(this))
                } catch (_: IllegalArgumentException) {
                    Toast.makeText(this, R.string.ai_gemini_model_invalid, Toast.LENGTH_LONG).show()
                }
            }.show()
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
                        fragments = listOf("Prueba de funcionamiento."), history = emptyList(),
                        geminiModel = AiModelSettings.current(applicationContext))
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
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brand.addView(TextView(this).apply {
            this.text = "‹"
            textSize = 30f
            gravity = Gravity.CENTER
            minHeight = dp(48)
            contentDescription = "Volver"
            setTextColor(getColor(R.color.bia_accent))
            setBackgroundResource(R.drawable.bg_premium_note)
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        brand.addView(TextView(this).apply {
            this.text = "BibliotecaAI"
            textSize = 20f
            setTypeface(Typeface.SERIF, Typeface.BOLD)
            setTextColor(getColor(R.color.bia_text))
            setPadding(dp(12), 0, 0, 0)
        })
        parent.addView(brand)
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 33f
            typeface = Typeface.SERIF
            setTextColor(getColor(R.color.bia_text))
            setPadding(0, dp(15), 0, dp(8))
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

    private fun action(parent: LinearLayout, text: String, primary: Boolean = false, onClick: () -> Unit) {
        parent.addView(MaterialButton(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 14f
            minHeight = dp(48)
            cornerRadius = dp(15)
            backgroundTintList = ColorStateList.valueOf(
                getColor(if (primary) R.color.bia_accent else R.color.bia_surface_high))
            setTextColor(getColor(if (primary) R.color.bia_background else R.color.bia_text))
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
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
