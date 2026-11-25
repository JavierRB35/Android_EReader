// Backend-only OAuth primitives. No public endpoint, real secret or admin session store.
import { randomBytes, createHash, timingSafeEqual } from "node:crypto";

export const DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file";
const TOKEN_URL = "https://oauth2.googleapis.com/token";
const AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
const STATE = /^[A-Za-z0-9_-]{43}$/;
const ID = /^[A-Za-z0-9_-]{1,256}$/;
const TTL = 10 * 60_000;
const fail = () => new Error("Drive authorization unavailable");
const requireValue = (ok) => { if (!ok) throw fail(); };
const secret = (v) => typeof v === "string" && v.length > 0 && v.length <= 8192 && !/[\s\x00-\x1f]/.test(v);
const exactScope = (v) => typeof v === "string" && v.trim() === DRIVE_SCOPE;

function config({clientId, clientSecret, redirectUri}) {
  requireValue(secret(clientId) && secret(clientSecret));
  let url;
  try { url = new URL(redirectUri); } catch { throw fail(); }
  requireValue(url.protocol === "https:" && !url.username && !url.password &&
    !url.hash && !url.search && url.pathname !== "/");
  return {clientId, clientSecret, redirectUri:url.href};
}
function equalState(a,b) {
  return typeof a === "string" && typeof b === "string" && STATE.test(a) && STATE.test(b) &&
    timingSafeEqual(Buffer.from(a),Buffer.from(b));
}
export function beginDriveAuthorization(settings, now=Date.now()) {
  const c=config(settings);
  requireValue(Number.isSafeInteger(now) && now > 0);
  const state=randomBytes(32).toString("base64url");
  const verifier=randomBytes(32).toString("base64url");
  const url=new URL(AUTH_URL);
  url.search=new URLSearchParams({
    client_id:c.clientId, redirect_uri:c.redirectUri, response_type:"code", scope:DRIVE_SCOPE,
    access_type:"offline", prompt:"consent", state,
    code_challenge:createHash("sha256").update(verifier).digest("base64url"),
    code_challenge_method:"S256"
  }).toString();
  // Persist transaction encrypted, bound to the authenticated owner session.
  // Only the URL is returned to the admin browser; never return the verifier.
  return {authorizationUrl:url.href, transaction:{state,verifier,expiresAt:now+TTL}};
}
async function tokenRequest(params, fetchImpl) {
  try {
    const res=await fetchImpl(TOKEN_URL,{method:"POST",redirect:"error",cache:"no-store",
      headers:{"Content-Type":"application/x-www-form-urlencoded","Accept":"application/json"},
      body:new URLSearchParams(params).toString(),signal:AbortSignal.timeout(15_000)});
    requireValue(res.ok);
    const data=await res.json();
    requireValue(secret(data.access_token) && typeof data.token_type === "string" &&
      data.token_type.toLowerCase()==="bearer" && Number.isSafeInteger(data.expires_in) &&
      data.expires_in>0 && data.expires_in<=86400);
    return data;
  } catch { throw fail(); }
}
// consumeTransaction is an authenticated server-side port. It MUST atomically
// consume the state from the same admin session before returning the record.
// A duplicate callback must return null even if the first exchange failed.
// No state or credentials originate from an Android request.
export async function exchangeDriveAuthorization(settings, {state,code}, consumeTransaction,
    {fetchImpl=fetch,now=Date.now()}={}) {
  const c=config(settings);
  requireValue(typeof consumeTransaction==="function" && typeof state==="string" && STATE.test(state) &&
    secret(code) && Number.isSafeInteger(now) && now>0);
  let txn;
  try { txn=await consumeTransaction(state); } catch { throw fail(); }
  requireValue(txn && equalState(state,txn.state) && STATE.test(txn.verifier) &&
    Number.isSafeInteger(txn.expiresAt) && txn.expiresAt>now && txn.expiresAt<=now+TTL);
  const data=await tokenRequest({client_id:c.clientId,client_secret:c.clientSecret,
    redirect_uri:c.redirectUri,code,code_verifier:txn.verifier,grant_type:"authorization_code"},fetchImpl);
  requireValue(exactScope(data.scope) && secret(data.refresh_token));
  // Caller stores refreshToken solely in backend secret storage, never logs it.
  return {accessToken:data.access_token,refreshToken:data.refresh_token,expiresAt:now+data.expires_in*1000};
}
// getRefreshToken loads only the verified grant from a trusted secret store.
// One in-flight refresh per process; no automatic widening or fallback.
export function createDriveAccessTokenProvider(settings,getRefreshToken,
    {fetchImpl=fetch,clock=Date.now}={}) {
  const c=config(settings);
  requireValue(typeof getRefreshToken==="function" && typeof clock==="function");
  let cached=null, pending=null;
  return async function getAccessToken() {
    const now=clock();
    if(cached && cached.expiresAt>now+60_000) return cached.token;
    if(!pending) pending=(async()=>{
      let refresh;
      try { refresh=await getRefreshToken(); } catch { throw fail(); }
      requireValue(secret(refresh));
      const data=await tokenRequest({client_id:c.clientId,client_secret:c.clientSecret,
        refresh_token:refresh,grant_type:"refresh_token"},fetchImpl);
      // The original grant was verified. Google may omit unchanged scope.
      requireValue(data.scope===undefined || exactScope(data.scope));
      cached={token:data.access_token,expiresAt:now+data.expires_in*1000};
      return cached.token;
    })().finally(()=>{pending=null;});
    return pending;
  };
}
// Read-only check of explicitly selected existing folder. Never searches Drive,
// grants permissions or assumes access to historical files under that folder.
export async function verifySelectedDriveFolder(folderId,getAccessToken,{fetchImpl=fetch}={}) {
  requireValue(typeof folderId==="string" && ID.test(folderId) && typeof getAccessToken==="function");
  try {
    const token=await getAccessToken();
    requireValue(secret(token));
    const url=new URL("https://www.googleapis.com/drive/v3/files/"+folderId);
    url.searchParams.set("fields","id,mimeType,trashed,capabilities(canAddChildren)");
    const res=await fetchImpl(url.href,{redirect:"error",cache:"no-store",
      headers:{Authorization:"Bearer "+token},signal:AbortSignal.timeout(15_000)});
    requireValue(res.ok);
    const data=await res.json();
    requireValue(data.id===folderId && data.mimeType==="application/vnd.google-apps.folder" &&
      data.trashed===false && data.capabilities?.canAddChildren===true);
    return {folderId,canAddChildren:true};
  } catch { throw fail(); }
}
