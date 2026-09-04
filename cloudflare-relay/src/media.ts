export interface R2ObjectLike { body: ReadableStream; httpEtag: string; writeHttpMetadata(headers: Headers): void }
export interface R2BucketLike { put(key: string, value: ReadableStream, options?: { httpMetadata?: { contentType?: string; cacheControl?: string }; customMetadata?: Record<string,string> }): Promise<unknown>; get(key: string): Promise<R2ObjectLike | null> }
export interface MediaEnv { SOCIAL_MEDIA: R2BucketLike; SUPABASE_URL: string; SUPABASE_PUBLISHABLE_KEY: string }

const MAX_BYTES = 20 * 1024 * 1024
const ALLOWED = new Set(["image/jpeg", "image/png", "image/webp", "image/gif", "video/mp4", "video/webm"])
const KINDS = new Set(["posts", "avatars", "covers"])

export async function mediaFetch(request: Request, env: MediaEnv): Promise<Response> {
  const url = new URL(request.url)
  if (request.method === "POST" && url.pathname === "/v1/media") return upload(request, env)
  if (request.method === "GET" && url.pathname.startsWith("/media/")) return download(url.pathname.slice(7), env)
  return json({ error: "not_found" }, 404)
}

async function authenticate(request: Request, env: MediaEnv): Promise<string | null> {
  const auth = request.headers.get("Authorization") ?? ""
  if (!auth.startsWith("Bearer ")) return null
  const token = auth.slice(7).trim()
  if (!token || token.length > 8192) return null
  try {
    const r = await fetch(`${env.SUPABASE_URL.replace(/\/$/, "")}/auth/v1/user`, { headers: { Authorization: `Bearer ${token}`, apikey: env.SUPABASE_PUBLISHABLE_KEY } })
    if (!r.ok) return null
    const u = await r.json() as { id?: string }
    return u.id && /^[0-9a-f-]{36}$/i.test(u.id) ? u.id : null
  } catch { return null }
}

async function upload(request: Request, env: MediaEnv): Promise<Response> {
  const userId = await authenticate(request, env)
  if (!userId) return json({ error: "unauthorized" }, 401)
  const url = new URL(request.url)
  const kind = (url.searchParams.get("kind") || "posts").toLowerCase()
  if (!KINDS.has(kind)) return json({ error: "invalid_media_kind" }, 400)

  const type = (request.headers.get("content-type") || "").split(";", 1)[0].toLowerCase()
  const size = Number(request.headers.get("content-length") || "-1")
  if (!ALLOWED.has(type)) return json({ error: "unsupported_media_type" }, 415)
  if (!Number.isSafeInteger(size) || size < 1 || size > MAX_BYTES) return json({ error: "media_too_large_or_unknown_size", max_bytes: MAX_BYTES }, 413)
  if (!request.body) return json({ error: "body_required" }, 400)

  if ((kind === "avatars" || kind === "covers") && !type.startsWith("image/")) return json({ error: "profile_media_must_be_image" }, 415)
  const ext = ({ "image/jpeg": "jpg", "image/png": "png", "image/webp": "webp", "image/gif": "gif", "video/mp4": "mp4", "video/webm": "webm" } as Record<string, string>)[type]
  const key = `${kind}/${userId}/${crypto.randomUUID()}.${ext}`
  await env.SOCIAL_MEDIA.put(key, request.body, {
    httpMetadata: { contentType: type, cacheControl: "public, max-age=31536000, immutable" },
    customMetadata: { owner_id: userId, byte_size: String(size), media_kind: kind }
  })
  return json({ key, url: `${url.origin}/media/${key}`, media_type: type.startsWith("video/") ? "video" : "image", byte_size: size })
}

async function download(key: string, env: MediaEnv): Promise<Response> {
  if (!/^(?:posts|avatars|covers)\/[0-9a-f-]{36}\/[A-Za-z0-9-]+\.(?:jpg|png|webp|gif|mp4|webm)$/.test(key)) return json({ error: "invalid_media_key" }, 400)
  const object = await env.SOCIAL_MEDIA.get(key)
  if (!object) return new Response("Not found", { status: 404 })
  const headers = new Headers()
  object.writeHttpMetadata(headers)
  headers.set("etag", object.httpEtag)
  headers.set("cache-control", "public, max-age=31536000, immutable")
  headers.set("x-content-type-options", "nosniff")
  return new Response(object.body, { headers })
}

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "access-control-allow-origin": "*" } })
}
