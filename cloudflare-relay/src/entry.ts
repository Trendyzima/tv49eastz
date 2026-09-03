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
  };

  override onStart() {
    console.log("stream-gateway container started", this.ctx.id.toString());
  }

  override onError(error: unknown) {
    console.error("stream-gateway container error", error);
  }
}

export { RelayTunnel };

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);

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
