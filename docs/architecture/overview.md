# Arquitectura — visión general

## Arquitectura vigente (26/11/2026)

- Android: Kotlin/XML, Room/SQLite, lectura EPUB/PDF/MOBI, cuaderno e IA BYOK opcional.
- Biblioteca única: carpeta de Google Drive elegida mediante Android SAF, originales legibles con nombres/extensiones reales, SHA-256 interno para deduplicación, metadatos separados en la subcarpeta técnica. Caché local y reconciliación offline; no se presupone atomicidad distribuida de SAF.
- Copia de seguridad: respaldo ZIP completo verificable cuando se solicite, separado de la vinculación a la biblioteca.
- GitHub: código, pruebas, documentación, APK firmada y checksum.
- No existe backend obligatorio, cuentas propias, OAuth propio, gateway, códigos de vinculación ni dependencia de Supabase. Esta arquitectura se ha descartado definitivamente por el propietario el 26/11/2026. Su implementación anterior permanece en el historial Git, no como alternativa activa. No se modifican servicios remotos ni datos existentes.
- Room conserva las tablas y migraciones históricas para permitir actualizaciones sin pérdida de información. Las credenciales de IA se aportan y guardan localmente, fuera de las copias.

## Organización

`app/`: Android; `docs/`: requisitos, arquitectura, QA y publicaciones; `tools/calibre-migrator/`: migrador independiente; `scripts/` y `.github/workflows/`: controles y publicación; `gradle/`: compilación.

## Contratos y validación

La cabecera de [#103](https://github.com/JavierRB35/Android_EReader/issues/103), la decisión posterior del propietario y [GOOGLE_DRIVE_ACCESS.md](GOOGLE_DRIVE_ACCESS.md) definen el acceso SAF. Conservar originales, notas, posiciones, clasificaciones, migraciones y pruebas de regresión. Las pruebas sintéticas no certifican el funcionamiento físico de Drive en dos dispositivos. Consultar el estado vivo y el registro de regresiones antes de afirmar aceptación funcional.
