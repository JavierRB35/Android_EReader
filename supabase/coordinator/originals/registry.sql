-- Private registry for immutable Drive originals. No actual Drive access.
begin;
create role bia_originals_caller nologin nosuperuser nocreatedb nocreaterole noinherit noreplication nobypassrls;
create table bia_coord.drive_folders (
  library_id uuid primary key references bia_coord.libraries(id),
  folder_id text not null unique check (length(folder_id) between 1 and 256 and folder_id ~ '^[A-Za-z0-9_-]+$'),
  unique(library_id,folder_id)
);
create table bia_coord.original_reservations (
  library_id uuid not null,
  folder_id text not null,
  sha256 text not null check (sha256 ~ '^[a-f0-9]{64}$'),
  file_id text not null unique check (length(file_id) between 1 and 256 and file_id ~ '^[A-Za-z0-9_-]+$'),
  size_bytes bigint not null check (size_bytes between 1 and 536870912),
  storage_version text check (storage_version is null or storage_version ~ '^[0-9]{1,30}$'),
  verified_at timestamptz,
  created_at timestamptz not null default now(),
  primary key (library_id,sha256),
  foreign key (library_id,folder_id) references bia_coord.drive_folders(library_id,folder_id),
  check ((storage_version is null) = (verified_at is null)),
  check (file_id<>folder_id)
);
alter table bia_coord.drive_folders enable row level security;
alter table bia_coord.original_reservations enable row level security;
revoke all on bia_coord.drive_folders,bia_coord.original_reservations from public,anon,authenticated,service_role,bia_originals_caller;

-- Only an authorized administrator may provision drive_folders after OAuth.
-- The trusted upload service receives device credentials, not arbitrary
-- folder authority; require_device serializes the reservation per library.
create function bia_coord.reserve_original(
  p_library uuid,p_device uuid,p_token text,p_folder text,p_hash text,p_size bigint,p_candidate text
) returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
declare v_folder text; v_row bia_coord.original_reservations%rowtype;
begin
  perform bia_coord.require_device(p_library,p_device,p_token);
  if p_hash is null or p_hash !~ '^[a-f0-9]{64}$'
    or p_size is null or p_size not between 1 and 536870912
    or p_candidate is null or length(p_candidate) not between 1 and 256 or p_candidate !~ '^[A-Za-z0-9_-]+$'
    then raise exception 'invalid original' using errcode='22023'; end if;
  select folder_id into v_folder from bia_coord.drive_folders where library_id=p_library;
  if not found or p_folder is distinct from v_folder then
    raise exception 'not authorized' using errcode='42501'; end if;
  select * into v_row from bia_coord.original_reservations
    where library_id=p_library and sha256=p_hash;
  if found then
    if v_row.size_bytes<>p_size or v_row.folder_id<>v_folder then
      raise exception 'reservation mismatch' using errcode='22023'; end if;
  else
    -- Reject ID collisions. Never update a winning reservation or upload a
    -- discarded candidate. The caller must wait for transaction commit.
    if p_candidate=v_folder or exists(select 1 from bia_coord.drive_folders where folder_id=p_candidate)
      then raise exception 'invalid candidate' using errcode='22023'; end if;
    insert into bia_coord.original_reservations(library_id,folder_id,sha256,file_id,size_bytes)
      values(p_library,v_folder,p_hash,p_candidate,p_size) returning * into v_row;
  end if;
  return jsonb_build_object('libraryId',v_row.library_id,'folderId',v_row.folder_id,
    'sha256',v_row.sha256,'fileId',v_row.file_id,'sizeBytes',v_row.size_bytes,
    'storageVersion',v_row.storage_version);
end $$;

-- Only the separate trusted storage worker calls this AFTER SHA-256 + size
-- readback and same-version metadata checks. Not an Android API or client claim.
-- A repeat may confirm the same version; unexpected versions fail closed.
create function bia_coord.verify_original(
  p_library uuid,p_device uuid,p_token text,p_folder text,p_hash text,
  p_size bigint,p_file text,p_version text
) returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
declare v_row bia_coord.original_reservations%rowtype;
begin
  perform bia_coord.require_device(p_library,p_device,p_token);
  if p_version is null or p_version !~ '^[0-9]{1,30}$'
    then raise exception 'invalid storage version' using errcode='22023'; end if;
  select * into v_row from bia_coord.original_reservations
    where library_id=p_library and sha256=p_hash for update;
  if not found or v_row.folder_id is distinct from p_folder or v_row.file_id is distinct from p_file
    or v_row.size_bytes is distinct from p_size
    or (v_row.storage_version is not null and v_row.storage_version<>p_version)
    then raise exception 'reservation mismatch' using errcode='22023'; end if;
  if v_row.storage_version is null then
    update bia_coord.original_reservations set storage_version=p_version,verified_at=clock_timestamp()
      where library_id=p_library and sha256=p_hash and storage_version is null
      returning * into v_row;
    if not found then raise exception 'reservation changed' using errcode='40001'; end if;
  end if;
  return jsonb_build_object('libraryId',v_row.library_id,'folderId',v_row.folder_id,
    'sha256',v_row.sha256,'fileId',v_row.file_id,'sizeBytes',v_row.size_bytes,
    'storageVersion',v_row.storage_version);
end $$;
revoke all on function bia_coord.reserve_original(uuid,uuid,text,text,text,bigint,text) from public,anon,authenticated,service_role;
revoke all on function bia_coord.verify_original(uuid,uuid,text,text,text,bigint,text,text) from public,anon,authenticated,service_role;
grant usage on schema bia_coord to bia_originals_caller;
grant execute on function bia_coord.reserve_original(uuid,uuid,text,text,text,bigint,text),
  bia_coord.verify_original(uuid,uuid,text,text,text,bigint,text,text) to bia_originals_caller;
commit;
