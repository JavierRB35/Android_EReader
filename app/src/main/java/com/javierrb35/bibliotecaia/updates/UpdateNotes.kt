package com.javierrb35.bibliotecaia.updates

/** Plain, bounded release information; no HTML execution or remote links. */
internal object UpdateNotes {
    private const val MAX = 4000
    private const val FALLBACK = "No se ha publicado un resumen detallado de esta versión."
    fun fromReleaseBody(body: String): String {
        val lines = body.lineSequence().map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("Compilación del commit") &&
                !it.startsWith("Actualización Android firmada y comprobada.") &&
                !it.startsWith("Serie de desarrollo;") && !it.startsWith("APK firmada y verificada.") }
            .map { it.removePrefix("### ").removePrefix("## ").removePrefix("# ")
                .replace(Regex("^[-*]\\s+"), "• ") }
            .toList()
        val text = lines.joinToString("\n").trim()
        return if (text.length <= MAX) text else text.take(MAX - 1).trimEnd() + "…"
    }
    fun detail(notes: String): String = notes.ifBlank { FALLBACK }
    fun preview(notes: String): String {
        val first = notes.lineSequence().map { it.trim().removePrefix("• ").trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("Cambios", ignoreCase = true) }
            ?: FALLBACK
        return if (first.length <= 130) first else first.take(129).trimEnd() + "…"
    }
}
