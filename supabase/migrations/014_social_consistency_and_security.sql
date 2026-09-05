-- TV 49 East: close remaining social consistency/security gaps.
-- Follow requests need an explicit state because acceptance/decline is transactional.
alter table public.follow_requests
  add column if not exists status text not null default 'pending'
  check (status in ('pending','accepted','declined'));

create index if not exists follow_requests_target_status_idx
  on public.follow_requests(target_id, status, created_at desc);

-- Preserve a single pending request per direction while allowing historical decisions.
drop index if exists public.follow_requests_pending_unique;
create unique index if not exists follow_requests_pending_unique
  on public.follow_requests(requester_id, target_id)
  where status = 'pending';

-- The acceptance RPC is privileged only because it must create a follow row owned by
-- the requester while the target user performs the acceptance. It authenticates the
-- target explicitly and never accepts a request for another user.
create or replace function public.respond_follow_request_atomic(p_requester_id uuid, p_accept boolean)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_status text;
begin
  if v_uid is null then raise exception 'authentication required'; end if;
  select status into v_status
  from public.follow_requests
  where requester_id = p_requester_id
    and target_id = v_uid
  for update;
  if not found then raise exception 'follow request not found'; end if;
  if v_status <> 'pending' then raise exception 'follow request is not pending'; end if;

  if p_accept then
    insert into public.follows(follower_id, following_id)
    values (p_requester_id, v_uid)
    on conflict (follower_id, following_id) do nothing;
    update public.follow_requests
      set status = 'accepted'
      where requester_id = p_requester_id and target_id = v_uid;
  else
    update public.follow_requests
      set status = 'declined'
      where requester_id = p_requester_id and target_id = v_uid;
  end if;
  return true;
end;
$$;
revoke execute on function public.respond_follow_request_atomic(uuid,boolean) from public, anon;
grant execute on function public.respond_follow_request_atomic(uuid,boolean) to authenticated;

-- Correct partial-index conflict inference for mobile message retries. The predicate must
-- match the actual unique partial index, not a parameter expression.
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
  on conflict (sender_id, client_message_id) where client_message_id is not null
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

-- Privileged functions must never be callable anonymously. These are retained only for
-- authenticated application/server flows that already depend on them.
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
