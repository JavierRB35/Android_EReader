begin;
-- A fixed, synthetic library with two independently authenticated devices.
select bia_coord.create_library('10000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000002',repeat('a',64));
select bia_coord.join_library('10000000-0000-4000-8000-000000000001',
  bia_coord.issue_pairing('10000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000002',repeat('a',64))->>'code',
  '10000000-0000-4000-8000-000000000003',repeat('b',64));
select bia_coord.create_library('10000000-0000-4000-8000-000000000004','10000000-0000-4000-8000-000000000005',repeat('c',64));
insert into bia_coord.drive_folders values
 ('10000000-0000-4000-8000-000000000001','folderA'),('10000000-0000-4000-8000-000000000004','folderB');
do $$
begin
  if has_table_privilege('bia_originals_caller','bia_coord.original_reservations','select')
    or has_table_privilege('bia_originals_caller','bia_coord.drive_folders','insert')
    or has_function_privilege('bia_originals_caller','bia_coord.create_library(uuid,uuid,text)','execute')
    or has_function_privilege('anon','bia_coord.reserve_original(uuid,uuid,text,text,text,bigint,text)','execute')
    or has_function_privilege('authenticated','bia_coord.verify_original(uuid,uuid,text,text,text,bigint,text,text)','execute')
    or has_function_privilege('service_role','bia_coord.verify_original(uuid,uuid,text,text,text,bigint,text,text)','execute')
    then raise exception 'original privilege boundary violated'; end if;
end $$;
set local role bia_originals_caller;
do $$
declare l uuid:='10000000-0000-4000-8000-000000000001';
  a uuid:='10000000-0000-4000-8000-000000000002'; b uuid:='10000000-0000-4000-8000-000000000003';
  l2 uuid:='10000000-0000-4000-8000-000000000004'; c uuid:='10000000-0000-4000-8000-000000000005';
  h text:=repeat('d',64); h2 text:=repeat('e',64); x jsonb;
begin
  x:=bia_coord.reserve_original(l,a,repeat('a',64),'folderA',h,1024,'fileWinner');
  if x->>'fileId'<>'fileWinner' or x->>'storageVersion' is not null then raise exception 'first reservation failed'; end if;
  x:=bia_coord.reserve_original(l,b,repeat('b',64),'folderA',h,1024,'fileDiscard');
  if x->>'fileId'<>'fileWinner' then raise exception 'retry created duplicate'; end if;
  begin
    perform bia_coord.reserve_original(l,b,repeat('b',64),'folderA',h,1025,'other');
    raise exception 'size mismatch accepted';
  exception when invalid_parameter_value then null; end;
  begin
    perform bia_coord.reserve_original(l,b,repeat('b',64),'folderB',h,1024,'other');
    raise exception 'foreign folder accepted';
  exception when insufficient_privilege then null; end;
  begin
    perform bia_coord.reserve_original(l,b,repeat('c',64),'folderA',h,1024,'other');
    raise exception 'wrong token accepted';
  exception when insufficient_privilege then null; end;
  begin
    perform bia_coord.reserve_original(l2,c,repeat('c',64),'folderB',h2,2048,'fileWinner');
    raise exception 'cross-library fileId collision accepted';
  exception when unique_violation then null; end;
  begin
    perform bia_coord.verify_original(l,b,repeat('b',64),'folderA',h,1024,'fileDiscard','1');
    raise exception 'discarded candidate verified';
  exception when invalid_parameter_value then null; end;
  x:=bia_coord.verify_original(l,a,repeat('a',64),'folderA',h,1024,'fileWinner','123');
  if x->>'storageVersion'<>'123' then raise exception 'verify failed'; end if;
  x:=bia_coord.verify_original(l,b,repeat('b',64),'folderA',h,1024,'fileWinner','123');
  if x->>'storageVersion'<>'123' then raise exception 'verify replay failed'; end if;
  begin
    perform bia_coord.verify_original(l,b,repeat('b',64),'folderA',h,1024,'fileWinner','124');
    raise exception 'version mismatch accepted';
  exception when invalid_parameter_value then null; end;
  x:=bia_coord.reserve_original(l,b,repeat('b',64),'folderA',h,1024,'anotherCandidate');
  if x->>'fileId'<>'fileWinner' or x->>'storageVersion'<>'123' then raise exception 'reservation changed'; end if;
end $$;
reset role;
do $$ begin
  if (select count(*) from bia_coord.original_reservations)<>1
    or not exists(select 1 from bia_coord.original_reservations where file_id='fileWinner' and verified_at is not null)
    then raise exception 'registry integrity failed'; end if;
end $$;
rollback;
