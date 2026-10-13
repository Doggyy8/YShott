import { createAuthHandler } from "./handler.ts";
function keyFrom(name: string, legacy: string): string {
  const keys = Deno.env.get(name);
  if (keys) return JSON.parse(keys).default || "";
  return Deno.env.get(legacy) || "";
}
const handler = createAuthHandler({
  url: Deno.env.get("SUPABASE_URL") || "",
  publicKey: keyFrom("SUPABASE_PUBLISHABLE_KEYS", "SUPABASE_ANON_KEY"),
  secretKey: keyFrom("SUPABASE_SECRET_KEYS", "SUPABASE_SERVICE_ROLE_KEY"),
});
Deno.serve(handler);
