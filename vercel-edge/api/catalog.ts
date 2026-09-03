import { json, required } from "./_lib";

export const config = { runtime: "edge" };

/**
 * Public, read-only receiver catalog.
 * The media URL is a preconfigured HTTPS relay; Vercel never becomes the
 * long-lived HLS media origin. This keeps the Edge layer a control plane.
 */
export default function handler(request: Request): Response {
  if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405);

  try {
    const relay = required("PUBLIC_RELAY_URL").trim();
    const streamId = required("PUBLIC_STREAM_ID").trim();
    const name = ((globalThis as any).process?.env?.PUBLIC_STREAM_NAME || "FadCam Live").trim();
    const owner = ((globalThis as any).process?.env?.PUBLIC_STREAM_OWNER || "FadCam Creator").trim();

    const url = new URL(relay);
    if (url.protocol !== "https:") throw new Error("PUBLIC_RELAY_URL must use HTTPS");
    if (!url.hostname || url.username || url.password || url.hash) throw new Error("invalid relay URL");
    if (!streamId || !/^[A-Za-z0-9._-]{1,128}$/.test(streamId)) throw new Error("invalid stream id");

    return json({
      version: 1,
      channels: [{
        id: streamId,
        name: name || "FadCam Live",
        owner: owner || "FadCam Creator",
        source: "fadcam",
        stream: url.pathname + (url.search || ""),
        relay: true,
        encrypted_transport: true,
        url: url.toString(),
      }],
    });
  } catch {
    return json({ version: 1, channels: [], error: "edge_not_configured" }, 503);
  }
}
