import { json, relayUrl, required, signRelayTicket } from "./_lib";

export const config = { runtime: "edge" };

/** Public read-only catalog. Each response contains a short-lived signed playback URL. */
export default async function handler(request: Request): Promise<Response> {
  if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405);
  try {
    const streamId = required("PUBLIC_STREAM_ID").trim();
    if (!/^[A-Za-z0-9._-]{1,128}$/.test(streamId)) throw new Error("invalid stream id");
    const relay = relayUrl();
    const ticket = await signRelayTicket(streamId, 900);
    const playback = `${new URL(request.url).origin}/api/live?ticket=${encodeURIComponent(ticket)}`;
    const name = (((globalThis as any).process?.env?.PUBLIC_STREAM_NAME || "FadCam Live") as string).trim() || "FadCam Live";
    const owner = (((globalThis as any).process?.env?.PUBLIC_STREAM_OWNER || "FadCam Creator") as string).trim() || "FadCam Creator";
    return json({
      version: 1,
      generated_at: new Date().toISOString(),
      channels: [{
        id: streamId,
        name,
        owner,
        source: "fadcam",
        relay: true,
        encrypted_transport: true,
        expires_in: 900,
        url: playback,
        relay_origin: new URL(relay).origin,
      }],
    });
  } catch {
    return json({ version: 1, channels: [], error: "edge_not_configured" }, 503);
  }
}
