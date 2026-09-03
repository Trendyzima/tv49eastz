import { json, required } from "./_lib";

export const config = { runtime: "edge" };

export default function handler(request: Request): Response {
  if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405);
  try {
    required("EDGE_SIGNING_SECRET");
    required("EDGE_PUBLISH_SECRET");
    required("PUBLIC_GATEWAY_URL");
    required("PUBLIC_STREAM_ID");
    required("PUBLIC_RELAY_URL");
    const relay = new URL(required("PUBLIC_RELAY_URL"));
    if (relay.protocol !== "https:" || relay.username || relay.password || relay.hash) throw new Error("invalid relay");
  } catch {
    return json({ ok: false, service: "tv49east-edge", error: "edge_not_configured" }, 503);
  }
  return json({ ok: true, service: "tv49east-edge", transport: "https", media: "external-relay" });
}
