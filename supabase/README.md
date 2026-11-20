# Supabase — BibliotecaIA

Proyecto: `BibliotecaIA` (ref `krxdcfyvksjhpohxxvcf`, región eu-west-3, plan gratuito 0 €/mes — sin servicios facturables).

## Despliegue confirmado del propietario — 20/11/2026

El esquema **ya fue aplicado** en `BibliotecaIA` mediante el conector oficial de Supabase, usando la versión endurecida de [PR #5](https://github.com/JavierRB35/Android_EReader/pull/5), **no** el SQL original de `main`. No volver a ejecutar `0001_initial_schema.sql` sobre este proyecto: las políticas no son recreables sin una migración específica.

Migraciones registradas en remoto:
- `20261120145506_initial_schema_hardened`
- `20261120145627_secure_updated_at_search_path`

Verificación realizada por consultas directas: 11 tablas con RLS (11/11), 14 políticas, 10 FK compuestas de mismo propietario, 10 columnas `user_id` con `DEFAULT auth.uid()`; `anon` sin SELECT sobre `books`; `authenticated` con SELECT; `annotation_versions` sin UPDATE ni DELETE para `authenticated`. Advisor de **seguridad: 0 avisos** tras fijar `search_path=pg_catalog` en la función `set_updated_at`. Los advisories de rendimiento (FK sin índice y RLS initplan) quedan para una revisión posterior por volumen y trazas reales, no equivalen a fallos de seguridad.

**IMPORTANTE, errores pendientes de la aplicación:** `SyncEngine.dropPoisoned` elimina de la cola cambios en casi cualquier `Result.Failure` (incluidos códigos 401/403 y 5xx), lo que puede hacer perder sincronizaciones sin que el usuario lo sepa. Corregir: NO descartar entradas ante fallo temporal, de autorización ni de RLS; retener cambios, permitir reintento y distinguir errores definitivos. Comprobar además que las escrituras reales invocan `enqueue` (no basta con implementar el método). No declarar sincronización lista hasta test real contra dos usuarios, datos conflictivos y modo avión.

No existe aún verificación real de OAuth, Drive o IA y la URL + clave publicable deben configurarse **solo en el equipo/dispositivo que compile** la aplicación. No se han incorporado secretos a Git.

---
## Aplicar el esquema

1. **Solo para proyectos vacíos nuevos o entornos de pruebas:** abrir el SQL Editor del proyecto que corresponda. En el proyecto BibliotecaIA ya está aplicado; no repetir.
2. Pegar y ejecutar completo `supabase/migrations/0001_initial_schema.sql`.
3. Verificar: `Table editor` debe mostrar `profiles, books, categories, tags, collections, book_categories, book_tags, collection_books, annotations, annotation_versions, reading_positions`, todas con **RLS habilitado** (candado cerrado).

Alternativa por CLI (opcional): `supabase db execute --file supabase/migrations/0001_initial_schema.sql` con el proyecto enlazado.

## Contrato de sincronización (Incremento 5-6)

- **Identidad de fila**: los UUID se generan en el dispositivo y son la clave primaria
  local y remota → inserciones idempotentes (repetir un push no duplica).
- **Aislamiento**: cada fila remota lleva `user_id`; RLS restringe todo a `auth.uid()`.
  El cliente nunca declara `user_id`: lo fija la política `with check (auth.uid() = user_id)`.
- **Dirección**: offline-first. El dispositivo local es la fuente primaria; Supabase
  replica para continuidad multidispositivo. La app empuja cambios pendientes y extrae
  filas con `updated_at > último_sync`.
- **Conflictos**: sin sobrescritura silenciosa (§15.5). Posiciones: gana la más reciente
  por `updatedAt` (ya implementado así en local con `upsertPositionIfNewer`). Anotaciones:
  historia de versiones conserva el contenido anterior; nunca se pierde texto.
- **Borrados**: lápidas (`deleted_at`) sincronizadas; la papelera local mapea a lápida remota.

## Claves

- El cliente usa **solo** la clave pública (`sb_publishable_...` o `anon`) desde el
  `local.properties` privado del equipo (`BIBLIOTECA_SUPABASE_URL`, `BIBLIOTECA_SUPABASE_KEY`).
  `app/build.gradle` ya valida que la clave sea anón/publicable y jamás acepta `service_role`.
- La clave `service_role`/`sb_secret_` queda exclusivamente en el panel (futuras Edge
  Functions). Nunca en repo, APK, issues ni chats.
