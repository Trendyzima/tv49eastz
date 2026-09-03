package com.fadcam.tv;

import android.net.Uri;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Loads the TV catalog, with LAN discovery as the zero-configuration FadCam path. */
public final class CatalogClient {
    public interface Listener {
        void onSuccess(List<ChannelStore.Channel> channels);
        void onError(Exception error);
    }

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();
    private final String baseUrl;

    public CatalogClient(String baseUrl) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
    }

    public void load(Listener listener) {
        if (baseUrl.isEmpty()) {
            loadLan(listener);
            return;
        }

        Request request = new Request.Builder()
                .url(baseUrl + "/v1/catalog")
                .header("Accept", "application/json")
                .header("User-Agent", "TV49East-FadCamReceiver/2")
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                // A configured relay must not hide an available local FadCam server.
                loadLanAfterRemoteFailure(listener, e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) {
                        loadLanAfterRemoteFailure(listener, new IOException("catalog HTTP " + r.code()));
                        return;
                    }
                    List<ChannelStore.Channel> channels = parse(r.body().string());
                    if (channels.isEmpty()) {
                        loadLanAfterRemoteFailure(listener, new IOException("FadCam catalog contains no playable channels"));
                    } else {
                        listener.onSuccess(channels);
                    }
                } catch (Exception e) {
                    loadLanAfterRemoteFailure(listener, e);
                }
            }
        });
    }

    private void loadLanAfterRemoteFailure(Listener listener, Exception remoteError) {
        FadCamLanDiscovery.discover(new FadCamLanDiscovery.Listener() {
            @Override public void onSearching() { }

            @Override public void onFound(@NonNull String discoveredBase, @NonNull String playlistUrl) {
                ArrayList<ChannelStore.Channel> result = new ArrayList<>();
                result.add(new ChannelStore.Channel(
                        "fadcam-local",
                        "FadCam Live",
                        "FadCam creator",
                        playlistUrl,
                        true));
                listener.onSuccess(result);
            }

            @Override public void onNotFound(@NonNull String reason) {
                listener.onError(remoteError != null ? remoteError : new IOException(reason));
            }
        });
    }

    private void loadLan(Listener listener) {
        FadCamLanDiscovery.discover(new FadCamLanDiscovery.Listener() {
            @Override public void onSearching() { }

            @Override public void onFound(@NonNull String discoveredBase, @NonNull String playlistUrl) {
                ArrayList<ChannelStore.Channel> result = new ArrayList<>();
                result.add(new ChannelStore.Channel(
                        "fadcam-local",
                        "FadCam Live",
                        "FadCam creator",
                        playlistUrl,
                        true));
                listener.onSuccess(result);
            }

            @Override public void onNotFound(@NonNull String reason) {
                listener.onError(new IOException(reason));
            }
        });
    }

    private List<ChannelStore.Channel> parse(String body) throws Exception {
        JSONArray channels = new JSONObject(body).optJSONArray("channels");
        ArrayList<ChannelStore.Channel> result = new ArrayList<>();
        if (channels == null) return result;
        for (int i = 0; i < channels.length(); i++) {
            JSONObject o = channels.getJSONObject(i);
            String id = o.optString("id", "");
            String name = o.optString("name", "FadCam Channel");
            String source = o.optString("source", "");
            String stream = o.optString("stream", "");
            boolean relay = o.optBoolean("relay", false);
            if (!"fadcam".equalsIgnoreCase(source)) continue;
            if (id.isEmpty() || stream.isEmpty() || !relay) continue;
            if (!stream.startsWith("/v1/relay?id=")) continue;
            result.add(new ChannelStore.Channel(
                    id,
                    name,
                    "FadCam creator",
                    baseUrl + stream,
                    false));
        }
        return result;
    }

    private static String normalizeBaseUrl(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        try {
            Uri uri = Uri.parse(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return "";
        } catch (Exception e) {
            return "";
        }
        return value;
    }
}
