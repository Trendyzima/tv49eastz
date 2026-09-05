-- TV 49 East: transactional social wiring and RLS hardening.
-- Keeps the Android client on the public Data API while moving multi-row mutations into guarded RPCs.

-- The previous messages SELECT policy accidentally compared the same column to itself,
-- which could expose messages whenever the caller belonged to any conversation.
drop policy if exists messages_member_read on public.messages;
create policy messages_member_read on public.messages
  for select to authenticated
  using (
    exists (
      select 1
      from public.conversation_members cm
      where cm.conversation_id = messages.conversation_id
        and cm.user_id = (select auth.uid())
    )
  );

-- Explicit idempotency constraint for retried mobile sends. Existing deployments already
-- have the equivalent sender/client index; this named constraint/index is kept compatible.
create unique index if not exists messages_sender_client_id_unique
  on public.messages(sender_id, client_message_id)
  where client_message_id is not null;

-- Atomic conversation creation. SECURITY DEFINER is required because the caller may add
-- other members while conversation_members RLS intentionally allows a user to write only
-- their own membership row. The function performs its own authentication and membership checks.
create or replace function public.create_conversation_atomic(p_member_ids uuid[])
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_conversation_id uuid;
  v_member_count integer;
begin
  if v_uid is null then raise exception 'authentication required'; end if;
  if p_member_ids is null or coalesce(array_length(p_member_ids, 1), 0) < 2 then
    raise exception 'at least two conversation members are required';
  end if;

  select count(distinct x) into v_member_count
  from unnest(p_member_ids || v_uid) as t(x)
  where x is not null;
  if v_member_count < 2 then raise exception 'at least two unique members are required'; end if;

  if exists (
    select 1 from unnest(p_member_ids || v_uid) as t(x)
    left join public.profiles p on p.id = x
    where x is null or p.id is null
  ) then
    raise exception 'conversation member does not exist';
  end if;

  insert into public.conversations default values returning id into v_conversation_id;
  insert into public.conversation_members(conversation_id, user_id)
    select v_conversation_id, x
    from (select distinct x from unnest(p_member_ids || v_uid) as t(x)) members;

  return v_conversation_id;
end;
$$;
revoke execute on function public.create_conversation_atomic(uuid[]) from public, anon;
grant execute on function public.create_conversation_atomic(uuid[]) to authenticated;

-- Atomic/idempotent message creation. A retried client_message_id returns the original row
-- instead of producing a duplicate message.
create or replace function public.send_message_idempotent(
  p_conversation_id uuid,
  p_body text,
  p_reply_to_message_id uuid default null,
  p_shared_post_id uuid default null,
  p_client_message_id text default null
)
returns uuid
language plpgsql
security invoker
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_id uuid;
begin
  if v_uid is null then raise exception 'authentication required'; end if;
  if not exists (
    select 1 from public.conversation_members cm
    where cm.conversation_id = p_conversation_id and cm.user_id = v_uid
  ) then raise exception 'not a conversation member'; end if;
  if coalesce(length(trim(p_body)), 0) = 0 and p_shared_post_id is null then
    raise exception 'message cannot be empty';
  end if;
  if length(coalesce(p_body, '')) > 10000 then raise exception 'message is too long'; end if;

  if p_client_message_id is not null then
    select id into v_id from public.messages
      where sender_id = v_uid and client_message_id = p_client_message_id
      limit 1;
    if v_id is not null then return v_id; end if;
  end if;

  insert into public.messages(
    conversation_id, sender_id, body, reply_to_message_id, shared_post_id, client_message_id
  ) values (
    p_conversation_id, v_uid, coalesce(p_body, ''), p_reply_to_message_id, p_shared_post_id, p_client_message_id
  )
  on conflict (sender_id, client_message_id) where p_client_message_id is not null
  do nothing
  returning id into v_id;

  if v_id is null and p_client_message_id is not null then
    select id into v_id from public.messages
      where sender_id = v_uid and client_message_id = p_client_message_id
      limit 1;
  end if;

  if v_id is null then raise exception 'message was not created'; end if;
  return v_id;
end;
$$;
revoke execute on function public.send_message_idempotent(uuid,text,uuid,uuid,text) from public, anon;
grant execute on function public.send_message_idempotent(uuid,text,uuid,uuid,text) to authenticated;

-- Real list timeline: members -> posts -> authors, with pagination in one request.
create or replace function public.get_list_timeline(p_list_id uuid, p_limit integer default 30, p_offset integer default 0)
returns jsonb
language sql
security invoker
set search_path = ''
as $$
  select coalesce(jsonb_agg(to_jsonb(q) order by q.created_at desc), '[]'::jsonb)
  from (
    select p.id, p.body, p.media_url, p.media_type, p.created_at,
           p.like_count, p.reply_count, p.repost_count, p.quote_count,
           jsonb_build_object(
             'id', pr.id,
             'username', pr.username,
             'display_name', pr.display_name,
             'avatar_url', pr.avatar_url,
             'bio', pr.bio
           ) as author
    from public.posts p
    join public.list_members lm on lm.user_id = p.author_id and lm.list_id = p_list_id
    join public.profiles pr on pr.id = p.author_id
    where p.deleted_at is null
    order by p.created_at desc
    limit greatest(1, least(coalesce(p_limit, 30), 100))
    offset greatest(0, coalesce(p_offset, 0))
  ) q;
$$;
revoke execute on function public.get_list_timeline(uuid,integer,integer) from public, anon;
grant execute on function public.get_list_timeline(uuid,integer,integer) to authenticated;

-- Conversation list with the newest message for each conversation. Membership is checked
-- by the invoker's RLS and explicit caller filter.
create or replace function public.get_conversations_with_latest(p_limit integer default 50)
returns jsonb
language sql
security invoker
set search_path = ''
as $$
  select coalesce(jsonb_agg(to_jsonb(q) order by q.updated_at desc), '[]'::jsonb)
  from (
    select c.id, c.created_at, c.updated_at,
      (
        select jsonb_build_object(
          'id', m.id, 'conversation_id', m.conversation_id, 'sender_id', m.sender_id,
          'body', m.body, 'media_url', m.media_url, 'media_type', m.media_type,
          'created_at', m.created_at, 'edited_at', m.edited_at, 'deleted_at', m.deleted_at,
          'reply_to_message_id', m.reply_to_message_id, 'shared_post_id', m.shared_post_id,
          'delivered_at', m.delivered_at, 'read_at', m.read_at
        )
        from public.messages m
        where m.conversation_id = c.id
        order by m.created_at desc
        limit 1
      ) as latest_message
    from public.conversations c
    join public.conversation_members cm on cm.conversation_id = c.id
    where cm.user_id = (select auth.uid())
    order by c.updated_at desc
    limit greatest(1, least(coalesce(p_limit, 50), 100))
  ) q;
$$;
revoke execute on function public.get_conversations_with_latest(integer) from public, anon;
grant execute on function public.get_conversations_with_latest(integer) to authenticated;

-- Keep the public trending view subject to the underlying posts RLS policy.
alter view public.trending_posts set (security_invoker = true);

-- New social objects are intentionally authenticated-only through the Data API.
grant select, insert, update, delete on public.follow_requests to authenticated;
grant select, insert, update, delete on public.message_requests to authenticated;
grant select, insert, update, delete on public.message_pins to authenticated;
grant select, insert, update, delete on public.bookmark_folders to authenticated;
grant select, insert, update, delete on public.bookmark_folder_items to authenticated;
grant select, insert, update, delete on public.pinned_posts to authenticated;

-- Remove anonymous execution from privileged existing functions. Authenticated execution is
-- retained where the existing Android/server feature surface depends on it; each such
-- function must still enforce auth/RLS internally.
revoke execute on function public.bump_post_like_count() from anon, public;
revoke execute on function public.bump_post_reply_count() from anon, public;
revoke execute on function public.bump_post_repost_count() from anon, public;
revoke execute on function public.claim_federation_deliveries(integer) from anon, public;
revoke execute on function public.ensure_wallet(uuid) from anon, public;
revoke execute on function public.finalize_paypal_topup(text,text) from anon, public;
revoke execute on function public.get_personalized_reels(integer,integer) from anon, public;
revoke execute on function public.get_unified_feed(integer,integer,text) from anon, public;
revoke execute on function public.get_unified_ranked_feed(integer,integer) from anon, public;
revoke execute on function public.notify_social_action() from anon, public;
revoke execute on function public.rank_reel_score(uuid,uuid,timestamptz,bigint,bigint,bigint,bigint) from anon, public;
revoke execute on function public.record_federated_feed_event(text,text,bigint) from anon, public;
revoke execute on function public.record_post_view(uuid) from anon, public;
revoke execute on function public.set_federated_follow(text,boolean) from anon, public;
revoke execute on function public.set_federated_reaction(text,text,boolean) from anon, public;
revoke execute on function public.social_sync_post_counters() from anon, public;
