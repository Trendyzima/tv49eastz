-- TV 49 East social completeness: requests, bookmark folders, pins, and safe view accounting.
-- Native implementation; no X code or dependency.

create table if not exists public.follow_requests (
  requester_id uuid not null references public.profiles(id) on delete cascade,
  target_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (requester_id, target_id),
  check (requester_id <> target_id)
);
create index if not exists follow_requests_target_idx on public.follow_requests(target_id, created_at desc);

create table if not exists public.message_requests (
  id uuid primary key default gen_random_uuid(),
  sender_id uuid not null references public.profiles(id) on delete cascade,
  recipient_id uuid not null references public.profiles(id) on delete cascade,
  conversation_id uuid references public.conversations(id) on delete cascade,
  status text not null default 'pending' check (status in ('pending','accepted','declined','blocked')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (sender_id <> recipient_id)
);
create unique index if not exists message_requests_pending_unique on public.message_requests(sender_id, recipient_id) where status = 'pending';
create index if not exists message_requests_recipient_idx on public.message_requests(recipient_id, created_at desc);

create table if not exists public.message_pins (
  message_id uuid primary key references public.messages(id) on delete cascade,
  pinned_by uuid not null references public.profiles(id) on delete cascade,
  pinned_at timestamptz not null default now()
);
create index if not exists message_pins_user_idx on public.message_pins(pinned_by, pinned_at desc);

create table if not exists public.bookmark_folders (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references public.profiles(id) on delete cascade,
  name text not null check (char_length(name) between 1 and 80),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(owner_id, name)
);
create table if not exists public.bookmark_folder_items (
  folder_id uuid not null references public.bookmark_folders(id) on delete cascade,
  post_id uuid not null references public.posts(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key(folder_id, post_id)
);
create index if not exists bookmark_folder_items_post_idx on public.bookmark_folder_items(post_id, created_at desc);

create table if not exists public.pinned_posts (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  post_id uuid not null references public.posts(id) on delete cascade,
  pinned_at timestamptz not null default now()
);

alter table public.posts add column if not exists quote_count integer not null default 0;
alter table public.posts add column if not exists deleted_at timestamptz;
create index if not exists posts_quote_count_idx on public.posts(quote_count desc, created_at desc);

alter table public.follow_requests enable row level security;
alter table public.message_requests enable row level security;
alter table public.message_pins enable row level security;
alter table public.bookmark_folders enable row level security;
alter table public.bookmark_folder_items enable row level security;
alter table public.pinned_posts enable row level security;

drop policy if exists follow_requests_read_own on public.follow_requests;
create policy follow_requests_read_own on public.follow_requests for select to authenticated using (requester_id = auth.uid() or target_id = auth.uid());
drop policy if exists follow_requests_write_requester on public.follow_requests;
create policy follow_requests_write_requester on public.follow_requests for insert to authenticated with check (requester_id = auth.uid());
drop policy if exists follow_requests_delete_requester on public.follow_requests;
create policy follow_requests_delete_requester on public.follow_requests for delete to authenticated using (requester_id = auth.uid());
drop policy if exists follow_requests_update_target on public.follow_requests;
create policy follow_requests_update_target on public.follow_requests for update to authenticated using (target_id = auth.uid()) with check (target_id = auth.uid());

drop policy if exists message_requests_participant_read on public.message_requests;
create policy message_requests_participant_read on public.message_requests for select to authenticated using (sender_id = auth.uid() or recipient_id = auth.uid());
drop policy if exists message_requests_sender_write on public.message_requests;
create policy message_requests_sender_write on public.message_requests for insert to authenticated with check (sender_id = auth.uid());
drop policy if exists message_requests_participant_update on public.message_requests;
create policy message_requests_participant_update on public.message_requests for update to authenticated using (sender_id = auth.uid() or recipient_id = auth.uid()) with check (sender_id = auth.uid() or recipient_id = auth.uid());

drop policy if exists message_pins_member_read on public.message_pins;
create policy message_pins_member_read on public.message_pins for select to authenticated using (exists (select 1 from public.messages m join public.conversation_members cm on cm.conversation_id = m.conversation_id where m.id = message_id and cm.user_id = auth.uid()));
drop policy if exists message_pins_member_write on public.message_pins;
create policy message_pins_member_write on public.message_pins for all to authenticated using (pinned_by = auth.uid() and exists (select 1 from public.messages m join public.conversation_members cm on cm.conversation_id = m.conversation_id where m.id = message_id and cm.user_id = auth.uid())) with check (pinned_by = auth.uid() and exists (select 1 from public.messages m join public.conversation_members cm on cm.conversation_id = m.conversation_id where m.id = message_id and cm.user_id = auth.uid()));

drop policy if exists bookmark_folders_own on public.bookmark_folders;
create policy bookmark_folders_own on public.bookmark_folders for all to authenticated using (owner_id = auth.uid()) with check (owner_id = auth.uid());
drop policy if exists bookmark_folder_items_own on public.bookmark_folder_items;
create policy bookmark_folder_items_own on public.bookmark_folder_items for all to authenticated using (exists (select 1 from public.bookmark_folders f where f.id = folder_id and f.owner_id = auth.uid())) with check (exists (select 1 from public.bookmark_folders f where f.id = folder_id and f.owner_id = auth.uid()));

drop policy if exists pinned_posts_own on public.pinned_posts;
create policy pinned_posts_own on public.pinned_posts for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

-- Atomic, authenticated post-view accounting. Repeated views update the same row instead of failing on the PK.
create or replace function public.record_post_view(p_post_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if auth.uid() is null then raise exception 'authentication required'; end if;
  if not exists (select 1 from public.posts where id = p_post_id and deleted_at is null) then raise exception 'post not found'; end if;
  insert into public.post_views(post_id, viewer_id, last_viewed_at, view_count)
  values (p_post_id, auth.uid(), now(), 1)
  on conflict (post_id, viewer_id) do update
    set last_viewed_at = excluded.last_viewed_at,
        view_count = public.post_views.view_count + 1;
end;
$$;
revoke all on function public.record_post_view(uuid) from public;
grant execute on function public.record_post_view(uuid) to authenticated;

-- Keep relationship counters correct for idempotent inserts/deletes.
create or replace function public.social_sync_post_counters()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  if tg_table_name = 'post_likes' then
    update public.posts set like_count = greatest(0, like_count + case when tg_op='INSERT' then 1 else -1 end) where id = coalesce(new.post_id, old.post_id);
  elsif tg_table_name = 'post_reposts' then
    update public.posts set repost_count = greatest(0, repost_count + case when tg_op='INSERT' then 1 else -1 end) where id = coalesce(new.post_id, old.post_id);
  elsif tg_table_name = 'post_replies' then
    update public.posts set reply_count = greatest(0, reply_count + case when tg_op='INSERT' then 1 else -1 end) where id = coalesce(new.post_id, old.post_id);
  elsif tg_table_name = 'post_quotes' then
    update public.posts set quote_count = greatest(0, quote_count + case when tg_op='INSERT' then 1 else -1 end) where id = coalesce(new.post_id, old.post_id);
  end if;
  return coalesce(new, old);
end;
$$;

drop trigger if exists post_likes_counter on public.post_likes;
create trigger post_likes_counter after insert or delete on public.post_likes for each row execute function public.social_sync_post_counters();
drop trigger if exists post_reposts_counter on public.post_reposts;
create trigger post_reposts_counter after insert or delete on public.post_reposts for each row execute function public.social_sync_post_counters();
drop trigger if exists post_replies_counter on public.post_replies;
create trigger post_replies_counter after insert or delete on public.post_replies for each row execute function public.social_sync_post_counters();
drop trigger if exists post_quotes_counter on public.post_quotes;
create trigger post_quotes_counter after insert or delete on public.post_quotes for each row execute function public.social_sync_post_counters();

-- Recalculate historical counters once, so the new invariant starts from actual rows.
update public.posts p set
  like_count = (select count(*) from public.post_likes x where x.post_id = p.id),
  repost_count = (select count(*) from public.post_reposts x where x.post_id = p.id),
  reply_count = (select count(*) from public.post_replies x where x.post_id = p.id),
  quote_count = (select count(*) from public.post_quotes x where x.post_id = p.id);

comment on function public.record_post_view(uuid) is 'Authenticated idempotent post-view accounting with per-viewer count.';
comment on table public.follow_requests is 'Private-account follow approval requests.';
comment on table public.message_requests is 'DM request workflow before a conversation is accepted.';
comment on table public.bookmark_folders is 'Private bookmark collections owned by a user.';
