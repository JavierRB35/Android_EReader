-- ============================================================================
-- 0001_initial_schema.sql — Biblioteca IA (Incremento 5-6)
-- Esquema multiusuario sobre auth.users de Supabase. Aislamiento estricto por
-- usuario mediante RLS (Prompt Maestro §15.3): ninguna tabla expone filas ajenas.
--
-- Aplicar como propietario del proyecto (SQL Editor o CLI):
--   supabase db execute --file supabase/migrations/0001_initial_schema.sql
-- Idempotente donde procede; los datos nunca se destruyen al reaplicar políticas.
--
-- Coste: usa solo Postgres incluido en el plan gratuito (sin pgvector ni Edge
-- Functions de pago). Sin claves en este archivo: la clave pública vive en el
-- cliente; la de servicio nunca sale del panel.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Utilidades
-- ---------------------------------------------------------------------------

create extension if not exists pgcrypto; -- gen_random_uuid()

create or replace function public.set_updated_at() returns trigger
language plpgsql as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

-- ---------------------------------------------------------------------------
-- Perfiles (1:1 con auth.users)
-- ---------------------------------------------------------------------------

create table if not exists public.profiles (
  id          uuid primary key references auth.users(id) on delete cascade,
  email       text not null,
  display_name text,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

alter table public.profiles enable row level security;

create policy profiles_select_own on public.profiles
  for select using (auth.uid() = id);
create policy profiles_insert_own on public.profiles
  for insert with check (auth.uid() = id);
create policy profiles_update_own on public.profiles
  for update using (auth.uid() = id) with check (auth.uid() = id);

-- El alta del perfil tras registrarse se realiza desde el cliente autenticado
-- (insert en profiles con su propio id). Sin triggers sobre auth.users para no
-- requerir permisos de superusuario al aplicar la migración.

-- ---------------------------------------------------------------------------
-- LIBROS (espejo de la tabla local `books`; UUID estable compartido)
-- ---------------------------------------------------------------------------

create table if not exists public.books (
  id             uuid primary key,
  user_id        uuid not null references auth.users(id) on delete cascade,
  title          text not null,
  authors        text,
  format         text not null check (format in ('EPUB', 'PDF', 'MOBI')),
  file_name      text not null,
  content_hash   text not null,          -- SHA-256; deduplicación por usuario
  size_bytes     bigint not null default 0,
  added_at       timestamptz not null default now(),
  last_opened_at timestamptz,
  reading_status text not null default 'PENDING'
                 check (reading_status in ('PENDING', 'READING', 'FINISHED', 'ABANDONED')),
  favorite       boolean not null default false,
  progress       real not null default 0,
  storage_path   text,                   -- objeto en Storage (Incremento de Drive/Storage)
  deleted_at     timestamptz,            -- lápida para sincronización (§15.5)
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now()
);

create unique index if not exists books_user_hash_active
  on public.books (user_id, content_hash) where deleted_at is null;
create index if not exists books_user_updated
  on public.books (user_id, updated_at desc);

alter table public.books enable row level security;
create policy books_own on public.books
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create trigger books_set_updated_at before update on public.books
  for each row execute function public.set_updated_at();

-- ---------------------------------------------------------------------------
-- CATEGORÍAS · ETIQUETAS · COLECCIONES (organización por usuario)
-- ---------------------------------------------------------------------------

create table if not exists public.categories (
  id         uuid primary key,
  user_id    uuid not null references auth.users(id) on delete cascade,
  name       text not null,
  parent_id  uuid references public.categories(id) on delete set null,
  sort_order integer not null default 0,
  deleted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists categories_user on public.categories (user_id);
alter table public.categories enable row level security;
create policy categories_own on public.categories
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create trigger categories_set_updated_at before update on public.categories
  for each row execute function public.set_updated_at();

create table if not exists public.tags (
  id         uuid primary key,
  user_id    uuid not null references auth.users(id) on delete cascade,
  name       text not null,
  deleted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create unique index if not exists tags_user_name on public.tags (user_id, name);
alter table public.tags enable row level security;
create policy tags_own on public.tags
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create trigger tags_set_updated_at before update on public.tags
  for each row execute function public.set_updated_at();

create table if not exists public.collections (
  id         uuid primary key,
  user_id    uuid not null references auth.users(id) on delete cascade,
  name       text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists collections_user on public.collections (user_id);
alter table public.collections enable row level security;
create policy collections_own on public.collections
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create trigger collections_set_updated_at before update on public.collections
  for each row execute function public.set_updated_at();

-- ---------------------------------------------------------------------------
-- RELACIONES N:M (usuario en cada fila: ninguna relación sin dueño, §15.2)
-- ---------------------------------------------------------------------------

create table if not exists public.book_categories (
  user_id     uuid not null references auth.users(id) on delete cascade,
  book_id     uuid not null references public.books(id) on delete cascade,
  category_id uuid not null references public.categories(id) on delete cascade,
  primary key (user_id, book_id, category_id)
);
alter table public.book_categories enable row level security;
create policy book_categories_own on public.book_categories
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create table if not exists public.book_tags (
  user_id uuid not null references auth.users(id) on delete cascade,
  book_id uuid not null references public.books(id) on delete cascade,
  tag_id  uuid not null references public.tags(id) on delete cascade,
  primary key (user_id, book_id, tag_id)
);
alter table public.book_tags enable row level security;
create policy book_tags_own on public.book_tags
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create table if not exists public.collection_books (
  user_id      uuid not null references auth.users(id) on delete cascade,
  collection_id uuid not null references public.collections(id) on delete cascade,
  book_id      uuid not null references public.books(id) on delete cascade,
  primary key (user_id, collection_id, book_id)
);
alter table public.collection_books enable row level security;
create policy collection_books_own on public.collection_books
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- ---------------------------------------------------------------------------
-- ANOTACIONES · VERSIONES · POSICIONES DE LECTURA
-- ---------------------------------------------------------------------------

create table if not exists public.annotations (
  id         uuid primary key,
  user_id    uuid not null references auth.users(id) on delete cascade,
  book_id    uuid references public.books(id) on delete cascade,
  locator    jsonb,                    -- localizador estable del formato
  quote      text not null default '',
  body       text not null default '',
  kind       text not null check (kind in ('NOTE', 'HIGHLIGHT', 'BOOKMARK')),
  color      integer not null default 0,
  deleted_at timestamptz,             -- papelera sincronizable
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists annotations_user_book on public.annotations (user_id, book_id);
create index if not exists annotations_user_updated on public.annotations (user_id, updated_at desc);
alter table public.annotations enable row level security;
create policy annotations_own on public.annotations
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create trigger annotations_set_updated_at before update on public.annotations
  for each row execute function public.set_updated_at();

create table if not exists public.annotation_versions (
  id            uuid primary key,
  user_id       uuid not null references auth.users(id) on delete cascade,
  annotation_id uuid not null references public.annotations(id) on delete cascade,
  body          text not null,
  color         integer not null default 0,
  saved_at      timestamptz not null default now()
);
create index if not exists annotation_versions_user_annotation
  on public.annotation_versions (user_id, annotation_id);
alter table public.annotation_versions enable row level security;
create policy annotation_versions_own on public.annotation_versions
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
-- Las versiones son inmutables: sin trigger updated_at ni UPDATE permitido.
create policy annotation_versions_no_update on public.annotation_versions
  for update using (false);

create table if not exists public.reading_positions (
  user_id    uuid not null references auth.users(id) on delete cascade,
  book_id    uuid not null references public.books(id) on delete cascade,
  locator    jsonb not null,           -- localizador estable del formato
  progress   real not null default 0,
  device     text,                     -- informativo para conflictos multidispositivo
  updated_at timestamptz not null default now(),
  primary key (user_id, book_id)
);
alter table public.reading_positions enable row level security;
create policy reading_positions_own on public.reading_positions
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- ---------------------------------------------------------------------------
-- Notas de operación
-- ---------------------------------------------------------------------------
-- · Respaldo del proyecto: Dashboard → Database → Backups (incluido en plan).
-- · Para consultar datos como propietario usar el SQL Editor; el cliente app
--   solo accede con su JWT y su rol anon/authenticated vía RLS.
-- · Nunca desactivar RLS ni crear políticas con roles de servicio en tablas de
--   usuario. El acceso con service_role queda reservado a futuras Edge Functions.
