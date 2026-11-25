-- Candidate private gateway boundary. Apply only after review and CI.
-- No login role, credentials or public endpoint are provisioned here.
begin;
create role bia_gateway_caller nologin nosuperuser nocreatedb nocreaterole noinherit noreplication nobypassrls;
create table bia_coord.bootstrap_approvals (
  code_hash bytea primary key check (octet_length(code_hash)=32),
  expires_at timestamptz not null,
  used_library uuid,
  used_device uuid,
  check ((used_library is null) = (used_device is null)),
  foreign key (used_library,used_device) references bia_coord.devices(library_id,id)
);
create table bia_coord.gateway_budget (
  bucket text primary key check (bucket in ('enrollment','sync')),
  window_start timestamptz not null,
  attempts integer not null check (attempts>=0)
);
alter table bia_coord.bootstrap_approvals enable row level security;
alter table bia_coord.gateway_budget enable row level security;
revoke all on bia_coord.bootstrap_approvals,bia_coord.gateway_budget from public,anon,authenticated,service_role,bia_gateway_caller;

-- Fixed-size global budgets, committed even on rejected credentials because
-- dispatch catches business errors inside a subtransaction. Coarse protection
-- for a small private deployment, not a replacement for ingress limits.
create function bia_coord.gateway_take_budget(p_bucket text) returns boolean
language plpgsql security invoker set search_path=pg_catalog as $$
declare v_window timestamptz:=date_trunc('minute',clock_timestamp());
        v_attempts integer; v_limit integer;
begin
  v_limit:=case p_bucket when 'enrollment' then 20 when 'sync' then 600 else 0 end;
  if v_limit=0 then return false; end if;
  insert into bia_coord.gateway_budget(bucket,window_start,attempts)
    values(p_bucket,v_window,1)
    on conflict(bucket) do update set
      window_start=greatest(bia_coord.gateway_budget.window_start,excluded.window_start),
      attempts=case when bia_coord.gateway_budget.window_start<excluded.window_start then 1
        else least(bia_coord.gateway_budget.attempts+1,v_limit+1) end
    returning attempts into v_attempts;
  return v_attempts<=v_limit;
end $$;

-- The owner provisions only a random code HASH and expiry privately, never
-- raw codes in repository or logs. A replay after losing the response is valid
-- only for the original approved identity and the original device token.
create function bia_coord.gateway_create_approved(p_library uuid,p_device uuid,p_token text,p_approval text)
returns jsonb language plpgsql security invoker set search_path=pg_catalog as $$
declare v_approval bia_coord.bootstrap_approvals%rowtype; v_reply jsonb;
begin
  perform bia_coord.secret_hash(p_token);
  select * into v_approval from bia_coord.bootstrap_approvals
    where code_hash=bia_coord.secret_hash(p_approval) for update;
  if not found then raise exception 'not authorized' using errcode='42501'; end if;
  if v_approval.used_library is not null then
    if v_approval.used_library<>p_library or v_approval.used_device<>p_device then
      raise exception 'not authorized' using errcode='42501';
    end if;
    perform bia_coord.require_device(p_library,p_device,p_token);
    return jsonb_build_object('libraryId',p_library,'deviceId',p_device);
  end if;
  if v_approval.expires_at<=clock_timestamp() or exists(
    select 1 from bia_coord.libraries where id=p_library
  ) then raise exception 'not authorized' using errcode='42501'; end if;
  -- Handle a concurrent create with the same library id as a rejection,
  -- not as another approval's success. The insert is strict.
  insert into bia_coord.libraries(id) values(p_library);
  insert into bia_coord.devices(library_id,id,token_hash)
    values(p_library,p_device,bia_coord.secret_hash(p_token));
  update bia_coord.bootstrap_approvals set used_library=p_library,used_device=p_device
    where code_hash=v_approval.code_hash;
  return jsonb_build_object('libraryId',p_library,'deviceId',p_device);
end $$;

-- The only executable entrypoint for the future least-privileged server role.
-- SECURITY DEFINER is intentional: caller has no table/core privileges.
-- Private schema, fixed search_path, no dynamic SQL, explicit authorization
-- via core device tokens/approval/pairing, no unauthenticated create/revoke.
create function bia_coord.gateway_dispatch(p_operation text,p_token text,p_body jsonb)
returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
declare v_library uuid; v_device uuid; v_data jsonb; v_keys text[];
begin
  if not bia_coord.gateway_take_budget(
    case when p_operation in ('create','join') then 'enrollment' else 'sync' end
  ) then return jsonb_build_object('http',429); end if;
  begin
    if p_token is null or p_token !~ '^[a-f0-9]{64}$' then
      return jsonb_build_object('http',401);
    end if;
    if p_body is null or jsonb_typeof(p_body)<>'object' or octet_length(p_body::text)>270336
      then return jsonb_build_object('http',400); end if;
    v_keys:=case p_operation
      when 'create' then array['libraryId','deviceId','approvalCode']
      when 'join' then array['libraryId','deviceId','code']
      when 'issue-pairing' then array['libraryId','deviceId']
      when 'apply' then array['libraryId','deviceId','operationId','kind','entityId','expectedRevision','action','payload']
      when 'pull' then array['libraryId','deviceId','after','limit']
      else null end;
    if v_keys is null then return jsonb_build_object('http',403); end if;
    if not (p_body ?& v_keys) or exists(select 1 from jsonb_object_keys(p_body) as k(key) where not (k.key=any(v_keys)))
      then return jsonb_build_object('http',400); end if;
    if jsonb_typeof(p_body->'libraryId')<>'string' or jsonb_typeof(p_body->'deviceId')<>'string'
      or (p_body->>'libraryId') !~ '^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$'
      or (p_body->>'deviceId') !~ '^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$'
      then return jsonb_build_object('http',400); end if;
    v_library:=(p_body->>'libraryId')::uuid;
    v_device:=(p_body->>'deviceId')::uuid;
    case p_operation
      when 'create' then
        if jsonb_typeof(p_body->'approvalCode')<>'string' then return jsonb_build_object('http',400); end if;
        v_data:=bia_coord.gateway_create_approved(v_library,v_device,p_token,p_body->>'approvalCode');
      when 'join' then
        if jsonb_typeof(p_body->'code')<>'string' then return jsonb_build_object('http',400); end if;
        v_data:=bia_coord.join_library(v_library,p_body->>'code',v_device,p_token);
      when 'issue-pairing' then
        v_data:=bia_coord.issue_pairing(v_library,v_device,p_token);
      when 'apply' then
        if jsonb_typeof(p_body->'operationId')<>'string' or jsonb_typeof(p_body->'kind')<>'string'
          or jsonb_typeof(p_body->'entityId')<>'string' or jsonb_typeof(p_body->'action')<>'string'
          or jsonb_typeof(p_body->'expectedRevision')<>'number'
          or (p_body->>'expectedRevision') !~ '^[0-9]+$'
          or jsonb_typeof(p_body->'payload')<>'object'
          then return jsonb_build_object('http',400); end if;
        v_data:=bia_coord.apply_authenticated(v_library,v_device,p_token,(p_body->>'operationId')::uuid,
          p_body->>'kind',p_body->>'entityId',(p_body->>'expectedRevision')::bigint,
          p_body->>'action',p_body->'payload');
      when 'pull' then
        if jsonb_typeof(p_body->'after')<>'number' or (p_body->>'after') !~ '^[0-9]+$'
          or jsonb_typeof(p_body->'limit')<>'number' or (p_body->>'limit') !~ '^[0-9]+$'
          or (p_body->>'limit')::integer not between 1 and 10
          then return jsonb_build_object('http',400); end if;
        v_data:=bia_coord.pull_authenticated(v_library,v_device,p_token,(p_body->>'after')::bigint,(p_body->>'limit')::integer);
    end case;
    return jsonb_build_object('http',200,'data',v_data);
  exception
    when insufficient_privilege then return jsonb_build_object('http',403);
    when invalid_parameter_value or invalid_text_representation or numeric_value_out_of_range or unique_violation
      then return jsonb_build_object('http',400);
    when others then return jsonb_build_object('http',500);
  end;
end $$;
revoke all on function bia_coord.gateway_take_budget(text) from public,anon,authenticated,service_role,bia_gateway_caller;
revoke all on function bia_coord.gateway_create_approved(uuid,uuid,text,text) from public,anon,authenticated,service_role,bia_gateway_caller;
revoke all on function bia_coord.gateway_dispatch(text,text,jsonb) from public,anon,authenticated,service_role;
grant usage on schema bia_coord to bia_gateway_caller;
grant execute on function bia_coord.gateway_dispatch(text,text,jsonb) to bia_gateway_caller;
commit;
