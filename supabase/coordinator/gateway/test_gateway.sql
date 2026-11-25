-- Synthetic PostgreSQL test; rolls back all fixture writes.
begin;
insert into bia_coord.bootstrap_approvals(code_hash,expires_at)
values(bia_coord.secret_hash(repeat('a',64)),clock_timestamp()+interval '10 minutes'),
      (bia_coord.secret_hash(repeat('e',64)),clock_timestamp()-interval '1 second');
do $$
begin
  if has_schema_privilege('anon','bia_coord','usage') or has_schema_privilege('authenticated','bia_coord','usage')
    or has_schema_privilege('service_role','bia_coord','usage')
    or has_table_privilege('bia_gateway_caller','bia_coord.devices','select')
    or has_function_privilege('bia_gateway_caller','bia_coord.create_library(uuid,uuid,text)','execute')
    or has_function_privilege('bia_gateway_caller','bia_coord.apply_change(uuid,uuid,uuid,text,text,bigint,text,jsonb)','execute')
    or has_function_privilege('bia_gateway_caller','bia_coord.gateway_create_approved(uuid,uuid,text,text)','execute')
    or not has_function_privilege('bia_gateway_caller','bia_coord.gateway_dispatch(text,text,jsonb)','execute')
    then raise exception 'gateway privilege boundary violated'; end if;
end $$;
set local role bia_gateway_caller;
do $$
declare
  l uuid:=gen_random_uuid(); a uuid:=gen_random_uuid(); b uuid:=gen_random_uuid(); c uuid:=gen_random_uuid();
  ta text:=repeat('b',64); tb text:=repeat('c',64); approval text:=repeat('a',64);
  request jsonb; reply jsonb; code text; op uuid:=gen_random_uuid(); base_a jsonb; base_b jsonb;
begin
  base_a:=jsonb_build_object('libraryId',l,'deviceId',a);
  base_b:=jsonb_build_object('libraryId',l,'deviceId',b);
  request:=base_a||jsonb_build_object('approvalCode',repeat('d',64));
  reply:=bia_coord.gateway_dispatch('create',ta,request);
  if reply->>'http' <> '403' then raise exception 'unapproved create'; end if;
  reply:=bia_coord.gateway_dispatch('create',ta,base_a||jsonb_build_object('approvalCode',repeat('e',64)));
  if reply->>'http' <> '403' then raise exception 'expired approval'; end if;
  request:=base_a||jsonb_build_object('approvalCode',approval);
  reply:=bia_coord.gateway_dispatch('create',ta,request);
  if reply->>'http'<>'200' or reply->'data'->>'libraryId'<>l::text then raise exception 'approved create failed'; end if;
  if bia_coord.gateway_dispatch('create',ta,request)->>'http'<>'200' then raise exception 'idempotent bootstrap failed'; end if;
  if bia_coord.gateway_dispatch('create',tb,request)->>'http'<>'403' then raise exception 'bootstrap accepted wrong token'; end if;
  if bia_coord.gateway_dispatch('create',tb,request||jsonb_build_object('libraryId',gen_random_uuid(),'deviceId',b))->>'http'<>'403'
    then raise exception 'approval reused for another identity'; end if;
  reply:=bia_coord.gateway_dispatch('issue-pairing',ta,base_a);
  if reply->>'http'<>'200' then raise exception 'pairing failed'; end if;
  code:=reply->'data'->>'code';
  request:=base_b||jsonb_build_object('code',code);
  if bia_coord.gateway_dispatch('join',tb,request)->>'http'<>'200'
    or bia_coord.gateway_dispatch('join',tb,request)->'data'->>'replayed'<>'true'
    or bia_coord.gateway_dispatch('join',repeat('f',64),request||jsonb_build_object('deviceId',c))->>'http'<>'403'
    then raise exception 'join/replay/reuse failed'; end if;
  request:=base_a||jsonb_build_object('operationId',op,'kind','note','entityId','n1','expectedRevision',0,'action','put','payload',jsonb_build_object('body','A'));
  reply:=bia_coord.gateway_dispatch('apply',ta,request);
  if reply->>'http'<>'200' or reply->'data'->>'status'<>'applied' then raise exception 'apply failed'; end if;
  reply:=bia_coord.gateway_dispatch('apply',tb,request||base_b||jsonb_build_object('operationId',gen_random_uuid()));
  if reply->>'http'<>'200' or reply->'data'->>'status'<>'conflict' then raise exception 'conflict not preserved'; end if;
  reply:=bia_coord.gateway_dispatch('pull',tb,base_b||jsonb_build_object('after',0,'limit',10));
  if reply->>'http'<>'200' or reply->'data'->>'cursor'<>'1' or reply->'data'->'items'->0->'payload'->>'body'<>'A'
    then raise exception 'B did not receive A delta'; end if;
  if bia_coord.gateway_dispatch('pull',ta,base_b||jsonb_build_object('after',0,'limit',10))->>'http'<>'403'
    or bia_coord.gateway_dispatch('pull',tb,base_b||jsonb_build_object('after',0,'limit',11))->>'http'<>'400'
    or bia_coord.gateway_dispatch('revoke',ta,base_a||jsonb_build_object('targetDeviceId',b))->>'http'<>'403'
    then raise exception 'auth, limit or revoke boundary failed'; end if;
end $$;
reset role;
do $$
begin
  if (select count(*) from bia_coord.bootstrap_approvals where used_library is not null)<>1
    then raise exception 'bootstrap consumption violated'; end if;
  insert into bia_coord.gateway_budget(bucket,window_start,attempts) values('enrollment',date_trunc('minute',clock_timestamp()),20)
    on conflict(bucket) do update set window_start=excluded.window_start,attempts=excluded.attempts;
end $$;
set local role bia_gateway_caller;
do $$
begin
  if bia_coord.gateway_dispatch('create',repeat('a',64),'{}'::jsonb)->>'http'<>'429'
    then raise exception 'rate limit failed'; end if;
end $$;
reset role;
rollback;
