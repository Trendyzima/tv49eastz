import { json, required, signDeviceTicket, constantTimeString } from "./_lib";

export const config = { runtime: "edge" };

/**
 * Trusted provisioning endpoint.
 *
 * EDGE_PUBLISH_SECRET must remain server-side. The resulting short-lived
 * device ticket is handed to an authenticated producer by the control plane;
 * it is never embedded in the APK and never exposes the signing secret.
 */
export default async function handler(request: Request): Promise<Response> {
  if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);

  const supplied = request.headers.get("authorization")?.replace(/^Bearer\s+/i, "").trim() ?? "";
  if (!constantTimeString(supplied, required("EDGE_PUBLISH_SECRET"))) {
    return json({ error: "unauthorized" }, 401);
  }

  let body: any;
  try {
    body = await request.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  const stream = typeof body?.stream === "string" ? body.stream.trim() : "";
  if (!stream || stream.length > 128 || !/^[A-Za-z0-9._-]+$/.test(stream)) {
    return json({ error: "invalid_stream" }, 400);
  }

  const ticket = await signDeviceTicket(stream, 900);
  return json({
    ticket,
    stream,
    expires_in: 900,
    websocket_path: `/tunnel?stream=${encodeURIComponent(stream)}&ticket=${encodeURIComponent(ticket)}`,
  });
}
