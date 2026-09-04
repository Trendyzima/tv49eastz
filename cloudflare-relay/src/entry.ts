import relayWorker, { RelayTunnel } from "./index";
import { SessionStore } from "./session-store";

export { RelayTunnel, SessionStore };

export interface Env {
  RELAY_TUNNEL: DurableObjectNamespace<RelayTunnel>;
  SESSION_STORE: DurableObjectNamespace<SessionStore>;
  RELAY_SIGNING_SECRET: string;
  RELAY_DEVICE_SECRET: string;
  GATEWAY_API_KEY: string;
  GATEWAY_CAPABILITY_KEY: string;
}

const DEVICE_PATH = /^(?:\/live\.m3u8|\/init\.mp4|\/status|\/audio\/volume|\/hls\/[A-Za-z0-9_-]+)$/;
const SESSION_TTL_SECONDS = 15 * 60;
function json(data: unknown, status = 200): Response { return new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "x-content-type-options": "nosniff" } }); }
function validId(value: string): boolean { return /^[A-Za-z0-9._~-]{1,128}$/.test(value); }
function constantTimeString(a: string, b: string): boolean { if (a.length !== b.length) return false; let d = 0; for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i); return d === 0; }
function base64Url(bytes: Uint8Array): string { let b = ""; for (const x of bytes) b += String.fromCharCode(x); return btoa(b).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, ""); }
async function hmac(secret: string, value: string): Promise<string> { const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]); return base64Url(new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(value)))); }
async function gatewayTicket(secret: string, session: string, stream: string, exp: number): Promise<string> { const payload = `gateway\x00${session}\x00${stream}\x00${exp}`; return `${base64Url(new TextEncoder().encode(payload))}.${await hmac(secret, payload)}`; }
async function relayTicket(secret: string, stream: string, ttlSeconds: number): Promise<string> { const exp = Math.floor(Date.now() / 1000) + ttlSeconds; const payload = `relay\x00${stream}\x00${exp}`; return `${base64Url(new TextEncoder().encode(payload))}.${await hmac(secret, payload)}`; }

async function gatewayRequest(request: Request, env: Env): Promise<Response> {
  const auth = request.headers.get("Authorization") ?? "";
  if (!env.GATEWAY_API_KEY || !constantTimeString(auth, `Bearer ${env.GATEWAY_API_KEY}`)) return json({ error: "unauthorized" }, 401);
  const match = new URL(request.url).pathname.match(/^\/v1\/session(?:\/([^/]+))?$/);
  if (request.method === "POST" && match?.[1] === undefined) {
    let body: { channel_id?: string; stream_id?: string };
    try { body = await request.json(); } catch { return json({ error: "invalid_json" }, 400); }
    const channel = String(body.channel_id ?? "").trim(); const stream = String(body.stream_id ?? "").trim();
    if (!channel || !validId(stream)) return json({ error: "invalid_session_request" }, 400);
    const session = crypto.randomUUID(); const exp = Math.floor(Date.now() / 1000) + SESSION_TTL_SECONDS;
    const saved = await env.SESSION_STORE.get(env.SESSION_STORE.idFromName(session)).fetch(new Request("https://session/store", { method: "POST", body: JSON.stringify({ session, stream, exp, channel }) }));
    if (!saved.ok) return json({ error: "session_store_failed" }, 503);
    return json({ session, expires_in: SESSION_TTL_SECONDS, playlist: `/stream/${session}/index.m3u8`, ticket: await gatewayTicket(env.GATEWAY_CAPABILITY_KEY, session, stream, exp) });
  }
  if (request.method === "DELETE" && match?.[1]) {
    const session = decodeURIComponent(match[1]); if (!/^[0-9a-f-]{36}$/i.test(session)) return json({ error: "invalid_session" }, 400);
    const response = await env.SESSION_STORE.get(env.SESSION_STORE.idFromName(session)).fetch(new Request("https://session/revoke", { method: "DELETE" }));
    return new Response(null, { status: response.ok ? 204 : 404 });
  }
  return json({ error: "method_not_allowed" }, 405);
}

async function gatewayStream(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405);
  const match = new URL(request.url).pathname.match(/^\/stream\/([^/]+)\/index\.m3u8$/); if (!match) return json({ error: "not_found" }, 404);
  const session = decodeURIComponent(match[1]); const supplied = new URL(request.url).searchParams.get("ticket") ?? "";
  if (!/^[0-9a-f-]{36}$/i.test(session) || !supplied) return json({ error: "unauthorized" }, 401);
  const verified = await env.SESSION_STORE.get(env.SESSION_STORE.idFromName(session)).fetch(new Request(`https://session/verify?ticket=${encodeURIComponent(supplied)}`));
  if (!verified.ok) return json({ error: "invalid_or_expired_session" }, 401);
  const state = await verified.json() as { stream: string; exp: number }; const ttl = state.exp - Math.floor(Date.now() / 1000); if (ttl <= 0) return json({ error: "expired_session" }, 401);
  const ticket = await relayTicket(env.RELAY_SIGNING_SECRET, state.stream, Math.min(60, ttl));
  const relay = new URL(request.url); relay.pathname = "/v1/relay"; relay.search = new URLSearchParams({ id: state.stream, ticket, path: "/live.m3u8" }).toString();
  return relayWorker.fetch(new Request(relay.toString(), { method: "GET", headers: request.headers }), env, ctx);
}

async function deviceProxy(request: Request, env: Env): Promise<Response> {
  if (request.method !== "GET") return new Response("method not allowed", { status: 405 });
  if (!env.RELAY_DEVICE_SECRET || request.headers.get("Authorization") !== `Bearer ${env.RELAY_DEVICE_SECRET}`) return new Response("unauthorized", { status: 401 });
  const match = new URL(request.url).pathname.match(/^\/device\/([^/]+)(\/.*)?$/); if (!match) return new Response("invalid device path", { status: 400 });
  const deviceId = decodeURIComponent(match[1] ?? "").trim(); const path = match[2] || "/live.m3u8";
  if (!validId(deviceId) || !DEVICE_PATH.test(path)) return new Response("invalid device request", { status: 400 });
  return env.RELAY_TUNNEL.get(env.RELAY_TUNNEL.idFromName(deviceId)).fetch(new Request(request, { headers: new Headers({ "x-relay-stream": deviceId, "x-relay-path": path }) }));
}

export default { async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const path = new URL(request.url).pathname;
  if (path.startsWith("/device/")) return deviceProxy(request, env);
  if (path === "/v1/session" || path.startsWith("/v1/session/")) return gatewayRequest(request, env);
  if (path.startsWith("/stream/")) return gatewayStream(request, env, ctx);
  return relayWorker.fetch(request, env, ctx);
} };
