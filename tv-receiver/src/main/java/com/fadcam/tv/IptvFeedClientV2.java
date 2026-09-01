package com.fadcam.tv;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
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

/** Resilient IPTV catalog loader. Sources are public/authorized playlists; playback stays direct. */
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
    private static final int MAX_REELS = 240;
    private static final int PER_SOURCE = 55;

    private final SharedPreferences prefs;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(22, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    public IptvFeedClientV2(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Returns cached channels immediately, then refreshes from multiple independent playlists. */
    public void load(Listener listener) {
        List<IptvReel> cached = readCache();
        if (!cached.isEmpty()) listener.onSuccess(cached);

        ArrayList<String> sources = new ArrayList<>();
        sources.add(KENYA);
        sources.add(EAST_AFRICA);
        sources.add(AFRICA);
        sources.add(NEWS);
        sources.add(SPORTS);
        sources.add(UK);
        sources.add(US);
        loadSources(sources, listener, cached.isEmpty());
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
                    List<IptvReel> parsed = parseM3u(r.body().string(), sourceName, MAX_REELS);
                    if (parsed.isEmpty()) throw new IOException("No playable HLS entries found");
                    listener.onSuccess(parsed);
                } catch (Exception e) { listener.onError(e); }
            }
        });
    }

    private void loadSources(List<String> urls, Listener listener, boolean mustReturnError) {
        List<IptvReel> merged = Collections.synchronizedList(new ArrayList<>());
        Set<String> seen = Collections.synchronizedSet(new HashSet<>());
        final int[] remaining = {urls.size()};
        final boolean[] delivered = {false};

        for (String url : urls) {
            client.newCall(request(url)).enqueue(new Callback() {
                @Override public void onFailure(@NonNull Call call, @NonNull IOException e) { finish(); }

                @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try (Response r = response) {
                        if (!r.isSuccessful() || r.body() == null) throw new IOException("HTTP " + r.code());
                        List<IptvReel> parsed = parseM3u(r.body().string(), sourceName(url), PER_SOURCE);
                        synchronized (merged) {
                            for (IptvReel reel : parsed) {
                                if (merged.size() >= MAX_REELS) break;
                                if (seen.add(reel.url)) merged.add(reel);
                            }
                            // Do not wait for every remote source. One healthy source is enough to start playback.
                            if (!delivered[0] && merged.size() >= 8) {
                                delivered[0] = true;
                                writeCache(merged);
                                listener.onSuccess(new ArrayList<>(merged));
                            }
                        }
                    } catch (Exception ignored) {
                        // Another source can still populate the feed.
                    }
                    finish();
                }

                private void finish() {
                    synchronized (remaining) {
                        remaining[0]--;
                        if (remaining[0] == 0) {
                            synchronized (merged) {
                                if (!merged.isEmpty()) {
                                    delivered[0] = true;
                                    writeCache(merged);
                                    listener.onSuccess(new ArrayList<>(merged));
                                } else if (mustReturnError && !delivered[0]) {
                                    listener.onError(new IOException("No playable IPTV HLS sources returned"));
                                }
                            }
                        }
                    }
                }
            });
        }
    }

    private Request request(String url) {
        return new Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.apple.mpegurl, text/plain, */*")
                .header("User-Agent", "TV49East/3.0 Android IPTV")
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

    private List<IptvReel> parseM3u(String body, String source, int limit) {
        ArrayList<IptvReel> result = new ArrayList<>();
        if (body == null || body.trim().isEmpty()) return result;

        String title = source;
        String referrer = "";
        String userAgent = "";
        int index = 0;
        String[] lines = body.split("\\r?\\n");

        for (String raw : lines) {
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
                result.add(new IptvReel(
                        source + "-" + index,
                        source,
                        safe(title, "Live channel"),
                        line,
                        "Live",
                        referrer,
                        userAgent,
                        source));
                index++;
                if (result.size() >= limit) break;
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
        if (lower.contains("youtube.com/") || lower.contains("youtu.be/") ||
                lower.contains("twitch.tv/") || lower.contains("facebook.com/") ||
                lower.contains("tiktok.com/") || lower.contains("instagram.com/")) return false;
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

    private void writeCache(List<IptvReel> list) {
        try {
            JSONArray array = new JSONArray();
            int count = 0;
            for (IptvReel r : list) {
                if (count++ >= MAX_REELS) break;
                JSONObject o = new JSONObject();
                o.put("id", r.id);
                o.put("channel", r.channel);
                o.put("title", r.title);
                o.put("url", r.url);
                o.put("quality", r.quality);
                o.put("referrer", r.referrer);
                o.put("userAgent", r.userAgent);
                o.put("source", r.source);
                array.put(o);
            }
            prefs.edit().putString(CACHE, array.toString()).apply();
        } catch (Exception ignored) { }
    }

    private List<IptvReel> readCache() {
        String raw = prefs.getString(CACHE, "");
        if (raw.isEmpty()) return new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            ArrayList<IptvReel> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < array.length() && result.size() < MAX_REELS; i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString("url", "").trim();
                if (!isPlayableHls(url) || !seen.add(url)) continue;
                result.add(new IptvReel(
                        o.optString("id", "cache-" + i),
                        o.optString("channel", "IPTV"),
                        o.optString("title", "Live channel"),
                        url,
                        o.optString("quality", "Live"),
                        o.optString("referrer", ""),
                        o.optString("userAgent", ""),
                        o.optString("source", "Cached")));
            }
            return result;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
