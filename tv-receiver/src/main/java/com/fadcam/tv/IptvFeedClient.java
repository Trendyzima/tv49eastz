package com.fadcam.tv;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Fetches direct public/authorized HLS streams for the vertical TV feed. */
public final class IptvFeedClient {
    public interface Listener { void onSuccess(List<IptvReel> reels); void onError(Exception error); }

    private static final String ONLINE = "https://dearbulut.github.io/iptv/playlists/online.m3u";
    private static final String KENYA = "https://dearbulut.github.io/iptv/playlists/country/ke.m3u";
    private static final String IPTV_ORG = "https://iptv-org.github.io/api/streams.json";
    private static final int MAX_RESULTS = 420;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    public void load(Listener listener) {
        Batch batch = new Batch(listener);
        loadText(ONLINE, new TextListener() {
            @Override public void onSuccess(String body) {
                batch.add(parseM3u(body, "TV 49 East • Health Checked", 0), 320);
                batch.success();
            }
            @Override public void onError(Exception ignored) { batch.done(); }
        });
        loadText(KENYA, new TextListener() {
            @Override public void onSuccess(String body) {
                batch.add(parseM3u(body, "Kenya", 100000), 80);
                batch.success();
            }
            @Override public void onError(Exception ignored) { batch.done(); }
        });
        loadText(IPTV_ORG, new TextListener() {
            @Override public void onSuccess(String body) {
                try {
                    batch.add(parseStreamsJson(body), 180);
                    batch.success();
                } catch (Exception e) {
                    batch.done();
                }
            }
            @Override public void onError(Exception ignored) { batch.done(); }
        });
    }

    private final class Batch {
        private final Listener listener;
        private final List<IptvReel> result = new ArrayList<>();
        private final Set<String> seen = new HashSet<>();
        private final AtomicInteger remaining = new AtomicInteger(3);
        private int successfulSources;
        private boolean finished;

        Batch(Listener listener) { this.listener = listener; }

        synchronized void add(List<IptvReel> source, int limit) {
            if (finished) return;
            for (IptvReel reel : source) {
                if (result.size() >= MAX_RESULTS || limit <= 0) break;
                if (seen.add(reel.url)) {
                    result.add(reel);
                    limit--;
                }
            }
        }

        synchronized void success() {
            if (finished) return;
            successfulSources++;
            doneLocked();
        }

        synchronized void done() {
            if (finished) return;
            doneLocked();
        }

        private void doneLocked() {
            if (remaining.decrementAndGet() != 0) return;
            finished = true;
            if (result.isEmpty() || successfulSources == 0) {
                listener.onError(new IOException("No direct HLS IPTV streams were returned"));
            } else {
                listener.onSuccess(new ArrayList<>(result));
            }
        }
    }

    private interface TextListener { void onSuccess(String body); void onError(Exception error); }

    private void loadText(String url, TextListener listener) {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.apple.mpegurl, application/json, text/plain, */*")
                .header("User-Agent", "TV49East/2.3 Android")
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) { listener.onError(e); }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) throw new IOException("HTTP " + r.code());
                    listener.onSuccess(r.body().string());
                } catch (Exception e) { listener.onError(e); }
            }
        });
    }

    private List<IptvReel> parseStreamsJson(String body) throws Exception {
        JSONArray array = new JSONArray(body);
        ArrayList<IptvReel> result = new ArrayList<>();
        for (int i = 0; i < array.length() && result.size() < 180; i++) {
            JSONObject o = array.getJSONObject(i);
            String channel = o.optString("channel", "").trim();
            String title = o.optString("title", channel).trim();
            String url = o.optString("url", "").trim();
            String label = o.optString("label", "").trim();
            if (channel.isEmpty() || !isDirectHls(url) || isBlockedLabel(label)) continue;
            result.add(new IptvReel(
                    "iptv-org-" + channel + "-" + i,
                    channel,
                    title.isEmpty() ? channel : title,
                    url,
                    safe(o.optString("quality", "Live")),
                    safe(o.optString("referrer", "")),
                    safe(o.optString("user_agent", "")),
                    "iptv-org", "", "", ""
            ));
        }
        return result;
    }

    private List<IptvReel> parseM3u(String body, String source, int offset) {
        ArrayList<IptvReel> result = new ArrayList<>();
        String title = source;
        String logo = "";
        String country = "";
        String group = "";
        String referrer = "";
        String userAgent = "";
        int index = 0;
        String[] lines = body.split("\\r?\\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#EXTINF:")) {
                title = extractTitle(line, source);
                logo = attribute(line, "tvg-logo");
                country = attribute(line, "tvg-country");
                group = attribute(line, "group-title");
                referrer = "";
                userAgent = "";
            } else if (line.regionMatches(true, 0, "#EXTVLCOPT:http-referrer=", 0, 24)) {
                referrer = line.substring(24).trim();
            } else if (line.regionMatches(true, 0, "#EXTVLCOPT:http-user-agent=", 0, 26)) {
                userAgent = line.substring(26).trim();
            } else if (!line.isEmpty() && !line.startsWith("#") && isDirectHls(line)) {
                result.add(new IptvReel(source + "-" + offset + "-" + index++, title, title, line,
                        "Live", referrer, userAgent, source, logo, country, group));
                if (result.size() >= 450) break;
            }
        }
        return result;
    }

    private String extractTitle(String line, String fallback) {
        int comma = line.indexOf(',');
        if (comma < 0 || comma + 1 >= line.length()) return fallback;
        String value = line.substring(comma + 1).trim();
        return value.isEmpty() ? fallback : value;
    }

    private String attribute(String line, String name) {
        String needle = name + "=\"";
        int start = line.indexOf(needle);
        if (start < 0) return "";
        start += needle.length();
        int end = line.indexOf('"', start);
        return end > start ? line.substring(start, end).trim() : "";
    }

    private boolean isBlockedLabel(String label) {
        String l = label.toLowerCase(Locale.US);
        return l.contains("nsfw") || l.contains("geo-blocked") || l.contains("blocked") || l.contains("broken");
    }

    static boolean isDirectHls(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        String url = value.trim();
        String lower = url.toLowerCase(Locale.US);
        if (!(lower.startsWith("https://") || lower.startsWith("http://"))) return false;
        if (!lower.contains(".m3u8")) return false;
        return !(lower.contains("youtube.com/") || lower.contains("youtu.be/") ||
                lower.contains("twitch.tv/") || lower.contains("facebook.com/") ||
                lower.contains("tiktok.com/") || lower.contains("instagram.com/"));
    }

    private String safe(String value) { return value == null ? "" : value.trim(); }
}
