// Web-standard HTTP boundary; no framework or provider credentials required
// for tests. The private dispatcher is the authority for every operation.
const MAX_REQUEST = 270336;
const MAX_RESPONSE = 4 * 1024 * 1024;
const OPS = new Set(["create", "join", "issue-pairing", "apply", "pull"]);
const STATUS = new Set([400, 401, 403, 429, 500]);
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const SECRET = /^[a-f0-9]{64}$/;
function response(status, data = null) {
  const headers = { "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store", "x-content-type-options": "nosniff" };
  if (status === 429) headers["retry-after"] = "60";
  return new Response(JSON.stringify(status === 200 ? data : { error: "coordinator_request_failed" }),
    { status, headers });
}
function object(value) { return value !== null && typeof value === "object" && !Array.isArray(value); }
async function readBody(request) {
  const advertised = request.headers.get("content-length");
  if (advertised !== null && (!/^[0-9]+$/.test(advertised) || Number(advertised) > MAX_REQUEST))
    throw new Error("limit");
  if (!request.body) throw new Error("body");
  const reader = request.body.getReader();
  const decoder = new TextDecoder("utf-8", { fatal: true });
  let count = 0, text = "";
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      count += value.byteLength;
      if (count > MAX_REQUEST) throw new Error("limit");
      text += decoder.decode(value, { stream: true });
    }
    return JSON.parse(text + decoder.decode());
  } catch (e) {
    await reader.cancel().catch(() => {});
    throw e;
  } finally { reader.releaseLock(); }
}
function validBody(operation, body) {
  if (!object(body) || !UUID.test(body.libraryId) || !UUID.test(body.deviceId) ||
      typeof body.libraryId !== "string" || typeof body.deviceId !== "string") return false;
  const keys = {
    create: ["approvalCode"], join: ["code"], "issue-pairing": [],
    apply: ["operationId","kind","entityId","expectedRevision","action","payload"],
    pull: ["after","limit"]
  }[operation].concat(["libraryId","deviceId"]);
  if (Object.keys(body).length !== keys.length || !keys.every(k => Object.hasOwn(body,k))) return false;
  if (operation === "create" || operation === "join") {
    const code = body[operation === "create" ? "approvalCode" : "code"];
    return typeof code === "string" && SECRET.test(code);
  }
  if (operation === "apply")
    return typeof body.operationId === "string" && UUID.test(body.operationId) &&
      typeof body.kind === "string" && typeof body.entityId === "string" &&
      typeof body.action === "string" && Number.isSafeInteger(body.expectedRevision) &&
      body.expectedRevision >= 0 && object(body.payload);
  if (operation === "pull")
    return Number.isSafeInteger(body.after) && body.after >= 0 &&
      Number.isSafeInteger(body.limit) && body.limit >= 1 && body.limit <= 10;
  return true;
}
export function makeHandler(dispatch) {
  return async request => {
    const url = new URL(request.url);
    // Supabase hosted Edge Function path includes the function slug;
    // local invocation may also supply its full /functions/v1 prefix.
    const match = url.pathname.match(/^\/(?:functions\/v1\/)?bia-coordinator\/([a-z-]+)$/);
    const operation = match?.[1];
    if (request.method !== "POST" || url.search || !OPS.has(operation)) return response(403);
    const auth = request.headers.get("authorization")?.match(/^Bearer ([a-f0-9]{64})$/);
    if (!auth) return response(401);
    if (request.headers.get("content-type")?.split(";")[0].trim().toLowerCase() !== "application/json")
      return response(400);
    let body;
    try {
      body = await readBody(request);
      if (!validBody(operation, body)) return response(400);
    } catch (_) { return response(400); }
    try {
      const envelope = await dispatch(operation, auth[1], body);
      if (!object(envelope)) return response(500);
      if (envelope.http !== 200) return response(STATUS.has(envelope.http) ? envelope.http : 500);
      if (!object(envelope.data)) return response(500);
      if (new TextEncoder().encode(JSON.stringify(envelope.data)).byteLength > MAX_RESPONSE)
        return response(500);
      return response(200, envelope.data);
    } catch (_) { return response(500); }
  };
}
