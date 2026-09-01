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
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Fast, resilient IPTV catalog loader with disk cache and playlist fallbacks. */
public final class IptvFeedClientV2 {
    public interface Listener { void onSuccess(List<IptvReel> reels); void onError(Exception error); }

    private static final String STREAMS = "https://iptv-org.github.io/api/streams.json";
    private static final String EAST_AFRICA = "https://iptv-org.github.io/iptv/regions/eaf.m3u";
    private static final String KENYA = "https://iptv-org.github.io/iptv/countries/ke.m3u";
    private static final String NEWS = "https://iptv-org.github.io/iptv/categories/news.m3u";
    private static final String SPORTS = "https://iptv-org.github.io/iptv/categories/sports.m3u";
    private static final String PREFS = "tv49_iptv_cache";
    private static final String CACHE = "reels";
    private static final int MAX_REELS = 240;

    private final SharedPreferences prefs;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    public IptvFeedClientV2(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void load(Listener listener) {
        List<IptvReel> cached = readCache();
        if (!cached.isEmpty()) listener.onSuccess(cached);

        ArrayList<String> sources = new ArrayList<>();
        sources.add(STREAMS);
        sources.add(EAST_AFRICA);
        sources.add(KENYA);
        sources.add(NEWS);
        sources.add(SPORTS);
        loadSources(sources, listener, cached.isEmpty());
    }

    public void loadSource(String url, String sourceName, Listener listener) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            listener.onError(new IOException("Invalid playlist URL"));
            return;
        }
        client.newCall(request(url)).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) { listener.onError(e); }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) throw new IOException("playlist HTTP " + r.code());
                    List<IptvReel> parsed = parseM3u(r.body().string(), sourceName, 0, MAX_REELS);
                    if (parsed.isEmpty()) throw new IOException("No playable entries found");
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

        for (int i = 0; i < urls.size(); i++) {
            final String url = urls.get(i);
            client.newCall(request(url)).enqueue(new Callback() {
                @Override public void onFailure(@NonNull Call call, @NonNull IOException e) { finish(); }
                @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try (Response r = response) {
                        if (!r.isSuccessful() || r.body() == null) throw new IOException("HTTP " + r.code());
                        String body = r.body().string();
                        List<IptvReel> parsed = url.equals(STREAMS) ? parseStreams(body) : parseM3u(body, sourceName(url), 0, 70);
                        synchronized (merged) {
                            for (IptvReel reel : parsed) {
                                if (merged.size() >= MAX_REELS) break;
                                if (seen.add(reel.url)) merged.add(reel);
                            }
                            if (!delivered[0] && merged.size() >= 12) {
                                delivered[0] = true;
                                writeCache(merged);
                                listener.onSuccess(new ArrayList<>(merged));
                            }
                        }
                    } catch (Exception ignored) { }
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
                                    listener.onError(new IOException("No IPTV sources returned playable streams"));
                                }
                            }
                        }
                    }
                }
            });
        }
    }

    private Request request(String url) {
        return new Request.Builder().url(url)
                .header("Accept", "application/json, text/plain, application/vnd.apple.mpegurl, */*")
                .header("Cache-Control", "no-cache")
                .build();
    }

    private String sourceName(String url) {
        if (url.equals(EAST_AFRICA)) return "East Africa";
        if (url.equals(KENYA)) return "Kenya";
        if (url.equals(NEWS)) return "News";
        if (url.equals(SPORTS)) return "Sports";
        return "IPTV";
    }

    private List<IptvReel> parseStreams(String body) throws Exception {
        JSONArray array = new JSONArray(body);
        ArrayList<IptvReel> result = new ArrayList<>();
        for (int i = 0; i < array.length() && result.size() < MAX_REELS; i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) continue;
            String channel = o.optString("channel", "").trim();
            String url = o.optString("url", "").trim();
            if (channel.isEmpty() || !isHttp(url)) continue;
            String label = o.optString("label", "").toLowerCase();
            if (label.contains("nsfw") || label.contains("blocked") || label.contains("timeout") || label.contains("error")) continue;
            result.add(new IptvReel(
                    channel + "-" + i,
                    channel,
                    safe(o.optString("title", ""), channel),
                    url,
                    safe(o.optString("quality", "Live"), "Live"),
                    o.optString("referrer", ""),
                    o.optString("user_agent", ""),
                    "iptv-org"));
        }
        return result;
    }

    private List<IptvReel> parseM3u(String body, String source, int offset, int limit) {
        ArrayList<IptvReel> result = new ArrayList<>();
        if (body == null) return result;
        String title = source;
        String referrer = "";
        String userAgent = "";
        int index = 0;
        String[] lines = body.split("\\r?\\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#EXTINF:")) {
                int comma = line.lastIndexOf(',');
                title = comma >= 0 ? line.substring(comma + 1).trim() : source;
                referrer = attribute(line, "http-referrer");
                if (referrer.isEmpty()) referrer = attribute(line, "referrer");
                userAgent = attribute(line, "http-user-agent");
                if (userAgent.isEmpty()) userAgent = attribute(line, "user-agent");
            } else if (isHttp(line)) {
                result.add(new IptvReel(source + "-" + (offset + index), source, safe(title, source), line,
                        "Live", referrer, userAgent, source));
                index++;
                if (result.size() >= limit) break;
            }
        }
        return result;
    }

    private String attribute(String line, String name) {
        String needle = name + "=\"";
        int start = line.toLowerCase().indexOf(needle.toLowerCase());
        if (start < 0) return "";
        start += needle.length();
        int end = line.indexOf('"', start);
        return end > start ? line.substring(start, end).trim() : "";
    }

    private boolean isHttp(String value) { return value.startsWith("http://") || value.startsWith("https://"); }
    private String safe(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value.trim(); }

    private void writeCache(List<IptvReel> list) {
        try {
            JSONArray array = new JSONArray();
            int count = 0;
            for (IptvReel r : list) {
                if (count++ >= MAX_REELS) break;
                JSONObject o = new JSONObject();
                o.put("id", r.id); o.put("channel", r.channel); o.put("title", r.title); o.put("url", r.url);
                o.put("quality", r.quality); o.put("referrer", r.referrer); o.put("userAgent", r.userAgent); o.put("source", r.source);
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
            for (int i = 0; i < array.length() && result.size() < MAX_REELS; i++) {
                JSONObject o = array.optJSONObject(i); if (o == null) continue;
                String url = o.optString("url", ""); if (!isHttp(url)) continue;
                result.add(new IptvReel(o.optString("id", "cache-" + i), o.optString("channel", "IPTV"),
                        o.optString("title", "Live channel"), url, o.optString("quality", "Live"),
                        o.optString("referrer", ""), o.optString("userAgent", ""), o.optString("source", "Cached")));
            }
            return result;
        } catch (Exception e) { return new ArrayList<>(); }
    }
}
