const encoder = new TextEncoder();

function bytesToBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function base64UrlToBytes(value: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new Error("invalid base64url");
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - (normalized.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

async function hmac(secret: string, value: string): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return new Uint8Array(await crypto.subtle.sign("HMAC", key, encoder.encode(value)));
}

export async function signTicket(session: string, stream: string, ttlSeconds: number): Promise<string> {
  const exp = Math.floor(Date.now() / 1000) + ttlSeconds;
  const payload = `gateway\x00${session}\x00${stream}\x00${exp}`;
  return `${bytesToBase64Url(encoder.encode(payload))}.${bytesToBase64Url(await hmac(required("EDGE_SIGNING_SECRET"), payload))}`;
}

export async function signRelayTicket(stream: string, ttlSeconds: number): Promise<string> {
  const exp = Math.floor(Date.now() / 1000) + ttlSeconds;
  const payload = `relay\x00${stream}\x00${exp}`;
  return `${bytesToBase64Url(encoder.encode(payload))}.${bytesToBase64Url(await hmac(required("EDGE_SIGNING_SECRET"), payload))}`;
}

export async function signDeviceTicket(stream: string, ttlSeconds: number): Promise<string> {
  const exp = Math.floor(Date.now() / 1000) + ttlSeconds;
  const payload = `device\x00${stream}\x00${exp}`;
  return `${bytesToBase64Url(encoder.encode(payload))}.${bytesToBase64Url(await hmac(required("EDGE_DEVICE_SIGNING_SECRET"), payload))}`;
}

export async function verifyTicket(token: string): Promise<{ kind: "gateway"; session: string; stream: string; exp: number } | { kind: "relay"; stream: string; exp: number }> {
  if (token.length > 4096) throw new Error("ticket too large");
  const parts = token.split(".");
  if (parts.length !== 2) throw new Error("invalid ticket");
  const payload = new TextDecoder().decode(base64UrlToBytes(parts[0]));
  const expected = await hmac(required("EDGE_SIGNING_SECRET"), payload);
  const supplied = base64UrlToBytes(parts[1]);
  if (expected.length !== supplied.length) throw new Error("invalid ticket");
  let diff = 0;
  for (let i = 0; i < expected.length; i++) diff |= expected[i] ^ supplied[i];
  if (diff !== 0) throw new Error("invalid ticket");

  const fields = payload.split("\x00");
  if (fields[0] === "gateway" && fields.length === 4) {
    const exp = Number(fields[3]);
    if (!fields[1] || !fields[2] || !Number.isSafeInteger(exp) || Math.floor(Date.now() / 1000) >= exp) throw new Error("expired ticket");
    return { kind: "gateway", session: fields[1], stream: fields[2], exp };
  }
  if (fields[0] === "relay" && fields.length === 3) {
    const exp = Number(fields[2]);
    if (!fields[1] || !Number.isSafeInteger(exp) || Math.floor(Date.now() / 1000) >= exp) throw new Error("expired ticket");
    return { kind: "relay", stream: fields[1], exp };
  }
  throw new Error("invalid ticket");
}

export function required(name: string): string {
  const value = (globalThis as any).process?.env?.[name] ?? undefined;
  if (!value) throw new Error(`${name} is not configured`);
  return value;
}

export function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "x-content-type-options": "nosniff" } });
}

export function gatewayUrl(path: string): string {
  const base = required("PUBLIC_GATEWAY_URL").replace(/\/+$/, "");
  if (!path.startsWith("/")) throw new Error("invalid gateway path");
  return `${base}${path}`;
}

export function relayUrl(): string {
  const value = required("PUBLIC_RELAY_URL").trim();
  const u = new URL(value);
  if (u.protocol !== "https:" || u.username || u.password || u.hash) throw new Error("PUBLIC_RELAY_URL must be HTTPS without credentials or fragments");
  return u.toString();
}

export function constantTimeString(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
