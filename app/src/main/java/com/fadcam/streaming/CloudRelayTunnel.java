package com.fadcam.streaming;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Native Android producer bridge for the TV 49 East Cloudflare relay.
 *
 * Flow:
 *   Supabase JWT -> POST /v1/device/session -> producer ticket
 *   wss://.../tunnel?stream=deviceId&ticket=... -> Cloudflare RelayTunnel
 *   relay request -> localhost HLS server -> response headers + binary body
 *
 * The relay protocol is deliberately small and streaming-friendly:
 *   request:  {"type":"request","id":N,"method":"GET","path":"/live.m3u8"}
 *   headers:  {"type":"response","id":N,"status":200,"headers":{...}}
 *   payload:  4-byte big-endian request id + body bytes
 *   complete: {"type":"end","id":N}
 *   cancel:   {"type":"cancel","id":N}
 */
public final class CloudRelayTunnel {
    private static final String TAG = "CloudRelayTunnel";
    private static final String RELAY_BASE_URL = "https://tv49eastz-relay.nahashonnyaga794.workers.dev";
    private static final String RELAY_WS_URL = "wss://tv49eastz-relay.nahashonnyaga794.workers.dev/tunnel";
    private static final int MAX_REQUEST_ID = 0x7fffffff;
    private static final int IO_BUFFER_SIZE = 32 * 1024;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024 * 1024;

    public interface Listener {
        void onReady();
        void onError(@NonNull String message);
    }

    private final Context context;
    private final CloudAuthManager authManager;
    private final OkHttpClient client;
    private final ExecutorService requestExecutor;
    private final Map<Integer, HttpURLConnection> activeRequests = new ConcurrentHashMap<>();

    private volatile WebSocket webSocket;
    private volatile boolean running;
    private volatile int localPort = -1;
    private volatile Listener listener;
    private int reconnectAttempt;

    public CloudRelayTunnel(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.authManager = CloudAuthManager.getInstance(context);
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
        this.requestExecutor = Executors.newFixedThreadPool(4);
    }

    public synchronized void start(int port, @Nullable Listener listener) {
        if (running) return;
        if (port <= 0 || port > 65535) {
            if (listener != null) listener.onError("Invalid local HLS port");
            return;
        }
        this.localPort = port;
        this.listener = listener;
        this.running = true;
        this.reconnectAttempt = 0;
        connectWithFreshTicket();
    }

    public synchronized void stop() {
        running = false;
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) ws.close(1000, "producer_stopped");
        for (HttpURLConnection connection : activeRequests.values()) {
            try { connection.disconnect(); } catch (Exception ignored) { }
        }
        activeRequests.clear();
    }

    public boolean isRunning() {
        return running;
    }

    private void connectWithFreshTicket() {
        if (!running) return;
        new Thread(() -> {
            try {
                String jwt = authManager.getJwtToken();
                String deviceId = authManager.getDeviceId();
                if (jwt == null || jwt.trim().isEmpty()) {
                    notifyError("Supabase authentication is required before starting cloud streaming");
                    scheduleReconnect();
                    return;
                }
                if (deviceId == null || deviceId.trim().isEmpty()) {
                    notifyError("Unable to determine Android device identity");
                    scheduleReconnect();
                    return;
                }

                JSONObject body = new JSONObject();
                body.put("channel_id", deviceId);
                body.put("stream_id", deviceId);
                body.put("device_id", deviceId);

                Request request = new Request.Builder()
                        .url(RELAY_BASE_URL + "/v1/device/session")
                        .post(okhttp3.RequestBody.create(body.toString(), okhttp3.MediaType.parse("application/json")))
                        .header("Authorization", "Bearer " + jwt)
                        .header("Accept", "application/json")
                        .build();

                try (okhttp3.Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        String detail = response.body() == null ? "HTTP " + response.code() : response.body().string();
                        notifyError("Cloud producer session failed: " + detail);
                        scheduleReconnect();
                        return;
                    }
                    JSONObject json = new JSONObject(response.body().string());
                    String ticket = json.optString("producer_ticket", "");
                    String returnedStream = json.optString("stream_id", deviceId);
                    if (ticket.isEmpty() || !deviceId.equals(returnedStream)) {
                        notifyError("Cloud producer session returned an invalid ticket");
                        scheduleReconnect();
                        return;
                    }
                    openWebSocket(returnedStream, ticket);
                }
            } catch (Exception e) {
                notifyError("Cloud producer session error: " + e.getMessage());
                scheduleReconnect();
            }
        }, "tv49-cloud-session").start();
    }

    private void openWebSocket(String stream, String ticket) {
        if (!running) return;
        String url = RELAY_WS_URL + "?stream=" + java.net.URLEncoder.encode(stream, java.nio.charset.StandardCharsets.UTF_8)
                + "&ticket=" + java.net.URLEncoder.encode(ticket, java.nio.charset.StandardCharsets.UTF_8);
        Request request = new Request.Builder().url(url).build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(@NonNull WebSocket ws, @NonNull okhttp3.Response response) {
                reconnectAttempt = 0;
                FLog.i(TAG, "Cloudflare producer tunnel connected for stream " + stream);
                ws.send(new JSONObject().put("type", "hello").toString());
                Listener current = listener;
                if (current != null) current.onReady();
            }

            @Override public void onMessage(@NonNull WebSocket ws, @NonNull String text) {
                handleText(ws, text);
            }

            @Override public void onMessage(@NonNull WebSocket ws, @NonNull ByteString bytes) {
                // Producer receives no binary frames from the relay. Ignore safely.
            }

            @Override public void onClosing(@NonNull WebSocket ws, int code, @NonNull String reason) {
                ws.close(1000, null);
            }

            @Override public void onClosed(@NonNull WebSocket ws, int code, @NonNull String reason) {
                if (webSocket == ws) webSocket = null;
                cancelAllRequests();
                scheduleReconnect();
            }

            @Override public void onFailure(@NonNull WebSocket ws, @NonNull Throwable t, @Nullable okhttp3.Response response) {
                if (webSocket == ws) webSocket = null;
                cancelAllRequests();
                notifyError("Cloudflare producer tunnel disconnected: " + t.getMessage());
                scheduleReconnect();
            }
        });
    }

    private void handleText(WebSocket ws, String text) {
        try {
            JSONObject message = new JSONObject(text);
            String type = message.optString("type", "");
            if ("ready".equals(type)) {
                return;
            }
            if ("request".equals(type)) {
                int id = message.optInt("id", -1);
                String method = message.optString("method", "GET");
                String path = message.optString("path", "");
                if (id < 0 || id > MAX_REQUEST_ID || !"GET".equalsIgnoreCase(method) || !validPath(path)) {
                    sendError(ws, id, "invalid_request");
                    return;
                }
                requestExecutor.execute(() -> proxyRequest(ws, id, path));
                return;
            }
            if ("cancel".equals(type)) {
                int id = message.optInt("id", -1);
                HttpURLConnection connection = activeRequests.remove(id);
                if (connection != null) connection.disconnect();
            }
        } catch (Exception e) {
            sendError(ws, -1, "invalid_message");
        }
    }

    private boolean validPath(String path) {
        if (path == null || path.length() > 2048 || path.contains("..") || path.contains("\\")) return false;
        return "/live.m3u8".equals(path)
                || "/init.mp4".equals(path)
                || "/status".equals(path)
                || "/audio/volume".equals(path)
                || path.matches("/hls/seg-[0-9]+")
                || path.matches("/seg-[0-9]+\\.m4s");
    }

    private String localPath(String relayPath) {
        if (relayPath.matches("/hls/seg-[0-9]+")) {
            return "/seg-" + relayPath.substring("/hls/seg-".length()) + ".m4s";
        }
        return relayPath;
    }

    private void proxyRequest(WebSocket ws, int id, String relayPath) {
        HttpURLConnection connection = null;
        try {
            String targetPath = localPath(relayPath);
            URL url = new URL("http://127.0.0.1:" + localPort + targetPath);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(20000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/vnd.apple.mpegurl,video/mp4,video/iso.segment,video/mp2t,application/octet-stream,*/*;q=0.5");
            activeRequests.put(id, connection);

            int status = connection.getResponseCode();
            Map<String, String> headers = new HashMap<>();
            String contentType = connection.getContentType();
            if (contentType != null) headers.put("content-type", contentType);
            String contentLength = connection.getHeaderField("Content-Length");
            if (contentLength != null) headers.put("content-length", contentLength);
            String cacheControl = connection.getHeaderField("Cache-Control");
            if (cacheControl != null) headers.put("cache-control", cacheControl);

            JSONObject response = new JSONObject();
            response.put("type", "response");
            response.put("id", id);
            response.put("status", status);
            JSONObject jsonHeaders = new JSONObject();
            for (Map.Entry<String, String> entry : headers.entrySet()) jsonHeaders.put(entry.getKey(), entry.getValue());
            response.put("headers", jsonHeaders);
            if (!ws.send(response.toString())) return;

            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (input != null) {
                byte[] buffer = new byte[IO_BUFFER_SIZE];
                long total = 0;
                int read;
                while (running && (read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_RESPONSE_BYTES) {
                        sendError(ws, id, "response_too_large");
                        return;
                    }
                    byte[] frame = new byte[4 + read];
                    ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN).putInt(id).put(buffer, 0, read);
                    if (!ws.send(ByteString.of(frame))) return;
                }
                input.close();
            }

            JSONObject end = new JSONObject();
            end.put("type", "end");
            end.put("id", id);
            ws.send(end.toString());
        } catch (Exception e) {
            if (running) sendError(ws, id, "local_proxy_error: " + e.getClass().getSimpleName());
        } finally {
            if (connection != null) {
                activeRequests.remove(id);
                connection.disconnect();
            }
        }
    }

    private void sendError(WebSocket ws, int id, String error) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "error");
            if (id >= 0) message.put("id", id);
            message.put("error", error);
            ws.send(message.toString());
        } catch (Exception ignored) { }
    }

    private void cancelAllRequests() {
        for (HttpURLConnection connection : activeRequests.values()) {
            try { connection.disconnect(); } catch (Exception ignored) { }
        }
        activeRequests.clear();
    }

    private synchronized void scheduleReconnect() {
        if (!running) return;
        int attempt = Math.min(++reconnectAttempt, 6);
        long delay = Math.min(30000L, 1000L << (attempt - 1));
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (running && webSocket == null) connectWithFreshTicket();
        }, delay);
    }

    private void notifyError(String message) {
        FLog.e(TAG, message);
        Listener current = listener;
        if (current != null) current.onError(message);
    }
}
