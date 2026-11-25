-- Coordinator v1, private and not connected to Android yet.
-- No auth.users, no public API, no Drive credentials. Existing legacy schema untouched.
begin;
create schema bia_coord;
revoke all on schema bia_coord from public, anon, authenticated, service_role;
create table bia_coord.libraries (
  id uuid primary key default gen_random_uuid(),
  revision bigint not null default 0 check (revision >= 0),
  created_at timestamptz not null default now()
);
create table bia_coord.devices (
  library_id uuid not null references bia_coord.libraries(id),
  id uuid not null,
  revoked_at timestamptz,
  primary key (library_id,id)
);
create table bia_coord.entities (
  library_id uuid not null references bia_coord.libraries(id),
  kind text not null check (kind in ('book','note','mark','position','category','tag','collection','book_category','book_tag','collection_book','synopsis','bookmark','annotation_version')),
  entity_id text not null check (length(entity_id) between 1 and 128),
  revision bigint not null check (revision > 0),
  deleted boolean not null default false,
  payload jsonb not null check (jsonb_typeof(payload)='object' and octet_length(payload::text)<=262144),
  primary key (library_id,kind,entity_id),
  check (kind not in ('book','position','synopsis') or entity_id ~ '^[a-f0-9]{64}$')
);
create index entities_revision_idx on bia_coord.entities(library_id,revision);
create table bia_coord.operations (
  library_id uuid not null,
  device_id uuid not null,
  operation_id uuid not null,
  request_hash bytea not null,
  revision bigint not null,
  primary key (library_id,device_id,operation_id),
  foreign key (library_id,device_id) references bia_coord.devices(library_id,id)
);
alter table bia_coord.libraries enable row level security;
alter table bia_coord.devices enable row level security;
alter table bia_coord.entities enable row level security;
alter table bia_coord.operations enable row level security;
revoke all on all tables in schema bia_coord from public, anon, authenticated, service_role;

-- Trusted backend only. Device membership must ALSO be authenticated at the
-- future gateway; an identifier is not a credential. No executable client role.
-- Compare-and-swap per entity, a monotonically increasing library revision,
-- stable hash keys and an idempotency receipt all under one database transaction.
create function bia_coord.apply_change(
  p_library uuid,p_device uuid,p_op uuid,p_kind text,p_entity text,
  p_expected bigint,p_action text,p_payload jsonb
) returns jsonb language plpgsql security invoker set search_path=pg_catalog as $$
declare
  v_revision bigint;
  v_current bigint;
  v_deleted boolean;
  v_hash bytea;
  v_receipt bia_coord.operations%rowtype;
  v_entity bia_coord.entities%rowtype;
begin
  if p_library is null or p_device is null or p_op is null or p_expected is null or p_expected < 0
    or p_kind is null or p_entity is null or length(p_entity) not between 1 and 128
    or p_kind not in ('book','note','mark','position','category','tag','collection','book_category','book_tag','collection_book','synopsis','bookmark','annotation_version')
    or (p_kind in ('book','position','synopsis') and p_entity !~ '^[a-f0-9]{64}$')
    or p_action is null or p_action not in ('put','delete','restore')
    or p_payload is null or jsonb_typeof(p_payload) <> 'object' or octet_length(p_payload::text)>262144
  then raise exception 'invalid operation' using errcode='22023'; end if;
  -- Serializes all accepted operations for a single library; separate libraries
  -- do not block each other. Membership revocation must take the same row lock.
  select revision into v_revision from bia_coord.libraries where id=p_library for update;
  if not found or not exists (select 1 from bia_coord.devices where library_id=p_library and id=p_device and revoked_at is null)
  then raise exception 'not authorized' using errcode='42501'; end if;
  v_hash := extensions.digest(convert_to(jsonb_build_array(p_kind,p_entity,p_expected,p_action,p_payload)::text,'UTF8'),'sha256');
  select * into v_receipt from bia_coord.operations
    where library_id=p_library and device_id=p_device and operation_id=p_op;
  if found then
    if v_receipt.request_hash <> v_hash then raise exception 'operation id reused' using errcode='22023'; end if;
    return jsonb_build_object('status','applied','revision',v_receipt.revision,'replayed',true);
  end if;
  select * into v_entity from bia_coord.entities where library_id=p_library and kind=p_kind and entity_id=p_entity;
  v_current:=coalesce(v_entity.revision,0);
  v_deleted:=coalesce(v_entity.deleted,false);
  if v_current<>p_expected or (v_deleted and p_action='put') or (p_action='restore' and not v_deleted) then
    return jsonb_build_object('status','conflict','revision',v_current,'deleted',v_deleted);
  end if;
  v_revision:=v_revision+1;
  update bia_coord.libraries set revision=v_revision where id=p_library;
  insert into bia_coord.entities(library_id,kind,entity_id,revision,deleted,payload)
    values(p_library,p_kind,p_entity,v_revision,p_action='delete',
      case when p_action='delete' then coalesce(v_entity.payload,'{}'::jsonb) else p_payload end)
    on conflict (library_id,kind,entity_id) do update set
      revision=excluded.revision,deleted=excluded.deleted,payload=excluded.payload;
  insert into bia_coord.operations values(p_library,p_device,p_op,v_hash,v_revision);
  return jsonb_build_object('status','applied','revision',v_revision,'replayed',false);
end;
$$;
revoke all on function bia_coord.apply_change(uuid,uuid,uuid,text,text,bigint,text,jsonb) from public, anon, authenticated, service_role;
commit;
