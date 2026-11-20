# ADR-008 (revisada) — Biblioteca IA sin cuentas y copia íntegra mediante SAF

**Estado: DECISIÓN VINCULANTE, 20/11/2026.** Sustituye la propuesta anterior de guardar solo metadatos con originales separados, y cualquier previsión de autenticación Google/Gmail/Supabase en la versión Android.

## Decisiones irrevocables sin nueva orden del propietario

1. **No usar OAuth ni registro/inicio de sesión con Gmail/Google ni cuentas Supabase.** Ninguna lectura, importación, nota, subrayado, IA BYOK ni copia solicita iniciar sesión. La PR #20 se cerró sin merge; los adaptadores de autenticación o sincronización heredados quedan deshabilitados/inaccesibles y no forman parte de V1.
2. **El usuario decide dónde guardar cada copia.** La app abre el selector del sistema mediante `ACTION_CREATE_DOCUMENT` / `ActivityResultContracts.CreateDocument("application/zip")`. Puede seleccionar Drive, memoria interna, USB u otro DocumentsProvider instalado. **Esto no pide OAuth a la aplicación ni permite recorrer toda la unidad Drive**; los permisos SAF se limitan al documento elegido. No presuponer que Drive estará instalado/disponible.
3. **Una copia completa contiene todo:** archivos físicos originales de **cada libro importado** (EPUB, PDF, MOBI), catálogo, categorías/etiquetas/colecciones, relaciones, favoritos, historial de notas, subrayados, marcadores, localizadores y posiciones. También incluye lápidas/historial de anotaciones en el JSON versionado; nunca incluir secretos, claves IA, tokens, sesiones ni credenciales.
4. **Formato transportable:** ZIP `BibliotecaIA-completa-YYYYMMDD-HHmm.zip`, con `biblioteca.json` y `books/<id>.bin` para cada libro. Se calcula/contrasta el **SHA-256** y tamaño originales en exportación y lectura antes de tocar Room. Se rechaza la copia si falta **un solo libro**. Los datos JSON siguen siendo el contrato histórico `BackupCodec`; la copia JSON antigua queda disponible únicamente como **importación heredada incompleta**, con advertencia.
5. **Restauración segura y aditiva:** leer ZIP al almacenamiento temporal con límites de expansión y validación de hashes; materializar originales sin sobrescribir ficheros distintos; restaurar Room en una transacción incluyendo asociación de rutas locales según hash. Si algo falla antes del commit, se limpian solo los archivos nuevos de ese intento. La operación debe ser reintentable y no destruir notas, marcados ni libros previos.
6. **La copia no es sincronización automática ni está cifrada**: el usuario controla frecuencia/ubicación. Advertir que el ZIP contiene íntegramente sus libros y anotaciones en claro; elegir ubicación privada. Drive/otro proveedor podrían requerir iniciar sesión **en su propia app**, no en Biblioteca IA.

## Criterios de aceptación

- Exportar e importar una copia que incluya **todos** los libros originales y sus anotaciones/versiones/locators, con ZIP situado mediante SAF donde el usuario elija.
- Ante PDF/EPUB/MOBI ausente, cambiado, truncado, hash erróneo, ZIP de rutas ajenas o bomba de descompresión, abortar sin restaurar parcialmente la base.
- Restaurar en otro dispositivo después de reinstalar: lectura de todos los originales sin revinculación manual, y marcas/notas/posiciones íntegramente disponibles, también tras cerrar/reabrir la app.
- Repetir la restauración no duplica libros o anotaciones, conserva contenido local preexistente y no retrocede en posiciones más recientes.
- QA Android real con Drive como DocumentsProvider y al menos otro destino, cortes de red durante copias, cancelación y espacio insuficiente; no afirmar esas pruebas hasta realizarlas.
- Sin APIs de Drive, Google OAuth, acceso Gmail, cuentas, sincronización Supabase ni registro del usuario.

## Compatibilidad

No se elimina el proyecto externo Supabase existente por el mero cambio de arquitectura: no es origen de verdad de V1 ni hace falta para backup. Se conserva código heredado sin conexión desde la navegación hasta que se retire técnicamente de forma segura. Para IA se mantiene BYOK local y el consentimiento explícito previo al envío de fragmentos a Gemini/Groq, independiente del backup.

Referencia oficial: [Android Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files).
