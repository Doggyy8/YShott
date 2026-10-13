import { createAuthHandler, type AuthConfig } from "./handler.ts";
const config: AuthConfig = { url: "https://example.supabase.co", publicKey: "sb_publishable_fixture", secretKey: "sb_secret_fixture" };
const strong = "a".repeat(64);
const session = { access_token: "fixture-user-access", refresh_token: "fixture-refresh", expires_in: 900,
 user: { id: "fixture-user", email: "parent@example.invalid", email_confirmed_at: "2026-10-13T00:00:00Z" } };
function assert(ok: unknown, message = "Assertion failed"): asserts ok { if (!ok) throw new Error(message); }
type Call = { url: URL; options: RequestInit; body: Record<string, unknown>; headers: Headers };
function fixture(responses: Array<{ status?: number; body: unknown }>) {
 const calls: Call[] = [];
 const transport: typeof fetch = async (input, options) => {
  const url = new URL(input instanceof Request ? input.url : input.toString());
  calls.push({ url, options: options || {}, body: JSON.parse(options?.body as string), headers: new Headers(options?.headers) });
  const response = responses.shift();
  if (!response) throw new Error("Unexpected provider call");
  return new Response(JSON.stringify(response.body), { status: response.status || 200, headers: { "Content-Type": "application/json" } });
 };
 return { calls, handler: createAuthHandler(config, transport) };
}
function request(body: unknown, extraHeaders: Record<string,string> = {}) {
 return new Request("https://example.supabase.co/functions/v1/yshott-auth", {
  method: "POST", headers: { "Content-Type": "application/json", ...extraHeaders }, body: JSON.stringify(body)
 });
}
const login = { action: "login", email: "parent@example.invalid", pin: "1234" };
const allowed = { body: { allowed: true, password: strong } };

Deno.test("GET requests do not contact the credential service", async () => {
 const f = fixture([]);
 assert((await f.handler(new Request("https://example.supabase.co"))).status === 405);
 assert(f.calls.length === 0);
});
Deno.test("Browser origin is not granted access", async () => {
 const f = fixture([]);
 assert((await f.handler(request(login, { Origin: "https://untrusted.invalid" }))).status === 403);
 assert(f.calls.length === 0);
});
Deno.test("Malformed email and non-four-digit PIN are rejected", async () => {
 for (const value of [{ ...login,email:"x" },{ ...login,pin:"12" },{ ...login,pin:1234 },{ ...login,pin:"١٢٣٤" }]) {
  const f = fixture([]); assert((await f.handler(request(value))).status===400); assert(f.calls.length===0);
 }
});
Deno.test("Oversized request body is rejected before any auth call", async () => {
 const f = fixture([]);
 assert((await f.handler(request({ ...login,padding:"x".repeat(5000) }))).status===400);
 assert(f.calls.length===0);
});
Deno.test("PIN rate limit prevents provider password attempts", async () => {
 const f = fixture([{ body:{allowed:false} }]);
 const result = await f.handler(request(login));
 assert(result.status===429); assert(f.calls.length===1);
});
Deno.test("Login uses a private derived password and strips provider fields", async () => {
 const f = fixture([allowed,{ body:{...session,internal_secret:"never-return"} }]);
 const response=await f.handler(request({ ...login,email:" Parent@Example.Invalid " }));
 const body=await response.json();
 assert(response.status===200); assert(body.session.user_id==="fixture-user");
 assert(f.calls[0].headers.get("apikey")===config.secretKey);
 assert(!f.calls[0].headers.has("Authorization"),"Modern secret keys are not bearer JWTs");
 assert(f.calls[0].body.email==="parent@example.invalid");
 assert(f.calls[1].url.pathname==="/auth/v1/token");
 assert(f.calls[1].body.password===strong && f.calls[1].body.password!==login.pin);
 assert(!JSON.stringify(body).includes(strong) && !JSON.stringify(body).includes("never-return"));
 assert(response.headers.get("Cache-Control")==="no-store");
});
Deno.test("Wrong PIN does not disclose whether email is registered", async () => {
 const f=fixture([allowed,{ status:400,body:{msg:"User not found"} }]);
 const response=await f.handler(request(login));
 assert(response.status===401); assert(!(await response.text()).includes("User not found"));
});
Deno.test("Provider failures do not expose request or secret details", async () => {
 const f=fixture([{ status:500,body:{sql:"sensitive",key:config.secretKey} }]);
 const response=await f.handler(request(login));
 assert(response.status===503); const text=await response.text();
 assert(!text.includes("sensitive") && !text.includes(config.secretKey));
});
Deno.test("Unconfirmed email cannot obtain a client session", async () => {
 const f=fixture([allowed,{body:{...session,user:{...session.user,email_confirmed_at:null}}}]);
 assert((await f.handler(request(login))).status===401);
});
Deno.test("Registration never returns a premature signup session", async () => {
 const f=fixture([allowed,{body:session}]);
 const response=await f.handler(request({...login,action:"register"})); const body=await response.json();
 assert(response.status===200 && body.status==="confirmation_required" && !body.session);
 assert(f.calls[1].body.password===strong);
});
Deno.test("Email verification still checks the chosen PIN before login", async () => {
 const f=fixture([allowed,{body:session},{body:session}]);
 const result=await f.handler(request({...login,action:"verify",token:"123456"}));
 assert(result.status===200);
 assert(f.calls[1].body.type==="email" && f.calls[1].body.token==="123456");
 assert(f.calls[2].url.searchParams.get("grant_type")==="password");
});
Deno.test("A verification result for a different email is denied", async () => {
 const f=fixture([allowed,{body:{...session,user:{...session.user,email:"other@example.invalid"}}}]);
 assert((await f.handler(request({...login,action:"verify",token:"123456"}))).status===401);
 assert(f.calls.length===2);
});
Deno.test("Invalid recovery code cannot change the PIN", async () => {
 const f=fixture([allowed,{status:400,body:{error:"expired"}}]);
 assert((await f.handler(request({...login,action:"reset",token:"123456"}))).status===401);
 assert(f.calls.length===2);
});
Deno.test("PIN reset requires email proof and revokes other refresh sessions", async () => {
 const f=fixture([allowed,{body:session},{body:session.user},{body:{}},{body:null}]);
 const result=await f.handler(request({...login,action:"reset",token:"123456"}));
 assert(result.status===200);
 assert(f.calls[1].body.type==="recovery");
 assert(f.calls[2].options.method==="PUT" && f.calls[2].body.password===strong);
 assert(f.calls[2].headers.get("Authorization")==="Bearer "+session.access_token);
 assert(f.calls[3].url.searchParams.get("scope")==="others");
 assert(f.calls[4].url.pathname==="/rest/v1/rpc/yshott_finish_recovery");
});
Deno.test("Email recovery works without a PIN and keeps existence private", async () => {
 for (const status of [200,400]) {
  const f=fixture([{body:{allowed:true,password:null}},{status,body:{}}]);
  const response=await f.handler(request({action:"recover",email:login.email}));
  assert(response.status===200); assert((await response.json()).status==="recovery_requested");
  assert(f.calls[0].body.pin===null && f.calls[0].body.purpose==="recover");
 }
});
Deno.test("Recovery service outage is reported without claiming delivery", async () => {
 const f=fixture([{body:{allowed:true,password:null}},{status:503,body:{}}]);
 assert((await f.handler(request({action:"recover",email:login.email}))).status===503);
});
Deno.test("Insecure configuration is refused", () => {
 let refused=false;
 try { createAuthHandler({...config,url:"http://example.invalid"}); } catch { refused=true; }
 assert(refused);
});
