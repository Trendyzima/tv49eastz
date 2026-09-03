import { gatewayUrl, json, required, signTicket, constantTimeString } from "./_lib";

export const config = { runtime: "edge" };

export default async function handler(request: Request): Promise<Response> {
  if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);

  const supplied = request.headers.get("authorization")?.replace(/^Bearer\s+/i, "").trim() ?? "";
  const secret = required("EDGE_PUBLISH_SECRET");
  if (!constantTimeString(supplied, secret)) return json({ error: "unauthorized" }, 401);

  let body: any;
  try {
    body = await request.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  const session = typeof body?.session === "string" ? body.session.trim() : "";
  const stream = typeof body?.stream === "string" ? body.stream.trim() : "";
  if (!session || session.length > 256 || !/^[A-Za-z0-9_-]+$/.test(session)) {
    return json({ error: "invalid_session" }, 400);
  }
  if (!stream || stream.length > 128 || !/^[A-Za-z0-9._-]+$/.test(stream)) {
    return json({ error: "invalid_stream" }, 400);
  }

  // A publish ticket is issued only for a session the public media gateway
  // currently recognizes. The edge never receives the producer's device key.
  let probe: Response;
  try {
    probe = await fetch(gatewayUrl(`/stream/${encodeURIComponent(session)}/index.m3u8`), {
      method: "GET",
      headers: { accept: "application/vnd.apple.mpegurl", "cache-control": "no-cache" },
    });
  } catch {
    return json({ error: "gateway_unreachable" }, 503);
  }
  if (!probe.ok) return json({ error: "session_not_live", gateway_status: probe.status }, 409);

  const ticket = await signTicket(session, stream, 900);
  const origin = new URL(request.url).origin;
  return json({
    ticket,
    expires_in: 900,
    viewer_url: `${origin}/api/live?ticket=${encodeURIComponent(ticket)}`,
    playlist_url: `${origin}/api/live?ticket=${encodeURIComponent(ticket)}`,
  });
}
