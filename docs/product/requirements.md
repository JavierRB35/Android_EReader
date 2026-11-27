# Requisitos funcionales — Biblioteca IA (condensado)

Fuente completa: `docs/product/master-prompt.md`. La directriz vigente de [#103](https://github.com/JavierRB35/Android_EReader/issues/103) y la decisión del propietario del 26/11/2026 prevalecen sobre pasajes históricos contradictorios. Supabase y OAuth propio quedan descartados definitivamente.

## 1. Formatos y lectura (V1)

- **EPUB** (candidato: Readium Kotlin Toolkit — verificar compatibilidad antes de integrar), **PDF** con texto y **PDF escaneado vía OCR** (investigar ML Kit), **MOBI** sin DRM (prueba técnica temprana obligatoria; no anunciar soporte completo si solo hay botón).
- Integridad en cada importación: validar formato/tamaño, hash, detectar duplicados, extraer metadatos, preservar original, permitir reintento; nunca mostrar registros incompletos como libros válidos.
- Localizadores estables para anotaciones (no solo número de página); las anotaciones conservan ubicación al cambiar tipografía o dispositivo.

## 2. Biblioteca

- Continuar leyendo, recientes, estados (favoritos/pendientes/en lectura/terminados/abandonados), colecciones, categorías (17 iniciales definidas en el Prompt Maestro §7.3), etiquetas (transversales), vista cuadrícula/lista, móvil y tablet.
- Metadatos completos (§7.2) editables manualmente. Búsqueda por título, autor, categoría, etiqueta, colección, texto indexado, notas y subrayados.
- Categoría ≠ etiqueta ≠ colección; múltiples relaciones sin duplicar libros.

## 3. Experiencia de lectura

- Tres espacios: Biblioteca, Leer, Conocimiento. Controles solo cuando el usuario los necesite.
- Configuración: tipografía, interlineado, márgenes, temas **OLED negro puro / oscuro / sepia / claro** (OLED prioritario: fondo #000000, texto cálido, sin blanco puro inicial), TTS del sistema.
- Tablet: paneles laterales opcionales (IA, anotaciones, índice). Eficiencia: no sincronizar por cada movimiento, agrupar progreso, sin conexiones permanentes en lectura. No prometer ahorro energético sin mediciones.

## 4. Anotaciones y conocimiento

- Subrayados (colores, notas, etiquetas, recuperar, buscar, navegar al origen), notas (fragmento/capítulo/libro/independientes, versiones), relaciones tipadas entre elementos, **cuaderno global** con búsqueda, filtros y exportación; distinguir claramente texto del libro / del usuario / de IA.

## 5. Inteligencia artificial

- Proveedor principal **Gemini API**; respaldo **GroqCloud API** (no confundir con Grok) mediante abstracción estable de proveedores y un único cambio controlado ante fallo recuperable.
- **Modo A (solo libro)**: responder solo con evidencia recuperada, con referencias navegables reales (libro/capítulo/localizador/fragmento); señalar insuficiencia; validar citas. **Modo B (libro + externo)**: separar contenido del libro y conocimiento externo.
- Pipeline RAG: extracción → segmentación con contexto → localizadores → índices/embeddings → recuperación selectiva (no enviar libros completos) → contexto acotado → validación de referencias.
- Filtro anti-spoilers aplicado antes de recuperar contexto. Consultas visuales bajo demanda (PDF/imágenes/tablas/gráficos) con página+coordenadas e historial.
- Asistente de configuración guiado de claves (no solo un campo): instrucciones para AI Studio y GroqCloud Console, prueba de conexión, diagnóstico diferenciado (clave inválida, cuota, facturación, modelo inexistente, etc.).
- Credenciales: aportadas por el usuario, cifradas, nunca en APK/repo/logs/backups. Historial persistente de conversaciones con proveedor/modelo/referencias/tokens.
- Blindar el sistema frente a prompt injection desde el contenido de libros.

## 6. Biblioteca única sin cuentas propias y respaldo independiente

- Una carpeta compartida seleccionada mediante SAF, sin OAuth propio, registro, gateway, código de vinculación ni Supabase. Vincular seleccionando la misma carpeta; comprobar permisos e identidad.
- Originales con nombre y extensión reales, deduplicación SHA-256, datos de sincronización separados. Catálogo completo y disponibilidad local diferenciados; lectura offline y reconciliación de cambios sin pérdida.
- Copia ZIP completa opcional e independiente de la vinculación/importación; preservar originales y datos, verificar hashes y restaurar sin sobrescrituras destructivas. No incluir credenciales ni secretos.
- No presuponer soporte de árboles o atomicidad de Drive; acreditar acceso y sincronización física A/B. No introducir otra arquitectura por incompatibilidades antiguas.

## 7. Datos y protección local

- Room/SQLite como persistencia de la instalación; la biblioteca compartida se reconcilia mediante SAF. Las tablas históricas se conservan para compatibilidad de actualización; no existe transporte Supabase.
- Importación por hash, notas versionadas, papelera, operaciones de respaldo/restauración reintentables y sin pérdida. Comprobar borrado y recuperación, interrupción, corrupción, reinstalación, pruebas con proveedor SAF y espacio insuficiente.
- Las copias las inicia el usuario. No afirmar una sincronización inexistente ni pedir credenciales para proteger su biblioteca.

## 8. Distribución y actualización

- GitHub Releases; comparar versionCode; descargar con integridad verificada; instalación por mecanismos autorizados de Android; mismo applicationId y firma; migraciones compatibles; nunca publicar keystore.

## 9. Seguridad transversal

TLS, mínimo privilegio, validación de entradas, límites de tamaño, protección ZIP bombs/traversal/SQL injection/prompt injection, logs sin secretos, no enviar libros completos a servicios externos por defecto, informar cuando se envíen fragmentos/imágenes a IA, no eludir DRM.

## 10. Entorno y calidad

- Compilación obligatoria en AndroidIDE-Rv2 (Gradle 8.9, AGP 8.7.3, SDK 34, JDK 17, `-Xmx768m`, sin daemon). Ver `AGENTS.md`.
- Etiquetado honesto: Implementado / Verificado localmente / Verificado en dispositivo / Verificado con servicio / Pendiente.
- Escenarios de aceptación A–L definidos en el Prompt Maestro §21.1.
- Fuera de alcance: pagos, tienda, redes sociales, colaboración, DRM circumvention.
