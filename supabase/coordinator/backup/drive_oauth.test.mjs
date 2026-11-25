import {test} from "node:test";
import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import {DRIVE_SCOPE,beginDriveAuthorization,exchangeDriveAuthorization,
  createDriveAccessTokenProvider,verifySelectedDriveFolder} from "./drive_oauth.mjs";

const cfg={clientId:"synthetic-id",clientSecret:"synthetic-secret",
  redirectUri:"https://example.invalid/admin/drive/callback"};
const now=1000000;
const ok=(overrides={})=>new Response(JSON.stringify({access_token:"synthetic-access",
  token_type:"Bearer",expires_in:3600,scope:DRIVE_SCOPE,refresh_token:"synthetic-refresh",...overrides}));
test("authorization requests offline file-only scope with state and PKCE",()=>{
  const a=beginDriveAuthorization(cfg,now),b=beginDriveAuthorization(cfg,now);
  const u=new URL(a.authorizationUrl),q=u.searchParams;
  assert.equal(u.origin,"https://accounts.google.com");
  assert.equal(q.get("scope"),DRIVE_SCOPE);
  assert.equal(q.get("access_type"),"offline");
  assert.equal(q.get("state"),a.transaction.state);
  assert.equal(q.get("code_challenge_method"),"S256");
  assert.equal(q.get("code_challenge"),createHash("sha256").update(a.transaction.verifier).digest("base64url"));
  assert.notEqual(a.transaction.state,b.transaction.state);
  assert.ok(!u.href.includes(cfg.clientSecret)&&!u.href.includes(a.transaction.verifier));
  assert.throws(()=>beginDriveAuthorization({...cfg,redirectUri:"http://example.invalid/callback"},now));
});
test("callback consumes state once and exchanges code on fixed HTTPS endpoint",async()=>{
  const {transaction}=beginDriveAuthorization(cfg,now);
  let available=transaction,calls=0;
  const consume=async s=>{const t=available;if(t?.state!==s)return null;available=null;return t;};
  const fetchImpl=async(url,options)=>{
    calls++;
    assert.equal(url,"https://oauth2.googleapis.com/token");
    assert.equal(options.redirect,"error");
    const params=new URLSearchParams(options.body);
    assert.equal(params.get("code_verifier"),transaction.verifier);
    assert.equal(params.get("grant_type"),"authorization_code");
    assert.equal(params.get("redirect_uri"),cfg.redirectUri);
    return ok();
  };
  const callback={state:transaction.state,code:"synthetic-code"};
  const result=await exchangeDriveAuthorization(cfg,callback,consume,{fetchImpl,now});
  assert.equal(result.refreshToken,"synthetic-refresh");
  await assert.rejects(exchangeDriveAuthorization(cfg,callback,consume,{fetchImpl,now}));
  assert.equal(calls,1);
});
test("rejects mismatched or expired state before network and excessive scope or missing refresh token",async()=>{
  const {transaction:t}=beginDriveAuthorization(cfg,now);
  const cb={state:t.state,code:"synthetic-code"};
  const never=()=>{assert.fail("network must not run");};
  await assert.rejects(exchangeDriveAuthorization(cfg,cb,async()=>({...t,state:"A".repeat(43)}),{fetchImpl:never,now}));
  await assert.rejects(exchangeDriveAuthorization(cfg,cb,async()=>t,{fetchImpl:never,now:t.expiresAt}));
  for(const over of [{scope:DRIVE_SCOPE+" https://www.googleapis.com/auth/drive"},{refresh_token:undefined},{scope:undefined}]){
    await assert.rejects(exchangeDriveAuthorization(cfg,cb,async()=>t,{fetchImpl:async()=>ok(over),now}),/Drive authorization unavailable/);
  }
});
test("refresh is single-flight, cached and fails closed without widening",async()=>{
  let time=now,calls=0;
  const get=createDriveAccessTokenProvider(cfg,async()=>"synthetic-refresh",{
    clock:()=>time,fetchImpl:async(url,options)=>{
      calls++;
      assert.equal(url,"https://oauth2.googleapis.com/token");
      assert.equal(new URLSearchParams(options.body).get("grant_type"),"refresh_token");
      return ok({scope:undefined,refresh_token:undefined});
    }});
  assert.deepEqual(await Promise.all([get(),get(),get()]),Array(3).fill("synthetic-access"));
  assert.equal(calls,1);
  time+=3600000;
  await get();assert.equal(calls,2);
  const bad=createDriveAccessTokenProvider(cfg,async()=>"secret-refresh",{
    fetchImpl:async()=>ok({scope:"https://www.googleapis.com/auth/drive"})});
  await assert.rejects(bad(),e=>e.message==="Drive authorization unavailable"&&!e.message.includes("secret-refresh"));
});
test("folder probe only reads explicitly selected folder and requires permission to add children",async()=>{
  const folder="selectedFolder";
  const meta={id:folder,mimeType:"application/vnd.google-apps.folder",trashed:false,capabilities:{canAddChildren:true}};
  const good=async(url,opts)=>{
    assert.equal(new URL(url).pathname,"/drive/v3/files/"+folder);
    assert.equal(opts.redirect,"error");
    assert.equal(opts.method,undefined);
    assert.equal(opts.headers.Authorization,"Bearer synthetic-access");
    return new Response(JSON.stringify(meta));
  };
  assert.deepEqual(await verifySelectedDriveFolder(folder,async()=>"synthetic-access",{fetchImpl:good}),{folderId:folder,canAddChildren:true});
  for(const patch of [{id:"other"},{trashed:true},{capabilities:{canAddChildren:false}},{mimeType:"text/plain"}]){
    await assert.rejects(verifySelectedDriveFolder(folder,async()=>"synthetic-access",{fetchImpl:async()=>new Response(JSON.stringify({...meta,...patch}))}));
  }
  await assert.rejects(verifySelectedDriveFolder("../private",async()=>"synthetic-access",{fetchImpl:()=>assert.fail("invalid folder")}));
});
