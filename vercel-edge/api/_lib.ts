const encoder = new TextEncoder();

function bytesToBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function base64UrlToBytes(value: string): Uint8Array {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - (normalized.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

async function hmac(secret: string, value: string): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign", "verify"],
  );
  return new Uint8Array(await crypto.subtle.sign("HMAC", key, encoder.encode(value)));
}

export async function signTicket(session: string, stream: string, ttlSeconds: number): Promise<string> {
  const exp = Math.floor(Date.now() / 1000) + ttlSeconds;
  const payload = `${session}\x00${stream}\x00${exp}`;
  const signature = bytesToBase64Url(await hmac(required("EDGE_SIGNING_SECRET"), payload));
  return `${bytesToBase64Url(encoder.encode(payload))}.${signature}`;
}

export async function verifyTicket(token: string): Promise<{ session: string; stream: string; exp: number }> {
  if (token.length > 4096) throw new Error("ticket too large");
  const [payloadPart, signaturePart] = token.split(".");
  if (!payloadPart || !signaturePart) throw new Error("invalid ticket");
  const payloadBytes = base64UrlToBytes(payloadPart);
  const payload = new TextDecoder().decode(payloadBytes);
  const expected = await hmac(required("EDGE_SIGNING_SECRET"), payload);
  const supplied = base64UrlToBytes(signaturePart);
  if (expected.length !== supplied.length) throw new Error("invalid ticket");
  let diff = 0;
  for (let i = 0; i < expected.length; i++) diff |= expected[i] ^ supplied[i];
  if (diff !== 0) throw new Error("invalid ticket");

  const parts = payload.split("\x00");
  if (parts.length !== 3 || !parts[0] || !parts[1]) throw new Error("invalid ticket");
  const exp = Number(parts[2]);
  if (!Number.isSafeInteger(exp) || Math.floor(Date.now() / 1000) >= exp) throw new Error("expired ticket");
  return { session: parts[0], stream: parts[1], exp };
}

export function required(name: string): string {
  const value = (globalThis as any).process?.env?.[name] ?? undefined;
  if (!value) throw new Error(`${name} is not configured`);
  return value;
}

export function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
    },
  });
}

export function gatewayUrl(path: string): string {
  const base = required("PUBLIC_GATEWAY_URL").replace(/\/+$/, "");
  if (!path.startsWith("/")) throw new Error("invalid gateway path");
  return `${base}${path}`;
}

export function constantTimeString(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
