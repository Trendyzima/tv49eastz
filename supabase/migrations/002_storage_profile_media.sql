-- TV 49 East profile + social media storage.
-- Storage is public-read, authenticated-write, owner-delete.
-- Keep private account secrets out of Storage metadata and client code.

insert into storage.buckets (id, name, public)
values ('tv49-profile-media', 'tv49-profile-media', true)
on conflict (id) do update set public = excluded.public;

-- Object layout:
--   avatars/<auth.uid>/<filename>
--   posts/<auth.uid>/<filename>

 drop policy if exists tv49_profile_media_read on storage.objects;
create policy tv49_profile_media_read
on storage.objects for select
to public
using (bucket_id = 'tv49-profile-media');

drop policy if exists tv49_profile_media_insert on storage.objects;
create policy tv49_profile_media_insert
on storage.objects for insert
to authenticated
with check (
  bucket_id = 'tv49-profile-media'
  and (storage.foldername(name))[2] = auth.uid()::text
  and (storage.foldername(name))[1] in ('avatars','posts')
);

drop policy if exists tv49_profile_media_update on storage.objects;
create policy tv49_profile_media_update
on storage.objects for update
to authenticated
using (
  bucket_id = 'tv49-profile-media'
  and (storage.foldername(name))[2] = auth.uid()::text
)
with check (
  bucket_id = 'tv49-profile-media'
  and (storage.foldername(name))[2] = auth.uid()::text
);

drop policy if exists tv49_profile_media_delete on storage.objects;
create policy tv49_profile_media_delete
on storage.objects for delete
to authenticated
using (
  bucket_id = 'tv49-profile-media'
  and (storage.foldername(name))[2] = auth.uid()::text
);

-- Keep profile avatar references normalized to the TV 49 bucket.
create or replace function public.validate_profile_avatar_url()
returns trigger
language plpgsql
as $$
begin
  if new.avatar_url is not null
     and new.avatar_url <> ''
     and position('/storage/v1/object/public/tv49-profile-media/avatars/' in new.avatar_url) = 0 then
    raise exception 'avatar_url must point to the TV 49 profile media bucket';
  end if;
  return new;
end;
$$;

drop trigger if exists profiles_avatar_url_guard on public.profiles;
create trigger profiles_avatar_url_guard
before insert or update of avatar_url on public.profiles
for each row execute procedure public.validate_profile_avatar_url();
