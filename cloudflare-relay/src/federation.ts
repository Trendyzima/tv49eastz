interface Env {
  SUPABASE_URL: string;
  SUPABASE_ANON_KEY: string;
  SUPABASE_SERVICE_ROLE_KEY: string;
  FEDERATION_DOMAIN: string;
  ACTOR_BASE_URL: string;
  FEDERATION_USER_AGENT?: string;
}

const ACTIVITY_JSON = 'application/activity+json';
const AS_JSON = 'application/ld+json; profile="https://www.w3.org/ns/activitystreams"';

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    try {
      if (request.method === 'GET' && url.pathname === '/.well-known/webfinger') return webfinger(url, env);
      if (request.method === 'GET' && url.pathname === '/.well-known/nodeinfo') return nodeinfo(env);
      if (request.method === 'GET' && url.pathname === '/nodeinfo/2.1') return nodeinfoDocument(env);
      if (request.method === 'GET' && url.pathname.startsWith('/users/')) return actor(url, env);
      if (request.method === 'GET' && url.pathname.startsWith('/posts/')) return object(url, env);
      if (request.method === 'GET' && url.pathname === '/inbox') return json({ type: 'OrderedCollection', id: `${env.ACTOR_BASE_URL}/inbox`, totalItems: 0, orderedItems: [] });
      if (request.method === 'POST' && url.pathname === '/inbox') return inbox(request, env);
      if (request.method === 'GET' && url.pathname.startsWith('/users/')) return actor(url, env);
      return json({ error: 'not_found' }, 404);
    } catch (error) {
      return json({ error: 'federation_error', message: error instanceof Error ? error.message : 'unknown error' }, 500);
    }
  }
};

async function webfinger(url: URL, env: Env): Promise<Response> {
  const resource = url.searchParams.get('resource') || '';
  const match = resource.match(/^acct:([^@]+)@([^@]+)$/i);
  if (!match || match[2].toLowerCase() !== env.FEDERATION_DOMAIN.toLowerCase()) return json({ error: 'resource_not_found' }, 404);
  const username = match[1].toLowerCase();
  const profile = await dbOne(env, `/rest/v1/profiles?username=eq.${encodeURIComponent(username)}&select=username`);
  if (!profile) return json({ error: 'resource_not_found' }, 404);
  const actorUrl = `${env.ACTOR_BASE_URL.replace(/\/$/, '')}/users/${encodeURIComponent(profile.username)}`;
  return json({
    subject: `acct:${profile.username}@${env.FEDERATION_DOMAIN}`,
    aliases: [actorUrl],
    links: [
      { rel: 'self', type: ACTIVITY_JSON, href: actorUrl },
      { rel: 'http://webfinger.net/rel/profile-page', type: 'text/html', href: `https://${env.FEDERATION_DOMAIN}/@${encodeURIComponent(profile.username)}` }
    ]
  });
}

async function nodeinfo(env: Env): Promise<Response> {
  return json({ links: [{ rel: 'http://nodeinfo.diaspora.software/ns/schema/2.1', href: `${env.ACTOR_BASE_URL.replace(/\/$/, '')}/nodeinfo/2.1` }] });
}

async function nodeinfoDocument(env: Env): Promise<Response> {
  return json({
    version: '2.1',
    software: { name: 'tv49-east', version: '2.3.0' },
    protocols: ['activitypub'],
    usage: { users: { total: 0, activeMonth: 0, activeHalfyear: 0 }, localPosts: 0, localComments: 0 },
    openRegistrations: true,
    metadata: { federation: 'activitypub', webfinger: true, media: 'cloudflare-r2' }
  });
}

async function actor(url: URL, env: Env): Promise<Response> {
  const username = decodeURIComponent(url.pathname.slice('/users/'.length)).replace(/[^A-Za-z0-9_\-.]/g, '');
  if (!username) return json({ error: 'not_found' }, 404);
  const profile = await dbOne(env, `/rest/v1/profiles?username=eq.${encodeURIComponent(username)}&select=id,username,display_name,bio,avatar_url,cover_url,verified_tier`);
  if (!profile) return json({ error: 'not_found' }, 404);
  const base = env.ACTOR_BASE_URL.replace(/\/$/, '');
  const actorId = `${base}/users/${encodeURIComponent(profile.username)}`;
  const publicKey = await getActorKey(env, actorId);
  return new Response(JSON.stringify({
    '@context': ['https://www.w3.org/ns/activitystreams', 'https://w3id.org/security/v1'],
    id: actorId,
    type: 'Person',
    preferredUsername: profile.username,
    name: profile.display_name || profile.username,
    summary: profile.bio || '',
    url: `https://${env.FEDERATION_DOMAIN}/@${encodeURIComponent(profile.username)}`,
    icon: profile.avatar_url ? { type: 'Image', mediaType: 'image/jpeg', url: profile.avatar_url } : undefined,
    image: profile.cover_url ? { type: 'Image', mediaType: 'image/jpeg', url: profile.cover_url } : undefined,
    inbox: `${actorId}/inbox`,
    outbox: `${actorId}/outbox`,
    followers: `${actorId}/followers`,
    following: `${actorId}/following`,
    discoverable: true,
    publicKey
  }), { status: 200, headers: activityHeaders() });
}

async function object(url: URL, env: Env): Promise<Response> {
  const id = decodeURIComponent(url.pathname.slice('/posts/'.length));
  if (!id) return json({ error: 'not_found' }, 404);
  const post = await dbOne(env, `/rest/v1/posts?id=eq.${encodeURIComponent(id)}&select=id,body,media_url,media_type,created_at,updated_at,author_id,author:profiles!posts_author_id_fkey(username)`);
  if (!post) return json({ error: 'not_found' }, 404);
  const username = post.author?.username || 'user';
  const actorId = `${env.ACTOR_BASE_URL.replace(/\/$/, '')}/users/${encodeURIComponent(username)}`;
  const objectId = `${env.ACTOR_BASE_URL.replace(/\/$/, '')}/posts/${encodeURIComponent(post.id)}`;
  const attachments = post.media_url ? [{ type: post.media_type === 'video' ? 'Video' : 'Image', mediaType: post.media_type === 'video' ? 'video/mp4' : 'image/jpeg', url: post.media_url }] : [];
  return new Response(JSON.stringify({ '@context': 'https://www.w3.org/ns/activitystreams', id: objectId, type: 'Note', attributedTo: actorId, content: escapeHtml(post.body || ''), published: post.created_at, updated: post.updated_at || post.created_at, url: `https://${env.FEDERATION_DOMAIN}/posts/${post.id}`, to: ['https://www.w3.org/ns/activitystreams#Public'], cc: [`${actorId}/followers`], attachment: attachments }), { status: 200, headers: activityHeaders() });
}

async function inbox(request: Request, env: Env): Promise<Response> {
  const raw = await request.text();
  if (raw.length > 2_000_000) return json({ error: 'activity_too_large' }, 413);
  const signature = request.headers.get('signature');
  if (!signature) return json({ error: 'signature_required' }, 401);
  const activity = JSON.parse(raw) as Record<string, unknown>;
  const actorUri = typeof activity.actor === 'string' ? activity.actor : '';
  if (!actorUri || !/^https:\/\//i.test(actorUri)) return json({ error: 'invalid_actor' }, 400);
  const verified = await verifyLegacyHttpSignature(request, raw, actorUri, signature, env);
  if (!verified) return json({ error: 'invalid_signature' }, 401);
  const activityId = typeof activity.id === 'string' ? activity.id : `${actorUri}#${await sha256(raw)}`;
  const type = typeof activity.type === 'string' ? activity.type : 'Unknown';
  const objectValue = activity.object;
  const objectUri = typeof objectValue === 'string' ? objectValue : (objectValue && typeof objectValue === 'object' && typeof (objectValue as Record<string, unknown>).id === 'string' ? (objectValue as Record<string, unknown>).id as string : null);
  const instanceDomain = new URL(actorUri).hostname.toLowerCase();
  const instance = await dbUpsert(env, '/rest/v1/federated_instances?on_conflict=domain', { domain: instanceDomain, protocol: 'activitypub', last_seen_at: new Date().toISOString(), updated_at: new Date().toISOString() }, 'resolution=merge-duplicates');
  const instanceId = instance?.[0]?.id;
  const activityRow = await dbUpsert(env, '/rest/v1/federated_activities?on_conflict=uri', { uri: activityId, activity_type: type, actor_uri: actorUri, object_uri: objectUri, instance_id: instanceId || null, raw_activity: activity, received_at: new Date().toISOString() }, 'resolution=merge-duplicates,return=representation');
  if (objectValue && typeof objectValue === 'object') {
    const object = objectValue as Record<string, unknown>;
    if (typeof object.id === 'string' && ['Note', 'Question', 'Article', 'Image', 'Audio', 'Video', 'Page', 'Event'].includes(String(object.type || 'Note'))) {
      await dbUpsert(env, '/rest/v1/federated_objects?on_conflict=uri', { uri: object.id, object_type: String(object.type || 'Note'), actor_uri: actorUri, instance_id: instanceId || null, url: typeof object.url === 'string' ? object.url : object.id, content: typeof object.content === 'string' ? object.content : typeof object.name === 'string' ? object.name : '', summary: typeof object.summary === 'string' ? object.summary : '', published_at: typeof object.published === 'string' ? object.published : null, updated_at: typeof object.updated === 'string' ? object.updated : null, sensitive: object.sensitive === true, in_reply_to_uri: typeof object.inReplyTo === 'string' ? object.inReplyTo : null, attachments: Array.isArray(object.attachment) ? object.attachment : [], tags: Array.isArray(object.tag) ? object.tag : [], raw_object: object }, 'resolution=merge-duplicates');
    }
  }
  if (activityRow) await dbPatch(env, `/rest/v1/federated_activities?uri=eq.${encodeURIComponent(activityId)}`, { processed_at: new Date().toISOString(), processing_error: null });
  return new Response(null, { status: 202 });
}

async function getActorKey(env: Env, actorId: string) {
  const existing = await dbOne(env, `/rest/v1/federated_actors?uri=eq.${encodeURIComponent(actorId)}&select=public_key_id,public_key_pem`);
  if (existing?.public_key_id && existing?.public_key_pem) return { id: existing.public_key_id, owner: actorId, publicKeyPem: existing.public_key_pem };
  return { id: `${actorId}#main-key`, owner: actorId, publicKeyPem: '' };
}

async function verifyLegacyHttpSignature(request: Request, raw: string, actorUri: string, header: string, env: Env): Promise<boolean> {
  const params: Record<string, string> = {};
  for (const part of header.split(/,(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)/)) {
    const m = part.trim().match(/^([A-Za-z][\w-]*)="([^"]*)"$/);
    if (m) params[m[1]] = m[2];
  }
  if (!params.keyId || !params.signature) return false;
  const actor = await fetchRemoteActor(params.keyId.split('#')[0], env);
  const pem = actor?.publicKey?.publicKeyPem;
  if (!pem) return false;
  const requestedHeaders = (params.headers || 'host').split(/\s+/);
  const lines: string[] = [];
  const target = `${request.method.toLowerCase()} ${new URL(request.url).pathname}`;
  for (const name of requestedHeaders) {
    if (name === '(request-target)') lines.push(`(request-target): ${target}`);
    else if (name === 'digest') {
      const supplied = request.headers.get('digest');
      if (!supplied) return false;
      lines.push(`digest: ${supplied}`);
    } else {
      const value = request.headers.get(name);
      if (!value) return false;
      lines.push(`${name.toLowerCase()}: ${value}`);
    }
  }
  const key = await importPublicKey(pem);
  const data = new TextEncoder().encode(lines.join('\n'));
  const sig = base64ToBytes(params.signature);
  return crypto.subtle.verify({ name: 'RSASSA-PKCS1-v1_5' }, key, sig, data);
}

async function fetchRemoteActor(uri: string, env: Env): Promise<any | null> {
  if (!/^https:\/\//i.test(uri)) return null;
  const host = new URL(uri).hostname.toLowerCase();
  if (host === new URL(env.ACTOR_BASE_URL).hostname.toLowerCase()) return null;
  const response = await fetch(uri, { headers: { Accept: `${ACTIVITY_JSON}, ${AS_JSON}`, 'User-Agent': env.FEDERATION_USER_AGENT || 'TV49-East-Federation/1.0' }, redirect: 'manual' });
  if (!response.ok) return null;
  const type = response.headers.get('content-type') || '';
  if (!type.includes('activity+json') && !type.includes('ld+json')) return null;
  const body = await response.json();
  if (body?.id && new URL(body.id).hostname.toLowerCase() !== host) return null;
  return body;
}

async function importPublicKey(pem: string): Promise<CryptoKey> {
  const clean = pem.replace(/-----BEGIN PUBLIC KEY-----/g, '').replace(/-----END PUBLIC KEY-----/g, '').replace(/\s/g, '');
  return crypto.subtle.importKey('spki', base64ToBytes(clean), { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
}

function base64ToBytes(value: string): Uint8Array { const binary = atob(value.replace(/-/g, '+').replace(/_/g, '/')); const out = new Uint8Array(binary.length); for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i); return out; }
async function sha256(value: string): Promise<string> { const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value)); return Array.from(new Uint8Array(hash)).map(x => x.toString(16).padStart(2, '0')).join(''); }
function escapeHtml(value: string): string { return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }
function activityHeaders(): HeadersInit { return { 'content-type': `${ACTIVITY_JSON}; charset=utf-8`, 'cache-control': 'public, max-age=60', vary: 'Accept' }; }
function json(value: unknown, status = 200): Response { return new Response(JSON.stringify(value), { status, headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'public, max-age=30' } }); }

async function dbOne(env: Env, path: string): Promise<any | null> {
  const response = await fetch(`${env.SUPABASE_URL}${path}`, { headers: { apikey: env.SUPABASE_SERVICE_ROLE_KEY, Authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}` } });
  if (!response.ok) throw new Error(`Supabase ${response.status}`);
  const rows = await response.json<any[]>();
  return rows[0] || null;
}
async function dbUpsert(env: Env, path: string, body: unknown, prefer = 'resolution=merge-duplicates,return=representation'): Promise<any[]> {
  const response = await fetch(`${env.SUPABASE_URL}${path}`, { method: 'POST', headers: { apikey: env.SUPABASE_SERVICE_ROLE_KEY, Authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`, 'content-type': 'application/json', Prefer: prefer }, body: JSON.stringify(body) });
  if (!response.ok) throw new Error(`Supabase ${response.status}: ${(await response.text()).slice(0, 200)}`);
  const text = await response.text(); return text ? JSON.parse(text) : [];
}
async function dbPatch(env: Env, path: string, body: unknown): Promise<void> {
  const response = await fetch(`${env.SUPABASE_URL}${path}`, { method: 'PATCH', headers: { apikey: env.SUPABASE_SERVICE_ROLE_KEY, Authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`, 'content-type': 'application/json', Prefer: 'return=minimal' }, body: JSON.stringify(body) });
  if (!response.ok) throw new Error(`Supabase ${response.status}`);
}
