// Candidate Supabase Edge Function; never fall back to SUPABASE_DB_URL or
// service-role. Provision the restricted login and secret out of band.
import postgres from "npm:postgres@3.4.7";
import { makeHandler } from "./handler.mjs";

const connection = Deno.env.get("BIA_GATEWAY_DB_URL");
if (!connection) throw new Error("Coordinator gateway is not provisioned");
const parsed = new URL(connection);
if (!["postgres:", "postgresql:"].includes(parsed.protocol) ||
    !/^bia_gateway_login(?:\.[a-z0-9]+)?$/.test(decodeURIComponent(parsed.username)) ||
    !parsed.password || parsed.search || parsed.hash) {
  throw new Error("Coordinator gateway credential is not valid");
}
const sql = postgres(connection, {
  ssl: { rejectUnauthorized: true },
  max: 1,
  prepare: false,
  connect_timeout: 5,
  idle_timeout: 10,
  onnotice: () => {},
});
Deno.serve(makeHandler(async (operation, token, body) => {
  const rows = await sql`select bia_coord.gateway_dispatch(${operation},${token},${sql.json(body)}) as envelope`;
  return rows[0]?.envelope;
}));
