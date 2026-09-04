import relayWorker, { RelayTunnel } from "./index";
import { SessionStore } from "./session-store";
import { mediaFetch } from "./media";

export { RelayTunnel, SessionStore };

export interface Env {
  RELAY_TUNNEL: DurableObjectNamespace<RelayTunnel>;
  SESSION_STORE: DurableObjectNamespace<SessionStore>;
  RELAY_SIGNING_SECRET: string;
  RELAY_DEVICE_SECRET: string;
  GATEWAY_API_KEY: string;
  GATEWAY_CAPABILITY_KEY: string;
  SOCIAL_MEDIA: import("./media").R2BucketLike;
  SUPABASE_URL: string;
  SUPABASE_PUBLISHABLE_KEY: string;
}

const DEVICE_PATH = /^(?:\/live\.m3u8|\/init\.mp4|\/status|\/audio\/volume|\/hls\/[A-Za-z0-9_-]+)$/;
const SESSION_TTL_SECONDS = 15 * 60;

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
    },
  });
}

function validId(value: string): boolean { return /^[A-Za-z0-9._~-]{1,128}$/.test(value); }
function constantTimeString(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}
function base64Url(bytes: Uint8Array): string {
  let b = "";
  for (const x of bytes) b += String.fromCharCode(x);
  return btoa(b).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
async function hmac(secret: string, value: string): Promise<string> {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return base64Url(new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(value))));
}
async function gatewayTicket(secret: string, session: string, stream: string, exp: number) {
  const p = `gateway\x00${session}\x00${stream}\x00${exp}`;
  return `${base64Url(new TextEncoder().encode(p))}.${await hmac(secret, p)}`;
}
async function relayTicket(secret: string, stream: string, ttl: number) {
  const exp = Math.floor(Date.now() / 1000) + ttl;
  const p = `relay\x00${stream}\x00${exp}`;
  return `${base64Url(new TextEncoder().encode(p))}.${await hmac(secret, p)}`;
}
async function deviceTicket(secret: string, stream: string, ttl: number) {
  const exp = Math.floor(Date.now() / 1000) + ttl;
  const p = `device\x00${stream}\x00${exp}`;
  return `${base64Url(new TextEncoder().encode(p))}.${await hmac(secret, p)}`;
}

async function authenticateSupabase(request: Request, env: Env): Promise<{ userId: string } | null> {
  const authorization = request.headers.get("Authorization") ?? "";
  if (!authorization.startsWith("Bearer ")) return null;
  const token = authorization.slice(7).trim();
  if (!token || token.length > 8192) return null;
  try {
    const response = await fetch(`${env.SUPABASE_URL}/auth/v1/user`, {
      headers: { Authorization: `Bearer ${token}`, apikey: env.SUPABASE_PUBLISHABLE_KEY },
    });
    if (!response.ok) return null;
    const user = await response.json() as { id?: string };
    return user.id && /^[0-9a-f-]{36}$/i.test(user.id) ? { userId: user.id } : null;
  } catch {
    return null;
  }
}

async function createProducerSession(request: Request, env: Env): Promise<Response> {
  const identity = await authenticateSupabase(request, env);
  if (!identity) return json({ error: "unauthorized" }, 401);
  let body: { channel_id?: string; stream_id?: string; device_id?: string };
  try { body = await request.json(); } catch { return json({ error: "invalid_json" }, 400); }
  const stream = String(body.stream_id ?? "").trim();
  const device = String(body.device_id ?? "").trim();
  const channel = String(body.channel_id ?? stream).trim();
  if (!validId(stream) || !validId(device) || !channel) return json({ error: "invalid_device_session_request" }, 400);
  if (stream !== device) return json({ error: "stream_device_mismatch" }, 400);

  const session = crypto.randomUUID();
  const exp = Math.floor(Date.now() / 1000) + SESSION_TTL_SECONDS;
  const saved = await env.SESSION_STORE.get(env.SESSION_STORE.idFromName(session)).fetch(
    new Request("https://session/store", {
      method: "POST",
      body: JSON.stringify({ session, stream, exp, channel, user_id: identity.userId, device_id: device }),
    }),
  );
  if (!saved.ok) return json({ error: "session_store_failed" }, 503);

  const viewerTicket = await gatewayTicket(env.GATEWAY_CAPABILITY_KEY, session, stream, exp);
  const producerTicket = await deviceTicket(env.RELAY_DEVICE_SECRET, stream, SESSION_TTL_SECONDS);
  const origin = new URL(request.url).origin;
  const playlistPath = `/stream/${session}/index.m3u8?ticket=${encodeURIComponent(viewerTicket)}`;
  return json({
    session,
    stream_id: stream,
    expires_in: SESSION_TTL_SECONDS,
    playlist: playlistPath,
    playlist_url: `${origin}${playlistPath}`,
    producer_ticket: producerTicket,
    user_id: identity.userId,
    device_id: device,
  });
}

async function gatewayRequest(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const match = url.pathname.match(/^\/v1\/session(?:\/([^/]+))?$/);

  // Viewer session creation uses the user's Supabase access token. The server
  // never requires the private GATEWAY_API_KEY to be embedded in an Android APK.
  if (request.method === "POST" && match?.[1] === undefined) {
    const identity = await authenticateSupabase(request, env);
    if (!identity) return json({ error: "unauthorized" }, 401);

    let body: { channel_id?: string; stream_id?: string };
    try { body = await request.json(); } catch { return json({ error: "invalid_json" }, 400); }
    const channel = String(body.channel_id ?? "").trim();
    const stream = String(body.stream_id ?? "").trim();
    if (!channel || !validId(stream)) return json({ error: "invalid_session_request" }, 400);

    const session = crypto.randomUUID();
    const exp = Math.floor(Date.now() / 1000) + SESSION_TTL_SECONDS;
    const saved = await env.SESSION_STORE.get(env.SESSION_STORE.idFromName(session)).fetch(
      new Request("https://session/store", {
        method: "POST",
        body: JSON.stringify({ session, stream, exp, channel, user_id: identity.userId }),
      }),
    );
    if (!saved.ok) return json({ error: "session_store_failed" }, 503);

    const ticket = await gatewayTicket(env.GATEWAY_CAPABILITY_KEY, session, stream, exp);
    const playlistPath = `/stream/${session}/index.m3u8?ticket=${encodeURIComponent(ticket)}`;
    const origin = url.origin;
    return json({
      session,
      stream_id: stream,
      expires_in: SESSION_TTL_SECONDS,
      playlist: playlistPath,
      playlist_url: `${origin}${playlistPath}`,
      ticket,
    });
  }

  // DELETE remains an internal administrative operation and is deliberately
  // protected by the server-side API key rather than a client credential.
  const auth = request.headers.get("Authorization") ?? "";
  if (!env.GATEWAY_API_KEY || !constantTimeString(auth, `Bearer ${env.GATEWAY_API_KEY}`)) return json({ error: "unauthorized" }, 401);
  if (request.method === "DELETE" && match?.[1]) {
    const session = decodeURIComponent(match[1]);
    if (!/^[0-9a-f-]{36}$/i.test(session)) return json({ error: "invalid_session" }, 400);
    const result = await env.SESSION_STORE.get(env.SESSION_STORE.idFromName(session)).fetch(new Request("https://session/revoke", { method: "DELETE" }));
    return new Response(null, { status: result.ok ? 204 : 404 });
  }
  return json({ error: "method_not_allowed" }, 405);
}

function appendTicket(uri: string, ticket: string): string {
  if (/^(?:data:|https?:)/i.test(uri) || uri.startsWith("#")) return uri;
  return `${uri}${uri.includes("?") ? "&" : "?"}ticket=${encodeURIComponent(ticket)}`;
}
function rewritePlaylist(text: string, ticket: string): string {
  return text.split(/\r?\n/).map((line) => {
    if (!line || line.startsWith("#EXTM3U") || line.startsWith("#EXT-X")) return line.replace(/URI="([^"]+)"/g, (_m, uri: string) => `URI="${appendTicket(uri, ticket)}"`);
    if (line.startsWith("#")) return line.replace(/URI="([^"]+)"/g, (_m, uri: string) => `URI="${appendTicket(uri, ticket)}"`);
    return appendTicket(line.trim(), ticket);
  }).join("\n");
}

async function gatewayStream(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405);
  const url = new URL(request.url);
  const match = url.pathname.match(/^\/stream\/([^/]+)\/index\.m3u8$/);
  if (!match) return json({ error: "not_found" }, 404);
  const session = decodeURIComponent(match[1]);
  const supplied = url.searchParams.get("ticket") ?? "";
  if (!/^[0-9a-f-]{36}$/i.test(session) || !supplied) return json({ error: "unauthorized" }, 401);

  const verified = await env.SESSION_STORE.get(env.SESSION_STORE.idFromName(session)).fetch(new Request(`https://session/verify?ticket=${encodeURIComponent(supplied)}`));
  if (!verified.ok) return json({ error: "invalid_or_expired_session" }, 401);
  const state = await verified.json() as { stream: string; exp: number };
  const ttl = state.exp - Math.floor(Date.now() / 1000);
  if (ttl <= 0) return json({ error: "expired_session" }, 401);

  const ticket = await relayTicket(env.RELAY_SIGNING_SECRET, state.stream, Math.min(60, ttl));
  const relay = new URL(request.url);
  relay.pathname = "/v1/relay";
  relay.search = new URLSearchParams({ id: state.stream, ticket, path: "/live.m3u8" }).toString();
  const upstream = await relayWorker.fetch(new Request(relay.toString(), { method: "GET", headers: request.headers }), env, ctx);
  if (!upstream.ok) return upstream;

  const rewritten = rewritePlaylist(await upstream.text(), supplied);
  const headers = new Headers(upstream.headers);
  headers.set("content-type", "application/vnd.apple.mpegurl; charset=utf-8");
  headers.set("cache-control", "private, no-store");
  headers.delete("content-length");
  return new Response(rewritten, { status: upstream.status, headers });
}

async function gatewayResource(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405);
  const url = new URL(request.url);
  const match = url.pathname.match(/^\/stream\/([^/]+)\/(.+)$/);
  if (!match) return json({ error: "not_found" }, 404);
  const session = decodeURIComponent(match[1]);
  const supplied = url.searchParams.get("ticket") ?? "";
  if (!/^[0-9a-f-]{36}$/i.test(session) || !supplied) return json({ error: "unauthorized" }, 401);

  const verified = await env.SESSION_STORE.get(env.SESSION_STORE.idFromName(session)).fetch(new Request(`https://session/verify?ticket=${encodeURIComponent(supplied)}`));
  if (!verified.ok) return json({ error: "invalid_or_expired_session" }, 401);
  const state = await verified.json() as { stream: string; exp: number };
  const ttl = state.exp - Math.floor(Date.now() / 1000);
  if (ttl <= 0) return json({ error: "expired_session" }, 401);

  let resource = "/" + match[2].split("?")[0];
  if (resource === "/live.m3u8") return gatewayStream(request, env, ctx);
  if (/^\/seg-[0-9]+\.m4s$/.test(resource)) resource = "/hls/" + resource.slice(1, -4);
  if (!DEVICE_PATH.test(resource)) return json({ error: "path_not_allowed" }, 403);

  const ticket = await relayTicket(env.RELAY_SIGNING_SECRET, state.stream, Math.min(60, ttl));
  const relay = new URL(request.url);
  relay.pathname = "/v1/relay";
  relay.search = new URLSearchParams({ id: state.stream, ticket, path: resource }).toString();
  return relayWorker.fetch(new Request(relay.toString(), { method: "GET", headers: request.headers }), env, ctx);
}

async function deviceProxy(request: Request, env: Env): Promise<Response> {
  if (request.method !== "GET") return new Response("method not allowed", { status: 405 });
  if (!env.RELAY_DEVICE_SECRET || request.headers.get("Authorization") !== `Bearer ${env.RELAY_DEVICE_SECRET}`) return new Response("unauthorized", { status: 401 });
  const match = new URL(request.url).pathname.match(/^\/device\/([^/]+)(\/.*)?$/);
  if (!match) return new Response("invalid device path", { status: 400 });
  const id = decodeURIComponent(match[1] ?? "").trim();
  const path = match[2] || "/live.m3u8";
  if (!validId(id) || !DEVICE_PATH.test(path)) return new Response("invalid device request", { status: 400 });
  return env.RELAY_TUNNEL.get(env.RELAY_TUNNEL.idFromName(id)).fetch(new Request(request, { headers: new Headers({ "x-relay-stream": id, "x-relay-path": path }) }));
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const path = new URL(request.url).pathname;
    if (path === "/v1/media" || path.startsWith("/media/")) return mediaFetch(request, env);
    if (path === "/v1/device/session" && request.method === "POST") return createProducerSession(request, env);
    if (path.startsWith("/device/")) return deviceProxy(request, env);
    if (path === "/v1/session" || path.startsWith("/v1/session/")) return gatewayRequest(request, env);
    if (path.match(/^\/stream\/[^/]+\/index\.m3u8$/)) return gatewayStream(request, env, ctx);
    if (path.startsWith("/stream/")) return gatewayResource(request, env, ctx);
    return relayWorker.fetch(request, env, ctx);
  },
};
