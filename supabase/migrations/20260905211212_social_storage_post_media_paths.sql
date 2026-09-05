drop policy if exists tv49_profile_media_insert on storage.objects;
create policy tv49_profile_media_insert on storage.objects
for insert to authenticated
with check (
  bucket_id = 'tv49-profile-media'
  and (storage.foldername(name))[2] = (auth.uid())::text
  and (storage.foldername(name))[1] = any (array['avatars'::text,'posts'::text,'post-image'::text,'post-video'::text])
);

drop policy if exists tv49_profile_media_update on storage.objects;
create policy tv49_profile_media_update on storage.objects
for update to authenticated
using (
  bucket_id = 'tv49-profile-media'
  and (storage.foldername(name))[2] = (auth.uid())::text
  and (storage.foldername(name))[1] = any (array['avatars'::text,'posts'::text,'post-image'::text,'post-video'::text])
)
with check (
  bucket_id = 'tv49-profile-media'
  and (storage.foldername(name))[2] = (auth.uid())::text
  and (storage.foldername(name))[1] = any (array['avatars'::text,'posts'::text,'post-image'::text,'post-video'::text])
);

drop policy if exists tv49_profile_media_delete on storage.objects;
create policy tv49_profile_media_delete on storage.objects
for delete to authenticated
using (
  bucket_id = 'tv49-profile-media'
  and (storage.foldername(name))[2] = (auth.uid())::text
  and (storage.foldername(name))[1] = any (array['avatars'::text,'posts'::text,'post-image'::text,'post-video'::text])
);