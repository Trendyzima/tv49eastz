-- Production federation hardening.
-- Adds durable interaction counters, outbound activity payloads, actor-cache
-- freshness, encrypted actor keys, retry leasing, and unified ranking.

alter table public.federated_actors
  add column if not exists etag text,
  add column if not exists cache_control text,
  add column if not exists last_error text,
  add column if not exists private_key_ciphertext text,
  add column if not exists private_key_iv text;

alter table public.federated_objects
  add column if not exists like_count integer not null default 0,
  add column if not exists announce_count integer not null default 0,
  add column if not exists reply_count integer not null default 0,
  add column if not exists quote_count integer not null default 0,
  add column if not exists view_count integer not null default 0,
  add column if not exists deleted_at timestamptz;

alter table public.federated_activities
  add column if not exists payload jsonb not null default '{}'::jsonb,
  add column if not exists direction text not null default 'inbound' check (direction in ('inbound','outbound')),
  add column if not exists local_user_id uuid references public.profiles(id) on delete set null;

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
  active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create unique index if not exists federated_interactions_local_unique
  on public.federated_object_interactions(object_uri, local_user_id, interaction_type)
  where local_user_id is not null;
create unique index if not exists federated_interactions_remote_unique
  on public.federated_object_interactions(object_uri, remote_actor_uri, interaction_type)
  where remote_actor_uri is not null;
create index if not exists federated_interactions_object_idx on public.federated_object_interactions(object_uri, interaction_type, active, created_at desc);
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

create or replace function public.claim_federation_deliveries(p_limit integer default 50)
returns setof public.federation_deliveries
language plpgsql security definer set search_path = public
as $$
declare row public.federation_deliveries;
begin
  for row in
    select * from public.federation_deliveries
    where status in ('pending','retry') and next_attempt_at <= now()
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
  select 'local'::text source, p.id::text id, p.author_id,
    pr.username author_username, pr.display_name author_display_name, pr.avatar_url author_avatar_url,
    p.body, p.media_url, p.media_type, p.created_at,
    coalesce(p.like_count,0)::integer like_count, coalesce(p.reply_count,0)::integer reply_count,
    coalesce(p.repost_count,0)::integer repost_count,
    (-0.12 * greatest(0, extract(epoch from (now()-p.created_at))/3600)
      + ln(1+coalesce(p.like_count,0))*0.75 + ln(1+coalesce(p.reply_count,0))*0.9
      + ln(1+coalesce(p.repost_count,0))*1.05) rank_score
  from posts p join profiles pr on pr.id=p.author_id
  where p.created_at > now()-interval '30 days'
), remote_items as (
  select 'federated'::text source, fo.uri id, null::uuid author_id,
    coalesce(fa.preferred_username, split_part(fo.actor_uri,'/',-1)) author_username,
    coalesce(fa.display_name, split_part(fo.actor_uri,'/',-1)) author_display_name,
    fa.avatar_url author_avatar_url, fo.content body,
    coalesce(fo.attachments->0->>'url', fo.url) media_url,
    case when coalesce(fo.attachments->0->>'mediaType','') like 'video/%' then 'video' else 'image' end media_type,
    coalesce(fo.published_at,fo.created_at) created_at,
    coalesce(fo.like_count,0)::integer like_count, coalesce(fo.reply_count,0)::integer reply_count,
    coalesce(fo.announce_count,0)::integer repost_count,
    (-0.12 * greatest(0, extract(epoch from (now()-coalesce(fo.published_at,fo.created_at)))/3600)
      + ln(1+coalesce(fo.like_count,0))*0.75 + ln(1+coalesce(fo.reply_count,0))*0.9
      + ln(1+coalesce(fo.announce_count,0))*1.05 + case when fo.sensitive then -2.0 else 0 end) rank_score
  from federated_objects fo left join federated_actors fa on fa.uri=fo.actor_uri
  where fo.deleted_at is null and coalesce(fo.published_at,fo.created_at) > now()-interval '30 days'
)
select * from (select * from local_items union all select * from remote_items) combined
order by rank_score desc, created_at desc
limit greatest(1,least(p_limit,100)) offset greatest(0,p_offset);
$$;

revoke all on function public.claim_federation_deliveries(integer) from public, anon, authenticated;
revoke all on function public.get_unified_ranked_feed(integer,integer) from public, anon;
grant execute on function public.get_unified_ranked_feed(integer,integer) to authenticated, service_role;
