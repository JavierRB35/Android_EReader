# ARCHIVADO — Sin Google OAuth ni cuentas (directriz 20/11/2026)

**No ejecutar este procedimiento.** El propietario ha decidido expresamente que
Biblioteca IA NO utilizará Google OAuth, cuentas Gmail, registro ni inicio de sesión
para leer, anotar, proteger o restaurar sus libros. La [PR #20](https://github.com/JavierRB35/Android_EReader/pull/20)
se cerró sin fusionar.

El usuario conserva la posibilidad de elegir **Drive, almacenamiento interno, USB u otro
DocumentsProvider** desde el selector SAF de Android al crear o abrir **copias completas**
que incluyen los archivos originales EPUB/PDF/MOBI y todas las anotaciones/versiones,
sin que Biblioteca IA necesite OAuth, acceso a cuentas, scopes o Google Drive API.

Para la implementación vigente seguir la [ADR-008 revisada](../architecture/adr-008-saf-drive-byok-v1.md)
y [requisitos](../product/requirements.md). La infraestructura anterior Supabase no se
elimina del servidor sin otra instrucción, pero no forma parte del flujo de V1.
