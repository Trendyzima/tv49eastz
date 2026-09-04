# Spotify catalog integration

The Android app calls this Supabase Edge Function for Spotify track discovery. Keep the Spotify client secret server-side.

Required Supabase Function secrets:

- `SPOTIFY_CLIENT_ID`
- `SPOTIFY_CLIENT_SECRET`

The function uses Spotify's client-credentials flow for catalog search. The app receives track metadata and the official Spotify URL. It does not download, cache, or synchronize Spotify audio into a TV 49 East Reel.

For actual Reel soundtracks, use original audio or audio for which TV 49 East/the creator has the necessary synchronization and distribution rights.
