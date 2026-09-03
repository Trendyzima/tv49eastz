import { DurableObject } from "cloudflare:workers";

export interface Env {
  RELAY_TUNNEL: DurableObjectNamespace<RelayTunnel>;
  RELAY_SIGNING_SECRET: string;
  RELAY_DEVICE_SECRET: string;
}

type TicketKind = "relay" | "device";
type Ticket = { kind: TicketKind; stream: string; exp: number };
type ConnectionState = { stream: string; role: "producer" };

type Subscriber = {
  controller: ReadableStreamDefaultController<Uint8Array> | null;
  headersReady: Promise<Response>;
  resolveHeaders: (response: Response) => void;
  rejectHeaders: (reason?: unknown) => void;
  closed: boolean;
};

type InFlight = {
  id: number;
  path: string;
  status: number;
  headers: Record<string, string>;
  headersReceived: boolean;
  preHeader: Uint8Array[];
  preHeaderBytes: number;
  bytes: number;
  subscribers: Set<Subscriber>;
  timer: ReturnType<typeof setTimeout>;
};

const REQUEST_TIMEOUT_MS = 20_000;
const MAX_RESPONSE_BYTES = 64 * 1024 * 1024;
const MAX_PREHEADER_BYTES = 256 * 1024;
const MAX_INFLIGHT = 512;
const MAX_SUBSCRIBERS_PER_REQUEST = 10_000;
const MAX_VIEWER_QUEUE_BYTES = 1024 * 1024;
const MAX_TICKET_BYTES = 4096;
const ALLOWED_PATH = /^(?:\/live\.m3u8|\/init\.mp4|\/status|\/audio\/volume|\/hls\/[A-Za-z0-9_-]+)$/;

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") {
      return json({
        ok: true,
        service: "tv49eastz-cloudflare-relay",
        transport: "https",
        tunnel: "websocket",
        architecture: "one-durable-object-per-stream",
        fanout: "edge-cache-plus-request-coalescing",
      });
    }

    if (request.method === "GET" && url.pathname === "/tunnel") {
      return handleTunnelUpgrade(request, env);
    }

    if (request.method === "GET" && url.pathname === "/v1/relay") {
      return handleRelayRequest(request, env, ctx);
    }

    return json({
      ok: true,
      service: "tv49eastz-cloudflare-relay",
      endpoints: ["/health", "/tunnel", "/v1/relay"],
    });
  },
};

async function handleTunnelUpgrade(request: Request, env: Env): Promise<Response> {
  if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
    return json({ error: "websocket_upgrade_required" }, 426);
  }

  const url = new URL(request.url);
  const stream = url.searchParams.get("stream")?.trim() ?? "";
  const ticket = url.searchParams.get("ticket") ?? "";
  if (!validStream(stream)) return json({ error: "invalid_stream" }, 400);

  const verified = await verifyTicket(ticket, env.RELAY_DEVICE_SECRET, "device");
  if (!verified || verified.stream !== stream) {
    return json({ error: "invalid_or_expired_device_ticket" }, 401);
  }

  const id = env.RELAY_TUNNEL.idFromName(stream);
  return env.RELAY_TUNNEL.get(id).fetch(request);
}

async function handleRelayRequest(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const url = new URL(request.url);
  const stream = url.searchParams.get("id")?.trim() ?? "";
  const ticket = url.searchParams.get("ticket") ?? "";
  const path = url.searchParams.get("path") ?? "/live.m3u8";

  if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405);
  if (!validStream(stream)) return json({ error: "invalid_stream" }, 400);
  if (!ALLOWED_PATH.test(path)) return json({ error: "path_not_allowed" }, 403);

  const verified = await verifyTicket(ticket, env.RELAY_SIGNING_SECRET, "relay");
  if (!verified || verified.stream !== stream) {
    return json({ error: "invalid_or_expired_ticket" }, 401);
  }

  // Never include the viewer ticket in a cache key. The signed ticket is
  // authorization; the stream/path pair is the media identity.
  const cacheTtl = cacheTtlForPath(path);
  if (cacheTtl > 0) {
    const cacheKey = makeCacheKey(request, stream, path);
    const cached = await caches.default.match(cacheKey);
    if (cached) {
      const response = new Response(cached.body, cached);
      response.headers.set("x-tv49-cache", "HIT");
      return response;
    }

    const response = await forwardToStreamDO(request, env, stream, path);
    if (response.ok) {
      const cacheable = new Response(response.body, response);
      cacheable.headers.set("cache-control", `public, max-age=0, s-maxage=${cacheTtl}`);
      cacheable.headers.set("x-tv49-cache", "MISS");
      // Cache is intentionally best-effort. A cache failure must never break
      // playback, and Cache API entries are data-center local.
      ctx.waitUntil(caches.default.put(cacheKey, cacheable.clone()).catch(() => undefined));
      return cacheable;
    }
    return response;
  }

  return forwardToStreamDO(request, env, stream, path);
}

function forwardToStreamDO(request: Request, env: Env, stream: string, path: string): Promise<Response> {
  const id = env.RELAY_TUNNEL.idFromName(stream);
  return env.RELAY_TUNNEL.get(id).fetch(new Request(request, {
    headers: new Headers({
      "x-relay-stream": stream,
      "x-relay-path": path,
      "x-relay-ticket-exp": request.headers.get("x-relay-ticket-exp") ?? "",
    }),
  }));
}

function makeCacheKey(request: Request, stream: string, path: string): Request {
  const base = new URL(request.url);
  base.pathname = "/__tv49_cache/v1/" + encodeURIComponent(stream) + "/" + encodeURIComponent(path);
  base.search = "";
  return new Request(base.toString(), { method: "GET" });
}

function cacheTtlForPath(path: string): number {
  if (path === "/live.m3u8") return 1;
  if (path === "/init.mp4") return 3600;
  if (path.startsWith("/hls/")) return 30;
  return 0;
}

export class RelayTunnel extends DurableObject<Env> {
  private inflight = new Map<string, InFlight>();
  private nextRequestId = 1;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    for (const ws of this.ctx.getWebSockets()) {
      const state = ws.deserializeAttachment() as ConnectionState | null;
      if (!state || state.role !== "producer" || !validStream(state.stream)) {
        ws.close(1011, "invalid connection state");
      }
    }
    this.ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  async fetch(request: Request): Promise<Response> {
    const upgrade = request.headers.get("Upgrade")?.toLowerCase();
    if (upgrade === "websocket") return this.acceptProducer(request);

    const stream = request.headers.get("x-relay-stream") ?? "";
    const path = request.headers.get("x-relay-path") ?? "/live.m3u8";
    if (!validStream(stream) || !ALLOWED_PATH.test(path)) {
      return json({ error: "invalid_relay_request" }, 400);
    }

    const producer = this.findProducer(stream);
    if (!producer) return json({ error: "producer_offline" }, 503);

    const existing = this.inflight.get(path);
    if (existing) {
      if (existing.subscribers.size >= MAX_SUBSCRIBERS_PER_REQUEST) {
        return json({ error: "fanout_capacity_reached", retry_after: 1 }, 429);
      }
      return this.subscribe(existing, request);
    }

    if (this.inflight.size >= MAX_INFLIGHT) {
      return json({ error: "stream_capacity_reached", retry_after: 1 }, 429);
    }

    const id = this.allocateRequestId();
    let timeout: ReturnType<typeof setTimeout>;
    const shared: InFlight = {
      id,
      path,
      status: 502,
      headers: {},
      headersReceived: false,
      preHeader: [],
      preHeaderBytes: 0,
      bytes: 0,
      subscribers: new Set(),
      timer: undefined as unknown as ReturnType<typeof setTimeout>,
    };
    timeout = setTimeout(() => this.failShared(shared, "producer_timeout"), REQUEST_TIMEOUT_MS);
    shared.timer = timeout;
    this.inflight.set(path, shared);

    const responsePromise = this.subscribe(shared, request);
    try {
      producer.send(JSON.stringify({
        type: "request",
        id,
        method: "GET",
        path,
        headers: {
          accept: "application/vnd.apple.mpegurl,video/mp4,video/iso.segment,video/mp2t,application/octet-stream,*/*;q=0.5",
          "user-agent": "tv49eastz-cloudflare-relay/2",
        },
      }));
    } catch {
      this.failShared(shared, "producer_send_failed");
    }
    return responsePromise;
  }

  private subscribe(shared: InFlight, request: Request): Promise<Response> {
    let resolveHeaders!: (response: Response) => void;
    let rejectHeaders!: (reason?: unknown) => void;
    const headersReady = new Promise<Response>((resolve, reject) => {
      resolveHeaders = resolve;
      rejectHeaders = reject;
    });

    const subscriber: Subscriber = {
      controller: null,
      headersReady,
      resolveHeaders,
      rejectHeaders,
      closed: false,
    };
    const stream = new ReadableStream<Uint8Array>({
      start: (controller) => {
        subscriber.controller = controller;
      },
      cancel: () => {
        this.removeSubscriber(shared, subscriber);
      },
    }, {
      highWaterMark: MAX_VIEWER_QUEUE_BYTES,
      size: (chunk) => chunk.byteLength,
    });

    shared.subscribers.add(subscriber);
    if (request.signal.aborted) {
      this.removeSubscriber(shared, subscriber);
      return Promise.reject(new Error("viewer_aborted"));
    }
    request.signal.addEventListener("abort", () => this.removeSubscriber(shared, subscriber), { once: true });

    const response = headersReady.then((headerResponse) => new Response(stream, {
      status: headerResponse.status,
      headers: headerResponse.headers,
    }));
    return response.catch((error) => {
      this.removeSubscriber(shared, subscriber);
      throw error;
    });
  }

  private removeSubscriber(shared: InFlight, subscriber: Subscriber): void {
    if (subscriber.closed) return;
    subscriber.closed = true;
    shared.subscribers.delete(subscriber);
    if (shared.subscribers.size === 0 && this.inflight.get(shared.path) === shared) {
      this.inflight.delete(shared.path);
      clearTimeout(shared.timer);
      const producer = this.findProducerFromAll();
      try { producer?.send(JSON.stringify({ type: "cancel", id: shared.id })); } catch { /* best effort */ }
    }
  }

  private acceptProducer(request: Request): Response {
    const url = new URL(request.url);
    const stream = url.searchParams.get("stream")?.trim() ?? "";
    if (!validStream(stream)) return json({ error: "invalid_stream" }, 400);

    const existing = this.findProducer(stream);
    if (existing) {
      // Reconnects are expected. Close the stale tunnel and replace it instead
      // of making the mobile producer permanently stuck in a 409 loop.
      try { existing.close(1000, "producer_replaced"); } catch { /* ignore */ }
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair) as [WebSocket, WebSocket];
    this.ctx.acceptWebSocket(server, [stream]);
    server.serializeAttachment({ stream, role: "producer" } satisfies ConnectionState);
    try {
      server.send(JSON.stringify({ type: "ready", stream, protocol: 2, capabilities: ["request-coalescing", "streaming", "cancel"] }));
    } catch {
      server.close(1011, "ready_failed");
      return json({ error: "producer_handshake_failed" }, 502);
    }

    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const state = ws.deserializeAttachment() as ConnectionState | null;
    if (!state || state.role !== "producer" || !validStream(state.stream)) {
      ws.close(1008, "unauthorized");
      return;
    }

    if (typeof message !== "string") {
      this.handleBinary(message);
      return;
    }

    let msg: any;
    try {
      msg = JSON.parse(message);
    } catch {
      ws.send(JSON.stringify({ type: "error", error: "invalid_json" }));
      return;
    }

    if (msg?.type === "response") {
      this.handleResponseHeaders(msg);
    } else if (msg?.type === "end") {
      this.finishResponse(Number(msg.id));
    } else if (msg?.type === "error") {
      this.failResponse(Number(msg.id), String(msg.error ?? "producer_error"));
    } else if (msg?.type === "hello") {
      ws.send(JSON.stringify({ type: "ready", stream: state.stream, protocol: 2, capabilities: ["request-coalescing", "streaming", "cancel"] }));
    }
  }

  private handleBinary(message: ArrayBuffer): void {
    if (message.byteLength < 4) return;
    const view = new DataView(message);
    const id = view.getUint32(0, false);
    const shared = this.findInflightById(id);
    if (!shared) return;

    const payload = new Uint8Array(message.slice(4));
    shared.bytes += payload.byteLength;
    if (shared.bytes > MAX_RESPONSE_BYTES) {
      this.failShared(shared, "response_too_large");
      return;
    }

    if (!shared.headersReceived) {
      shared.preHeaderBytes += payload.byteLength;
      if (shared.preHeaderBytes > MAX_PREHEADER_BYTES) {
        this.failShared(shared, "headers_missing");
        return;
      }
      shared.preHeader.push(payload);
      return;
    }

    this.broadcast(shared, payload);
  }

  private handleResponseHeaders(msg: any): void {
    const id = Number(msg.id);
    const shared = this.findInflightById(id);
    if (!shared) return;

    const status = Number(msg.status);
    if (!Number.isInteger(status) || status < 100 || status > 599) {
      this.failShared(shared, "invalid_status");
      return;
    }
    shared.status = status;
    shared.headers = sanitizeHeaders(msg.headers);
    shared.headersReceived = true;

    const headers = new Headers(shared.headers);
    headers.set("x-tv49east-relay", "cloudflare");
    headers.set("cache-control", `public, max-age=0, s-maxage=${cacheTtlForPath(shared.path)}`);
    for (const subscriber of [...shared.subscribers]) {
      if (subscriber.closed) continue;
      subscriber.resolveHeaders(new Response(null, { status: shared.status, headers }));
    }

    for (const chunk of shared.preHeader) this.broadcast(shared, chunk);
    shared.preHeader = [];
    shared.preHeaderBytes = 0;
  }

  private broadcast(shared: InFlight, payload: Uint8Array): void {
    for (const subscriber of [...shared.subscribers]) {
      if (subscriber.closed || !subscriber.controller) continue;
      const desired = subscriber.controller.desiredSize;
      if (desired !== null && desired < payload.byteLength) {
        try { subscriber.controller.error(new Error("viewer_backpressure")); } catch { /* ignore */ }
        subscriber.closed = true;
        shared.subscribers.delete(subscriber);
        subscriber.rejectHeaders(new Error("viewer_backpressure"));
        continue;
      }
      try {
        subscriber.controller.enqueue(payload.slice());
      } catch {
        subscriber.closed = true;
        shared.subscribers.delete(subscriber);
      }
    }
  }

  private finishResponse(id: number): void {
    const shared = this.findInflightById(id);
    if (!shared) return;
    this.inflight.delete(shared.path);
    clearTimeout(shared.timer);

    if (!shared.headersReceived) {
      for (const subscriber of shared.subscribers) subscriber.rejectHeaders(new Error("producer_ended_without_headers"));
      return;
    }
    for (const subscriber of shared.subscribers) {
      if (subscriber.closed || !subscriber.controller) continue;
      try { subscriber.controller.close(); } catch { /* ignore */ }
    }
  }

  private failResponse(id: number, error: string): void {
    const shared = this.findInflightById(id);
    if (shared) this.failShared(shared, error);
  }

  private failShared(shared: InFlight, error: string): void {
    if (this.inflight.get(shared.path) === shared) this.inflight.delete(shared.path);
    clearTimeout(shared.timer);
    const failure = new Error(error);
    for (const subscriber of shared.subscribers) {
      subscriber.rejectHeaders(failure);
      if (subscriber.controller) {
        try { subscriber.controller.error(failure); } catch { /* ignore */ }
      }
      subscriber.closed = true;
    }
    shared.subscribers.clear();
  }

  async webSocketClose(ws: WebSocket): Promise<void> {
    const state = ws.deserializeAttachment() as ConnectionState | null;
    if (state?.role !== "producer") return;
    for (const shared of [...this.inflight.values()]) this.failShared(shared, "producer_disconnected");
  }

  async webSocketError(ws: WebSocket, _error: unknown): Promise<void> {
    const state = ws.deserializeAttachment() as ConnectionState | null;
    if (state?.role !== "producer") return;
    for (const shared of [...this.inflight.values()]) this.failShared(shared, "producer_websocket_error");
    try { ws.close(1011, "websocket error"); } catch { /* ignore */ }
  }

  private findProducer(stream: string): WebSocket | undefined {
    return this.ctx.getWebSockets(stream).find((ws) => {
      const state = ws.deserializeAttachment() as ConnectionState | null;
      return state?.role === "producer" && state.stream === stream && ws.readyState === WebSocket.OPEN;
    });
  }

  private findProducerFromAll(): WebSocket | undefined {
    return this.ctx.getWebSockets().find((ws) => ws.readyState === WebSocket.OPEN);
  }

  private findInflightById(id: number): InFlight | undefined {
    for (const shared of this.inflight.values()) if (shared.id === id) return shared;
    return undefined;
  }

  private allocateRequestId(): number {
    for (let i = 0; i < 0xffffffff; i++) {
      const id = this.nextRequestId >>> 0;
      this.nextRequestId = (id + 1) >>> 0;
      if (id !== 0 && !this.findInflightById(id)) return id;
    }
    throw new Error("request id space exhausted");
  }
}

function validStream(value: string): boolean {
  return /^[A-Za-z0-9._-]{1,128}$/.test(value);
}

async function verifyTicket(raw: string, secret: string, expectedKind: TicketKind): Promise<Ticket | null> {
  if (!raw || raw.length > MAX_TICKET_BYTES || !secret) return null;
  const parts = raw.split(".");
  if (parts.length !== 2 || !parts[0] || !parts[1]) return null;
  try {
    const payload = new TextDecoder().decode(fromBase64Url(parts[0]));
    const fields = payload.split("\0");
    if (fields.length !== 3) return null;
    const [kind, stream, expRaw] = fields;
    const exp = Number(expRaw);
    const now = Math.floor(Date.now() / 1000);
    if (kind !== expectedKind || !validStream(stream) || !Number.isSafeInteger(exp) || now >= exp) return null;

    const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
    const expected = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(payload)));
    const actual = fromBase64Url(parts[1]);
    if (actual.length !== expected.length) return null;
    let diff = 0;
    for (let i = 0; i < expected.length; i++) diff |= expected[i] ^ actual[i];
    return diff === 0 ? { kind: expectedKind, stream, exp } : null;
  } catch {
    return null;
  }
}

function fromBase64Url(value: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new Error("invalid base64url");
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((value.length + 3) % 4);
  const binary = atob(normalized);
  return Uint8Array.from(binary, (c) => c.charCodeAt(0));
}

function sanitizeHeaders(input: unknown): Record<string, string> {
  const output: Record<string, string> = {};
  if (!input || typeof input !== "object") return output;
  for (const [key, value] of Object.entries(input as Record<string, unknown>)) {
    if (!/^[A-Za-z0-9-]+$/.test(key) || typeof value !== "string") continue;
    const lower = key.toLowerCase();
    if (["connection", "upgrade", "transfer-encoding", "content-length", "set-cookie", "server-timing"].includes(lower)) continue;
    if (value.length > 4096) continue;
    output[key] = value;
  }
  return output;
}

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
    },
  });
}
