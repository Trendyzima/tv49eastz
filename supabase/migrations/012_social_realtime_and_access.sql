-- TV 49 East: conversation access, follow-request acceptance, message receipts, realtime.

-- Conversation rows are visible only to members. Writes stay behind the atomic RPC.
drop policy if exists conversations_member_read on public.conversations;
create policy conversations_member_read on public.conversations
  for select to authenticated
  using (exists (select 1 from public.conversation_members cm where cm.conversation_id = conversations.id and cm.user_id = (select auth.uid())));

-- Accepting a follow request must create the follow relationship atomically.
create or replace function public.respond_follow_request_atomic(p_requester_id uuid, p_accept boolean)
returns boolean language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := auth.uid();
begin
 if v_uid is null then raise exception 'authentication required'; end if;
 if not exists (select 1 from public.follow_requests fr where fr.requester_id=p_requester_id and fr.target_id=v_uid) then raise exception 'follow request not found'; end if;
 if p_accept then
   insert into public.follows(follower_id,following_id) values(p_requester_id,v_uid) on conflict (follower_id,following_id) do nothing;
   update public.follow_requests set status='accepted' where requester_id=p_requester_id and target_id=v_uid;
 else
   update public.follow_requests set status='declined' where requester_id=p_requester_id and target_id=v_uid;
 end if;
 return true;
end; $$;
revoke execute on function public.respond_follow_request_atomic(uuid,boolean) from public,anon;
grant execute on function public.respond_follow_request_atomic(uuid,boolean) to authenticated;

-- Recipients can update only delivery/read timestamps through this guarded RPC; the base
-- messages update policy remains sender-scoped so a recipient cannot edit message content.
create or replace function public.mark_message_status(p_message_id uuid,p_status text)
returns boolean language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := auth.uid();
begin
 if v_uid is null then raise exception 'authentication required'; end if;
 if p_status not in ('delivered','read') then raise exception 'invalid message status'; end if;
 if not exists (select 1 from public.messages m join public.conversation_members cm on cm.conversation_id=m.conversation_id where m.id=p_message_id and cm.user_id=v_uid) then raise exception 'not a conversation member'; end if;
 if p_status='delivered' then
   update public.messages set delivered_at=coalesce(delivered_at,now()) where id=p_message_id;
 else
   update public.messages set delivered_at=coalesce(delivered_at,now()),read_at=now() where id=p_message_id;
 end if;
 return true;
end; $$;
revoke execute on function public.mark_message_status(uuid,text) from public,anon;
grant execute on function public.mark_message_status(uuid,text) to authenticated;

-- Keep conversation.updated_at aligned with new messages.
create or replace function public.touch_conversation_on_message()
returns trigger language plpgsql security invoker set search_path = '' as $$
begin
 update public.conversations set updated_at=coalesce(new.created_at,now()) where id=new.conversation_id;
 return new;
end; $$;
drop trigger if exists messages_touch_conversation on public.messages;
create trigger messages_touch_conversation after insert on public.messages for each row execute function public.touch_conversation_on_message();

-- Realtime publication is additive and idempotent.
do $$
declare t text;
begin
 foreach t in array array['follow_requests','message_requests','post_likes','post_reposts','post_replies','post_quotes'] loop
   if not exists (select 1 from pg_publication_tables where pubname='supabase_realtime' and schemaname='public' and tablename=t) then
     execute format('alter publication supabase_realtime add table public.%I',t);
   end if;
 end loop;
end $$;
