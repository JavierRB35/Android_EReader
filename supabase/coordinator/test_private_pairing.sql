-- Synthetic data; executed in a transaction and rolled back.
begin;
do $$
declare l uuid:=gen_random_uuid(); other uuid:=gen_random_uuid(); a uuid:=gen_random_uuid();
  b uuid:=gen_random_uuid(); c uuid:=gen_random_uuid(); ta text:=repeat('a',64);
  tb text:=repeat('b',64); tc text:=repeat('c',64); code text; expired text; x jsonb;
begin
  perform bia_coord.create_library(l,a,ta);
  perform bia_coord.create_library(l,a,ta);
  perform bia_coord.create_library(other,c,tc);
  begin
    perform bia_coord.create_library(l,b,tb);
    raise exception 'duplicate creation unauthorized';
  exception when insufficient_privilege then null; end;
  x:=bia_coord.issue_pairing(l,a,ta);
  code:=x->>'code';
  x:=bia_coord.join_library(l,code,b,tb);
  if x->>'libraryId'<>l::text or x->>'replayed'<>'false' then raise exception 'join'; end if;
  x:=bia_coord.join_library(l,code,b,tb);
  if x->>'replayed'<>'true' then raise exception 'join retry'; end if;
  begin
    perform bia_coord.join_library(l,code,c,tc);
    raise exception 'pairing reused';
  exception when insufficient_privilege then null; end;
  expired:=bia_coord.issue_pairing(l,a,ta)->>'code';
  update bia_coord.pairings set expires_at=clock_timestamp()-interval '1 second'
    where library_id=l and code_hash=bia_coord.secret_hash(expired);
  begin
    perform bia_coord.join_library(l,expired,c,tc);
    raise exception 'expired code accepted';
  exception when insufficient_privilege then null; end;
  begin
    perform bia_coord.pull_authenticated(l,c,tc,0,10);
    raise exception 'cross-library read';
  exception when insufficient_privilege then null; end;
  begin
    perform bia_coord.apply_authenticated(l,b,ta,gen_random_uuid(),'note','n1',0,'put','{}');
    raise exception 'device id substituted';
  exception when insufficient_privilege then null; end;
  x:=bia_coord.apply_authenticated(l,a,ta,gen_random_uuid(),'note','n1',0,'put','{"text":"from A"}');
  if x->>'status'<>'applied' then raise exception 'write A'; end if;
  x:=bia_coord.apply_authenticated(l,b,tb,gen_random_uuid(),'position',repeat('d',64),0,'put','{"locator":"p2"}');
  if x->>'status'<>'applied' then raise exception 'write B'; end if;
  x:=bia_coord.pull_authenticated(l,b,tb,0,1);
  if x->>'cursor'<>'1' or jsonb_array_length(x->'items')<>1 then raise exception 'first page'; end if;
  x:=bia_coord.pull_authenticated(l,b,tb,1,10);
  if x->>'cursor'<>'2' or jsonb_array_length(x->'items')<>1 then raise exception 'second page'; end if;
  perform bia_coord.revoke_device(l,a,ta,b);
  begin
    perform bia_coord.pull_authenticated(l,b,tb,0,10);
    raise exception 'revoked device read';
  exception when insufficient_privilege then null; end;
  if (select count(*) from bia_coord.libraries where id=l)<>1
    or (select count(*) from bia_coord.devices where library_id=l)<>2
    or (select count(*) from bia_coord.entities where library_id=l)<>2 then raise exception 'single library invariant'; end if;
  if has_schema_privilege('anon','bia_coord','USAGE') or has_schema_privilege('service_role','bia_coord','USAGE')
    or has_function_privilege('anon','bia_coord.join_library(uuid,text,uuid,text)','EXECUTE')
    or has_function_privilege('authenticated','bia_coord.pull_authenticated(uuid,uuid,text,bigint,integer)','EXECUTE')
    or has_function_privilege('service_role','bia_coord.create_library(uuid,uuid,text)','EXECUTE')
    then raise exception 'unexpected direct client access'; end if;
end $$;
rollback;
