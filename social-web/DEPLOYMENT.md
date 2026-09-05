# Testagram production deployment

This folder is the production web/social client for `testagram.site`.

## Vercel

Set the Vercel project's **Root Directory** to `social-web`, Framework Preset to **Next.js**, and deploy from the `master` branch after merging the social-platform branch.

Required environment variables:

- `NEXT_PUBLIC_SUPABASE_URL=https://aepbqfrmheihfsauzcby.supabase.co`
- `NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY=<Supabase publishable key>`
- `NEXT_PUBLIC_APP_URL=https://testagram.site`

The app intentionally contains no localhost API endpoint. Supabase is the production data/auth plane.

## Domain cutover

The existing Vercel project currently serving `testagram.site` must release the domain first. Then add `testagram.site` and `www.testagram.site` to the Vercel project that deploys `social-web`. Keep the existing DNS records Vercel provides; do not create a second competing A/CNAME record.

After propagation, verify `/`, `/settings`, `/privacy`, `/terms`, `/community-guidelines`, `/media`, `/explore`, and the Android App Links host.

## Android

The TV receiver now declares camera, microphone and Android 13+ media-read permissions as optional device capabilities. Runtime permission prompts must still be requested by the activity that actually captures media.
