-- TV 49 East native social foundation.
-- This schema is independent of the XClone reference repository.
-- Run with the Supabase CLI or SQL editor for the project used by the APK.

create extension if not exists pgcrypto;

create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  username text not null unique check (username ~ '^[A-Za-z0-9_]{3,32}$'),
  display_name text not null default '',
  avatar_url text,
  bio text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.posts (
  id uuid primary key default gen_random_uuid(),
  author_id uuid not null references public.profiles(id) on delete cascade,
  body text not null default '' check (char_length(body) <= 5000),
  media_url text,
  media_type text check (media_type in ('image','video') or media_type is null),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  like_count integer not null default 0,
  reply_count integer not null default 0,
  repost_count integer not null default 0,
  check (char_length(body) > 0 or media_url is not null)
);

create index if not exists posts_created_at_idx on public.posts(created_at desc);
create index if not exists posts_author_created_at_idx on public.posts(author_id, created_at desc);

create table if not exists public.post_likes (
  post_id uuid not null references public.posts(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (post_id, user_id)
);

create table if not exists public.post_replies (
  id uuid primary key default gen_random_uuid(),
  post_id uuid not null references public.posts(id) on delete cascade,
  author_id uuid not null references public.profiles(id) on delete cascade,
  body text not null check (char_length(body) between 1 and 5000),
  created_at timestamptz not null default now()
);

create index if not exists post_replies_post_created_idx on public.post_replies(post_id, created_at desc);

create table if not exists public.post_reposts (
  post_id uuid not null references public.posts(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (post_id, user_id)
);

create table if not exists public.follows (
  follower_id uuid not null references public.profiles(id) on delete cascade,
  following_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (follower_id, following_id),
  check (follower_id <> following_id)
);

create table if not exists public.notifications (
  id uuid primary key default gen_random_uuid(),
  recipient_id uuid not null references public.profiles(id) on delete cascade,
  actor_id uuid references public.profiles(id) on delete set null,
  kind text not null check (kind in ('like','reply','repost','follow','mention')),
  post_id uuid references public.posts(id) on delete cascade,
  read_at timestamptz,
  created_at timestamptz not null default now()
);

create index if not exists notifications_recipient_created_idx on public.notifications(recipient_id, created_at desc);

alter table public.profiles enable row level security;
alter table public.posts enable row level security;
alter table public.post_likes enable row level security;
alter table public.post_replies enable row level security;
alter table public.post_reposts enable row level security;
alter table public.follows enable row level security;
alter table public.notifications enable row level security;

-- Profiles and public feed are readable by signed-in clients. Keep writes owner-scoped.
drop policy if exists profiles_read on public.profiles;
create policy profiles_read on public.profiles for select to authenticated using (true);
drop policy if exists profiles_insert_own on public.profiles;
create policy profiles_insert_own on public.profiles for insert to authenticated with check (id = auth.uid());
drop policy if exists profiles_update_own on public.profiles;
create policy profiles_update_own on public.profiles for update to authenticated using (id = auth.uid()) with check (id = auth.uid());

drop policy if exists posts_read on public.posts;
create policy posts_read on public.posts for select to authenticated using (true);
drop policy if exists posts_insert_own on public.posts;
create policy posts_insert_own on public.posts for insert to authenticated with check (author_id = auth.uid());
drop policy if exists posts_update_own on public.posts;
create policy posts_update_own on public.posts for update to authenticated using (author_id = auth.uid()) with check (author_id = auth.uid());
drop policy if exists posts_delete_own on public.posts;
create policy posts_delete_own on public.posts for delete to authenticated using (author_id = auth.uid());

drop policy if exists likes_read on public.post_likes;
create policy likes_read on public.post_likes for select to authenticated using (true);
drop policy if exists likes_write_own on public.post_likes;
create policy likes_write_own on public.post_likes for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

drop policy if exists replies_read on public.post_replies;
create policy replies_read on public.post_replies for select to authenticated using (true);
drop policy if exists replies_write_own on public.post_replies;
create policy replies_write_own on public.post_replies for all to authenticated using (author_id = auth.uid()) with check (author_id = auth.uid());

drop policy if exists reposts_read on public.post_reposts;
create policy reposts_read on public.post_reposts for select to authenticated using (true);
drop policy if exists reposts_write_own on public.post_reposts;
create policy reposts_write_own on public.post_reposts for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

drop policy if exists follows_read on public.follows;
create policy follows_read on public.follows for select to authenticated using (true);
drop policy if exists follows_write_own on public.follows;
create policy follows_write_own on public.follows for all to authenticated using (follower_id = auth.uid()) with check (follower_id = auth.uid());

drop policy if exists notifications_read_own on public.notifications;
create policy notifications_read_own on public.notifications for select to authenticated using (recipient_id = auth.uid());
drop policy if exists notifications_update_own on public.notifications;
create policy notifications_update_own on public.notifications for update to authenticated using (recipient_id = auth.uid()) with check (recipient_id = auth.uid());

-- Create a profile automatically after signup when metadata contains a username.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer set search_path = public
as $$
begin
  insert into public.profiles (id, username, display_name)
  values (
    new.id,
    coalesce(nullif(new.raw_user_meta_data->>'username',''), 'user_' || substr(replace(new.id::text,'-',''),1,10)),
    coalesce(nullif(new.raw_user_meta_data->>'display_name',''), '')
  )
  on conflict (id) do nothing;
  return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
after insert on auth.users
for each row execute procedure public.handle_new_user();
