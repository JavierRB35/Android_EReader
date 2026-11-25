-- Synthetic fixture only, always rolled back. Run against isolated test schema/project.
begin;
do $$
declare l uuid:=gen_random_uuid(); a uuid:=gen_random_uuid(); b uuid:=gen_random_uuid();
  op uuid:=gen_random_uuid(); x jsonb; rev bigint; hash text:=repeat('a',64);
begin
  insert into bia_coord.libraries(id) values(l);
  insert into bia_coord.devices(library_id,id) values(l,a),(l,b);
  x:=bia_coord.apply_change(l,a,op,'note','n1',0,'put','{"text":"A"}');
  if x->>'status'<>'applied' or (x->>'revision')::bigint<>1 then raise exception 'first write'; end if;
  x:=bia_coord.apply_change(l,b,gen_random_uuid(),'position',hash,0,'put','{"locator":"p2"}');
  if (x->>'revision')::bigint<>2 then raise exception 'disjoint change'; end if;
  x:=bia_coord.apply_change(l,a,op,'note','n1',0,'put','{"text":"A"}');
  if x->>'replayed'<>'true' or (x->>'revision')::bigint<>1 then raise exception 'idempotency'; end if;
  begin
    perform bia_coord.apply_change(l,a,op,'note','n1',0,'put','{"text":"different"}');
    raise exception 'id reuse accepted';
  exception when invalid_parameter_value then null; end;
  x:=bia_coord.apply_change(l,b,gen_random_uuid(),'note','n1',0,'put','{"text":"B"}');
  if x->>'status'<>'conflict' then raise exception 'lost note protection'; end if;
  x:=bia_coord.apply_change(l,a,gen_random_uuid(),'note','n1',1,'delete','{}');
  rev:=(x->>'revision')::bigint;
  if rev<>3 then raise exception 'delete revision'; end if;
  x:=bia_coord.apply_change(l,b,gen_random_uuid(),'note','n1',rev,'put','{"text":"stale"}');
  if x->>'status'<>'conflict' then raise exception 'tombstone resurrected'; end if;
  x:=bia_coord.apply_change(l,a,gen_random_uuid(),'note','n1',rev,'restore','{"text":"restored"}');
  if (x->>'revision')::bigint<>4 then raise exception 'explicit restore'; end if;
  if (select count(*) from bia_coord.entities where library_id=l)<>2 then raise exception 'entity count'; end if;
  if (select count(*) from bia_coord.operations where library_id=l)<>4 then raise exception 'receipt count'; end if;
  update bia_coord.devices set revoked_at=now() where library_id=l and id=b;
  begin
    perform bia_coord.apply_change(l,b,gen_random_uuid(),'note','n2',0,'put','{}');
    raise exception 'revoked device accepted';
  exception when insufficient_privilege then null; end;
  if has_schema_privilege('anon','bia_coord','USAGE') or has_schema_privilege('authenticated','bia_coord','USAGE')
    or has_schema_privilege('service_role','bia_coord','USAGE')
    or has_function_privilege('anon','bia_coord.apply_change(uuid,uuid,uuid,text,text,bigint,text,jsonb)','EXECUTE')
    or has_function_privilege('authenticated','bia_coord.apply_change(uuid,uuid,uuid,text,text,bigint,text,jsonb)','EXECUTE')
  then raise exception 'unexpected public access'; end if;
end $$;
rollback;
