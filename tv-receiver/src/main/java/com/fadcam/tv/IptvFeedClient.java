package com.fadcam.tv;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Reads public/free IPTV metadata and stream URLs without copying or relaying media.
 * iptv-org is the primary source; Free-TV is a secondary free-playlist fallback.
 */
public final class IptvFeedClient {
    public interface Listener {
        void onSuccess(List<IptvReel> reels);
        void onError(Exception error);
    }

    private static final String STREAMS = "https://iptv-org.github.io/api/streams.json";
    private static final String CHANNELS = "https://iptv-org.github.io/api/channels.json";
    private static final String BLOCKLIST = "https://iptv-org.github.io/api/blocklist.json";
    private static final String FREE_TV = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8";

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    public void load(Listener listener) {
        client.newCall(get(STREAMS)).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) { listener.onError(e); }

            @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) throw new IOException("streams HTTP " + r.code());
                    loadMetadata(parseStreams(r.body().string()), listener);
                } catch (Exception e) {
                    listener.onError(e);
                }
            }
        });
    }

    private void loadMetadata(List<IptvReel> streams, Listener listener) {
        client.newCall(get(CHANNELS)).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                loadBlocklist(streams, listener);
            }

            @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) throw new IOException("channels HTTP " + r.code());
                    Set<String> nsfw = parseNsfw(r.body().string());
                    ArrayList<IptvReel> safe = new ArrayList<>();
                    for (IptvReel reel : streams) if (!nsfw.contains(reel.channel)) safe.add(reel);
                    loadBlocklist(safe, listener);
                } catch (Exception e) {
                    loadBlocklist(streams, listener);
                }
            }
        });
    }

    private void loadBlocklist(List<IptvReel> streams, Listener listener) {
        client.newCall(get(BLOCKLIST)).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) { finish(streams, new HashSet<>(), listener); }

            @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) throw new IOException("blocklist HTTP " + r.code());
                    finish(streams, parseBlocklist(r.body().string()), listener);
                } catch (Exception e) {
                    finish(streams, new HashSet<>(), listener);
                }
            }
        });
    }

    private void finish(List<IptvReel> streams, Set<String> blocked, Listener listener) {
        ArrayList<IptvReel> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (IptvReel reel : streams) {
            if (blocked.contains(reel.channel)) continue;
            if (!seen.add(reel.url)) continue;
            result.add(reel);
            if (result.size() >= 80) break;
        }
        if (result.size() < 12) loadFreeTv(result, listener); else listener.onSuccess(result);
    }

    private void loadFreeTv(ArrayList<IptvReel> current, Listener listener) {
        client.newCall(get(FREE_TV)).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) { listener.onSuccess(current); }

            @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) throw new IOException("free-tv HTTP " + r.code());
                    current.addAll(parseM3u(r.body().string(), current.size()));
                    listener.onSuccess(current);
                } catch (Exception e) {
                    listener.onSuccess(current);
                }
            }
        });
    }

    private Request get(String url) {
        return new Request.Builder().url(url).header("Accept", "application/json, text/plain, */*").build();
    }

    private List<IptvReel> parseStreams(String body) throws Exception {
        JSONArray array = new JSONArray(body);
        ArrayList<IptvReel> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.getJSONObject(i);
            String channel = o.optString("channel", "");
            String url = o.optString("url", "").trim();
            if (channel.isEmpty() || url.isEmpty()) continue;
            if (!(url.startsWith("https://") || url.startsWith("http://"))) continue;
            String label = o.optString("label", "");
            if (label.toLowerCase().contains("nsfw")) continue;
            result.add(new IptvReel(
                    channel + "-" + i,
                    channel,
                    o.optString("title", channel),
                    url,
                    o.optString("quality", "Live"),
                    o.optString("referrer", ""),
                    o.optString("user_agent", ""),
                    "iptv-org"));
        }
        return result;
    }

    private Set<String> parseNsfw(String body) throws Exception {
        JSONArray array = new JSONArray(body);
        Set<String> result = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.getJSONObject(i);
            if (o.optBoolean("is_nsfw", false)) result.add(o.optString("id", ""));
        }
        return result;
    }

    private Set<String> parseBlocklist(String body) throws Exception {
        JSONArray array = new JSONArray(body);
        Set<String> result = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            String id = array.getJSONObject(i).optString("channel", "");
            if (!id.isEmpty()) result.add(id);
        }
        return result;
    }

    private List<IptvReel> parseM3u(String body, int offset) {
        ArrayList<IptvReel> result = new ArrayList<>();
        String[] lines = body.split("\\r?\\n");
        String title = "Free TV";
        int index = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#EXTINF:")) {
                int comma = line.lastIndexOf(',');
                title = comma >= 0 ? line.substring(comma + 1).trim() : "Free TV";
            } else if (!line.isEmpty() && !line.startsWith("#") && (line.startsWith("http://") || line.startsWith("https://"))) {
                result.add(new IptvReel("free-" + offset + "-" + index++, title, title, line, "Live", "", "", "Free-TV"));
                if (result.size() >= 20) break;
            }
        }
        return result;
    }
}
