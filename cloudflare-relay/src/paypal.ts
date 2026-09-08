export interface PayPalEnv {
  PAYPAL_CLIENT_ID?: string;
  PAYPAL_CLIENT_SECRET?: string;
  PAYPAL_ENV?: string;
  PAYPAL_WEBHOOK_ID?: string;
}

type JsonRecord = Record<string, unknown>;

const MAX_BODY_BYTES = 32 * 1024;
const ALLOWED_CURRENCIES = /^[A-Z]{3}$/;

export async function handlePayPalRequest(request: Request, env: PayPalEnv): Promise<Response> {
  const url = new URL(request.url);

  if (request.method === "GET" && url.pathname === "/v1/paypal/config") {
    return json({
      ok: true,
      provider: "paypal",
      environment: paypalBaseUrl(env).includes("sandbox") ? "sandbox" : "live",
      configured: Boolean(env.PAYPAL_CLIENT_ID && env.PAYPAL_CLIENT_SECRET),
      endpoints: [
        "/v1/paypal/orders",
        "/v1/paypal/orders/:orderId/capture",
        "/v1/paypal/orders/:orderId",
        "/v1/paypal/webhook",
      ],
    });
  }

  if (url.pathname === "/v1/paypal/orders" && request.method === "POST") return createOrder(request, env);

  const captureMatch = url.pathname.match(/^\/v1\/paypal\/orders\/([^/]+)\/capture$/);
  if (captureMatch && request.method === "POST") return captureOrder(captureMatch[1], env);

  const orderMatch = url.pathname.match(/^\/v1\/paypal\/orders\/([^/]+)$/);
  if (orderMatch && request.method === "GET") return showOrder(orderMatch[1], env);

  if (url.pathname === "/v1/paypal/webhook" && request.method === "POST") return verifyWebhook(request, env);

  return json({ error: "paypal_route_not_found" }, 404);
}

async function createOrder(request: Request, env: PayPalEnv): Promise<Response> {
  const body = await readJson(request);
  if (!body) return json({ error: "invalid_json" }, 400);

  const amount = String(body.amount ?? "").trim();
  const currency = String(body.currency ?? "USD").trim().toUpperCase();
  const description = String(body.description ?? "TV49 East purchase").trim().slice(0, 127);
  const referenceId = String(body.reference_id ?? crypto.randomUUID()).trim().slice(0, 256);
  const returnUrl = String(body.return_url ?? "").trim();
  const cancelUrl = String(body.cancel_url ?? "").trim();

  if (!/^\d+(?:\.\d{1,2})?$/.test(amount) || Number(amount) <= 0) return json({ error: "invalid_amount" }, 400);
  if (!ALLOWED_CURRENCIES.test(currency)) return json({ error: "invalid_currency" }, 400);
  if (returnUrl && !isHttpsUrl(returnUrl)) return json({ error: "return_url_must_be_https" }, 400);
  if (cancelUrl && !isHttpsUrl(cancelUrl)) return json({ error: "cancel_url_must_be_https" }, 400);

  const accessToken = await getAccessToken(env);
  if (!accessToken) return json({ error: "paypal_not_configured" }, 503);

  const applicationContext: JsonRecord = { shipping_preference: "NO_SHIPPING", user_action: "PAY_NOW" };
  if (returnUrl) applicationContext.return_url = returnUrl;
  if (cancelUrl) applicationContext.cancel_url = cancelUrl;

  const payload = {
    intent: "CAPTURE",
    purchase_units: [{ reference_id: referenceId, description, amount: { currency_code: currency, value: amount } }],
    application_context: applicationContext,
  };

  const response = await paypalFetch(paypalBaseUrl(env), "/v2/checkout/orders", accessToken, {
    method: "POST",
    headers: { "PayPal-Request-Id": `tv49-${crypto.randomUUID()}` },
    body: JSON.stringify(payload),
  });
  return paypalResponse(response);
}

async function captureOrder(orderId: string, env: PayPalEnv): Promise<Response> {
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(orderId)) return json({ error: "invalid_order_id" }, 400);
  const accessToken = await getAccessToken(env);
  if (!accessToken) return json({ error: "paypal_not_configured" }, 503);

  const response = await paypalFetch(paypalBaseUrl(env), `/v2/checkout/orders/${encodeURIComponent(orderId)}/capture`, accessToken, {
    method: "POST",
    headers: { "PayPal-Request-Id": `tv49-capture-${orderId}` },
    body: "{}",
  });
  return paypalResponse(response);
}

async function showOrder(orderId: string, env: PayPalEnv): Promise<Response> {
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(orderId)) return json({ error: "invalid_order_id" }, 400);
  const accessToken = await getAccessToken(env);
  if (!accessToken) return json({ error: "paypal_not_configured" }, 503);
  const response = await paypalFetch(paypalBaseUrl(env), `/v2/checkout/orders/${encodeURIComponent(orderId)}`, accessToken);
  return paypalResponse(response);
}

async function verifyWebhook(request: Request, env: PayPalEnv): Promise<Response> {
  if (!env.PAYPAL_WEBHOOK_ID) return json({ error: "paypal_webhook_not_configured" }, 503);

  const rawBody = await request.text();
  if (new TextEncoder().encode(rawBody).byteLength > MAX_BODY_BYTES) return json({ error: "webhook_body_too_large" }, 413);

  const transmissionId = request.headers.get("paypal-transmission-id");
  const transmissionTime = request.headers.get("paypal-transmission-time");
  const certUrl = request.headers.get("paypal-cert-url");
  const authAlgo = request.headers.get("paypal-auth-algo");
  const transmissionSig = request.headers.get("paypal-transmission-sig");
  if (!transmissionId || !transmissionTime || !certUrl || !authAlgo || !transmissionSig) return json({ error: "missing_paypal_webhook_headers" }, 400);

  const accessToken = await getAccessToken(env);
  if (!accessToken) return json({ error: "paypal_not_configured" }, 503);

  const verifyResponse = await paypalFetch(paypalBaseUrl(env), "/v1/notifications/verify-webhook-signature", accessToken, {
    method: "POST",
    body: JSON.stringify({
      auth_algo: authAlgo,
      cert_url: certUrl,
      transmission_id: transmissionId,
      transmission_sig: transmissionSig,
      transmission_time: transmissionTime,
      webhook_id: env.PAYPAL_WEBHOOK_ID,
      webhook_event: safeJson(rawBody),
    }),
  });

  if (!verifyResponse.ok) return paypalResponse(verifyResponse);
  const verification = await verifyResponse.json<JsonRecord>();
  if (verification.verification_status !== "SUCCESS") return json({ error: "paypal_webhook_signature_invalid" }, 400);

  const event = safeJson(rawBody) as JsonRecord;
  return json({
    ok: true,
    verified: true,
    event_id: typeof event.id === "string" ? event.id : null,
    event_type: typeof event.event_type === "string" ? event.event_type : "UNKNOWN",
  });
}

async function getAccessToken(env: PayPalEnv): Promise<string | null> {
  if (!env.PAYPAL_CLIENT_ID || !env.PAYPAL_CLIENT_SECRET) return null;
  const credentials = btoa(`${env.PAYPAL_CLIENT_ID}:${env.PAYPAL_CLIENT_SECRET}`);
  const response = await fetch(`${paypalBaseUrl(env)}/v1/oauth2/token`, {
    method: "POST",
    headers: { Authorization: `Basic ${credentials}`, "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" },
    body: "grant_type=client_credentials",
  });
  if (!response.ok) return null;
  const data = await response.json<{ access_token?: string }>();
  return typeof data.access_token === "string" ? data.access_token : null;
}

function paypalBaseUrl(env: PayPalEnv): string {
  const mode = String(env.PAYPAL_ENV ?? "sandbox").toLowerCase();
  return mode === "live" || mode === "production" ? "https://api-m.paypal.com" : "https://api-m.sandbox.paypal.com";
}

async function paypalFetch(baseUrl: string, path: string, accessToken: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set("Authorization", `Bearer ${accessToken}`);
  headers.set("Accept", "application/json");
  if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  return fetch(`${baseUrl}${path}`, { ...init, headers });
}

function isHttpsUrl(value: string): boolean {
  try { return new URL(value).protocol === "https:"; } catch { return false; }
}

async function readJson(request: Request): Promise<JsonRecord | null> {
  const length = Number(request.headers.get("content-length") ?? "0");
  if (length > MAX_BODY_BYTES) return null;
  try {
    const value = await request.json();
    return value && typeof value === "object" && !Array.isArray(value) ? value as JsonRecord : null;
  } catch { return null; }
}

function safeJson(raw: string): unknown {
  try { return JSON.parse(raw); } catch { return {}; }
}

async function paypalResponse(response: Response): Promise<Response> {
  const body = await response.text();
  return new Response(body || "{}", {
    status: response.status,
    headers: {
      "content-type": response.headers.get("content-type") ?? "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
    },
  });
}

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "x-content-type-options": "nosniff" },
  });
}
