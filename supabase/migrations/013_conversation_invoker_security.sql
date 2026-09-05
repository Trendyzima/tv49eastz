-- Remove SECURITY DEFINER from conversation creation by making conversation ownership explicit.
alter table public.conversations add column if not exists created_by uuid references public.profiles(id) on delete set null;
create index if not exists conversations_created_by_idx on public.conversations(created_by,updated_at desc);

drop policy if exists conversations_member_read on public.conversations;
create policy conversations_member_read on public.conversations for select to authenticated
  using (created_by=(select auth.uid()) or exists(select 1 from public.conversation_members cm where cm.conversation_id=conversations.id and cm.user_id=(select auth.uid())));

drop policy if exists conversations_owner_insert on public.conversations;
create policy conversations_owner_insert on public.conversations for insert to authenticated
  with check (created_by=(select auth.uid()));

drop policy if exists conversation_member_write on public.conversation_members;
create policy conversation_member_write on public.conversation_members for all to authenticated
  using (user_id=(select auth.uid()) or exists(select 1 from public.conversations c where c.id=conversation_members.conversation_id and c.created_by=(select auth.uid())))
  with check (user_id=(select auth.uid()) or exists(select 1 from public.conversations c where c.id=conversation_members.conversation_id and c.created_by=(select auth.uid())));

create or replace function public.create_conversation_atomic(p_member_ids uuid[])
returns uuid language plpgsql security invoker set search_path = '' as $$
declare v_uid uuid:=auth.uid(); v_conversation_id uuid; v_member_count integer;
begin
 if v_uid is null then raise exception 'authentication required'; end if;
 if p_member_ids is null or coalesce(array_length(p_member_ids,1),0)<2 then raise exception 'at least two conversation members are required'; end if;
 select count(distinct x) into v_member_count from unnest(p_member_ids||v_uid) as t(x) where x is not null;
 if v_member_count<2 then raise exception 'at least two unique members are required'; end if;
 if exists(select 1 from unnest(p_member_ids||v_uid) as t(x) left join public.profiles p on p.id=x where x is null or p.id is null) then raise exception 'conversation member does not exist'; end if;
 insert into public.conversations(created_by) values(v_uid) returning id into v_conversation_id;
 insert into public.conversation_members(conversation_id,user_id) select v_conversation_id,x from (select distinct x from unnest(p_member_ids||v_uid) as t(x)) members;
 return v_conversation_id;
end; $$;
revoke execute on function public.create_conversation_atomic(uuid[]) from public,anon;
grant execute on function public.create_conversation_atomic(uuid[]) to authenticated;
