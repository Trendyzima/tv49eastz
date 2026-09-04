-- TV 49 East reels, recommendation signals, creator monetization and music metadata.
-- Spotify is metadata/deep-link only. Spotify audio is NOT stored or synchronized with video.

create table if not exists public.reels (
  id uuid primary key default gen_random_uuid(),
  author_id uuid not null references public.profiles(id) on delete cascade,
  video_url text not null,
  caption text not null default '' check(char_length(caption) <= 4000),
  duration_ms bigint not null default 0 check(duration_ms between 1 and 600000),
  width integer,
  height integer,
  view_count bigint not null default 0,
  like_count bigint not null default 0,
  share_count bigint not null default 0,
  comment_count bigint not null default 0,
  completion_count bigint not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists reels_created_idx on public.reels(created_at desc);
create index if not exists reels_author_idx on public.reels(author_id,created_at desc);

create table if not exists public.reel_sounds (
  reel_id uuid primary key references public.reels(id) on delete cascade,
  source text not null check(source in ('original','licensed','spotify_reference')),
  title text not null default '',
  artist text not null default '',
  spotify_track_id text,
  spotify_uri text,
  spotify_url text,
  audio_asset_url text,
  created_at timestamptz not null default now(),
  check(source <> 'spotify_reference' or (spotify_track_id is not null and audio_asset_url is null))
);

create table if not exists public.reel_events (
  id uuid primary key default gen_random_uuid(),
  reel_id uuid not null references public.reels(id) on delete cascade,
  user_id uuid references public.profiles(id) on delete set null,
  event_type text not null check(event_type in ('impression','view_start','watch','complete','like','share','comment','save','follow_creator','skip','not_interested')),
  watch_ms bigint not null default 0 check(watch_ms >= 0),
  session_id uuid,
  created_at timestamptz not null default now()
);
create index if not exists reel_events_reel_idx on public.reel_events(reel_id,created_at desc);
create index if not exists reel_events_user_idx on public.reel_events(user_id,created_at desc);

create table if not exists public.content_events (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references public.profiles(id) on delete cascade,
  post_id uuid references public.posts(id) on delete cascade,
  event_type text not null check(event_type in ('impression','open','like','reply','repost','share','save','follow','hide','not_interested')),
  dwell_ms bigint not null default 0 check(dwell_ms >= 0),
  created_at timestamptz not null default now(),
  check(post_id is not null)
);
create index if not exists content_events_user_idx on public.content_events(user_id,created_at desc);

create table if not exists public.recommendation_feedback (
  user_id uuid not null references public.profiles(id) on delete cascade,
  topic text not null,
  action text not null check(action in ('boost','reduce','mute')),
  weight numeric(8,3) not null default 1,
  updated_at timestamptz not null default now(),
  primary key(user_id,topic)
);

create table if not exists public.creator_programs (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  enabled boolean not null default false,
  ads_share_bps integer not null default 0 check(ads_share_bps between 0 and 10000),
  subscriptions_enabled boolean not null default true,
  tips_enabled boolean not null default true,
  shopping_enabled boolean not null default false,
  minimum_payout_cents bigint not null default 1000 check(minimum_payout_cents >= 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.payout_requests (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  amount_cents bigint not null check(amount_cents > 0),
  currency text not null default 'USD',
  provider text,
  provider_ref text,
  status text not null default 'requested' check(status in ('requested','processing','paid','failed','cancelled')),
  created_at timestamptz not null default now(),
  processed_at timestamptz
);
create index if not exists payout_requests_user_idx on public.payout_requests(user_id,created_at desc);

-- Personalized ranking primitive. It deliberately uses TV 49 East interaction signals,
-- not Spotify content or Spotify listening data.
create or replace function public.rank_reel_score(
  p_reel_id uuid,
  p_user_id uuid,
  p_created_at timestamptz,
  p_like_count bigint,
  p_share_count bigint,
  p_comment_count bigint,
  p_completion_count bigint
) returns numeric
language sql stable security definer set search_path=public as $$
  select
    (greatest(0, 1.0 - extract(epoch from (now() - p_created_at))/604800.0) * 3.0)
    + ln(1 + greatest(0,p_like_count)) * 1.2
    + ln(1 + greatest(0,p_share_count)) * 2.2
    + ln(1 + greatest(0,p_comment_count)) * 1.4
    + ln(1 + greatest(0,p_completion_count)) * 1.8
    + coalesce((select case when exists(select 1 from public.reel_events e where e.reel_id=p_reel_id and e.user_id=p_user_id and e.event_type in ('like','complete','save')) then 5 else 0 end),0)
    + coalesce((select case when exists(select 1 from public.follows f join public.reels r on r.author_id=f.following_id where r.id=p_reel_id and f.follower_id=p_user_id) then 4 else 0 end),0);
$$;
revoke all on function public.rank_reel_score(uuid,uuid,timestamptz,bigint,bigint,bigint,bigint) from public;
grant execute on function public.rank_reel_score(uuid,uuid,timestamptz,bigint,bigint,bigint,bigint) to authenticated;

alter table public.reels enable row level security;
alter table public.reel_sounds enable row level security;
alter table public.reel_events enable row level security;
alter table public.content_events enable row level security;
alter table public.recommendation_feedback enable row level security;
alter table public.creator_programs enable row level security;
alter table public.payout_requests enable row level security;

create policy reels_read on public.reels for select to authenticated using(true);
create policy reels_write_own on public.reels for all to authenticated using(author_id=auth.uid()) with check(author_id=auth.uid());
create policy reel_sounds_read on public.reel_sounds for select to authenticated using(true);
create policy reel_sounds_write on public.reel_sounds for all to authenticated using(exists(select 1 from public.reels r where r.id=reel_id and r.author_id=auth.uid())) with check(exists(select 1 from public.reels r where r.id=reel_id and r.author_id=auth.uid()));
create policy reel_events_own on public.reel_events for insert to authenticated with check(user_id=auth.uid());
create policy reel_events_read_own on public.reel_events for select to authenticated using(user_id=auth.uid());
create policy content_events_own on public.content_events for insert to authenticated with check(user_id=auth.uid());
create policy content_events_read_own on public.content_events for select to authenticated using(user_id=auth.uid());
create policy recommendation_feedback_own on public.recommendation_feedback for all to authenticated using(user_id=auth.uid()) with check(user_id=auth.uid());
create policy creator_program_read_own on public.creator_programs for select to authenticated using(user_id=auth.uid());
create policy creator_program_write_own on public.creator_programs for all to authenticated using(user_id=auth.uid()) with check(user_id=auth.uid());
create policy payout_requests_own on public.payout_requests for select to authenticated using(user_id=auth.uid());
create policy payout_requests_insert_own on public.payout_requests for insert to authenticated with check(user_id=auth.uid());
