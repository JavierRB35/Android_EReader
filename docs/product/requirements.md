# Requisitos funcionales — Biblioteca IA (condensado)

Fuente completa: `docs/product/master-prompt.md` (Prompt Maestro multiagente). Este documento resume el alcance; en caso de duda prevalece el Prompt Maestro.

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

## 6. Cuentas y almacenamiento

- **Modo local sin cuenta**: importar, leer, anotar, categorías, cuaderno, exportar/restaurar copias. Datos en el dispositivo.
- **Modo autenticado (Google)**: Drive (scope mínimo `drive.file`), Supabase Auth, sincronización, IA, backups nube. Conversión local→cuenta: idempotente, reanudable, sin eliminar datos locales antes de verificar.
- Autenticación (identidad) ≠ autorización (Drive). Credential Manager; no OAuth obsoletos. Carpeta propia `Biblioteca IA` en Drive con identificadores estables; no borrar local antes de confirmar remoto.

## 7. Datos y sincronización

- Supabase PostgreSQL + RLS por usuario en todas las operaciones; multiusuario desde el diseño; Room local con operaciones pendientes.
- Sincronización incremental idempotente con detección de conflictos; **no perder anotaciones concurrentes** por sobrescritura silenciosa; posiciones de lectura sin retrocesos silenciosos.
- Protección V1: sincronización + copias periódicas + versiones de notas + papelera (30 días) + restauración verificada + exportación manual (sin credenciales). La sincronización NO es copia de seguridad; prever segunda vía independiente. Probar: eliminación/recuperación, interrupciones, corrupción, reinstalación, concurrencia.

## 8. Distribución y actualización

- GitHub Releases; comparar versionCode; descargar con integridad verificada; instalación por mecanismos autorizados de Android; mismo applicationId y firma; migraciones compatibles; nunca publicar keystore.

## 9. Seguridad transversal

TLS, mínimo privilegio, RLS, validación de entradas, límites de tamaño, protección ZIP bombs/traversal/SQL injection/prompt injection, logs sin secretos, no enviar libros completos a servicios externos por defecto, informar cuando se envíen fragmentos/imágenes a IA, no eludir DRM.

## 10. Entorno y calidad

- Compilación obligatoria en AndroidIDE-Rv2 (Gradle 8.9, AGP 8.7.3, SDK 34, JDK 17, `-Xmx768m`, sin daemon). Ver `AGENTS.md`.
- Etiquetado honesto: Implementado / Verificado localmente / Verificado en dispositivo / Verificado con servicio / Pendiente.
- Escenarios de aceptación A–L definidos en el Prompt Maestro §21.1.
- Fuera de alcance: pagos, tienda, redes sociales, colaboración, DRM circumvention.
