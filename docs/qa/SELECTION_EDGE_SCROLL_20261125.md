# Selección multipantalla — 25/11/2026

Se añade un controlador compartido de los extremos de selección de TextView en SavedMarkScrollView. Se conserva la selección nativa, sus spans y menú de acciones; cada extremo tiene su propio control de arrastre. La velocidad vertical aumenta cuadráticamente dentro de las franjas superior/inferior de 72 dp. Alejar el dedo del borde o soltarlo detiene el avance. Durante selección se deshabilitan los cambios de capítulo por gesto horizontal.

Alcance: EPUB en Selección precisa (un recurso del spine), MOBI (bloque cargado) y transcripción PDF/OCR (página actual). No se alteran los locators, los límites de interpretación IA, el PDF original ni la selección de WebView de Readium en Maquetación; esta última sigue dependiendo del motor. No se permite seleccionar entre capítulos, bloques o páginas OCR independientes. Android 29+ usa las APIs públicas de personalización de los tiradores; Android 26–28 conserva los nativos además de los controles ampliados.

QA: tests de velocidad y validación instrumentada del arrastre real, parada y mantenimiento de selección. Verificar también en Samsung físico antes de afirmar que queda resuelto en el dispositivo del propietario.

## Diagnóstico CI 25/11/2026
- HEAD d21607e: quality, annotation regression y journeys correctos. Startup API34: 17/22, cinco errores RootViewWithoutFocusException. Artefacto 15032972505: dumpsys window contiene ventana `Application Not Responding: com.google.android.apps.nexuslauncher`; energía Awake/StayOn, timeout máximo. Evidencia de interferencia del launcher, no demuestra que explique todos los errores. Startup conserva las 22 pruebas, sustituye imagen google_apis por AOSP default; SAF se simula y no necesita Google. No se ocultan ANR ni se relajan aserciones.
- API36: 3/5, dos errores RootViewWithoutFocusException al abrir modales EPUB/MOBI; API34 Scriptura (incluye prueba de autoscroll) correcto. Ambos jobs Scriptura pasan a QA aislado, conservan listas de clases y capturan diagnóstico al fallar. No se ha demostrado la causa exacta de API36; nuevo CI pendiente. Validación física no realizada.
