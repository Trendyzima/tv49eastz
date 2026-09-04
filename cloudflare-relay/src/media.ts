import type { R2Bucket } from "@cloudflare/workers-types";

export interface MediaEnv {
  SOCIAL_MEDIA: R2Bucket;
  SUPABASE_URL: string;
  SUPABASE_PUBLISHABLE_KEY: string;
}

const MAX_BYTES = 20 * 1024 * 1024;
const ALLOWED = new Set(["image/jpeg", "image/png", "image/webp", "image/gif", "video/mp4", "video/webm"]);

export async function mediaFetch(request: Request, env: MediaEnv): Promise<Response> {
  const url = new URL(request.url);
  if (request.method === "POST" && url.pathname === "/v1/media") return upload(request, env);
  if (request.method === "GET" && url.pathname.startsWith("/media/")) return download(request, env, url.pathname.slice("/media/".length));
  return json({ error: "not_found" }, 404);
}

async function authenticate(request: Request, env: MediaEnv): Promise<string | null> {
  const auth = request.headers.get("Authorization") ?? "";
  if (!auth.startsWith("Bearer ")) return null;
  const token = auth.slice(7).trim();
  if (!token || token.length > 8192) return null;
  try {
    const response = await fetch(`${env.SUPABASE_URL.replace(/\/$/, "")}/auth/v1/user`, { headers: { Authorization: `Bearer ${token}`, apikey: env.SUPABASE_PUBLISHABLE_KEY } });
    if (!response.ok) return null;
    const user = await response.json() as { id?: string };
    return user.id && /^[0-9a-f-]{36}$/i.test(user.id) ? user.id : null;
  } catch { return null; }
}

async function upload(request: Request, env: MediaEnv): Promise<Response> {
  const userId = await authenticate(request, env);
  if (!userId) return json({ error: "unauthorized" }, 401);
  const type = (request.headers.get("content-type") || "").split(";", 1)[0].toLowerCase();
  const size = Number(request.headers.get("content-length") || "-1");
  if (!ALLOWED.has(type)) return json({ error: "unsupported_media_type" }, 415);
  if (!Number.isSafeInteger(size) || size < 1 || size > MAX_BYTES) return json({ error: "media_too_large_or_unknown_size", max_bytes: MAX_BYTES }, 413);
  if (!request.body) return json({ error: "body_required" }, 400);

  const extension = ({ "image/jpeg": "jpg", "image/png": "png", "image/webp": "webp", "image/gif": "gif", "video/mp4": "mp4", "video/webm": "webm" } as Record<string,string>)[type];
  const key = `social/${userId}/${crypto.randomUUID()}.${extension}`;
  await env.SOCIAL_MEDIA.put(key, request.body, { httpMetadata: { contentType: type, cacheControl: "public, max-age=31536000, immutable" }, customMetadata: { owner_id: userId, byte_size: String(size) } });
  return json({ key, url: `${urlOrigin(request)}/media/${key}`, media_type: type.startsWith("video/") ? "video" : "image", byte_size: size });
}

async function download(_request: Request, env: MediaEnv, key: string): Promise<Response> {
  if (!/^social\/[0-9a-f-]{36}\/[A-Za-z0-9-]+\.(?:jpg|png|webp|gif|mp4|webm)$/.test(key)) return json({ error: "invalid_media_key" }, 400);
  const object = await env.SOCIAL_MEDIA.get(key);
  if (!object) return new Response("Not found", { status: 404 });
  const headers = new Headers();
  object.writeHttpMetadata(headers);
  headers.set("etag", object.httpEtag);
  headers.set("cache-control", "public, max-age=31536000, immutable");
  headers.set("x-content-type-options", "nosniff");
  return new Response(object.body, { headers });
}

function urlOrigin(request: Request): string { return new URL(request.url).origin; }
function json(value: unknown, status = 200): Response { return new Response(JSON.stringify(value), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "access-control-allow-origin": "*" } }); }
