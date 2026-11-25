-- Private pairing and authenticated coordinator operations. No public API.
-- This increment requires an authenticated, rate-limited TLS gateway before
-- Android can access it. Tokens are independently random 256-bit secrets.
begin;
alter table bia_coord.devices add column token_hash bytea;
alter table bia_coord.devices add constraint device_token_hash_length
  check (token_hash is null or octet_length(token_hash)=32);
create table bia_coord.pairings (
  library_id uuid not null references bia_coord.libraries(id),
  code_hash bytea not null check (octet_length(code_hash)=32),
  issued_by uuid not null,
  expires_at timestamptz not null,
  used_by uuid,
  primary key (library_id,code_hash),
  foreign key (library_id,issued_by) references bia_coord.devices(library_id,id),
  foreign key (library_id,used_by) references bia_coord.devices(library_id,id)
);
alter table bia_coord.pairings enable row level security;
revoke all on bia_coord.pairings from public, anon, authenticated, service_role;

create function bia_coord.secret_hash(p_secret text) returns bytea
language plpgsql security invoker set search_path=pg_catalog as $$
begin
  if p_secret is null or p_secret !~ '^[a-f0-9]{64}$' then
    raise exception 'invalid credential' using errcode='42501';
  end if;
  return extensions.digest(convert_to(p_secret,'UTF8'),'sha256');
end $$;

-- All changes to credentials/operations take the same per-library lock.
-- Device IDs alone never authorize anything.
create function bia_coord.require_device(p_library uuid,p_device uuid,p_token text)
returns bigint language plpgsql security invoker set search_path=pg_catalog as $$
declare v_revision bigint; v_hash bytea;
begin
  v_hash:=bia_coord.secret_hash(p_token);
  select revision into v_revision from bia_coord.libraries where id=p_library for update;
  if not found or not exists(select 1 from bia_coord.devices where library_id=p_library
    and id=p_device and revoked_at is null and token_hash=v_hash) then
    raise exception 'not authorized' using errcode='42501';
  end if;
  return v_revision;
end $$;

-- Client generates stable random IDs and token once, persists locally before
-- first request; retries after a lost response use exactly the same values.
create function bia_coord.create_library(p_library uuid,p_device uuid,p_token text)
returns jsonb language plpgsql security invoker set search_path=pg_catalog as $$
declare v_hash bytea; v_created uuid;
begin
  if p_library is null or p_device is null then raise exception 'invalid ids' using errcode='22023'; end if;
  v_hash:=bia_coord.secret_hash(p_token);
  insert into bia_coord.libraries(id) values(p_library) on conflict do nothing returning id into v_created;
  if v_created is not null then
    insert into bia_coord.devices(library_id,id,token_hash) values(p_library,p_device,v_hash);
  else
    perform bia_coord.require_device(p_library,p_device,p_token);
  end if;
  return jsonb_build_object('libraryId',p_library,'deviceId',p_device);
end $$;

create function bia_coord.issue_pairing(p_library uuid,p_device uuid,p_token text)
returns jsonb language plpgsql security invoker set search_path=pg_catalog as $$
declare v_code text; v_expiry timestamptz;
begin
  perform bia_coord.require_device(p_library,p_device,p_token);
  v_code:=encode(extensions.gen_random_bytes(32),'hex');
  v_expiry:=clock_timestamp()+interval '10 minutes';
  insert into bia_coord.pairings(library_id,code_hash,issued_by,expires_at)
    values(p_library,bia_coord.secret_hash(v_code),p_device,v_expiry);
  return jsonb_build_object('libraryId',p_library,'code',v_code,'expiresAt',v_expiry);
end $$;

create function bia_coord.join_library(p_library uuid,p_code text,p_device uuid,p_token text)
returns jsonb language plpgsql security invoker set search_path=pg_catalog as $$
declare v_pair bia_coord.pairings%rowtype; v_hash bytea;
begin
  if p_device is null then raise exception 'invalid device' using errcode='22023'; end if;
  v_hash:=bia_coord.secret_hash(p_token);
  perform 1 from bia_coord.libraries where id=p_library for update;
  if not found then raise exception 'invalid pairing' using errcode='42501'; end if;
  select * into v_pair from bia_coord.pairings where library_id=p_library
    and code_hash=bia_coord.secret_hash(p_code);
  if not found then raise exception 'invalid pairing' using errcode='42501'; end if;
  if v_pair.used_by is not null then
    if v_pair.used_by<>p_device then raise exception 'pairing already used' using errcode='42501'; end if;
    perform bia_coord.require_device(p_library,p_device,p_token);
    return jsonb_build_object('libraryId',p_library,'deviceId',p_device,'replayed',true);
  end if;
  if v_pair.expires_at<=clock_timestamp() or not exists (
    select 1 from bia_coord.devices where library_id=p_library and id=v_pair.issued_by and revoked_at is null)
  then raise exception 'invalid pairing' using errcode='42501'; end if;
  -- Do not replace a previous credential, including revoked credentials.
  if exists(select 1 from bia_coord.devices where library_id=p_library and id=p_device)
  then raise exception 'device already registered' using errcode='42501'; end if;
  insert into bia_coord.devices(library_id,id,token_hash) values(p_library,p_device,v_hash);
  update bia_coord.pairings set used_by=p_device where library_id=p_library and code_hash=v_pair.code_hash;
  return jsonb_build_object('libraryId',p_library,'deviceId',p_device,'replayed',false);
end $$;

create function bia_coord.revoke_device(p_library uuid,p_device uuid,p_token text,p_target uuid)
returns void language plpgsql security invoker set search_path=pg_catalog as $$
begin
  perform bia_coord.require_device(p_library,p_device,p_token);
  if p_target=p_device or p_target is null then raise exception 'invalid target' using errcode='22023'; end if;
  update bia_coord.devices set revoked_at=clock_timestamp()
    where library_id=p_library and id=p_target and revoked_at is null;
end $$;

create function bia_coord.apply_authenticated(
  p_library uuid,p_device uuid,p_token text,p_op uuid,p_kind text,p_entity text,
  p_expected bigint,p_action text,p_payload jsonb)
returns jsonb language plpgsql security invoker set search_path=pg_catalog as $$
begin
  perform bia_coord.require_device(p_library,p_device,p_token);
  return bia_coord.apply_change(p_library,p_device,p_op,p_kind,p_entity,p_expected,p_action,p_payload);
end $$;

-- Consistent latest-entity delta (including tombstones). Client commits the
-- returned cursor only after persisting the entire batch in a local transaction.
create function bia_coord.pull_authenticated(p_library uuid,p_device uuid,p_token text,
  p_after bigint,p_limit integer default 100)
returns jsonb language plpgsql security invoker set search_path=pg_catalog as $$
declare v_head bigint; v_next bigint; v_items jsonb; v_count integer;
begin
  v_head:=bia_coord.require_device(p_library,p_device,p_token);
  if p_after is null or p_after<0 or p_after>v_head or p_limit is null or p_limit not between 1 and 100
  then raise exception 'invalid cursor' using errcode='22023'; end if;
  select coalesce(jsonb_agg(to_jsonb(q) order by revision),'[]'::jsonb),max(revision),count(*)
  into v_items,v_next,v_count from (
    select kind,entity_id,revision,deleted,payload from bia_coord.entities
    where library_id=p_library and revision>p_after order by revision limit p_limit
  ) q;
  return jsonb_build_object('libraryId',p_library,'head',v_head,'cursor',
    case when v_count<p_limit then v_head else coalesce(v_next,p_after) end,'items',v_items);
end $$;
revoke all on all functions in schema bia_coord from public, anon, authenticated, service_role;
commit;
