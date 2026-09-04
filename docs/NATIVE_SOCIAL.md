# TV 49 East Native Social

The Social mode is a first-class native Android feature. The XClone repository is treated only as a feature reference; it is not copied, embedded, cloned into the APK, or added as a dependency.

## Runtime layers

```text
TV 49 East Android APK
├── Java receiver layer
│   ├── HomeActivity
│   ├── MainActivity
│   ├── FadCam handoff
│   └── Media3 / HLS playback
├── Kotlin social layer
│   ├── SocialActivity
│   ├── SocialModels
│   └── SupabaseSocialRepository
└── native dependencies
    ├── Media3 / Android media stack
    └── existing FadCam native components

Cloud services
├── Supabase
│   ├── Auth
│   ├── Postgres + RLS
│   ├── Storage (next slice)
│   └── Realtime (next slice)
└── Cloudflare / existing TV gateway
    └── protected streaming data plane
```

## Build configuration

The APK must contain only the public Supabase project URL and public anon/publishable key. Never put a Supabase `service_role` key in Android resources, source code, Gradle files, or the APK.

```bash
./gradlew :tv-receiver:assembleDebug \
  -PsupabaseUrl=https://YOUR_PROJECT.supabase.co \
  -PsupabaseAnonKey=YOUR_PUBLIC_ANON_OR_PUBLISHABLE_KEY
```

For CI, use repository/environment secrets and pass them as Gradle properties. The social client remains safe because Postgres Row Level Security is the authorization boundary.

## Current vertical slice

- Native Social entry point from the TV 49 East launcher.
- Native feed rendering with D-pad and touch focus.
- Supabase email/password sign-in and account creation.
- Supabase PostgREST feed reads.
- Authenticated text post creation.
- Profile, post, like, reply, repost, follow and notification schema with RLS.

## Next implementation slices

1. Media picker + Supabase Storage upload with image/video previews.
2. Like, reply, repost and bookmark mutations with optimistic UI and rollback.
3. Profile and follow screens.
4. Notifications and Supabase Realtime events.
5. Communities, hashtags, search and trending.
6. Direct messaging with conversation/member/message tables and realtime delivery.
7. Creator tools, polls and analytics.
8. Unified Home that combines Live TV recommendations with social content without coupling the two authorization domains.

## Streaming boundary

Social traffic does not become a path around the protected TV gateway. Live TV remains on its existing authorized HLS route; the social client never receives private FadCam LAN addresses or server credentials.
