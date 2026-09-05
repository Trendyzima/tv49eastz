-- X-style profile/account parity for TV 49 East.
-- Social binaries remain on Cloudflare R2; this migration stores only profile metadata.

alter table public.profiles add column if not exists pinned_post_id uuid references public.posts(id) on delete set null;
alter table public.profiles add column if not exists protected_account boolean not null default false;
alter table public.profiles add column if not exists birth_date date;
alter table public.profiles add column if not exists allow_dm_requests boolean not null default true;
alter table public.profiles add column if not exists show_read_receipts boolean not null default true;
alter table public.profiles add column if not exists discoverable_by_email boolean not null default true;
alter table public.profiles add column if not exists discoverable_by_phone boolean not null default true;
alter table public.profiles add column if not exists default_post_visibility text not null default 'public';
alter table public.profiles drop constraint if exists profiles_default_post_visibility_check;
alter table public.profiles add constraint profiles_default_post_visibility_check check (default_post_visibility in ('public','followers'));

create index if not exists profiles_pinned_post_id_idx on public.profiles(pinned_post_id);
create index if not exists profiles_birth_date_idx on public.profiles(birth_date);
create index if not exists posts_author_created_at_idx on public.posts(author_id, created_at desc);
create index if not exists post_media_post_id_sort_order_idx on public.post_media(post_id, sort_order);

create or replace function public.pin_profile_post(p_post_id uuid)
returns boolean
language plpgsql
security invoker
set search_path = public
as $$
begin
  if p_post_id is null then
    update public.profiles set pinned_post_id = null where id = auth.uid();
    return true;
  end if;
  if not exists (select 1 from public.posts where id = p_post_id and author_id = auth.uid() and deleted_at is null) then
    raise exception 'post_not_owned';
  end if;
  update public.profiles set pinned_post_id = p_post_id where id = auth.uid();
  return true;
end;
$$;
grant execute on function public.pin_profile_post(uuid) to authenticated;

create or replace function public.get_public_profile(p_username text)
returns jsonb
language sql
security invoker
stable
set search_path = public
as $$
  select to_jsonb(p) || jsonb_build_object(
    'post_count', (select count(*) from public.posts x where x.author_id=p.id and x.deleted_at is null),
    'like_count', (select count(*) from public.post_likes l join public.posts x on x.id=l.post_id where x.author_id=p.id and x.deleted_at is null),
    'is_following', exists(select 1 from public.follows f where f.follower_id=auth.uid() and f.following_id=p.id)
  )
  from public.profiles p where lower(p.username)=lower(p_username) limit 1;
$$;
grant execute on function public.get_public_profile(text) to anon, authenticated;
