import { DurableObject } from "cloudflare:workers";

export interface Env {
  RELAY_TUNNEL: DurableObjectNamespace<RelayTunnel>;
  PUBLIC_STREAM_ID: string;
  RELAY_SIGNING_SECRET: string;
  RELAY_DEVICE_SECRET: string;
}

type TicketKind = "relay" | "device";
type Ticket = { kind: TicketKind; stream: string; exp: number };
type ConnectionState = { stream: string; role: "producer" };
type PendingResponse = {
  status: number;
  headers: Record<string, string>;
  chunks: Uint8Array[];
  bytes: number;
  resolve: (value: Response) => void;
  reject: (reason?: unknown) => void;
};

const MAX_BODY = 16 * 1024 * 1024;
const REQUEST_TIMEOUT_MS = 20_000;
const ALLOWED_PATH = /^(?:\/live\.m3u8|\/init\.mp4|\/status|\/audio\/volume|\/hls\/[A-Za-z0-9_-]+)$/;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") {
      return json({ ok: true, service: "tv49eastz-cloudflare-relay", transport: "https", tunnel: "websocket" });
    }

    if (request.method === "GET" && url.pathname === "/tunnel") {
      return handleTunnelUpgrade(request, env);
    }

    if (request.method === "GET" && url.pathname === "/v1/relay") {
      return handleRelayRequest(request, env);
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
  if (!validStream(stream) || stream !== env.PUBLIC_STREAM_ID) {
    return json({ error: "stream_not_allowed" }, 403);
  }

  const verified = await verifyTicket(ticket, env.RELAY_DEVICE_SECRET, "device");
  if (!verified || verified.stream !== stream) {
    return json({ error: "invalid_or_expired_device_ticket" }, 401);
  }

  const id = env.RELAY_TUNNEL.idFromName(stream);
  return env.RELAY_TUNNEL.get(id).fetch(request);
}

async function handleRelayRequest(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const stream = url.searchParams.get("id")?.trim() ?? "";
  const ticket = url.searchParams.get("ticket") ?? "";
  const path = url.searchParams.get("path") ?? "/live.m3u8";

  if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405);
  if (!validStream(stream) || stream !== env.PUBLIC_STREAM_ID) {
    return json({ error: "stream_not_allowed" }, 403);
  }
  if (!ALLOWED_PATH.test(path)) return json({ error: "path_not_allowed" }, 403);

  const verified = await verifyTicket(ticket, env.RELAY_SIGNING_SECRET, "relay");
  if (!verified || verified.stream !== stream) {
    return json({ error: "invalid_or_expired_ticket" }, 401);
  }

  const id = env.RELAY_TUNNEL.idFromName(stream);
  return env.RELAY_TUNNEL.get(id).fetch(new Request(request, {
    headers: new Headers({
      "x-relay-stream": stream,
      "x-relay-path": path,
      "x-relay-ticket-exp": String(verified.exp),
    }),
  }));
}

export class RelayTunnel extends DurableObject<Env> {
  private pending = new Map<number, PendingResponse>();
  private nextRequestId = 1;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.ctx.getWebSockets().forEach((ws) => {
      if (!ws.deserializeAttachment()) ws.close(1011, "missing connection state");
    });
    this.ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  async fetch(request: Request): Promise<Response> {
    const upgrade = request.headers.get("Upgrade")?.toLowerCase();
    if (upgrade === "websocket") return this.acceptProducer(request);

    const stream = request.headers.get("x-relay-stream") ?? "";
    const path = request.headers.get("x-relay-path") ?? "/live.m3u8";
    if (!validStream(stream) || stream !== this.env.PUBLIC_STREAM_ID || !ALLOWED_PATH.test(path)) {
      return json({ error: "invalid_relay_request" }, 400);
    }

    const producer = this.findProducer(stream);
    if (!producer) return json({ error: "producer_offline" }, 503);

    const id = this.allocateRequestId();
    const response = new Promise<Response>((resolve, reject) => {
      this.pending.set(id, {
        status: 502,
        headers: {},
        chunks: [],
        bytes: 0,
        resolve,
        reject,
      });
    });

    try {
      producer.send(JSON.stringify({
        type: "request",
        id,
        method: "GET",
        path,
        headers: {
          accept: "application/vnd.apple.mpegurl,video/mp4,video/iso.segment,video/mp2t,application/octet-stream,*/*;q=0.5",
          "user-agent": "tv49eastz-cloudflare-relay/1",
        },
      }));
    } catch {
      this.pending.delete(id);
      return json({ error: "producer_send_failed" }, 502);
    }

    return await withTimeout(response, REQUEST_TIMEOUT_MS, () => {
      const pending = this.pending.get(id);
      this.pending.delete(id);
      pending?.reject(new Error("producer request timeout"));
    }).catch(() => {
      this.pending.delete(id);
      return json({ error: "producer_timeout" }, 504);
    });
  }

  private acceptProducer(request: Request): Response {
    const url = new URL(request.url);
    const stream = url.searchParams.get("stream")?.trim() ?? "";
    if (!validStream(stream) || stream !== this.env.PUBLIC_STREAM_ID) {
      return json({ error: "stream_not_allowed" }, 403);
    }

    const existing = this.findProducer(stream);
    if (existing) return json({ error: "producer_already_connected" }, 409);

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.ctx.acceptWebSocket(server, [stream]);
    server.serializeAttachment({ stream, role: "producer" } satisfies ConnectionState);
    server.send(JSON.stringify({ type: "ready", stream, protocol: 1 }));

    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const state = ws.deserializeAttachment() as ConnectionState | null;
    if (!state || state.role !== "producer") {
      ws.close(1008, "unauthorized");
      return;
    }

    if (typeof message !== "string") {
      await this.handleBinary(ws, message);
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
      ws.send(JSON.stringify({ type: "ready", stream: state.stream, protocol: 1 }));
    }
  }

  private async handleBinary(_ws: WebSocket, message: ArrayBuffer): Promise<void> {
    if (message.byteLength < 4) return;
    const view = new DataView(message);
    const id = view.getUint32(0, false);
    const pending = this.pending.get(id);
    if (!pending) return;

    const payload = new Uint8Array(message.slice(4));
    pending.bytes += payload.byteLength;
    if (pending.bytes > MAX_BODY) {
      this.failResponse(id, "response_too_large");
      return;
    }
    pending.chunks.push(payload);
  }

  private handleResponseHeaders(msg: any): void {
    const id = Number(msg.id);
    const pending = this.pending.get(id);
    if (!pending) return;
    const status = Number(msg.status);
    if (!Number.isInteger(status) || status < 100 || status > 599) {
      this.failResponse(id, "invalid_status");
      return;
    }
    pending.status = status;
    pending.headers = sanitizeHeaders(msg.headers);
  }

  private finishResponse(id: number): void {
    const pending = this.pending.get(id);
    if (!pending) return;
    this.pending.delete(id);

    const body = concat(pending.chunks, pending.bytes);
    const headers = new Headers(pending.headers);
    headers.set("cache-control", "no-store");
    headers.set("x-tv49east-relay", "cloudflare");
    pending.resolve(new Response(body, { status: pending.status, headers }));
  }

  private failResponse(id: number, error: string): void {
    const pending = this.pending.get(id);
    if (!pending) return;
    this.pending.delete(id);
    pending.reject(new Error(error));
  }

  async webSocketClose(ws: WebSocket): Promise<void> {
    for (const pending of this.pending.values()) pending.reject(new Error("producer disconnected"));
    this.pending.clear();
    ws.close();
  }

  async webSocketError(ws: WebSocket, _error: unknown): Promise<void> {
    for (const pending of this.pending.values()) pending.reject(new Error("producer websocket error"));
    this.pending.clear();
    ws.close(1011, "websocket error");
  }

  private findProducer(stream: string): WebSocket | undefined {
    return this.ctx.getWebSockets(stream).find((ws) => {
      const state = ws.deserializeAttachment() as ConnectionState | null;
      return state?.role === "producer" && state.stream === stream && ws.readyState === WebSocket.OPEN;
    });
  }

  private allocateRequestId(): number {
    for (let i = 0; i < 0xffffffff; i++) {
      const id = this.nextRequestId >>> 0;
      this.nextRequestId = (id + 1) >>> 0;
      if (id !== 0 && !this.pending.has(id)) return id;
    }
    throw new Error("request id space exhausted");
  }
}

function validStream(value: string): boolean {
  return /^[A-Za-z0-9._-]{1,128}$/.test(value);
}

async function verifyTicket(raw: string, secret: string, expectedKind: TicketKind): Promise<Ticket | null> {
  const [payloadPart, signaturePart] = raw.split(".");
  if (!payloadPart || !signaturePart || !secret) return null;
  try {
    const payload = new TextDecoder().decode(fromBase64Url(payloadPart));
    const [kind, stream, expRaw] = payload.split("\0");
    const exp = Number(expRaw);
    if (kind !== expectedKind || !validStream(stream) || !Number.isFinite(exp) || exp < Math.floor(Date.now() / 1000)) return null;

    const key = await crypto.subtle.importKey(
      "raw",
      new TextEncoder().encode(secret),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["sign"],
    );
    const expected = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(payload)));
    const actual = fromBase64Url(signaturePart);
    if (actual.length !== expected.length) return null;
    let diff = 0;
    for (let i = 0; i < expected.length; i++) diff |= expected[i] ^ actual[i];
    if (diff !== 0) return null;
    return { kind: expectedKind, stream, exp };
  } catch {
    return null;
  }
}

function fromBase64Url(value: string): Uint8Array {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((value.length + 3) % 4);
  const binary = atob(normalized);
  return Uint8Array.from(binary, (c) => c.charCodeAt(0));
}

function concat(chunks: Uint8Array[], length: number): Uint8Array {
  const output = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    output.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return output;
}

function sanitizeHeaders(input: unknown): Record<string, string> {
  const output: Record<string, string> = {};
  if (!input || typeof input !== "object") return output;
  for (const [key, value] of Object.entries(input as Record<string, unknown>)) {
    if (!/^[A-Za-z0-9-]+$/.test(key) || typeof value !== "string") continue;
    const lower = key.toLowerCase();
    if (["connection", "upgrade", "transfer-encoding", "content-length", "set-cookie"].includes(lower)) continue;
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

async function withTimeout<T>(promise: Promise<T>, ms: number, onTimeout: () => void): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      promise,
      new Promise<T>((_, reject) => {
        timer = setTimeout(() => {
          onTimeout();
          reject(new Error("timeout"));
        }, ms);
      }),
    ]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}
