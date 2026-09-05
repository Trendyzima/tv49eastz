# CI / Build Lessons

## 2026-08-31 — Android zipalign certification

- `zipalign` does not expose a `version` subcommand. Invoking `zipalign version` is parsed as an invalid command and exits with status 2 after printing the usage banner.
- A valid health check must use the executable's help/usage path (for example `zipalign -h`), inspect the banner, and explicitly accept its expected usage exit status.
- Android SDK build-tools discovery and tool validation are separate concerns: first resolve an executable `zipalign` under `$ANDROID_HOME/build-tools`, then validate it, then use `zipalign -c -P 16 -v 4` against real APK outputs.
- Do not treat a successful build followed by a certification-tool invocation failure as an APK build failure; diagnose the first failing command in the certification step precisely.

## 2026-09-05 — Social media storage boundary

- TV 49 East social text, post metadata, reactions, profiles, and media URLs belong in Supabase/Postgres.
- Binary photos and videos belong in Cloudflare R2 behind the authenticated Cloudflare media Worker; the Android client must never fall back to Supabase Storage for social media uploads.
- A Supabase `media_url` column stores only the Cloudflare object URL/reference. This keeps Postgres small and makes CDN/object lifecycle independent from relational data.
- Normalize Android post upload destinations (`post-image`/`post-video`) to the Cloudflare Worker `posts` bucket prefix; profile images use `avatars` and covers use `covers`.
- A storage-policy error is not proof that authentication failed: always verify the actual object path and storage boundary before changing RLS.
