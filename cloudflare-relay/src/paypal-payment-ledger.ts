export type SupabaseAdminEnv = {
  SUPABASE_URL: string;
  SUPABASE_SECRET_KEY?: string;
  SUPABASE_SERVICE_ROLE_KEY?: string;
};

export function supabaseServerKey(env: SupabaseAdminEnv): string {
  return env.SUPABASE_SECRET_KEY ?? env.SUPABASE_SERVICE_ROLE_KEY ?? "";
}

async function request(env: SupabaseAdminEnv, path: string, init: RequestInit = {}): Promise<Response> {
  const key = supabaseServerKey(env);
  if (!key) throw new Error("missing_supabase_server_key");
  const headers = new Headers(init.headers);
  headers.set("apikey", key);
  headers.set("Authorization", `Bearer ${key}`);
  headers.set("content-type", "application/json");
  return fetch(`${env.SUPABASE_URL}/rest/v1${path}`, { ...init, headers });
}

export async function createPendingTopup(env: SupabaseAdminEnv, userId: string, orderId: string, amountCents: number, currency: string) {
  if (!Number.isSafeInteger(amountCents) || amountCents <= 0) throw new Error("invalid_amount");
  const existing = await request(env, `/wallet_transactions?provider=eq.paypal&provider_order_id=eq.${encodeURIComponent(orderId)}&select=id,user_id,amount_cents,currency,status&limit=1`);
  if (!existing.ok) throw new Error(`supabase_lookup_${existing.status}`);
  const rows = await existing.json() as Array<{id:string;user_id:string;amount_cents:number;currency:string;status:string}>;
  if (rows[0]) {
    if (rows[0].user_id !== userId || rows[0].amount_cents !== amountCents || rows[0].currency !== currency) throw new Error("payment_order_conflict");
    return rows[0];
  }
  const txResponse = await request(env, "/wallet_transactions", {
    method: "POST",
    headers: { Prefer: "return=representation" },
    body: JSON.stringify({ user_id: userId, kind: "topup", amount_cents: amountCents, currency, direction: "credit", status: "pending", provider: "paypal", provider_order_id: orderId, description: "PayPal wallet top-up" }),
  });
  if (!txResponse.ok) throw new Error(`supabase_insert_${txResponse.status}`);
  const txRows = await txResponse.json() as Array<{id:string;user_id:string;amount_cents:number;currency:string;status:string}>;
  return txRows[0];
}

export async function createPaypalOrderRecord(env: SupabaseAdminEnv, userId: string, orderId: string, amountCents: number, currency: string, approvalUrl?: string) {
  const response = await request(env, "/paypal_orders", {
    method: "POST",
    headers: { Prefer: "return=representation,resolution=ignore-duplicates" },
    body: JSON.stringify({ user_id: userId, wallet_transaction_id: undefined, paypal_order_id: orderId, amount_cents: amountCents, currency, status: "created", approval_url: approvalUrl ?? null }),
  });
  if (!response.ok) throw new Error(`paypal_order_insert_${response.status}`);
  return response.json();
}

export async function markPaypalOrderApproved(env: SupabaseAdminEnv, orderId: string) {
  const response = await request(env, `/paypal_orders?paypal_order_id=eq.${encodeURIComponent(orderId)}`, { method: "PATCH", body: JSON.stringify({ status: "approved" }) });
  if (!response.ok) throw new Error(`paypal_order_approve_${response.status}`);
}

export async function finalizePaypalCapture(env: SupabaseAdminEnv, orderId: string, captureId: string) {
  const response = await request(env, "/rpc/finalize_paypal_topup", { method: "POST", body: JSON.stringify({ p_order_id: orderId, p_capture_id: captureId }) });
  if (!response.ok) throw new Error(`paypal_finalize_${response.status}`);
  return response.json();
}

export async function recordWebhook(env: SupabaseAdminEnv, event: { eventId:string; eventType:string; orderId?:string; captureId?:string; transmissionId?:string; payload:unknown }) {
  const response = await request(env, "/paypal_webhook_events", {
    method: "POST",
    headers: { Prefer: "return=representation,resolution=ignore-duplicates" },
    body: JSON.stringify({ event_id:event.eventId, event_type:event.eventType, paypal_order_id:event.orderId ?? null, paypal_capture_id:event.captureId ?? null, transmission_id:event.transmissionId ?? null, verified:true, processed:false, payload:event.payload }),
  });
  if (!response.ok) throw new Error(`webhook_record_${response.status}`);
  const rows = await response.json() as unknown[];
  return rows.length > 0;
}

export async function markWebhookProcessed(env: SupabaseAdminEnv, eventId: string, error?: string) {
  const response = await request(env, `/paypal_webhook_events?event_id=eq.${encodeURIComponent(eventId)}`, { method:"PATCH", body:JSON.stringify({ processed:!error, processing_error:error ?? null, processed_at:error ? null : new Date().toISOString() }) });
  if (!response.ok) throw new Error(`webhook_mark_${response.status}`);
}
