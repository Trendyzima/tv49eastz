package com.fadcam.tv;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Bounded, incremental IPTV catalog. Never loads the whole catalog into memory. */
public final class IptvFeedClientV2 {
    public interface Listener { void onSuccess(List<IptvReel> reels); void onError(Exception error); }

    private static final String KENYA = "https://iptv-org.github.io/iptv/countries/ke.m3u";
    private static final String EAST_AFRICA = "https://iptv-org.github.io/iptv/regions/eaf.m3u";
    private static final String AFRICA = "https://iptv-org.github.io/iptv/regions/afr.m3u";
    private static final String NEWS = "https://iptv-org.github.io/iptv/categories/news.m3u";
    private static final String SPORTS = "https://iptv-org.github.io/iptv/categories/sports.m3u";
    private static final String UK = "https://iptv-org.github.io/iptv/countries/uk.m3u";
    private static final String US = "https://iptv-org.github.io/iptv/countries/us.m3u";
    private static final String PREFS = "tv49_iptv_cache";
    private static final String CACHE = "reels";
    private static final int INITIAL_BATCH = 8;
    private static final int BATCH_SIZE = 10;
    private static final int MAX_CACHE = 60;
    private static final long MAX_PLAYLIST_CHARS = 4L * 1024L * 1024L;

    private final SharedPreferences prefs;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(22, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    private final ArrayList<String> sources = new ArrayList<>();
    private final Set<String> seenUrls = new HashSet<>();
    private int sourceIndex;
    private boolean loading;

    public IptvFeedClientV2(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sources.add(KENYA);
        sources.add(EAST_AFRICA);
        sources.add(AFRICA);
        sources.add(NEWS);
        sources.add(SPORTS);
        sources.add(UK);
        sources.add(US);
    }

    /** First homepage batch: cached entries are capped, then only one source is fetched. */
    public void loadInitial(Listener listener) {
        if (loading) return;
        seenUrls.clear();
        sourceIndex = 0;
        List<IptvReel> cached = readCache(INITIAL_BATCH);
        for (IptvReel r : cached) seenUrls.add(r.url);
        if (!cached.isEmpty()) listener.onSuccess(cached);
        fetchNextSource(listener, cached.isEmpty());
    }

    /** Lazy-load the next source only when the UI approaches the end of the current batch. */
    public void loadMore(Listener listener) {
        if (loading) return;
        fetchNextSource(listener, false);
    }

    /** Backwards-compatible entry point. */
    public void load(Listener listener) { loadInitial(listener); }

    private void fetchNextSource(Listener listener, boolean reportError) {
        if (sourceIndex >= sources.size()) {
            if (reportError) listener.onError(new IOException("No playable IPTV HLS sources returned"));
            return;
        }
        final String url = sources.get(sourceIndex++);
        loading = true;
        client.newCall(request(url)).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                loading = false;
                fetchNextSource(listener, reportError);
            }

            @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                ArrayList<IptvReel> batch = new ArrayList<>();
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) throw new IOException("HTTP " + r.code());
                    List<IptvReel> parsed = parseM3u(r.body().charStream(), sourceName(url), BATCH_SIZE);
                    for (IptvReel reel : parsed) {
                        if (seenUrls.add(reel.url)) batch.add(reel);
                    }
                    if (!batch.isEmpty()) writeCache(batch);
                } catch (Exception ignored) {
                    // A broken public source must never crash the homepage.
                } finally {
                    loading = false;
                }
                if (!batch.isEmpty()) listener.onSuccess(batch);
                else fetchNextSource(listener, reportError);
            }
        });
    }

    public void loadSource(String url, String sourceName, Listener listener) {
        if (!isHttp(url)) {
            listener.onError(new IOException("Invalid playlist URL"));
            return;
        }
        client.newCall(request(url)).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) { listener.onError(e); }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) throw new IOException("playlist HTTP " + r.code());
                    List<IptvReel> parsed = parseM3u(r.body().charStream(), sourceName, MAX_CACHE);
                    if (parsed.isEmpty()) throw new IOException("No playable HLS entries found");
                    listener.onSuccess(parsed);
                } catch (Exception e) { listener.onError(e); }
            }
        });
    }

    private Request request(String url) {
        return new Request.Builder().url(url)
                .header("Accept", "application/vnd.apple.mpegurl, text/plain, */*")
                .header("User-Agent", "TV49East/3.1 Android IPTV")
                .header("Cache-Control", "no-cache")
                .build();
    }

    private String sourceName(String url) {
        if (url.equals(KENYA)) return "Kenya";
        if (url.equals(EAST_AFRICA)) return "East Africa";
        if (url.equals(AFRICA)) return "Africa";
        if (url.equals(NEWS)) return "News";
        if (url.equals(SPORTS)) return "Sports";
        if (url.equals(UK)) return "United Kingdom";
        if (url.equals(US)) return "United States";
        return "IPTV";
    }

    private List<IptvReel> parseM3u(Reader sourceReader, String source, int limit) throws IOException {
        ArrayList<IptvReel> result = new ArrayList<>();
        if (sourceReader == null) return result;
        String title = source;
        String referrer = "";
        String userAgent = "";
        int index = 0;
        long charsRead = 0;
        try (BufferedReader reader = new BufferedReader(sourceReader, 8192)) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                charsRead += raw.length() + 1L;
                if (charsRead > MAX_PLAYLIST_CHARS) break;
                String line = raw.trim();
                if (line.startsWith("#EXTINF:")) {
                    int comma = line.indexOf(',');
                    title = comma >= 0 ? line.substring(comma + 1).trim() : source;
                    referrer = firstAttribute(line, "http-referrer", "referrer");
                    userAgent = firstAttribute(line, "http-user-agent", "user-agent");
                } else if (line.regionMatches(true, 0, "#EXTVLCOPT:http-referrer=", 0, 25)) {
                    referrer = line.substring(25).trim();
                } else if (line.regionMatches(true, 0, "#EXTVLCOPT:http-user-agent=", 0, 27)) {
                    userAgent = line.substring(27).trim();
                } else if (isPlayableHls(line)) {
                    result.add(new IptvReel(source + "-" + index, source, safe(title, "Live channel"), line,
                            "Live", referrer, userAgent, source));
                    index++;
                    if (result.size() >= limit) break;
                }
            }
        }
        return result;
    }

    private String firstAttribute(String line, String first, String second) {
        String value = attribute(line, first);
        return value.isEmpty() ? attribute(line, second) : value;
    }

    private String attribute(String line, String name) {
        String needle = name + "=\"";
        String lower = line.toLowerCase(Locale.US);
        int start = lower.indexOf(needle.toLowerCase(Locale.US));
        if (start < 0) return "";
        start += needle.length();
        int end = line.indexOf('"', start);
        return end > start ? line.substring(start, end).trim() : "";
    }

    static boolean isPlayableHls(String value) {
        if (!isHttp(value)) return false;
        String lower = value.trim().toLowerCase(Locale.US);
        if (lower.contains("youtube.com/") || lower.contains("youtu.be/") || lower.contains("twitch.tv/") ||
                lower.contains("facebook.com/") || lower.contains("tiktok.com/") || lower.contains("instagram.com/")) return false;
        return lower.contains(".m3u8") || lower.contains("m3u8?") || lower.contains("m3u8/");
    }

    private static boolean isHttp(String value) {
        if (value == null) return false;
        String v = value.trim().toLowerCase(Locale.US);
        return v.startsWith("https://") || v.startsWith("http://");
    }

    private String safe(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private synchronized void writeCache(List<IptvReel> additions) {
        try {
            List<IptvReel> existing = readCache(MAX_CACHE);
            JSONArray array = new JSONArray();
            int count = 0;
            HashSet<String> urls = new HashSet<>();
            for (IptvReel r : additions) {
                if (count >= MAX_CACHE || !urls.add(r.url)) continue;
                JSONObject o = toJson(r);
                array.put(o);
                count++;
            }
            for (IptvReel r : existing) {
                if (count >= MAX_CACHE || !urls.add(r.url)) continue;
                array.put(toJson(r));
                count++;
            }
            prefs.edit().putString(CACHE, array.toString()).apply();
        } catch (Exception ignored) { }
    }

    private JSONObject toJson(IptvReel r) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", r.id); o.put("channel", r.channel); o.put("title", r.title); o.put("url", r.url);
        o.put("quality", r.quality); o.put("referrer", r.referrer); o.put("userAgent", r.userAgent); o.put("source", r.source);
        return o;
    }

    private List<IptvReel> readCache(int limit) {
        String raw = prefs.getString(CACHE, "");
        if (raw.isEmpty()) return new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            ArrayList<IptvReel> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < array.length() && result.size() < limit; i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString("url", "").trim();
                if (!isPlayableHls(url) || !seen.add(url)) continue;
                result.add(new IptvReel(o.optString("id", "cache-" + i), o.optString("channel", "IPTV"),
                        o.optString("title", "Live channel"), url, o.optString("quality", "Live"),
                        o.optString("referrer", ""), o.optString("userAgent", ""), o.optString("source", "Cached")));
            }
            return result;
        } catch (Exception e) { return new ArrayList<>(); }
    }
}
