-- TV 49 East world-class native social expansion.
-- Feature reference only: no XClone code/dependency is used.
-- Supabase Auth/Postgres remains the identity/data control plane.
-- Media can later be moved behind Cloudflare R2/Workers without changing these social records.

-- ---------- Profiles / discovery ----------
alter table public.profiles add column if not exists cover_url text;
alter table public.profiles add column if not exists website text;
alter table public.profiles add column if not exists location text;
alter table public.profiles add column if not exists social_links jsonb not null default '{}'::jsonb;
alter table public.profiles add column if not exists verified_tier text not null default 'none' check (verified_tier in ('none','basic','premium','vip'));
alter table public.profiles add column if not exists profile_views bigint not null default 0;
alter table public.profiles add column if not exists follower_count bigint not null default 0;
alter table public.profiles add column if not exists following_count bigint not null default 0;

create table if not exists public.blocks (
  blocker_id uuid not null references public.profiles(id) on delete cascade,
  blocked_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (blocker_id, blocked_id),
  check (blocker_id <> blocked_id)
);

-- ---------- Media / multi-image posts ----------
create table if not exists public.post_media (
  id uuid primary key default gen_random_uuid(),
  post_id uuid not null references public.posts(id) on delete cascade,
  owner_id uuid not null references public.profiles(id) on delete cascade,
  media_url text not null,
  media_type text not null check (media_type in ('image','video','gif','audio','voice')),
  mime_type text,
  byte_size bigint not null default 0 check (byte_size >= 0 and byte_size <= 20971520),
  width integer,
  height integer,
  duration_ms bigint,
  sort_order smallint not null default 0 check (sort_order between 0 and 3),
  created_at timestamptz not null default now(),
  unique(post_id, sort_order)
);
create index if not exists post_media_post_idx on public.post_media(post_id, sort_order);

-- ---------- Hashtags / trends / interests ----------
create table if not exists public.hashtags (
  id uuid primary key default gen_random_uuid(),
  tag text not null unique check (tag = lower(tag)),
  post_count bigint not null default 0,
  last_used_at timestamptz not null default now()
);
create table if not exists public.post_hashtags (
  post_id uuid not null references public.posts(id) on delete cascade,
  hashtag_id uuid not null references public.hashtags(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key(post_id, hashtag_id)
);
create table if not exists public.user_interests (
  user_id uuid not null references public.profiles(id) on delete cascade,
  topic text not null,
  weight numeric(8,3) not null default 1,
  updated_at timestamptz not null default now(),
  primary key(user_id, topic)
);
create index if not exists hashtags_trending_idx on public.hashtags(last_used_at desc, post_count desc);

-- ---------- Bookmarks / lists ----------
create table if not exists public.bookmarks (
  user_id uuid not null references public.profiles(id) on delete cascade,
  post_id uuid not null references public.posts(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key(user_id, post_id)
);
create table if not exists public.lists (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references public.profiles(id) on delete cascade,
  name text not null check(char_length(name) between 1 and 80),
  description text not null default '',
  is_private boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create table if not exists public.list_members (
  list_id uuid not null references public.lists(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key(list_id,user_id)
);

-- ---------- Polls ----------
create table if not exists public.polls (
  id uuid primary key default gen_random_uuid(),
  post_id uuid not null unique references public.posts(id) on delete cascade,
  expires_at timestamptz,
  multiple_choice boolean not null default false,
  created_at timestamptz not null default now()
);
create table if not exists public.poll_options (
  id uuid primary key default gen_random_uuid(),
  poll_id uuid not null references public.polls(id) on delete cascade,
  label text not null check(char_length(label) between 1 and 160),
  vote_count bigint not null default 0,
  sort_order smallint not null default 0,
  unique(poll_id, sort_order)
);
create table if not exists public.poll_votes (
  poll_id uuid not null references public.polls(id) on delete cascade,
  option_id uuid not null references public.poll_options(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key(poll_id, option_id, user_id)
);

-- ---------- Communities ----------
create table if not exists public.communities (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references public.profiles(id) on delete cascade,
  name text not null unique,
  slug text not null unique,
  description text not null default '',
  avatar_url text,
  is_private boolean not null default false,
  member_count bigint not null default 0,
  created_at timestamptz not null default now()
);
create table if not exists public.community_members (
  community_id uuid not null references public.communities(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  role text not null default 'member' check(role in ('member','moderator','admin')),
  created_at timestamptz not null default now(),
  primary key(community_id,user_id)
);
create index if not exists communities_popular_idx on public.communities(member_count desc);

-- ---------- Notifications ----------
alter table public.notifications add column if not exists data jsonb not null default '{}'::jsonb;

-- ---------- Direct messages ----------
create table if not exists public.conversations (
  id uuid primary key default gen_random_uuid(),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create table if not exists public.conversation_members (
  conversation_id uuid not null references public.conversations(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  joined_at timestamptz not null default now(),
  last_read_at timestamptz,
  primary key(conversation_id,user_id)
);
create table if not exists public.messages (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references public.conversations(id) on delete cascade,
  sender_id uuid not null references public.profiles(id) on delete cascade,
  body text not null default '',
  media_url text,
  media_type text,
  created_at timestamptz not null default now(),
  edited_at timestamptz,
  deleted_at timestamptz,
  check(char_length(body) <= 10000)
);
create index if not exists messages_conversation_idx on public.messages(conversation_id,created_at desc);

-- ---------- Spaces / voice notes / translations ----------
create table if not exists public.audio_spaces (
  id uuid primary key default gen_random_uuid(),
  host_id uuid not null references public.profiles(id) on delete cascade,
  title text not null check(char_length(title) between 1 and 160),
  status text not null default 'scheduled' check(status in ('scheduled','live','ended')),
  listener_count bigint not null default 0,
  started_at timestamptz,
  ended_at timestamptz,
  recording_url text,
  recording_expires_at timestamptz,
  created_at timestamptz not null default now()
);
create table if not exists public.space_members (
  space_id uuid not null references public.audio_spaces(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  role text not null default 'listener' check(role in ('listener','speaker','cohost')),
  joined_at timestamptz not null default now(),
  primary key(space_id,user_id)
);
create table if not exists public.voice_notes (
  id uuid primary key default gen_random_uuid(),
  author_id uuid not null references public.profiles(id) on delete cascade,
  post_id uuid references public.posts(id) on delete cascade,
  media_url text not null,
  duration_ms bigint not null check(duration_ms between 1 and 600000),
  transcript text,
  created_at timestamptz not null default now()
);
create table if not exists public.post_translations (
  post_id uuid not null references public.posts(id) on delete cascade,
  language_code text not null,
  translated_body text not null,
  created_at timestamptz not null default now(),
  primary key(post_id,language_code)
);

-- ---------- Moderation / reports ----------
create table if not exists public.reports (
  id uuid primary key default gen_random_uuid(),
  reporter_id uuid not null references public.profiles(id) on delete cascade,
  post_id uuid references public.posts(id) on delete cascade,
  reported_user_id uuid references public.profiles(id) on delete cascade,
  reason text not null,
  details text not null default '',
  status text not null default 'open' check(status in ('open','reviewing','resolved','dismissed')),
  created_at timestamptz not null default now(),
  resolved_at timestamptz
);

-- ---------- Creator analytics / monetization ----------
create table if not exists public.post_analytics (
  post_id uuid primary key references public.posts(id) on delete cascade,
  views bigint not null default 0,
  likes bigint not null default 0,
  replies bigint not null default 0,
  reposts bigint not null default 0,
  engagement_rate numeric(10,5) not null default 0,
  updated_at timestamptz not null default now()
);
create table if not exists public.profile_analytics_daily (
  user_id uuid not null references public.profiles(id) on delete cascade,
  day date not null,
  profile_views bigint not null default 0,
  impressions bigint not null default 0,
  engagements bigint not null default 0,
  primary key(user_id,day)
);
create table if not exists public.creator_earnings (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  source text not null,
  amount_cents bigint not null default 0 check(amount_cents >= 0),
  currency text not null default 'USD',
  status text not null default 'pending' check(status in ('pending','available','paid','reversed')),
  created_at timestamptz not null default now()
);
create table if not exists public.subscriptions (
  id uuid primary key default gen_random_uuid(),
  subscriber_id uuid not null references public.profiles(id) on delete cascade,
  creator_id uuid not null references public.profiles(id) on delete cascade,
  tier text not null default 'basic',
  status text not null default 'active' check(status in ('active','paused','cancelled')),
  provider text,
  provider_ref text,
  created_at timestamptz not null default now(),
  unique(subscriber_id,creator_id)
);
create table if not exists public.tips (
  id uuid primary key default gen_random_uuid(),
  sender_id uuid not null references public.profiles(id) on delete cascade,
  recipient_id uuid not null references public.profiles(id) on delete cascade,
  amount_cents bigint not null check(amount_cents > 0),
  currency text not null default 'USD',
  provider text,
  provider_ref text,
  status text not null default 'pending' check(status in ('pending','completed','failed','refunded')),
  created_at timestamptz not null default now()
);
create table if not exists public.verification_requests (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  tier text not null check(tier in ('basic','premium','vip')),
  status text not null default 'pending' check(status in ('pending','approved','rejected')),
  created_at timestamptz not null default now(),
  reviewed_at timestamptz
);

-- ---------- Scheduling / products ----------
create table if not exists public.scheduled_posts (
  id uuid primary key default gen_random_uuid(),
  author_id uuid not null references public.profiles(id) on delete cascade,
  body text not null,
  scheduled_for timestamptz not null,
  status text not null default 'scheduled' check(status in ('scheduled','published','cancelled','failed')),
  created_at timestamptz not null default now()
);
create table if not exists public.products (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references public.profiles(id) on delete cascade,
  name text not null,
  description text not null default '',
  image_url text,
  external_url text,
  price_cents bigint,
  currency text default 'USD',
  created_at timestamptz not null default now()
);
create table if not exists public.post_products (
  post_id uuid not null references public.posts(id) on delete cascade,
  product_id uuid not null references public.products(id) on delete cascade,
  primary key(post_id,product_id)
);

-- ---------- Search ----------
create index if not exists profiles_username_search_idx on public.profiles using gin (to_tsvector('simple', coalesce(username,'') || ' ' || coalesce(display_name,'') || ' ' || coalesce(bio,'')));
create index if not exists posts_body_search_idx on public.posts using gin (to_tsvector('simple', body));

-- ---------- RLS ----------

do $$ declare t text; begin
  foreach t in array array['blocks','post_media','hashtags','post_hashtags','user_interests','bookmarks','lists','list_members','polls','poll_options','poll_votes','communities','community_members','conversations','conversation_members','messages','audio_spaces','space_members','voice_notes','post_translations','reports','post_analytics','profile_analytics_daily','creator_earnings','subscriptions','tips','verification_requests','scheduled_posts','products','post_products'] loop
    execute format('alter table public.%I enable row level security', t);
  end loop;
end $$;

-- Safe owner/member policies. Public feed remains authenticated-read; mutations are actor-scoped.
create policy blocks_read on public.blocks for select to authenticated using (blocker_id=auth.uid() or blocked_id=auth.uid());
create policy blocks_write on public.blocks for all to authenticated using (blocker_id=auth.uid()) with check (blocker_id=auth.uid());
create policy post_media_read on public.post_media for select to authenticated using (true);
create policy post_media_write on public.post_media for all to authenticated using (owner_id=auth.uid()) with check (owner_id=auth.uid());
create policy hashtags_read on public.hashtags for select to authenticated using (true);
create policy post_hashtags_read on public.post_hashtags for select to authenticated using (true);
create policy interests_own on public.user_interests for all to authenticated using(user_id=auth.uid()) with check(user_id=auth.uid());
create policy bookmarks_own on public.bookmarks for all to authenticated using(user_id=auth.uid()) with check(user_id=auth.uid());
create policy lists_read on public.lists for select to authenticated using(not is_private or owner_id=auth.uid());
create policy lists_write on public.lists for all to authenticated using(owner_id=auth.uid()) with check(owner_id=auth.uid());
create policy list_members_read on public.list_members for select to authenticated using(exists(select 1 from public.lists l where l.id=list_id and (not l.is_private or l.owner_id=auth.uid())));
create policy list_members_write on public.list_members for all to authenticated using(exists(select 1 from public.lists l where l.id=list_id and l.owner_id=auth.uid())) with check(exists(select 1 from public.lists l where l.id=list_id and l.owner_id=auth.uid()));
create policy polls_read on public.polls for select to authenticated using(true);
create policy poll_options_read on public.poll_options for select to authenticated using(true);
create policy poll_votes_own on public.poll_votes for all to authenticated using(user_id=auth.uid()) with check(user_id=auth.uid());
create policy communities_read on public.communities for select to authenticated using(not is_private or exists(select 1 from public.community_members m where m.community_id=id and m.user_id=auth.uid()));
create policy communities_owner on public.communities for all to authenticated using(owner_id=auth.uid()) with check(owner_id=auth.uid());
create policy community_members_read on public.community_members for select to authenticated using(true);
create policy community_members_own on public.community_members for all to authenticated using(user_id=auth.uid()) with check(user_id=auth.uid());
create policy conversation_member_read on public.conversation_members for select to authenticated using(user_id=auth.uid());
create policy conversation_member_write on public.conversation_members for all to authenticated using(user_id=auth.uid()) with check(user_id=auth.uid());
create policy messages_member_read on public.messages for select to authenticated using(exists(select 1 from public.conversation_members m where m.conversation_id=conversation_id and m.user_id=auth.uid()));
create policy messages_sender_write on public.messages for all to authenticated using(sender_id=auth.uid()) with check(sender_id=auth.uid());
create policy spaces_read on public.audio_spaces for select to authenticated using(true);
create policy spaces_host_write on public.audio_spaces for all to authenticated using(host_id=auth.uid()) with check(host_id=auth.uid());
create policy space_members_own on public.space_members for all to authenticated using(user_id=auth.uid()) with check(user_id=auth.uid());
create policy voice_notes_own on public.voice_notes for all to authenticated using(author_id=auth.uid()) with check(author_id=auth.uid());
create policy translations_read on public.post_translations for select to authenticated using(true);
create policy reports_own on public.reports for insert to authenticated with check(reporter_id=auth.uid());
create policy reports_read_own on public.reports for select to authenticated using(reporter_id=auth.uid());
create policy post_analytics_read on public.post_analytics for select to authenticated using(exists(select 1 from public.posts p where p.id=post_id and p.author_id=auth.uid()));
create policy profile_analytics_read on public.profile_analytics_daily for select to authenticated using(user_id=auth.uid());
create policy earnings_read on public.creator_earnings for select to authenticated using(user_id=auth.uid());
create policy subscriptions_read on public.subscriptions for select to authenticated using(subscriber_id=auth.uid() or creator_id=auth.uid());
create policy tips_read on public.tips for select to authenticated using(sender_id=auth.uid() or recipient_id=auth.uid());
create policy tips_send on public.tips for insert to authenticated with check(sender_id=auth.uid());
create policy verification_own on public.verification_requests for all to authenticated using(user_id=auth.uid()) with check(user_id=auth.uid());
create policy scheduled_own on public.scheduled_posts for all to authenticated using(author_id=auth.uid()) with check(author_id=auth.uid());
create policy products_read on public.products for select to authenticated using(true);
create policy products_own on public.products for all to authenticated using(owner_id=auth.uid()) with check(owner_id=auth.uid());
create policy post_products_read on public.post_products for select to authenticated using(true);

-- ---------- Counter + notification automation ----------
create or replace function public.bump_post_like_count() returns trigger language plpgsql security definer set search_path=public as $$
begin
  if TG_OP='INSERT' then update posts set like_count=like_count+1 where id=new.post_id;
  else update posts set like_count=greatest(0,like_count-1) where id=old.post_id; end if; return coalesce(new,old);
end $$;
drop trigger if exists post_like_counter on public.post_likes;
create trigger post_like_counter after insert or delete on public.post_likes for each row execute function public.bump_post_like_count();

create or replace function public.bump_post_reply_count() returns trigger language plpgsql security definer set search_path=public as $$
begin
  if TG_OP='INSERT' then update posts set reply_count=reply_count+1 where id=new.post_id;
  else update posts set reply_count=greatest(0,reply_count-1) where id=old.post_id; end if; return coalesce(new,old);
end $$;
drop trigger if exists post_reply_counter on public.post_replies;
create trigger post_reply_counter after insert or delete on public.post_replies for each row execute function public.bump_post_reply_count();

create or replace function public.bump_post_repost_count() returns trigger language plpgsql security definer set search_path=public as $$
begin
  if TG_OP='INSERT' then update posts set repost_count=repost_count+1 where id=new.post_id;
  else update posts set repost_count=greatest(0,repost_count-1) where id=old.post_id; end if; return coalesce(new,old);
end $$;
drop trigger if exists post_repost_counter on public.post_reposts;
create trigger post_repost_counter after insert or delete on public.post_reposts for each row execute function public.bump_post_repost_count();

create or replace function public.notify_social_action() returns trigger language plpgsql security definer set search_path=public as $$
declare recipient uuid; kind_name text; target_post uuid;
begin
  if TG_TABLE_NAME='follows' then recipient:=new.following_id; kind_name:='follow'; target_post:=null;
  elsif TG_TABLE_NAME='post_likes' then select author_id into recipient from posts where id=new.post_id; kind_name:='like'; target_post:=new.post_id;
  elsif TG_TABLE_NAME='post_replies' then select author_id into recipient from posts where id=new.post_id; kind_name:='reply'; target_post:=new.post_id;
  elsif TG_TABLE_NAME='post_reposts' then select author_id into recipient from posts where id=new.post_id; kind_name:='repost'; target_post:=new.post_id;
  end if;
  if recipient is not null and recipient <> auth.uid() then
    insert into notifications(recipient_id,actor_id,kind,post_id) values(recipient,auth.uid(),kind_name,target_post);
  end if;
  return new;
end $$;
drop trigger if exists follow_notification on public.follows;
create trigger follow_notification after insert on public.follows for each row execute function public.notify_social_action();
drop trigger if exists like_notification on public.post_likes;
create trigger like_notification after insert on public.post_likes for each row execute function public.notify_social_action();
drop trigger if exists reply_notification on public.post_replies;
create trigger reply_notification after insert on public.post_replies for each row execute function public.notify_social_action();
drop trigger if exists repost_notification on public.post_reposts;
create trigger repost_notification after insert on public.post_reposts for each row execute function public.notify_social_action();

-- Weighted trending score: recency + engagement. Queryable by the native Explore screen.
create or replace view public.trending_posts as
select p.id,p.body,p.media_url,p.media_type,p.created_at,p.like_count,p.reply_count,p.repost_count,
  ((p.like_count*1.0)+(p.reply_count*2.0)+(p.repost_count*3.0)) /
  greatest(1,extract(epoch from (now()-p.created_at))/3600.0 + 2) as trend_score
from public.posts p
order by trend_score desc;

-- Realtime is intentionally enabled only for user-facing event tables.
alter table public.notifications replica identity full;
alter table public.messages replica identity full;
alter table public.post_likes replica identity full;
alter table public.post_replies replica identity full;
alter table public.post_reposts replica identity full;
