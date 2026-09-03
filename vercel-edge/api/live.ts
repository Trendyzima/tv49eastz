import { gatewayUrl, json, verifyTicket } from "./_lib";

export const config = { runtime: "edge" };

export default async function handler(request: Request): Promise<Response> {
  if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405);

  const ticket = new URL(request.url).searchParams.get("ticket") ?? "";
  if (!ticket) return json({ error: "missing_ticket" }, 400);

  let verified: { session: string; stream: string; exp: number };
  try {
    verified = await verifyTicket(ticket);
  } catch {
    return json({ error: "invalid_or_expired_ticket" }, 401);
  }

  const playlist = gatewayUrl(`/stream/${encodeURIComponent(verified.session)}/index.m3u8`);
  return new Response(null, {
    status: 302,
    headers: {
      location: playlist,
      "cache-control": "no-store",
      "referrer-policy": "no-referrer",
      "x-content-type-options": "nosniff",
    },
  });
}
