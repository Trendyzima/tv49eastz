-- TV 49 East social interaction + messaging completeness layer.
-- Native implementation; no proprietary X code or dependency.
-- All exposed tables are RLS protected and actor-scoped.

-- ---------- Post relationships ----------
create table if not exists public.post_quotes (
  id uuid primary key default gen_random_uuid(),
  author_id uuid not null references public.profiles(id) on delete cascade,
  post_id uuid not null references public.posts(id) on delete cascade,
  comment text not null default '' check (char_length(comment) <= 25000),
  created_at timestamptz not null default now(),
  unique(author_id, post_id)
);
create index if not exists post_quotes_post_idx on public.post_quotes(post_id, created_at desc);
create index if not exists post_quotes_author_idx on public.post_quotes(author_id, created_at desc);

create table if not exists public.post_views (
  post_id uuid not null references public.posts(id) on delete cascade,
  viewer_id uuid not null references public.profiles(id) on delete cascade,
  last_viewed_at timestamptz not null default now(),
  view_count bigint not null default 1 check (view_count >= 1),
  primary key(post_id, viewer_id)
);
create index if not exists post_views_post_idx on public.post_views(post_id, last_viewed_at desc);

-- ---------- Lists ----------
create table if not exists public.list_followers (
  list_id uuid not null references public.lists(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key(list_id, user_id)
);
create index if not exists list_followers_user_idx on public.list_followers(user_id, created_at desc);

-- ---------- Message features ----------
alter table public.messages add column if not exists reply_to_message_id uuid references public.messages(id) on delete set null;
alter table public.messages add column if not exists shared_post_id uuid references public.posts(id) on delete set null;
alter table public.messages add column if not exists client_message_id text;
alter table public.messages add column if not exists delivered_at timestamptz;
alter table public.messages add column if not exists read_at timestamptz;
create unique index if not exists messages_sender_client_id_idx
  on public.messages(sender_id, client_message_id)
  where client_message_id is not null;
create index if not exists messages_reply_idx on public.messages(reply_to_message_id);
create index if not exists messages_shared_post_idx on public.messages(shared_post_id);

create table if not exists public.message_reactions (
  message_id uuid not null references public.messages(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  reaction text not null check (char_length(reaction) between 1 and 32),
  created_at timestamptz not null default now(),
  primary key(message_id, user_id, reaction)
);
create index if not exists message_reactions_message_idx on public.message_reactions(message_id, created_at);

create table if not exists public.message_attachments (
  id uuid primary key default gen_random_uuid(),
  message_id uuid not null references public.messages(id) on delete cascade,
  owner_id uuid not null references public.profiles(id) on delete cascade,
  media_url text not null,
  media_type text not null check (media_type in ('image','video','gif','audio','file','voice')),
  mime_type text,
  byte_size bigint not null default 0 check (byte_size between 0 and 52428800),
  duration_ms bigint,
  width integer,
  height integer,
  created_at timestamptz not null default now()
);
create index if not exists message_attachments_message_idx on public.message_attachments(message_id, created_at);

-- ---------- Account controls ----------
create table if not exists public.mutes (
  muter_id uuid not null references public.profiles(id) on delete cascade,
  muted_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key(muter_id, muted_id),
  check(muter_id <> muted_id)
);

create table if not exists public.user_blocks (
  blocker_id uuid not null references public.profiles(id) on delete cascade,
  blocked_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key(blocker_id, blocked_id),
  check(blocker_id <> blocked_id)
);

-- ---------- Drafts / editing / scheduling ----------
create table if not exists public.post_drafts (
  id uuid primary key default gen_random_uuid(),
  author_id uuid not null references public.profiles(id) on delete cascade,
  body text not null default '' check(char_length(body) <= 25000),
  metadata jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now(),
  created_at timestamptz not null default now()
);
create index if not exists post_drafts_author_idx on public.post_drafts(author_id, updated_at desc);

alter table public.posts add column if not exists edited_at timestamptz;
alter table public.posts add column if not exists quote_of_post_id uuid references public.posts(id) on delete set null;
alter table public.posts add column if not exists reply_to_post_id uuid references public.posts(id) on delete set null;
create index if not exists posts_quote_idx on public.posts(quote_of_post_id);
create index if not exists posts_reply_idx on public.posts(reply_to_post_id);

-- ---------- RLS ----------
alter table public.post_quotes enable row level security;
alter table public.post_views enable row level security;
alter table public.list_followers enable row level security;
alter table public.message_reactions enable row level security;
alter table public.message_attachments enable row level security;
alter table public.mutes enable row level security;
alter table public.user_blocks enable row level security;
alter table public.post_drafts enable row level security;

-- Public/authenticated social reads are intentionally limited to authenticated users.
create policy post_quotes_read on public.post_quotes for select to authenticated using (true);
create policy post_quotes_write on public.post_quotes for all to authenticated
  using (author_id = auth.uid()) with check (author_id = auth.uid());

create policy post_views_own on public.post_views for all to authenticated
  using (viewer_id = auth.uid()) with check (viewer_id = auth.uid());

create policy list_followers_read on public.list_followers for select to authenticated using (true);
create policy list_followers_write on public.list_followers for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

create policy message_reactions_member_read on public.message_reactions for select to authenticated
  using (exists (select 1 from public.messages m join public.conversation_members cm on cm.conversation_id=m.conversation_id where m.id=message_id and cm.user_id=auth.uid()));
create policy message_reactions_member_write on public.message_reactions for all to authenticated
  using (user_id=auth.uid() and exists (select 1 from public.messages m join public.conversation_members cm on cm.conversation_id=m.conversation_id where m.id=message_id and cm.user_id=auth.uid()))
  with check (user_id=auth.uid() and exists (select 1 from public.messages m join public.conversation_members cm on cm.conversation_id=m.conversation_id where m.id=message_id and cm.user_id=auth.uid()));

create policy message_attachments_member_read on public.message_attachments for select to authenticated
  using (exists (select 1 from public.messages m join public.conversation_members cm on cm.conversation_id=m.conversation_id where m.id=message_id and cm.user_id=auth.uid()));
create policy message_attachments_owner_write on public.message_attachments for all to authenticated
  using (owner_id=auth.uid()) with check (owner_id=auth.uid());

create policy mutes_own on public.mutes for all to authenticated
  using(muter_id=auth.uid()) with check(muter_id=auth.uid());
create policy user_blocks_own on public.user_blocks for all to authenticated
  using(blocker_id=auth.uid()) with check(blocker_id=auth.uid());

create policy post_drafts_own on public.post_drafts for all to authenticated
  using(author_id=auth.uid()) with check(author_id=auth.uid());

-- Keep the two block representations synchronized for callers using either API surface.
insert into public.user_blocks(blocker_id, blocked_id, created_at)
select blocker_id, blocked_id, created_at from public.blocks
on conflict do nothing;

comment on table public.post_quotes is 'Quote-post relationships and optional commentary.';
comment on table public.message_reactions is 'Per-user message reactions for DM/group conversations.';
comment on table public.post_drafts is 'Private composer drafts owned by a user.';
