import { Container, getContainer } from "@cloudflare/containers";
import relayWorker, { RelayTunnel } from "./index";

export interface Env {
  RELAY_TUNNEL: DurableObjectNamespace<RelayTunnel>;
  STREAM_GATEWAY: DurableObjectNamespace<StreamGatewayContainer>;
  RELAY_SIGNING_SECRET: string;
  RELAY_DEVICE_SECRET: string;
  GATEWAY_API_KEY: string;
  GATEWAY_CAPABILITY_KEY: string;
  TUNNEL_PROXY_BASE_URL: string;
}

const DEVICE_PATH = /^(?:\/live\.m3u8|\/init\.mp4|\/status|\/audio\/volume|\/hls\/[A-Za-z0-9_-]+)$/;

export class StreamGatewayContainer extends Container<Env> {
  defaultPort = 8080;
  sleepAfter = "15m";
  pingEndpoint = "health";
  envVars = {
    GATEWAY_LISTEN: ":8787",
    TAP_UPSTREAM: "http://127.0.0.1:8786",
    GATEWAY_API_KEY: this.env.GATEWAY_API_KEY,
    GATEWAY_CAPABILITY_KEY: this.env.GATEWAY_CAPABILITY_KEY,
    TUNNEL_PROXY_BASE_URL: this.env.TUNNEL_PROXY_BASE_URL,
    TUNNEL_PROXY_AUTH: this.env.RELAY_DEVICE_SECRET,
  };

  override onStart() {
    console.log("stream-gateway container started", this.ctx.id.toString());
  }

  override onError(error: unknown) {
    console.error("stream-gateway container error", error);
  }
}

export { RelayTunnel };

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

    if (url.pathname.startsWith("/device/")) {
      return deviceProxy(request, env);
    }

    if (
      url.pathname === "/health" ||
      url.pathname === "/v1/session" ||
      url.pathname.startsWith("/v1/session/") ||
      url.pathname.startsWith("/stream/")
    ) {
      const container = getContainer(env.STREAM_GATEWAY, "production-gateway");
      return container.fetch(request);
    }

    return relayWorker.fetch(request, env, ctx);
  },
};
