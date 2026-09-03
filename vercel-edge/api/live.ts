import { gatewayUrl, json, relayUrl, verifyTicket } from "./_lib";

export const config = { runtime: "edge" };

export default async function handler(request: Request): Promise<Response> {
  if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405);
  const ticket = new URL(request.url).searchParams.get("ticket") ?? "";
  if (!ticket) return json({ error: "missing_ticket" }, 400);

  try {
    const verified = await verifyTicket(ticket);
    if (verified.kind === "gateway") {
      return new Response(null, {
        status: 302,
        headers: {
          location: gatewayUrl(`/stream/${encodeURIComponent(verified.session)}/index.m3u8`),
          "cache-control": "no-store",
          "referrer-policy": "no-referrer",
          "x-content-type-options": "nosniff",
        },
      });
    }

    const destination = new URL(relayUrl());
    destination.searchParams.set("id", verified.stream);
    destination.searchParams.set("ticket", ticket);
    return new Response(null, {
      status: 302,
      headers: {
        location: destination.toString(),
        "cache-control": "no-store",
        "referrer-policy": "no-referrer",
        "x-content-type-options": "nosniff",
      },
    });
  } catch {
    return json({ error: "invalid_or_expired_ticket" }, 401);
  }
}
