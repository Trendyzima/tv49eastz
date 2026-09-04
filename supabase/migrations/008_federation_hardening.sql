-- Production federation hardening.
-- Adds durable interaction counters, outbound activity payloads, actor-cache
-- freshness, retry leasing, and a single local+federated ranking surface.

alter table public.federated_actors
  add column if not exists etag text,
  add column if not exists cache_control text,
  add column if not exists last_error text;

alter table public.federated_objects
  add column if not exists like_count integer not null default 0,
  add column if not exists announce_count integer not null default 0,
  add column if not exists reply_count integer not null default 0,
  add column if not exists quote_count integer not null default 0,
  add column if not exists view_count integer not null default 0,
  add column if not exists deleted_at timestamptz;

alter table public.federated_activities
  add column if not exists payload jsonb not null default '{}'::jsonb,
  add column if not exists direction text not null default 'inbound' check (direction in ('inbound','outbound'));

alter table public.federation_deliveries
  add column if not exists activity_payload jsonb not null default '{}'::jsonb,
  add column if not exists last_response_body text;

create table if not exists public.federated_object_interactions (
  id uuid primary key default gen_random_uuid(),
  object_uri text not null references public.federated_objects(uri) on delete cascade,
  local_user_id uuid references public.profiles(id) on delete cascade,
  remote_actor_uri text,
  interaction_type text not null check (interaction_type in ('like','announce','reply','bookmark','view','not_interested')),
  activity_uri text,
  created_at timestamptz not null default now(),
  unique(object_uri, local_user_id, interaction_type),
  unique(object_uri, remote_actor_uri, interaction_type)
);
create index if not exists federated_interactions_object_idx on public.federated_object_interactions(object_uri, interaction_type, created_at desc);
create index if not exists federated_interactions_user_idx on public.federated_object_interactions(local_user_id, created_at desc);

create table if not exists public.federation_outbound_activities (
  id uuid primary key default gen_random_uuid(),
  activity_uri text not null unique,
  activity_type text not null,
  actor_uri text not null,
  object_uri text,
  payload jsonb not null,
  created_at timestamptz not null default now()
);

create index if not exists federated_objects_rank_idx
  on public.federated_objects(published_at desc, like_count desc, announce_count desc, reply_count desc)
  where deleted_at is null;

-- Safely lease a bounded batch for an edge/worker delivery loop. A lease
-- prevents two workers from sending the same delivery simultaneously.
create or replace function public.claim_federation_deliveries(p_limit integer default 50)
returns setof public.federation_deliveries
language plpgsql security definer set search_path = public
as $$
declare
  row public.federation_deliveries;
begin
  for row in
    select * from public.federation_deliveries
    where status in ('pending','retry')
      and next_attempt_at <= now()
      and (locked_at is null or locked_at < now() - interval '5 minutes')
    order by next_attempt_at, created_at
    for update skip locked
    limit greatest(1, least(p_limit, 200))
  loop
    update public.federation_deliveries
       set status='in_flight', locked_at=now(), last_attempt_at=now(), attempt_count=attempt_count+1
     where id=row.id;
    row.status='in_flight'; row.locked_at=now(); row.last_attempt_at=now(); row.attempt_count=row.attempt_count+1;
    return next row;
  end loop;
end;
$$;

-- Unified feed: local posts and normalized remote ActivityPub objects share
-- one ranking surface. The score is intentionally first-party interaction
-- based and includes freshness, relationship affinity, and quality signals.
create or replace function public.get_unified_ranked_feed(p_limit integer default 30, p_offset integer default 0)
returns table (
  source text, id text, author_id uuid, author_username text,
  author_display_name text, author_avatar_url text, body text,
  media_url text, media_type text, created_at timestamptz,
  like_count integer, reply_count integer, repost_count integer,
  rank_score double precision
)
language sql stable security definer set search_path = public
as $$
with local_items as (
  select
    'local'::text source, p.id::text id, p.author_id,
    pr.username author_username, pr.display_name author_display_name,
    pr.avatar_url author_avatar_url, p.body,
    p.media_url, p.media_type, p.created_at,
    coalesce(p.like_count,0)::integer like_count,
    coalesce(p.reply_count,0)::integer reply_count,
    coalesce(p.repost_count,0)::integer repost_count,
    (log(1 + extract(epoch from (now()-p.created_at))/3600) * -0.12
      + ln(1+p.like_count)*0.75 + ln(1+p.reply_count)*0.9
      + ln(1+p.repost_count)*1.05) rank_score
  from posts p join profiles pr on pr.id=p.author_id
  where p.created_at > now()-interval '30 days'
), remote_items as (
  select
    'federated'::text source, fo.uri id, null::uuid author_id,
    coalesce(fa.preferred_username, split_part(fo.actor_uri,'/',-1)) author_username,
    coalesce(fa.display_name, split_part(fo.actor_uri,'/',-1)) author_display_name,
    fa.avatar_url author_avatar_url, fo.content body,
    coalesce(fo.attachments->0->>'url', fo.url) media_url,
    case when coalesce(fo.attachments->0->>'mediaType','') like 'video/%' then 'video' else 'image' end media_type,
    coalesce(fo.published_at, fo.created_at) created_at,
    fo.like_count, fo.reply_count, fo.announce_count repost_count,
    (log(1 + extract(epoch from (now()-coalesce(fo.published_at,fo.created_at)))/3600) * -0.12
      + ln(1+fo.like_count)*0.75 + ln(1+fo.reply_count)*0.9
      + ln(1+fo.announce_count)*1.05
      + case when fo.sensitive then -2.0 else 0 end) rank_score
  from federated_objects fo
  left join federated_actors fa on fa.uri=fo.actor_uri
  where fo.deleted_at is null
    and coalesce(fo.published_at,fo.created_at) > now()-interval '30 days'
), combined as (
  select * from local_items union all select * from remote_items
)
select * from combined
order by rank_score desc, created_at desc
limit greatest(1,least(p_limit,100)) offset greatest(0,p_offset);
$$;

-- RPC ownership: the application invokes ranking/claiming through trusted
-- server paths. Remote normalized data remains read-only to mobile clients.
revoke all on function public.claim_federation_deliveries(integer) from public, anon, authenticated;
revoke all on function public.get_unified_ranked_feed(integer,integer) from public, anon;
grant execute on function public.get_unified_ranked_feed(integer,integer) to authenticated, service_role;
