# Supabase — BibliotecaIA

Proyecto: `BibliotecaIA` (ref `krxdcfyvksjhpohxxvcf`, región eu-west-3, plan gratuito 0 €/mes — sin servicios facturables).

## Aplicar el esquema

1. Abrir el SQL Editor del proyecto en el panel de Supabase.
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
