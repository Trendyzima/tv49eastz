package com.fadcam.streaming;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Bridges FadCam's loopback HLS source to the Cloudflare Durable Object relay. */
public final class CloudflareRelayProducer {
    public static final String DEFAULT_RELAY_URL = "https://tv49eastz-relay.nahashonnyaga794.workers.dev";
    private static final String TAG = "CloudflareRelayProducer";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int CHUNK_SIZE = 32 * 1024;
    private static final int MAX_PATH_LENGTH = 512;
    private static final long MIN_RECONNECT_MS = 1_000L;
    private static final long MAX_RECONNECT_MS = 30_000L;

    private final Context context;
    private final CloudAuthManager auth;
    private final OkHttpClient client;
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    private volatile WebSocket socket;
    private volatile String sessionId;
    private volatile String producerTicket;
    private volatile String streamId;
    private volatile String playlistUrl;
    private volatile int localPort = -1;
    private volatile long reconnectDelayMs = MIN_RECONNECT_MS;

    public CloudflareRelayProducer(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.auth = CloudAuthManager.getInstance(this.context);
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public void start(@NonNull String streamId, @Nullable String channelId) {
        if (!running.compareAndSet(false, true)) return;
        this.streamId = streamId.trim();
        if (this.streamId.isEmpty()) {
            running.set(false);
            return;
        }
        String channel = channelId == null || channelId.trim().isEmpty() ? this.streamId : channelId.trim();
        openSession(channel);
    }

    public void stop() {
        running.set(false);
        WebSocket old = socket;
        socket = null;
        if (old != null) old.close(1000, "producer_stopped");
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        sessionId = null;
        producerTicket = null;
        playlistUrl = null;
    }

    @Nullable public String getPlaylistUrl() { return playlistUrl; }
    public boolean isRunning() { return running.get(); }

    private void openSession(String channelId) {
        final String jwt = auth.getJwtToken();
        if (jwt == null || jwt.isEmpty()) {
            FLog.w(TAG, "Cloud relay requires a linked FadCam account");
            scheduleReconnect(channelId);
            return;
        }
        io.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(DEFAULT_RELAY_URL + "/v1/device/session");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setDoOutput(true);
                connection.setRequestProperty("Authorization", "Bearer " + jwt);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Accept", "application/json");
                JSONObject body = new JSONObject();
                body.put("channel_id", channelId);
                body.put("stream_id", streamId);
                body.put("device_id", auth.getDeviceId());
                connection.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));

                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new IOException("relay session HTTP " + code);
                InputStream input = new BufferedInputStream(connection.getInputStream());
                byte[] bytes = readAll(input, 128 * 1024);
                input.close();
                JSONObject response = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                String returnedStream = response.optString("stream_id", "");
                String ticket = response.optString("producer_ticket", "");
                String playlist = response.optString("playlist_url", "");
                if (!streamId.equals(returnedStream) || ticket.isEmpty() || playlist.isEmpty()) {
                    throw new IOException("relay returned an incomplete producer session");
                }
                sessionId = response.optString("session", "");
                producerTicket = ticket;
                playlistUrl = playlist;
                reconnectDelayMs = MIN_RECONNECT_MS;
                connectTunnel();
            } catch (Exception e) {
                FLog.e(TAG, "Cloudflare producer session creation failed", e);
                scheduleReconnect(channelId);
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void connectTunnel() {
        if (!running.get() || producerTicket == null || streamId == null) return;
        if (!connecting.compareAndSet(false, true)) return;

        String wsBase = DEFAULT_RELAY_URL.replaceFirst("^https://", "wss://").replaceFirst("^http://", "ws://");
        String tunnelUrl = wsBase + "/tunnel?stream=" + urlEncode(streamId) + "&ticket=" + urlEncode(producerTicket);
        Request request = new Request.Builder().url(tunnelUrl).build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                connecting.set(false);
                reconnectDelayMs = MIN_RECONNECT_MS;
                webSocket.send("{\"type\":\"hello\",\"protocol\":2}");
                FLog.i(TAG, "Cloudflare producer tunnel connected for " + streamId);
            }

            @Override public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                handleTextMessage(webSocket, text);
            }

            @Override public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                connecting.set(false);
                if (socket == webSocket) socket = null;
                FLog.w(TAG, "Cloudflare producer tunnel failed: " + t.getMessage());
                scheduleReconnect(streamId);
            }

            @Override public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                connecting.set(false);
                if (socket == webSocket) socket = null;
                if (running.get()) scheduleReconnect(streamId);
            }
        });
    }

    private void handleTextMessage(WebSocket webSocket, String text) {
        try {
            JSONObject message = new JSONObject(text);
            String type = message.optString("type", "");
            if ("ready".equals(type) || "hello".equals(type)) return;
            if (!"request".equals(type)) return;
            int id = message.getInt("id");
            String path = message.getString("path");
            if (path.length() > MAX_PATH_LENGTH || !isAllowedRelayPath(path)) {
                sendError(webSocket, id, "path_not_allowed");
                return;
            }
            io.execute(() -> serveRequest(webSocket, id, path));
        } catch (Exception e) {
            FLog.w(TAG, "Ignoring malformed relay command: " + e.getMessage());
        }
    }

    private void serveRequest(WebSocket webSocket, int id, String relayPath) {
        String localPath = relayPath;
        if (relayPath.startsWith("/hls/")) {
            String token = relayPath.substring("/hls/".length());
            if (!token.matches("seg-[0-9]+")) {
                sendError(webSocket, id, "invalid_segment");
                return;
            }
            localPath = "/" + token + ".m4s";
        }
        if (localPath.contains("?") || localPath.contains("#") || localPath.contains("..")) {
            sendError(webSocket, id, "invalid_path");
            return;
        }
        if (localPort <= 0) {
            localPort = context.getSharedPreferences("FadCamPrefs", Context.MODE_PRIVATE)
                    .getInt("stream_server_port", 8080);
        }

        HttpURLConnection connection = null;
        try {
            URL local = new URL("http", "127.0.0.1", localPort, localPath);
            connection = (HttpURLConnection) local.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/vnd.apple.mpegurl,video/mp4,video/iso.segment,application/octet-stream,*/*;q=0.5");
            connection.setRequestProperty("Cache-Control", "no-cache");
            int status = connection.getResponseCode();

            JSONObject response = new JSONObject();
            response.put("type", "response");
            response.put("id", id);
            response.put("status", status);
            JSONObject headers = new JSONObject();
            copyHeader(connection, headers, "Content-Type");
            copyHeader(connection, headers, "Cache-Control");
            copyHeader(connection, headers, "Content-Length");
            copyHeader(connection, headers, "ETag");
            copyHeader(connection, headers, "Last-Modified");
            response.put("headers", headers);
            webSocket.send(response.toString());

            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (input != null) {
                byte[] buffer = new byte[CHUNK_SIZE];
                int count;
                while (running.get() && (count = input.read(buffer)) != -1) {
                    byte[] frame = new byte[4 + count];
                    ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN).putInt(id).put(buffer, 0, count);
                    if (!webSocket.send(okhttp3.ByteString.of(frame))) break;
                }
                input.close();
            }
            JSONObject end = new JSONObject();
            end.put("type", "end");
            end.put("id", id);
            webSocket.send(end.toString());
        } catch (Exception e) {
            sendError(webSocket, id, "local_hls_error: " + safeError(e));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean isAllowedRelayPath(String path) {
        return "/live.m3u8".equals(path) || "/init.mp4".equals(path) || "/status".equals(path)
                || "/audio/volume".equals(path) || path.matches("/hls/seg-[0-9]+");
    }

    private static void copyHeader(HttpURLConnection connection, JSONObject target, String name) {
        String value = connection.getHeaderField(name);
        if (value != null && value.length() <= 1024) {
            try { target.put(name, value); } catch (Exception ignored) { }
        }
    }

    private static void sendError(WebSocket socket, int id, String error) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "error");
            message.put("id", id);
            message.put("error", error);
            socket.send(message.toString());
        } catch (Exception ignored) { }
    }

    private void scheduleReconnect(String channelId) {
        if (!running.get()) return;
        long delay = reconnectDelayMs;
        reconnectDelayMs = Math.min(MAX_RECONNECT_MS, reconnectDelayMs * 2);
        main.postDelayed(() -> {
            if (!running.get()) return;
            if (producerTicket == null || sessionId == null) openSession(channelId);
            else connectTunnel();
        }, delay);
    }

    private static byte[] readAll(InputStream input, int maxBytes) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maxBytes) throw new IOException("response too large");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String safeError(Exception e) {
        String message = e.getClass().getSimpleName();
        return message.length() > 64 ? message.substring(0, 64) : message;
    }

    private static String urlEncode(String value) {
        try { return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (Exception e) { return value; }
    }
}
