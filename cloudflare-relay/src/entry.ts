import relayWorker, { RelayTunnel } from "./index";

export interface Env {
  RELAY_TUNNEL: DurableObjectNamespace<RelayTunnel>;
  RELAY_SIGNING_SECRET: string;
  RELAY_DEVICE_SECRET: string;
  GATEWAY_ORIGIN: string;
}

const DEVICE_PATH = /^(?:\/live\.m3u8|\/init\.mp4|\/status|\/audio\/volume|\/hls\/[A-Za-z0-9_-]+)$/;

function gatewayOrigin(env: Env): URL | null {
  const raw = (env.GATEWAY_ORIGIN ?? "").trim().replace(/\/+$/, "");
  if (!raw) return null;
  try {
    const url = new URL(raw);
    if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash) return null;
    return url;
  } catch {
    return null;
  }
}

async function gatewayProxy(request: Request, env: Env): Promise<Response> {
  const origin = gatewayOrigin(env);
  if (!origin) return new Response("gateway not configured", { status: 503 });

  const incoming = new URL(request.url);
  origin.pathname = incoming.pathname;
  origin.search = incoming.search;

  const headers = new Headers(request.headers);
  headers.delete("host");
  headers.set("X-Forwarded-Proto", "https");
  headers.set("X-Forwarded-Host", incoming.host);

  return fetch(new Request(origin.toString(), {
    method: request.method,
    headers,
    body: request.method === "GET" || request.method === "HEAD" ? undefined : request.body,
    redirect: "manual",
  }));
}

async function deviceProxy(request: Request, env: Env): Promise<Response> {
  if (request.method !== "GET") return new Response("method not allowed", { status: 405 });
  const expected = `Bearer ${env.RELAY_DEVICE_SECRET}`;
  const supplied = request.headers.get("Authorization") ?? "";
  if (!env.RELAY_DEVICE_SECRET || supplied !== expected) {
    return new Response("unauthorized", { status: 401 });
  }

  const url = new URL(request.url);
  const match = url.pathname.match(/^\/device\/([^/]+)(\/.*)?$/);
  if (!match) return new Response("invalid device path", { status: 400 });

  const deviceId = decodeURIComponent(match[1] ?? "").trim();
  const path = match[2] || "/live.m3u8";
  if (!/^[A-Za-z0-9._~-]{1,128}$/.test(deviceId) || !DEVICE_PATH.test(path)) {
    return new Response("invalid device request", { status: 400 });
  }

  const id = env.RELAY_TUNNEL.idFromName(deviceId);
  const stub = env.RELAY_TUNNEL.get(id);
  const forwarded = new Request(request, {
    headers: new Headers({
      "x-relay-stream": deviceId,
      "x-relay-path": path,
    }),
  });
  return stub.fetch(forwarded);
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname.startsWith("/device/")) return deviceProxy(request, env);

    if (
      url.pathname === "/health" ||
      url.pathname === "/v1/session" ||
      url.pathname.startsWith("/v1/session/") ||
      url.pathname.startsWith("/stream/")
    ) {
      return gatewayProxy(request, env);
    }

    return relayWorker.fetch(request, env, ctx);
  },
};
