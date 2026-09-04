import { DurableObject } from "cloudflare:workers";

type SessionEnv = { GATEWAY_CAPABILITY_KEY: string };
export type SessionRecord = { session: string; stream: string; exp: number; channel: string; revoked?: boolean };

export class SessionStore extends DurableObject<SessionEnv> {
  async fetch(request: Request): Promise<Response> {
    if (request.method === "POST") {
      const record = await request.json() as SessionRecord;
      if (!record.session || !record.stream || !Number.isSafeInteger(record.exp)) return new Response("invalid", { status: 400 });
      await this.ctx.storage.put("record", record);
      return new Response("ok");
    }
    const record = await this.ctx.storage.get<SessionRecord>("record");
    if (!record) return new Response("not_found", { status: 404 });
    if (request.method === "DELETE") {
      record.revoked = true;
      await this.ctx.storage.put("record", record);
      return new Response(null, { status: 204 });
    }
    if (request.method === "GET" && new URL(request.url).pathname === "/verify") {
      if (record.revoked || record.exp <= Math.floor(Date.now() / 1000)) return new Response("invalid", { status: 401 });
      const token = new URL(request.url).searchParams.get("ticket") ?? "";
      const parts = token.split(".");
      if (parts.length !== 2 || parts[0].length > 4096) return new Response("invalid", { status: 401 });
      try {
        const payload = new TextDecoder().decode(base64UrlDecode(parts[0]));
        const fields = payload.split("\x00");
        if (fields.length !== 4 || fields[0] !== "gateway" || fields[1] !== record.session || fields[2] !== record.stream || Number(fields[3]) !== record.exp) return new Response("invalid", { status: 401 });
        const expected = await hmac(this.env.GATEWAY_CAPABILITY_KEY, payload);
        if (!constantTime(expected, parts[1])) return new Response("invalid", { status: 401 });
        return Response.json({ stream: record.stream, exp: record.exp });
      } catch { return new Response("invalid", { status: 401 }); }
    }
    return new Response("method_not_allowed", { status: 405 });
  }
}

function base64UrlDecode(value: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new Error("invalid base64url");
  const padded = value.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - value.length % 4) % 4);
  const binary = atob(padded); const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
  return out;
}
async function hmac(secret: string, value: string): Promise<string> {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const bytes = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(value)));
  let binary = ""; for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
function constantTime(a: string, b: string): boolean { if (a.length !== b.length) return false; let diff = 0; for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i); return diff === 0; }
