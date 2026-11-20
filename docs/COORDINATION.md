# Coordinación activa — Biblioteca IA

Fecha: 2026-11-20. Coordinador: chat Codex «Desarrollar Biblioteca IA para Android».
Integración: `feature/reader-integration`. Remoto canónico: `JavierRB35/Android_EReader`.

## Propiedad de trabajo

| Responsable | Rama / carpeta hermana | Archivos reservados |
|---|---|---|
| Coordinador | feature/reader-integration / biblioteca-integration | Room, migraciones, cuaderno, manifest, navegación entre lectores, pruebas integradas, DEVELOPMENT_STATE |
| UX | feature/design-system / biblioteca-ux | MainActivity, BookAdapter, activity_main, item_book, themes, recursos nuevos de diseño y portadas |
| EPUB | feature/reader-epub / biblioteca-epub | reader/epub, recursos/pruebas EPUB, dependencias Readium |
| PDF/MOBI | feature/reader-pdf-mobi / biblioteca-formats | reader/pdf, reader/mobi, recursos/pruebas de formatos |

Cada agente usa su worktree. No cambiar la rama del checkout de otro agente.
Antes de editar: comprobar estado, rama y nuevas referencias remotas. No sobrescribir trabajo ajeno.
Antes de integrar: revisar diff, contratos y pruebas; integrar solo commits propios del módulo,
sin duplicar el commit compartido de persistencia que los lectores incorporan para compilar.
Cada rama funcional se publica y confirma mediante `git ls-remote`.
No hay autorización para fusionar a main ni publicar una release.

## Contratos compartidos

`ReadingRepository(context)` ofrece `position(bookId)`, `savePosition(bookId, locator, progress)`
y `observeAnnotations(bookId)`. Room v3 conserva la biblioteca v2 mediante migración aditiva.
`AnnotationEditor.show(activity, bookId?, locator?, quote, kind)` guarda NOTE/HIGHLIGHT/BOOKMARK.
Cada lector expone `start(context, bookId, locatorJson: String? = null)` para navegar desde el cuaderno.
EPUB conserva Readium Locator JSON; PDF página base cero; MOBI offset textual estable.
Paleta UX: negro #000000, grafito #161819, texto #E8DFCE, acento #D4B483.

## Coordinación externa

El inventario de chats Codex no muestra otros chats activos de Biblioteca IA.
Se detectó un checkout externo de la copia original hacia main durante el trabajo.
El commit local ce402d8 contiene solo la persistencia creada por este coordinador y NO se ha enviado a main.
Su equivalente 21fb33e está aislado en feature/reader-integration. Se conserva el checkout original
para no interferir con otro proceso. Los agentes externos deben anunciar aquí rama, alcance y commit
antes de trabajar sobre archivos reservados. El coordinador espera identificación del canal externo.
