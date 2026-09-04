interface Env {
  SUPABASE_URL: string;
  SUPABASE_SERVICE_ROLE_KEY: string;
  FEDERATION_USER_AGENT?: string;
}

type Delivery = {
  id: string; activity_payload: Record<string, unknown>; target_inbox: string;
  attempt_count: number; instance_domain: string;
};

const MAX_ATTEMPTS = 12;
const BACKOFF_SECONDS = [15,30,60,120,300,600,1200,2400,4800,9600,19200,38400];

export default {
  async scheduled(_event: ScheduledEvent, env: Env, ctx: ExecutionContext) {
    ctx.waitUntil(drain(env));
  },
  async fetch(_request: Request, env: Env) {
    const result = await drain(env);
    return Response.json(result);
  }
};

async function drain(env: Env) {
  const claimed = await rpc(env, 'claim_federation_deliveries', { p_limit: 50 });
  let delivered = 0, retried = 0, dead = 0;
  for (const d of (claimed || []) as Delivery[]) {
    try {
      const response = await fetch(d.target_inbox, {
        method: 'POST',
        headers: {
          'content-type': 'application/activity+json',
          accept: 'application/activity+json',
          'user-agent': env.FEDERATION_USER_AGENT || 'TV49-East-Federation/1.0'
        },
        body: JSON.stringify(d.activity_payload),
        redirect: 'manual'
      });
      const responseBody = (await response.text()).slice(0, 2000);
      if (response.ok || response.status === 202 || response.status === 204) {
        await patch(env, `federation_deliveries?id=eq.${encodeURIComponent(d.id)}`, {
          status: 'delivered', delivered_at: new Date().toISOString(), locked_at: null,
          last_status_code: response.status, last_response_body: responseBody, last_error: null
        });
        delivered++;
      } else if (response.status === 408 || response.status === 425 || response.status === 429 || response.status >= 500) {
        await retry(env, d, `HTTP ${response.status}: ${responseBody}`);
        retried++;
      } else {
        await patch(env, `federation_deliveries?id=eq.${encodeURIComponent(d.id)}`, {
          status: 'dead', locked_at: null, last_status_code: response.status,
          last_response_body: responseBody, last_error: `permanent HTTP ${response.status}`
        });
        dead++;
      }
    } catch (error) {
      await retry(env, d, error instanceof Error ? error.message : 'network error');
      retried++;
    }
  }
  return { claimed: (claimed || []).length, delivered, retried, dead };
}

async function retry(env: Env, d: Delivery, error: string) {
  if (d.attempt_count >= MAX_ATTEMPTS) {
    await patch(env, `federation_deliveries?id=eq.${encodeURIComponent(d.id)}`, { status:'dead', locked_at:null, last_error:error });
    return;
  }
  const seconds = BACKOFF_SECONDS[Math.min(d.attempt_count, BACKOFF_SECONDS.length-1)];
  await patch(env, `federation_deliveries?id=eq.${encodeURIComponent(d.id)}`, {
    status:'retry', locked_at:null, next_attempt_at:new Date(Date.now()+seconds*1000).toISOString(), last_error:error
  });
}

async function rpc(env: Env, fn: string, body: unknown) {
  const r = await fetch(`${env.SUPABASE_URL}/rest/v1/rpc/${fn}`, {
    method:'POST', headers:{apikey:env.SUPABASE_SERVICE_ROLE_KEY,Authorization:`Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,'content-type':'application/json'}, body:JSON.stringify(body)
  });
  if (!r.ok) throw new Error(`Supabase RPC ${r.status}: ${(await r.text()).slice(0,300)}`);
  return r.json();
}
async function patch(env: Env, path: string, body: unknown) {
  const r = await fetch(`${env.SUPABASE_URL}/rest/v1/${path}`, {
    method:'PATCH', headers:{apikey:env.SUPABASE_SERVICE_ROLE_KEY,Authorization:`Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,'content-type':'application/json',Prefer:'return=minimal'}, body:JSON.stringify(body)
  });
  if (!r.ok) throw new Error(`Supabase PATCH ${r.status}`);
}
