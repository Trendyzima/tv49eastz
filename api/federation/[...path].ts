export const config = { runtime: 'edge' };

const ACTIVITY_JSON = 'application/activity+json';
const LD_JSON = 'application/ld+json; profile="https://www.w3.org/ns/activitystreams"';

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
      'x-content-type-options': 'nosniff',
      'access-control-allow-origin': '*',
    },
  });
}

function origin(value: string | undefined): string {
  return (value || '').trim().replace(/\/$/, '');
}

function isActivityPub(request: Request): boolean {
  const accept = request.headers.get('accept') || '';
  return accept.includes(ACTIVITY_JSON) || accept.includes(LD_JSON) || accept.includes('application/activity+json');
}

async function forward(request: Request, base: string, path: string): Promise<Response> {
  const target = `${base}${path}${new URL(request.url).search}`;
  const headers = new Headers(request.headers);
  headers.delete('host');
  headers.set('x-tv49-edge', 'vercel');
  headers.set('x-forwarded-host', new URL(request.url).host);
  headers.set('x-forwarded-proto', 'https');

  const method = request.method.toUpperCase();
  const body = method === 'GET' || method === 'HEAD' ? undefined : request.body;
  return fetch(target, { method, headers, body, redirect: 'manual' });
}

function responseFromUpstream(upstream: Response, activityPub: boolean): Response {
  const headers = new Headers(upstream.headers);
  headers.delete('content-length');
  headers.set('x-tv49-federation-edge', 'vercel');
  headers.set('access-control-allow-origin', '*');
  headers.set('vary', 'Accept');
  if (activityPub) {
    headers.set('content-type', `${ACTIVITY_JSON}; charset=utf-8`);
    if (upstream.status >= 200 && upstream.status < 300) headers.set('cache-control', 'public, max-age=30, stale-while-revalidate=120');
  }
  return new Response(upstream.body, { status: upstream.status, statusText: upstream.statusText, headers });
}

export default async function handler(request: Request): Promise<Response> {
  const url = new URL(request.url);
  const path = url.pathname.replace(/^\/api\/federation/, '') || '/';
  const primary = origin((globalThis as any).process?.env?.FEDERATION_ORIGIN) || origin((globalThis as any).FEDERATION_ORIGIN);
  const fallback = origin((globalThis as any).process?.env?.FEDERATION_FALLBACK_ORIGIN) || origin((globalThis as any).FEDERATION_FALLBACK_ORIGIN);

  if (request.method === 'OPTIONS') {
    return new Response(null, {
      status: 204,
      headers: {
        'access-control-allow-origin': '*',
        'access-control-allow-methods': 'GET,POST,OPTIONS',
        'access-control-allow-headers': 'Accept, Content-Type, Date, Digest, Host, Signature, User-Agent',
        'access-control-max-age': '86400',
      },
    });
  }

  if (path === '/health') {
    const candidates = [primary, fallback].filter(Boolean) as string[];
    const checks: Array<{ origin: string; ok: boolean; status: number }> = [];
    for (const candidate of candidates) {
      try {
        const r = await fetch(`${candidate}/.well-known/nodeinfo`, {
          headers: { Accept: 'application/json', 'User-Agent': 'TV49-East-Vercel-Edge/1.0' },
        });
        checks.push({ origin: candidate, ok: r.ok, status: r.status });
      } catch {
        checks.push({ origin: candidate, ok: false, status: 0 });
      }
    }
    return json({ service: 'tv49-east-federation-edge', healthy: checks.some(x => x.ok), checks }, checks.some(x => x.ok) ? 200 : 503);
  }

  if (!primary && !fallback) return json({ error: 'federation_origin_not_configured' }, 503);

  const candidates = [primary, fallback].filter(Boolean) as string[];
  const activityPub = isActivityPub(request);
  let lastStatus = 502;

  for (const candidate of candidates) {
    try {
      const upstream = await forward(request, candidate, path);
      lastStatus = upstream.status;
      if (upstream.status >= 500 || upstream.status === 429) continue;
      return responseFromUpstream(upstream, activityPub);
    } catch {
      // Try the next edge origin. This keeps federation available during an origin deploy/outage.
    }
  }

  return json({ error: 'federation_origin_unavailable' }, lastStatus);
}
