-- Unified local + ActivityPub feed and first-party ranking signals.
-- Ranking is X-style (candidate generation -> filtering -> scoring -> diversity -> pagination),
-- not a copy of any proprietary implementation.

alter table public.federated_activities add column if not exists direction text not null default 'inbound' check(direction in ('inbound','outbound'));
alter table public.federated_activities add column if not exists local_user_id uuid references public.profiles(id) on delete set null;
alter table public.federated_actors add column if not exists private_key_ciphertext text;
alter table public.federated_actors add column if not exists private_key_iv text;

create table if not exists public.federated_object_reactions (
  id uuid primary key default gen_random_uuid(),
  object_uri text not null references public.federated_objects(uri) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  reaction_type text not null check(reaction_type in ('like','repost','save')),
  activity_uri text unique,
  active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(object_uri,user_id,reaction_type)
);
create index if not exists federated_object_reactions_object_idx on public.federated_object_reactions(object_uri,reaction_type,active);
create index if not exists federated_object_reactions_user_idx on public.federated_object_reactions(user_id,created_at desc);

create table if not exists public.federated_follow_requests (
  id uuid primary key default gen_random_uuid(),
  local_user_id uuid not null references public.profiles(id) on delete cascade,
  remote_actor_uri text not null references public.federated_actors(uri) on delete cascade,
  activity_uri text unique,
  state text not null default 'pending' check(state in ('pending','active','accepted','rejected','removed')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(local_user_id,remote_actor_uri)
);
create index if not exists federated_follow_requests_user_idx on public.federated_follow_requests(local_user_id,state);

create table if not exists public.federated_feed_events (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  object_uri text not null references public.federated_objects(uri) on delete cascade,
  event_type text not null check(event_type in ('impression','open','like','reply','repost','share','save','follow','hide','not_interested','dwell')),
  dwell_ms bigint not null default 0 check(dwell_ms>=0),
  created_at timestamptz not null default now()
);
create index if not exists federated_feed_events_user_idx on public.federated_feed_events(user_id,created_at desc);
create index if not exists federated_feed_events_object_idx on public.federated_feed_events(object_uri,created_at desc);

alter table public.federated_object_reactions enable row level security;
alter table public.federated_follow_requests enable row level security;
alter table public.federated_feed_events enable row level security;
drop policy if exists federated_object_reactions_own on public.federated_object_reactions;
create policy federated_object_reactions_own on public.federated_object_reactions for all to authenticated using(user_id=auth.uid()) with check(user_id=auth.uid());
drop policy if exists federated_follow_requests_own on public.federated_follow_requests;
create policy federated_follow_requests_own on public.federated_follow_requests for all to authenticated using(local_user_id=auth.uid()) with check(local_user_id=auth.uid());
drop policy if exists federated_feed_events_own on public.federated_feed_events;
create policy federated_feed_events_own on public.federated_feed_events for insert to authenticated with check(user_id=auth.uid());

create or replace function public.record_federated_feed_event(p_object_uri text,p_event_type text,p_dwell_ms bigint default 0)
returns uuid language plpgsql security definer set search_path=public as $$
declare v_id uuid;
begin
  if auth.uid() is null then raise exception 'unauthorized'; end if;
  if not exists(select 1 from public.federated_objects where uri=p_object_uri) then raise exception 'object_not_found'; end if;
  insert into public.federated_feed_events(user_id,object_uri,event_type,dwell_ms) values(auth.uid(),p_object_uri,p_event_type,greatest(coalesce(p_dwell_ms,0),0)) returning id into v_id;
  return v_id;
end; $$;
revoke all on function public.record_federated_feed_event(text,text,bigint) from public;
grant execute on function public.record_federated_feed_event(text,text,bigint) to authenticated;

create or replace function public.get_unified_feed(p_limit integer default 30,p_offset integer default 0,p_mode text default 'for_you')
returns table(source text,object_key text,author_id uuid,author_uri text,author_username text,author_display_name text,author_avatar_url text,body text,media_url text,media_type text,created_at timestamptz,like_count bigint,reply_count bigint,repost_count bigint,score numeric)
language sql stable security definer set search_path=public as $$
with params as (select least(greatest(coalesce(p_limit,30),1),50)::int lim,greatest(coalesce(p_offset,0),0)::int off,coalesce(nullif(p_mode,''),'for_you') mode),
local_base as (
 select 'local'::text source,('local:'||p.id::text) object_key,p.author_id,null::text author_uri,pf.username author_username,pf.display_name author_display_name,pf.avatar_url author_avatar_url,p.body,p.media_url,p.media_type,p.created_at,p.like_count::bigint like_count,p.reply_count::bigint reply_count,p.repost_count::bigint repost_count,
  (greatest(0,1.0-extract(epoch from(now()-p.created_at))/172800.0)*3.0)+ln(1+p.like_count)*1.15+ln(1+p.reply_count)*1.45+ln(1+p.repost_count)*1.9
  +case when exists(select 1 from public.follows f where f.follower_id=auth.uid() and f.following_id=p.author_id) then 5 else 0 end
  +case when exists(select 1 from public.content_events e where e.user_id=auth.uid() and e.post_id=p.id and e.event_type in('open','like','reply','repost','save')) then 4 else 0 end
  -case when exists(select 1 from public.content_events e where e.user_id=auth.uid() and e.post_id=p.id and e.event_type in('hide','not_interested')) then 15 else 0 end
  +case when p.created_at>now()-interval '15 minutes' then 2.5 else 0 end score
 from public.posts p join public.profiles pf on pf.id=p.author_id
),
remote_base as (
 select 'federated'::text source,('remote:'||fo.uri) object_key,null::uuid author_id,fo.actor_uri,
   coalesce(fa.preferred_username,split_part(split_part(fo.actor_uri,'/',4),'@',1),'remote') author_username,
   coalesce(fa.display_name,fa.preferred_username,'Federated user') author_display_name,fa.avatar_url author_avatar_url,
   fo.content body,coalesce(fo.attachments->0->>'url',fo.url) media_url,
   case when fo.attachments->0->>'type' in('Video','video') then 'video' when fo.attachments->0->>'type' in('Image','image') then 'image' else null end media_type,
   coalesce(fo.published_at,fo.created_at) created_at,
   (select count(*) from public.federated_object_reactions r where r.object_uri=fo.uri and r.reaction_type='like' and r.active)::bigint + (select count(*) from public.federated_activities a where a.object_uri=fo.uri and a.activity_type='Like') like_count,
   (select count(*) from public.federated_activities a where a.object_uri=fo.uri and a.activity_type in('Create','Reply'))::bigint reply_count,
   (select count(*) from public.federated_object_reactions r where r.object_uri=fo.uri and r.reaction_type='repost' and r.active)::bigint + (select count(*) from public.federated_activities a where a.object_uri=fo.uri and a.activity_type='Announce') repost_count,
   (greatest(0,1.0-extract(epoch from(now()-coalesce(fo.published_at,fo.created_at)))/259200.0)*2.8)
   +ln(1+(select count(*) from public.federated_activities a where a.object_uri=fo.uri and a.activity_type='Like'))*1.1
   +ln(1+(select count(*) from public.federated_activities a where a.object_uri=fo.uri and a.activity_type='Announce'))*1.8
   +ln(1+(select count(*) from public.federated_activities a where a.object_uri=fo.uri and a.activity_type in('Create','Reply')))*1.25
   +case when exists(select 1 from public.federated_follow_requests fr where fr.local_user_id=auth.uid() and fr.remote_actor_uri=fo.actor_uri and fr.state in('active','accepted')) then 5 else 0 end
   +case when exists(select 1 from public.federated_feed_events e where e.user_id=auth.uid() and e.object_uri=fo.uri and e.event_type in('open','like','reply','repost','save')) then 4 else 0 end
   -case when exists(select 1 from public.federated_feed_events e where e.user_id=auth.uid() and e.object_uri=fo.uri and e.event_type in('hide','not_interested')) then 15 else 0 end
   +case when coalesce(fo.published_at,fo.created_at)>now()-interval '30 minutes' then 2 else 0 end score
 from public.federated_objects fo left join public.federated_actors fa on fa.uri=fo.actor_uri
 where not fo.sensitive and fo.object_type in('Note','Article','Image','Video','Page','Question','Event')
),
candidates as (
 select * from local_base where (select mode from params)<>'following' or exists(select 1 from public.follows f where f.follower_id=auth.uid() and f.following_id=local_base.author_id)
 union all
 select * from remote_base where (select mode from params)<>'following' or exists(select 1 from public.federated_follow_requests fr where fr.local_user_id=auth.uid() and fr.remote_actor_uri=remote_base.author_uri and fr.state in('active','accepted'))
),
filtered as (
 select c.*,row_number() over(partition by coalesce(c.author_id::text,c.author_uri) order by c.score desc,c.created_at desc) author_rank,
        row_number() over(partition by coalesce(split_part(c.author_uri,'/',3),'local') order by c.score desc,c.created_at desc) domain_rank
 from candidates c
),
diverse as (select * from filtered where author_rank<=3 and domain_rank<=6),
explore as (select d.*,d.score+(random()*0.35) exploration_score from diverse d)
select source,object_key,author_id,author_uri,author_username,author_display_name,author_avatar_url,body,media_url,media_type,created_at,like_count,reply_count,repost_count,exploration_score score
from explore order by score desc,created_at desc limit (select lim from params) offset (select off from params); $$;
revoke all on function public.get_unified_feed(integer,integer,text) from public;
grant execute on function public.get_unified_feed(integer,integer,text) to authenticated;

create or replace function public.set_federated_reaction(p_object_uri text,p_reaction_type text,p_active boolean default true)
returns jsonb language plpgsql security definer set search_path=public as $$
declare v_id uuid;
begin
 if auth.uid() is null then raise exception 'unauthorized'; end if;
 if not exists(select 1 from public.federated_objects where uri=p_object_uri) then raise exception 'object_not_found'; end if;
 insert into public.federated_object_reactions(object_uri,user_id,reaction_type,active,updated_at) values(p_object_uri,auth.uid(),p_reaction_type,p_active,now())
 on conflict(object_uri,user_id,reaction_type) do update set active=excluded.active,updated_at=now() returning id into v_id;
 return jsonb_build_object('id',v_id,'object_uri',p_object_uri,'reaction_type',p_reaction_type,'active',p_active);
end; $$;
revoke all on function public.set_federated_reaction(text,text,boolean) from public;
grant execute on function public.set_federated_reaction(text,text,boolean) to authenticated;

create or replace function public.set_federated_follow(p_remote_actor_uri text,p_active boolean default true)
returns jsonb language plpgsql security definer set search_path=public as $$
declare v_id uuid;
begin
 if auth.uid() is null then raise exception 'unauthorized'; end if;
 if not exists(select 1 from public.federated_actors where uri=p_remote_actor_uri) then raise exception 'actor_not_found'; end if;
 insert into public.federated_follow_requests(local_user_id,remote_actor_uri,state,updated_at) values(auth.uid(),p_remote_actor_uri,case when p_active then 'pending' else 'removed' end,now())
 on conflict(local_user_id,remote_actor_uri) do update set state=case when p_active then 'pending' else 'removed' end,updated_at=now() returning id into v_id;
 return jsonb_build_object('id',v_id,'remote_actor_uri',p_remote_actor_uri,'active',p_active);
end; $$;
revoke all on function public.set_federated_follow(text,boolean) from public;
grant execute on function public.set_federated_follow(text,boolean) to authenticated;
