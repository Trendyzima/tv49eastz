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

async function rpc(env: SupabaseAdminEnv, name: string, body: Record<string, unknown>): Promise<Response> {
  return request(env, `/rpc/${name}`, { method: "POST", body: JSON.stringify(body) });
}

export async function createPendingTopup(
  env: SupabaseAdminEnv,
  userId: string,
  orderId: string,
  amountCents: number,
  currency: string,
) {
  if (!Number.isSafeInteger(amountCents) || amountCents <= 0) throw new Error("invalid_amount");
  const amount = (amountCents / 100).toFixed(2);

  const walletResponse = await rpc(env, "ensure_wallet", { p_user_id: userId });
  if (!walletResponse.ok) throw new Error(`wallet_provision_${walletResponse.status}`);
  const wallet = await walletResponse.json() as { id?: string; user_id?: string; currency?: string };
  if (!wallet.id || wallet.user_id !== userId) throw new Error("wallet_provision_invalid_response");

  const existing = await request(
    env,
    `/wallet_transactions?provider=eq.paypal&provider_reference=eq.${encodeURIComponent(orderId)}&select=id,wallet_id,user_id,amount,currency,status&limit=1`,
  );
  if (!existing.ok) throw new Error(`supabase_lookup_${existing.status}`);
  const rows = await existing.json() as Array<{
    id: string;
    wallet_id: string;
    user_id: string;
    amount: string | number;
    currency: string;
    status: string;
  }>;

  if (rows[0]) {
    const existingAmount = Number(rows[0].amount);
    if (
      rows[0].user_id !== userId ||
      rows[0].wallet_id !== wallet.id ||
      Math.round(existingAmount * 100) !== amountCents ||
      rows[0].currency !== currency
    ) throw new Error("payment_order_conflict");
    return rows[0];
  }

  const txResponse = await request(env, "/wallet_transactions", {
    method: "POST",
    headers: { Prefer: "return=representation" },
    body: JSON.stringify({
      wallet_id: wallet.id,
      user_id: userId,
      type: "deposit",
      status: "pending",
      amount,
      currency,
      provider: "paypal",
      provider_reference: orderId,
      provider_status: "CREATED",
      payment_method: "paypal",
      description: "PayPal wallet top-up",
      metadata: { paypal_order_id: orderId },
    }),
  });
  if (!txResponse.ok) throw new Error(`supabase_insert_${txResponse.status}`);
  const txRows = await txResponse.json() as Array<Record<string, unknown>>;
  if (!txRows[0]) throw new Error("supabase_insert_empty");
  return txRows[0];
}

export async function createPaypalOrderRecord(
  env: SupabaseAdminEnv,
  userId: string,
  txId: string,
  orderId: string,
  amountCents: number,
  currency: string,
  approvalUrl?: string,
) {
  const response = await request(env, "/paypal_orders", {
    method: "POST",
    headers: { Prefer: "return=representation,resolution=ignore-duplicates" },
    body: JSON.stringify({
      user_id: userId,
      wallet_transaction_id: txId,
      order_id: orderId,
      amount: (amountCents / 100).toFixed(2),
      currency,
      status: "created",
      approval_url: approvalUrl ?? null,
    }),
  });
  if (!response.ok) throw new Error(`paypal_order_insert_${response.status}`);
  return response.json();
}

export async function markPaypalOrderApproved(env: SupabaseAdminEnv, orderId: string) {
  const r = await request(env, `/paypal_orders?order_id=eq.${encodeURIComponent(orderId)}`, {
    method: "PATCH",
    body: JSON.stringify({ status: "approved", updated_at: new Date().toISOString() }),
  });
  if (!r.ok) throw new Error(`paypal_order_approve_${r.status}`);
}

export async function finalizePaypalCapture(env: SupabaseAdminEnv, orderId: string, captureId: string) {
  const r = await rpc(env, "finalize_paypal_topup", { p_order_id: orderId, p_capture_id: captureId });
  if (!r.ok) throw new Error(`paypal_finalize_${r.status}`);
  return r.json();
}

export async function recordWebhook(
  env: SupabaseAdminEnv,
  event: {
    eventId: string;
    eventType: string;
    orderId?: string;
    captureId?: string;
    transmissionId?: string;
    payload: unknown;
  },
) {
  const resourceId = event.captureId ?? event.orderId ?? null;
  const r = await request(env, "/paypal_webhook_events", {
    method: "POST",
    headers: { Prefer: "return=representation,resolution=ignore-duplicates" },
    body: JSON.stringify({
      event_id: event.eventId,
      event_type: event.eventType,
      resource_id: resourceId,
      transmission_id: event.transmissionId ?? null,
      verified: true,
      processed: false,
      payload: event.payload,
    }),
  });
  if (!r.ok) throw new Error(`webhook_record_${r.status}`);
  const rows = await r.json() as unknown[];
  return rows.length > 0;
}

export async function markWebhookProcessed(env: SupabaseAdminEnv, eventId: string, error?: string) {
  const r = await request(env, `/paypal_webhook_events?event_id=eq.${encodeURIComponent(eventId)}`, {
    method: "PATCH",
    body: JSON.stringify({
      processed: !error,
      processing_error: error ?? null,
      processed_at: error ? null : new Date().toISOString(),
    }),
  });
  if (!r.ok) throw new Error(`webhook_mark_${r.status}`);
}
