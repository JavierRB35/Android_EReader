import test from "node:test";
import assert from "node:assert/strict";
import { preparePublication, confirmPublication } from "./publication.mjs";

const h = c => c.repeat(64);
const originals = [{sha256:h("a"),sizeBytes:100},{sha256:h("b"),sizeBytes:200}];
const snapshot = {libraryId:"L",revision:9,catalogueSha256:h("c"),originals};
const archive = {...snapshot,archiveSha256:h("d"),archiveSizeBytes:1000,missingHashes:[]};
const previous = {libraryId:"L",revision:8,fileId:"F",storageVersion:"v8",
  archiveSha256:h("e"),archiveSizeBytes:900};
const intent = () => preparePublication(snapshot,archive,previous,"F");
const write = {applied:true,fileId:"F",expectedStorageVersion:"v8",storageVersion:"v9"};
const read = {fileId:"F",storageVersion:"v9",archiveSha256:h("d"),archiveSizeBytes:1000};
test("complete archive replaces only the stable target and verifies readback",()=>{
  assert.deepEqual(confirmPublication(intent(),read,write),{
    libraryId:"L",revision:9,fileId:"F",storageVersion:"v9",
    archiveSha256:h("d"),archiveSizeBytes:1000});
});
test("reject missing, extraneous, duplicate and size-mismatched originals",()=>{
  for(const items of [originals.slice(0,1),[...originals,{sha256:h("f"),sizeBytes:1}],
    [originals[0],originals[0]], [{...originals[0],sizeBytes:101},originals[1]]])
    assert.throws(()=>preparePublication(snapshot,{...archive,originals:items},previous,"F"));
  assert.throws(()=>preparePublication(snapshot,{...archive,missingHashes:[h("a")]},previous,"F"));
});
test("reject wrong catalogue, revision, library, malformed hash",()=>{
  for(const patch of [{catalogueSha256:h("e")},{revision:10},{libraryId:"other"},{archiveSha256:"invalid"}])
    assert.throws(()=>preparePublication(snapshot,{...archive,...patch},previous,"F"));
});
test("reject stale revision and target change",()=>{
  assert.throws(()=>preparePublication(snapshot,archive,{...previous,revision:9},"F"));
  assert.throws(()=>preparePublication(snapshot,archive,previous,"G"));
});
test("reject absent CAS, stale token, wrong target and failed storage write",()=>{
  for(const proof of [null,{...write,applied:false},{...write,expectedStorageVersion:"v7"},
    {...write,fileId:"G"},{...write,storageVersion:"v8"}])
    assert.throws(()=>confirmPublication(intent(),read,proof));
});
test("reject corrupt, truncated, stale or wrong-file readback",()=>{
  for(const patch of [{archiveSha256:h("e")},{archiveSizeBytes:999},{storageVersion:"v8"},{fileId:"G"}])
    assert.throws(()=>confirmPublication(intent(),{...read,...patch},write));
});
test("initial publication requires a conditional create/empty target, not replacement",()=>{
  const first=preparePublication(snapshot,archive,null,"F");
  assert.equal(first.expectedStorageVersion,null);
  assert.throws(()=>confirmPublication(first,read,write));
  assert.equal(confirmPublication(first,read,{...write,expectedStorageVersion:null}).revision,9);
});
