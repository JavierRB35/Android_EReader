import test from "node:test";
import assert from "node:assert/strict";
import { makeHandler } from "./handler.mjs";
const root = "https://example.invalid/functions/v1/bia-coordinator/";
const token = "a".repeat(64);
const ids = { libraryId: "11223344-5566-7788-99aa-bbccddeeff00",
  deviceId: "aabbccdd-eeff-0011-2233-445566778899" };
function post(op, body, overrides = {}) {
  return new Request(root + op, { method: "POST",
    headers: { authorization: "Bearer " + token, "content-type": "application/json" },
    body: JSON.stringify(body), ...overrides });
}
test("authorized create passes exact approval, token and identity to dispatcher", async () => {
  const body = { ...ids, approvalCode: "b".repeat(64) };
  const handler = makeHandler(async (op, credential, request) => {
    assert.equal(op, "create"); assert.equal(credential, token); assert.deepEqual(request, body);
    return { http: 200, data: ids };
  });
  const result = await handler(post("create", body));
  assert.equal(result.status, 200); assert.deepEqual(await result.json(), ids);
  assert.equal(result.headers.get("cache-control"), "no-store");
});
test("rejects missing bearer, unknown routes, revoke, extra fields and unapproved create", async () => {
  let called = false;
  const handler = makeHandler(async () => { called = true; });
  assert.equal((await handler(post("create", ids))).status, 400);
  assert.equal((await handler(post("revoke", ids))).status, 403);
  assert.equal((await handler(post("pull?token=x", { ...ids, after: 0, limit: 1 }))).status, 403);
  assert.equal((await handler(post("issue-pairing", ids, { headers: { "content-type": "application/json" } }))).status, 401);
  assert.equal((await handler(post("issue-pairing", { ...ids, secret: token }))).status, 400);
  assert.equal((await handler(post("pull", { ...ids, after: 0, limit: 11 }))).status, 400);
  assert.equal((await handler(post("pull", { ...ids, after: Number.MAX_SAFE_INTEGER+1, limit: 10 }))).status, 400);
  assert.equal(called, false);
});
test("streaming size cap and invalid UTF-8 never reach SQL", async () => {
  const handler = makeHandler(async () => assert.fail("dispatcher invoked"));
  assert.equal((await handler(post("apply", { ...ids, blob: "x".repeat(270337) }))).status, 400);
  assert.equal((await handler(post("create", ids, { body: new Uint8Array([0xff,0xfe]) }))).status, 400);
});
test("returns conflict as 200, preserves 429, sanitizes failures and rejects oversized replies", async () => {
  const body = { ...ids, operationId: ids.deviceId, kind: "note", entityId: "n1",
    expectedRevision: 0, action: "put", payload: { body: "synthetic" } };
  const conflict = { status: "conflict", revision: 1, deleted: false };
  const ok = await makeHandler(async () => ({ http: 200, data: conflict }))(post("apply",body));
  assert.deepEqual(await ok.json(), conflict);
  const rate = await makeHandler(async () => ({ http: 429, secret: token }))(post("apply",body));
  assert.equal(rate.status,429); assert.equal(rate.headers.get("retry-after"),"60");
  assert.equal((await rate.text()).includes(token),false);
  const err = await makeHandler(async () => { throw new Error(token); })(post("apply",body));
  assert.equal(err.status,500); assert.equal((await err.text()).includes(token),false);
  const large = await makeHandler(async () => ({http:200,data:{text:"x".repeat(4*1024*1024)}}))(post("apply",body));
  assert.equal(large.status,500);
});
