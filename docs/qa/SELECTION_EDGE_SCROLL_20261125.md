# Selección multipantalla — 25/11/2026

Se añade un controlador compartido de los extremos de selección de TextView en SavedMarkScrollView. Se conserva la selección nativa, sus spans y menú de acciones; cada extremo tiene su propio control de arrastre. La velocidad vertical aumenta cuadráticamente dentro de las franjas superior/inferior de 72 dp. Alejar el dedo del borde o soltarlo detiene el avance. Durante selección se deshabilitan los cambios de capítulo por gesto horizontal.

Alcance: EPUB en Selección precisa (un recurso del spine), MOBI (bloque cargado) y transcripción PDF/OCR (página actual). No se alteran los locators, los límites de interpretación IA, el PDF original ni la selección de WebView de Readium en Maquetación; esta última sigue dependiendo del motor. No se permite seleccionar entre capítulos, bloques o páginas OCR independientes. Android 29+ usa las APIs públicas de personalización de los tiradores; Android 26–28 conserva los nativos además de los controles ampliados.

QA: tests de velocidad y validación instrumentada del arrastre real, parada y mantenimiento de selección. Verificar también en Samsung físico antes de afirmar que queda resuelto en el dispositivo del propietario.
