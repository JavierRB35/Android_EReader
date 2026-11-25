// Temporary JWT-protected deployment probe; no library mutation, no secret output.
// Replace with the reviewed gateway only after connection validation.
import postgres from "npm:postgres@3.4.7";

const headers = { "content-type": "application/json", "cache-control": "no-store" };
const reply = (status: number, data: unknown) => new Response(JSON.stringify(data), { status, headers });

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return reply(405, { ok: false });
  let sql: ReturnType<typeof postgres> | undefined;
  try {
    const connection = Deno.env.get("BIA_GATEWAY_DB_URL");
    if (!connection) return reply(503, { ok: false, stage: "secret_missing" });
    const parsed = new URL(connection);
    if (!["postgres:", "postgresql:"].includes(parsed.protocol) ||
        !/^bia_gateway_login(?:\.[a-z0-9]+)?$/.test(decodeURIComponent(parsed.username)) ||
        !parsed.hostname.endsWith(".pooler.supabase.com") || parsed.port !== "6543" ||
        parsed.pathname !== "/postgres" || !parsed.password || parsed.search || parsed.hash)
      return reply(503, { ok: false, stage: "secret_format" });
    sql = postgres(connection, {
      ssl: { rejectUnauthorized: true }, max: 1, prepare: false,
      connect_timeout: 5, idle_timeout: 2, onnotice: () => {},
    });
    const rows = await sql`select current_user = 'bia_gateway_login' as role_ok,
      has_function_privilege(current_user,'bia_coord.gateway_dispatch(text,text,jsonb)','execute') as dispatch_ok,
      not has_table_privilege(current_user,'bia_coord.devices','select') as private_ok`;
    const row = rows[0];
    const ok = row?.role_ok === true && row?.dispatch_ok === true && row?.private_ok === true;
    return reply(ok ? 200 : 503, { ok, stage: ok ? "verified" : "permissions" });
  } catch (_) {
    return reply(503, { ok: false, stage: "connection_failed" });
  } finally {
    if (sql) await sql.end({ timeout: 2 }).catch(() => {});
  }
});
