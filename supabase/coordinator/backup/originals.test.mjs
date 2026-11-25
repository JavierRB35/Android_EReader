import test from "node:test";
import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import {Readable} from "node:stream";
import {ensureOriginal, DriveOriginalAdapter} from "./originals.mjs";
const bytes = Buffer.from("synthetic original");
const sha256 = createHash("sha256").update(bytes).digest("hex");
const request = {libraryId:"library",folderId:"folder",sha256,sizeBytes:bytes.length,
  openSource:()=>Readable.from([bytes])};
function fixture() {
  const rows = new Map(), objects = new Map(), verified = [];
  let generated=0, uploads=0;
  const registry = {async reserve(r) {
    const key=r.libraryId+":"+r.sha256;
    if (!rows.has(key)) { const {candidateFileId,...rest}=r; rows.set(key,{...rest,fileId:candidateFileId}); }
    return rows.get(key);
  }, async markVerified(r) { verified.push(r); }};
  const drive = {async generateId(){return "id"+(++generated);},
    async metadata(id){return objects.get(id)?.meta ?? null;},
    async download(id){return Readable.from([objects.get(id).bytes]);},
    async createOnly(b){
      await Promise.resolve();
      if(objects.has(b.fileId))return;
      uploads++;
      objects.set(b.fileId,{bytes,meta:{id:b.fileId,name:b.sha256+".bin",parents:[b.folderId],
        trashed:false,mimeType:"application/octet-stream",size:String(b.sizeBytes),version:"1"}});
    }};
  return {registry,drive,rows,objects,verified,uploads:()=>uploads};
}
test("two concurrent imports share one definitive ID and one physical original",async()=>{
  const f=fixture();
  const results=await Promise.all([ensureOriginal(request,f.registry,f.drive),ensureOriginal(request,f.registry,f.drive)]);
  assert.equal(results[0].fileId,results[1].fileId);
  assert.equal(f.objects.size,1); assert.equal(f.uploads(),1); assert.equal(f.verified.length,2);
});
test("lost upload response retries the reserved ID without creating another object",async()=>{
  const f=fixture(), create=f.drive.createOnly;
  f.drive.createOnly=async b=>{await create(b);throw Error("lost response");};
  await assert.rejects(ensureOriginal(request,f.registry,f.drive),/lost response/);
  assert.equal(f.verified.length,0); assert.equal(f.objects.size,1);
  f.drive.createOnly=create;
  const result=await ensureOriginal(request,f.registry,f.drive);
  assert.equal(result.fileId,"id1"); assert.equal(f.uploads(),1);
});
test("invalid source never reserves or uploads",async()=>{
  const f=fixture();
  await assert.rejects(ensureOriginal({...request,openSource:()=>Readable.from([Buffer.from("wrong")])},f.registry,f.drive));
  assert.equal(f.rows.size,0); assert.equal(f.objects.size,0);
});
test("conflicting bytes, folder or version never verify and are not overwritten",async()=>{
  for(const mutate of [o=>o.bytes=Buffer.alloc(bytes.length),o=>o.meta.parents=["elsewhere"],
    o=>o.meta.size="0"]){
    const f=fixture();
    const first=await ensureOriginal(request,f.registry,f.drive);
    mutate(f.objects.get(first.fileId)); f.verified.length=0;
    await assert.rejects(ensureOriginal(request,f.registry,f.drive));
    assert.equal(f.verified.length,0); assert.equal(f.uploads(),1);
  }
  const f=fixture(), get=f.drive.metadata;
  await ensureOriginal(request,f.registry,f.drive);f.verified.length=0;
  let reads=0;f.drive.metadata=async id=>({...await get(id),version:String(++reads)});
  await assert.rejects(ensureOriginal(request,f.registry,f.drive),/changed during/);
  assert.equal(f.verified.length,0);
});
test("HTTP create uses reserved ID, dedicated parent and never follows redirects",async()=>{
  const calls=[];
  const fake=async(url,options)=>{
    calls.push({url,options});
    if(options.method==="POST")return new Response(null,{status:200,headers:{location:"https://www.googleapis.com/upload/drive/v3/files?upload_id=synthetic"}});
    for await(const _ of options.body){}
    return new Response("{}",{status:200});
  };
  const adapter=new DriveOriginalAdapter({getAccessToken:async()=>"synthetic",fetchImpl:fake});
  await adapter.createOnly({...request,fileId:"reserved"},request.openSource);
  assert.deepEqual(JSON.parse(calls[0].options.body),{id:"reserved",parents:["folder"],name:sha256+".bin",mimeType:"application/octet-stream"});
  assert.equal(calls[1].options.method,"PUT");assert.equal(calls[1].options.headers["Content-Length"],String(bytes.length));
  assert.ok(calls.every(c=>c.options.redirect==="error"));
});
test("reject foreign resumable URL and treat create conflict as readback requirement",async()=>{
  let calls=0;
  const adapter=new DriveOriginalAdapter({getAccessToken:async()=>"synthetic",fetchImpl:async()=>{
    calls++;return new Response(null,{status:200,headers:{location:"https://example.invalid/upload?upload_id=x"}});
  }});
  await assert.rejects(adapter.createOnly({...request,fileId:"reserved"},request.openSource),/unexpected upload/);
  assert.equal(calls,1);
  const conflict=new DriveOriginalAdapter({getAccessToken:async()=>"synthetic",fetchImpl:async()=>new Response(null,{status:409})});
  await conflict.createOnly({...request,fileId:"reserved"},request.openSource);
});
