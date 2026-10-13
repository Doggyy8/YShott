export type AuthConfig = { url: string; publicKey: string; secretKey: string };
type Json = Record<string, unknown>;
const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const actions = new Set(["register", "login", "verify", "recover", "reset"]);
const headers = { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" };

function reply(status: number, body: Json): Response {
  return new Response(JSON.stringify(body), { status, headers });
}
async function bodyOf(request: Request): Promise<Json> {
  if (!request.body) throw new Error("invalid_body");
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const result = await reader.read();
      if (result.done) break;
      size += result.value.length;
      if (size > 4096) { await reader.cancel(); throw new Error("invalid_body"); }
      chunks.push(result.value);
    }
  } finally { reader.releaseLock(); }
  const all = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { all.set(chunk, offset); offset += chunk.length; }
  const body = JSON.parse(new TextDecoder().decode(all));
  if (!body || typeof body !== "object" || Array.isArray(body)) throw new Error("invalid_body");
  return body;
}
function sessionOf(body: Json, email: string): Json | null {
  const user = body.user as Json | undefined;
  if (!user || typeof user.id !== "string" || typeof user.email !== "string" ||
    user.email.toLowerCase() !== email || !user.email_confirmed_at ||
    typeof body.access_token !== "string" || typeof body.refresh_token !== "string" ||
    typeof body.expires_in !== "number") return null;
  return { access_token: body.access_token, refresh_token: body.refresh_token,
    expires_in: body.expires_in, token_type: "bearer", user_id: user.id, email: user.email };
}

/**
 * Public credential entry point. JWT checks cannot precede sign-in.
 * Custom authentication is performed through rate-limited PIN credential derivation,
 * followed by Supabase Auth email verification/password validation.
 * Never logs request bodies, PINs, derived passwords, service keys or tokens.
 */
export function createAuthHandler(config: AuthConfig, transport: typeof fetch = fetch) {
  const base = new URL(config.url);
  if (base.protocol !== "https:" || base.pathname !== "/" || base.username || base.password || base.search || base.hash || !config.publicKey || !config.secretKey)
    throw new Error("Missing secure auth configuration");
  async function api(path: string, payload: Json, privileged = false, bearer = "", method = "POST") {
    const key = privileged ? config.secretKey : config.publicKey;
    const requestHeaders: Record<string, string> = { apikey: key, "Content-Type": "application/json" };
    if (bearer) requestHeaders.Authorization = "Bearer " + bearer;
    else if (privileged && !key.startsWith("sb_secret_")) requestHeaders.Authorization = "Bearer " + key;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 15000);
    try {
    const response = await transport(new URL(path, base), {
      method, headers: requestHeaders, body: JSON.stringify(payload),
      signal: controller.signal, redirect: "error",
    });
    const json = await response.json().catch(() => ({})) as Json;
    return { ok: response.ok, status: response.status, body: json };
    } finally { clearTimeout(timer); }
  }
  return async (request: Request): Promise<Response> => {
    if (request.method !== "POST") return reply(405, { error: "method_not_allowed" });
    // Native Android sends no Origin. Browser callers are deliberately not granted CORS.
    if (request.headers.has("Origin")) return reply(403, { error: "native_client_required" });
    let body: Json;
    try { body = await bodyOf(request); } catch { return reply(400, { error: "invalid_request" }); }
    const action = typeof body.action === "string" ? body.action : "";
    const email = typeof body.email === "string" ? body.email.trim().toLowerCase() : "";
    const pin = typeof body.pin === "string" ? body.pin : "";
    const token = typeof body.token === "string" ? body.token : "";
    if (!actions.has(action) || email.length > 254 || !emailPattern.test(email) ||
      (action !== "recover" && !/^[0-9]{4}$/.test(pin)) ||
      ((action === "verify" || action === "reset") && !/^[0-9]{6,10}$/.test(token)))
      return reply(400, { error: "invalid_request" });
    try {
      const reserved = await api("/rest/v1/rpc/yshott_prepare_auth",
        { email, pin: action === "recover" ? null : pin, purpose: action === "verify" ? "reset" : action }, true);
      if (!reserved.ok) return reply(503, { error: "service_unavailable" });
      if (reserved.body.allowed !== true) return reply(429, { error: "try_later" });
      const password = reserved.body.password;
      if (action !== "recover" && (typeof password !== "string" || password.length !== 64))
        return reply(503, { error: "service_unavailable" });
      if (action === "register") {
        const result = await api("/auth/v1/signup", { email, password });
        if (!result.ok) return reply(503, { error: "registration_unavailable" });
        // No claim of delivery and no user-existence disclosure. Do not return signup sessions.
        return reply(200, { status: "confirmation_required" });
      }
      if (action === "recover") {
        // Same reply for an existing or absent account. Provider email delivery still needs testing.
        const recovered = await api("/auth/v1/recover", { email });
        if (recovered.status >= 500) return reply(503, { error: "service_unavailable" });
        return reply(200, { status: "recovery_requested" });
      }
      if (action === "verify" || action === "reset") {
        const verified = await api("/auth/v1/verify", { email, token, type: action === "reset" ? "recovery" : "email" });
        const verifiedSession = verified.ok ? sessionOf(verified.body, email) : null;
        if (!verifiedSession) return reply(401, { error: "invalid_code" });
        if (action === "reset") {
          const access = verifiedSession.access_token as string;
          const updated = await api("/auth/v1/user", { password }, false, access, "PUT");
          if (!updated.ok) return reply(503, { error: "reset_unavailable" });
          // Revoke other refresh sessions. Existing short-lived access tokens may remain valid.
          const signedOut = await api("/auth/v1/logout?scope=others", {}, false, access);
          if (!signedOut.ok) return reply(503, { error: "reset_unavailable" });
          const unlocked = await api("/rest/v1/rpc/yshott_finish_recovery", { email }, true);
          if (!unlocked.ok) return reply(503, { error: "reset_unavailable" });
          return reply(200, { status: "pin_changed", session: verifiedSession });
        }
      }
      const signedIn = await api("/auth/v1/token?grant_type=password", { email, password });
      const session = signedIn.ok ? sessionOf(signedIn.body, email) : null;
      if (!session) return reply(401, { error: "invalid_credentials_or_unconfirmed_email" });
      if (action === "login") {
        const deviceToken = typeof body.device_refresh_token === "string" && body.device_refresh_token.length <= 1024
          ? body.device_refresh_token : "";
        let trustedSession: Json | null = null;
        if (deviceToken) {
          const refreshed = await api("/auth/v1/token?grant_type=refresh_token", { refresh_token: deviceToken });
          trustedSession = refreshed.ok ? sessionOf(refreshed.body, email) : null;
        }
        // Password validation above creates a temporary session; never expose it before device proof.
        const closed = await api("/auth/v1/logout?scope=local", {}, false, session.access_token as string);
        if (!closed.ok) return reply(503, { error: "service_unavailable" });
        if (trustedSession) return reply(200, { status: "signed_in", session: trustedSession });
        const mailed = await api("/auth/v1/otp", { email, create_user: false });
        if (!mailed.ok) return reply(503, { error: "verification_unavailable" });
        return reply(200, { status: "device_verification_required" });
      }
      return reply(200, { status: "signed_in", session });
    } catch {
      return reply(503, { error: "service_unavailable" });
    }
  };
}
