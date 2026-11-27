package com.javierrb35.bibliotecaia.ai

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.javierrb35.bibliotecaia.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Two independent credentials. Never prefill, copy or persist a plaintext draft. */
class AiSetupActivity : AppCompatActivity() {
    private data class ProviderCard(
        val provider: String,
        val name: String,
        val input: TextInputEditText,
        val status: TextView,
        val save: MaterialButton,
        val test: MaterialButton,
        val delete: MaterialButton,
        var busy: Boolean = false
    )

    private lateinit var fallback: SwitchMaterial
    private lateinit var fallbackStatus: TextView
    private lateinit var modelButton: MaterialButton
    private val cards = mutableMapOf<String, ProviderCard>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
            isFocusableInTouchMode = true
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.bia_background))
            isFillViewport = true
            addView(layout)
        })

        header(layout, "Configurar inteligencia artificial")
        hint(layout, "Gemini es el proveedor principal y Groq el respaldo automático. Introduce las claves por separado; puedes utilizar solo uno de los servicios.")

        addProvider(layout, AiClients.GEMINI, "Gemini", "Proveedor principal", AiClients.GEMINI_KEYS_URL)
        modelButton = action(layout, getString(R.string.ai_gemini_model_label, AiModelSettings.current(this))) {
            chooseGeminiModel()
        }
        addProvider(layout, AiClients.GROQ, "Groq", "Proveedor de respaldo", AiClients.GROQ_KEYS_URL)

        section(layout, "Respaldo automático")
        hint(layout, "Si Gemini agota su cuota, falla temporalmente o supera el tiempo de espera, Groq puede recibir la misma consulta y sus fragmentos. Se activa por defecto al guardar una clave Groq; puedes desactivarlo aquí.")
        fallback = SwitchMaterial(this).apply {
            text = "Usar Groq automáticamente"
            contentDescription = "Respaldo automático con Groq"
            isChecked = AiFallbackConsent.preferred(this@AiSetupActivity)
            setTextColor(getColor(R.color.bia_text))
            minHeight = dp(48)
        }
        layout.addView(fallback)
        fallbackStatus = statusView(layout)
        fallback.setOnCheckedChangeListener { _, checked ->
            AiFallbackConsent.setEnabled(this, checked)
            refreshFallback()
        }
        refreshFallback()
        hint(layout, "Las claves se guardan cifradas en este dispositivo. La prueba usa una consulta de ejemplo, nunca tus libros. Los fragmentos reales solo se envían cuando consultas la IA.")
        layout.requestFocus()
    }

    private fun addProvider(parent: LinearLayout, provider: String, name: String, role: String, url: String) {
        section(parent, "$name · $role")
        val status = statusView(parent)
        val keyLayout = TextInputLayout(this).apply {
            hint = "Clave API de $name"
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            contentDescription = "Clave API de $name"
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setTextColor(getColor(R.color.bia_text))
        }
        keyLayout.addView(input)
        parent.addView(keyLayout)
        if (provider == AiClients.GROQ) {
            hint(parent, "Al guardar esta clave autorizas el respaldo automático descrito abajo, salvo que lo hayas desactivado.")
        }
        val save = action(parent, "Guardar clave $name", primary = true) { saveKey(cards.getValue(provider)) }
        val test = action(parent, "Probar $name") { testKey(cards.getValue(provider)) }
        val delete = action(parent, "Eliminar clave $name") { confirmDelete(cards.getValue(provider)) }
        action(parent, "Obtener clave de $name") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: android.content.ActivityNotFoundException) {
                Toast.makeText(this, "No hay un navegador disponible.", Toast.LENGTH_SHORT).show()
            }
        }
        val card = ProviderCard(provider, name, input, status, save, test, delete)
        cards[provider] = card
        refreshCard(card)
    }

    private fun statusView(parent: LinearLayout): TextView = TextView(this).apply {
        textSize = 14f
        setTextColor(getColor(R.color.bia_text_secondary))
        setPadding(0, dp(6), 0, dp(8))
        parent.addView(this)
    }

    private fun refreshCard(card: ProviderCard, message: String? = null) {
        val saved = !AiKeyStore.getKey(this, card.provider).isNullOrBlank()
        card.status.text = "${card.name}: " + (message ?: if (saved) "clave guardada (cifrada). Puedes probarla." else "sin clave configurada.")
        card.input.isEnabled = !card.busy
        card.save.isEnabled = !card.busy
        card.test.isEnabled = saved && !card.busy
        card.delete.isEnabled = saved && !card.busy
        if (card.provider == AiClients.GEMINI && ::modelButton.isInitialized) {
            modelButton.isEnabled = !card.busy
        }
    }

    private fun refreshFallback() {
        val hasGroq = !AiKeyStore.getKey(this, AiClients.GROQ).isNullOrBlank()
        fallbackStatus.text = when {
            !fallback.isChecked -> "Respaldo desactivado."
            !hasGroq -> "Preparado: falta guardar la clave Groq."
            else -> "Respaldo activo: Gemini → Groq."
        }
    }

    private fun saveKey(card: ProviderCard) {
        val key = card.input.text?.toString()?.trim().orEmpty()
        if (key.isBlank()) {
            card.input.error = "Introduce la clave de ${card.name}."
            return
        }
        // Preserve the reader's existing explicit opt-out; new configurations default to on.
        val preferred = AiFallbackConsent.preferred(this)
        if (!AiKeyStore.saveKey(this, card.provider, key)) {
            refreshCard(card, "no se pudo guardar la clave. La prueba no se ha realizado.")
            return
        }
        if (card.provider == AiClients.GROQ) {
            AiFallbackConsent.setEnabled(this, preferred)
        }
        card.input.text?.clear()
        card.input.error = null
        refreshCard(card)
        refreshFallback()
    }

    private fun testKey(card: ProviderCard) {
        val key = AiKeyStore.getKey(this, card.provider) ?: return
        val model = AiModelSettings.current(this)
        card.busy = true
        refreshCard(card, "probando conexión…")
        lifecycleScope.launch {
            val result = try {
                val answer = withContext(Dispatchers.IO) {
                    AiClients.ask(card.provider, key, "Responde únicamente: OK",
                        fragments = listOf("Prueba de funcionamiento."), history = emptyList(),
                        geminiModel = model)
                }
                "conexión correcta · ${answer.model}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiClients.AiException) {
                when (e.kind) {
                    AiClients.AiErrorKind.INVALID_KEY -> "clave inválida."
                    AiClients.AiErrorKind.PERMISSION -> "clave sin permiso."
                    AiClients.AiErrorKind.QUOTA -> "cuota agotada."
                    AiClients.AiErrorKind.MODEL -> "modelo no disponible."
                    AiClients.AiErrorKind.TIMEOUT -> "tiempo de espera agotado."
                    AiClients.AiErrorKind.NETWORK -> "sin conexión."
                    AiClients.AiErrorKind.SERVER -> "servicio temporalmente no disponible."
                    else -> "respuesta no válida del proveedor."
                }
            } catch (_: Exception) {
                "no se pudo comprobar la conexión."
            }
            card.busy = false
            refreshCard(card, result)
        }
    }

    private fun confirmDelete(card: ProviderCard) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Eliminar clave ${card.name}")
            .setMessage("Solo se eliminará la clave de ${card.name} de este dispositivo.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Eliminar") { _, _ ->
                AiKeyStore.deleteKey(this, card.provider)
                card.input.text?.clear()
                refreshCard(card)
                refreshFallback()
            }.show()
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
                    modelButton.text = getString(R.string.ai_gemini_model_label, AiModelSettings.current(this))
                    refreshCard(cards.getValue(AiClients.GEMINI))
                } catch (_: IllegalArgumentException) {
                    Toast.makeText(this, R.string.ai_gemini_model_invalid, Toast.LENGTH_LONG).show()
                }
            }.show()
    }

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

    private fun action(parent: LinearLayout, text: String, primary: Boolean = false, onClick: () -> Unit): MaterialButton {
        val button = MaterialButton(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 14f
            minHeight = dp(48)
            cornerRadius = dp(15)
            backgroundTintList = ColorStateList.valueOf(
                getColor(if (primary) R.color.bia_accent else R.color.bia_surface_high))
            setTextColor(getColor(if (primary) R.color.bia_background else R.color.bia_text))
            setOnClickListener { onClick() }
        }
        parent.addView(button, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(6)
        })
        return button
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
