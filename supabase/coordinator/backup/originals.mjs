import { createHash } from "node:crypto";
import { Readable } from "node:stream";

const SHA = /^[a-f0-9]{64}$/;
const ID = /^[A-Za-z0-9_-]{1,256}$/;
const MAX = 512 * 1024 * 1024;
const check = (ok, reason) => { if (!ok) throw new Error(reason); };
const isId = v => typeof v === "string" && ID.test(v);
const isHash = v => typeof v === "string" && SHA.test(v);

async function digest(stream, limit) {
  const hash = createHash("sha256");
  let size = 0;
  try {
    for await (const chunk of stream) {
      check(chunk instanceof Uint8Array, "invalid byte stream");
      size += chunk.length;
      check(size <= limit, "original exceeds declared size");
      hash.update(chunk);
    }
  } finally {
    if (typeof stream.destroy === "function") stream.destroy();
  }
  return { sha256: hash.digest("hex"), sizeBytes: size };
}
function same(actual, expected) {
  return actual.sha256 === expected.sha256 && actual.sizeBytes === expected.sizeBytes;
}
function validateMetadata(meta, binding) {
  check(meta && meta.id === binding.fileId && meta.trashed === false &&
    Array.isArray(meta.parents) && meta.parents.length === 1 && meta.parents[0] === binding.folderId &&
    meta.name === binding.sha256 + ".bin" &&
    meta.mimeType === "application/octet-stream" &&
    meta.size === String(binding.sizeBytes) && typeof meta.version === "string" && /^[0-9]+$/.test(meta.version),
    "original metadata does not match authorized reservation");
}

// Trusted backend only. A registry must atomically reserve one unique
// (libraryId, sha256) -> immutable fileId, and one fileId -> reservation.
// A candidate ID may be discarded but never uploaded before the winning
// reservation has been durably committed. No fallback to names/list search.
// Source must be a private, immutable staged file, not a client stream or URL.
export async function ensureOriginal({libraryId, folderId, sha256, sizeBytes, openSource}, registry, drive) {
  check(isId(libraryId) && isId(folderId) && isHash(sha256) &&
    Number.isSafeInteger(sizeBytes) && sizeBytes > 0 && sizeBytes <= MAX &&
    typeof openSource === "function", "invalid original request");
  const expected = {libraryId, folderId, sha256, sizeBytes};
  check(same(await digest(await openSource(), sizeBytes), expected), "staged original does not verify");
  // A lost response after reserve is safe: the registry must return the same
  // row for the next call even if another candidate has been generated.
  const candidate = await drive.generateId();
  check(isId(candidate), "invalid generated ID");
  const binding = await registry.reserve({...expected, candidateFileId:candidate});
  check(binding && isId(binding.fileId) && binding.libraryId === libraryId &&
    binding.folderId === folderId && same(binding, expected), "reservation mismatch");

  let meta = await drive.metadata(binding.fileId);
  if (meta === null) {
    // create only, with the pre-generated reserved ID. Conflict is not proof
    // of content integrity. Timeout/permission failure leaves reservation intact.
    await drive.createOnly(binding, openSource);
    meta = await drive.metadata(binding.fileId);
  }
  validateMetadata(meta, binding);
  const actual = await digest(await drive.download(binding.fileId), sizeBytes);
  check(same(actual, expected), "remote original hash mismatch");
  const after = await drive.metadata(binding.fileId);
  validateMetadata(after, binding);
  check(after.version === meta.version, "original changed during readback");
  // registry implementation must use CAS on reservation identity and version.
  await registry.markVerified({...binding, storageVersion:after.version});
  return {...binding, storageVersion:after.version};
}

// Google Drive v3 adapter. Only accesses IDs handed down by trusted reservation
// code. No listing, permissions, updates, deletes, or access to legacy files.
export class DriveOriginalAdapter {
  constructor({getAccessToken, fetchImpl = fetch}) {
    this.getAccessToken = getAccessToken;
    this.fetchImpl = fetchImpl;
  }
  async request(url, options = {}) {
    const token = await this.getAccessToken();
    check(typeof token === "string" && token.length > 0 && !/\s/.test(token), "Drive authorization missing");
    try {
      return await this.fetchImpl(url, {...options, redirect:"error",
        signal:AbortSignal.timeout(120_000),
        headers:{...options.headers, Authorization:"Bearer " + token}});
    } catch {
      throw new Error("Drive transfer failed; retain reservation and reconcile same ID");
    }
  }
  async json(response) {
    check(response.ok, "Drive request failed: " + response.status);
    try { return await response.json(); } catch { throw new Error("invalid Drive response"); }
  }
  async generateId() {
    const result = await this.json(await this.request("https://www.googleapis.com/drive/v3/files/generateIds?count=1&space=drive&type=files"));
    check(Array.isArray(result.ids) && result.ids.length === 1 && isId(result.ids[0]), "invalid Drive ID");
    return result.ids[0];
  }
  async metadata(fileId) {
    check(isId(fileId), "invalid file ID");
    const response = await this.request("https://www.googleapis.com/drive/v3/files/" + fileId +
      "?fields=id,name,parents,trashed,mimeType,size,version");
    if (response.status === 404) return null;
    return this.json(response);
  }
  async download(fileId) {
    check(isId(fileId), "invalid file ID");
    const response = await this.request("https://www.googleapis.com/drive/v3/files/" + fileId + "?alt=media");
    check(response.ok && response.body, "Drive readback failed: " + response.status);
    return response.body;
  }
  async createOnly(binding, openSource) {
    check(isId(binding.fileId) && isId(binding.folderId) && isHash(binding.sha256) &&
      Number.isSafeInteger(binding.sizeBytes) && binding.sizeBytes > 0 && binding.sizeBytes <= MAX,
      "invalid reserved original");
    const begin = await this.request("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id", {
      method:"POST", headers:{"Content-Type":"application/json; charset=UTF-8",
        "X-Upload-Content-Type":"application/octet-stream",
        "X-Upload-Content-Length":String(binding.sizeBytes)},
      body:JSON.stringify({id:binding.fileId,parents:[binding.folderId],
        name:binding.sha256 + ".bin",mimeType:"application/octet-stream"})
    });
    if (begin.status === 409) return; // caller must verify the actual bytes
    check(begin.ok, "Drive create session failed: " + begin.status);
    const location = begin.headers.get("location");
    let url;
    try { url = new URL(location); } catch { throw new Error("invalid upload session URL"); }
    check(url.origin === "https://www.googleapis.com" && url.pathname === "/upload/drive/v3/files" &&
      !url.username && !url.password && !url.hash && url.searchParams.has("upload_id"),
      "unexpected upload session URL");
    const input = await openSource();
    try {
      const result = await this.request(url.href, {method:"PUT",duplex:"half",
        headers:{"Content-Type":"application/octet-stream","Content-Length":String(binding.sizeBytes)},
        body:Readable.from(input)});
      check(result.ok || result.status === 409, "Drive create incomplete: " + result.status);
    } finally {
      if (typeof input.destroy === "function") input.destroy();
    }
  }
}
