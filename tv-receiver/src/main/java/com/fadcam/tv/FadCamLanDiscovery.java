package com.fadcam.tv;

import android.os.SystemClock;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Finds an active FadCam Server Room on the local network.
 *
 * FadCam's normal live endpoint is HTTP on port 8080. The receiver previously
 * required a compile-time HTTPS catalog URL, which meant an otherwise healthy
 * local FadCam server could never be discovered by the standalone TV APK.
 * Discovery is deliberately LAN-only: it enumerates directly attached IPv4
 * networks and accepts a candidate only when /live.m3u8 contains real HLS
 * media markers.
 */
public final class FadCamLanDiscovery {
    public static final int DEFAULT_PORT = 8080;
    private static final int MAX_HOSTS = 512;
    private static final int MAX_WORKERS = 24;
    private static final long PROBE_TIMEOUT_MS = 700L;

    private FadCamLanDiscovery() {}

    public interface Listener {
        void onSearching();
        void onFound(@NonNull String baseUrl, @NonNull String playlistUrl);
        void onNotFound(@NonNull String reason);
    }

    public static void discover(@NonNull Listener listener) {
        listener.onSearching();
        Thread thread = new Thread(() -> {
            String found = findServer();
            if (found != null) {
                listener.onFound(found, found + "/live.m3u8");
            } else {
                listener.onNotFound("No active FadCam live server found on the local network");
            }
        }, "fadcam-lan-discovery");
        thread.setDaemon(true);
        thread.start();
    }

    private static String findServer() {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();

        List<String> candidates = candidates();
        if (candidates.isEmpty()) return null;

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(MAX_WORKERS, candidates.size()));
        try {
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(candidates.size());
            java.util.concurrent.atomic.AtomicReference<String> found = new java.util.concurrent.atomic.AtomicReference<>();
            for (String base : candidates) {
                pool.execute(() -> {
                    try {
                        if (found.get() == null && probe(client, base)) found.compareAndSet(null, base);
                    } finally {
                        done.countDown();
                    }
                });
            }
            long deadline = SystemClock.elapsedRealtime() + 8_000L;
            while (found.get() == null && SystemClock.elapsedRealtime() < deadline) {
                if (done.await(150, TimeUnit.MILLISECONDS)) break;
            }
            return found.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            pool.shutdownNow();
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }

    private static boolean probe(OkHttpClient client, String base) {
        Request request = new Request.Builder()
                .url(base + "/live.m3u8")
                .header("Accept", "application/vnd.apple.mpegurl,text/plain;q=0.8")
                .header("User-Agent", "TV49East-FadCamDiscovery/1")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return false;
            String body = response.body().string();
            if (!body.trim().startsWith("#EXTM3U")) return false;
            return body.contains("#EXT-X-MAP:")
                    || body.contains("/init.mp4")
                    || body.contains(".m4s")
                    || body.contains(".mp4");
        } catch (IOException ignored) {
            return false;
        }
    }

    private static List<String> candidates() {
        Set<String> seen = new HashSet<>();
        List<String> result = new ArrayList<>();
        addCandidate(result, seen, "http://127.0.0.1:" + DEFAULT_PORT);
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return result;
            while (interfaces.hasMoreElements() && result.size() < MAX_HOSTS) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopback()) continue;
                    byte[] ip = address.getAddress();
                    byte[] mask = ipv4Mask(iface, address);
                    if (mask == null) continue;
                    long network = ipv4(ip) & ipv4(mask);
                    int prefix = prefixLength(mask);
                    int hostBits = 32 - prefix;
                    if (hostBits < 1 || hostBits > 24) continue;
                    long count = Math.min(1L << hostBits, MAX_HOSTS);
                    for (long offset = 0; offset < count && result.size() < MAX_HOSTS; offset++) {
                        long candidate = network + offset;
                        if (candidate == network || candidate == network + count - 1) continue;
                        addCandidate(result, seen, "http://" + formatIpv4(candidate) + ":" + DEFAULT_PORT);
                    }
                }
            }
        } catch (Exception ignored) {
            // Loopback remains available even when interface enumeration is restricted.
        }
        return result;
    }

    private static void addCandidate(List<String> result, Set<String> seen, String value) {
        if (seen.add(value) && result.size() < MAX_HOSTS) result.add(value);
    }

    private static byte[] ipv4Mask(NetworkInterface iface, InetAddress address) {
        java.util.Enumeration<java.net.InterfaceAddress> addresses = iface.getInterfaceAddresses().elements();
        while (addresses.hasMoreElements()) {
            java.net.InterfaceAddress ia = addresses.nextElement();
            if (address.equals(ia.getAddress()) && address instanceof Inet4Address) {
                short prefix = ia.getNetworkPrefixLength();
                if (prefix < 1 || prefix > 30) return null;
                int bits = 0;
                for (int i = 0; i < 4; i++) {
                    int remaining = Math.max(0, Math.min(8, prefix - bits));
                    int value = remaining == 0 ? 0 : (0xff << (8 - remaining)) & 0xff;
                    bits += remaining;
                    if (i == 0) {
                        // handled below from prefix; keep loop only for validation
                    }
                }
                return maskForPrefix(prefix);
            }
        }
        return null;
    }

    private static byte[] maskForPrefix(int prefix) {
        byte[] mask = new byte[4];
        for (int i = 0; i < 4; i++) {
            int bits = Math.max(0, Math.min(8, prefix - i * 8));
            mask[i] = (byte) (bits == 0 ? 0 : (0xff << (8 - bits)) & 0xff);
        }
        return mask;
    }

    private static int prefixLength(byte[] mask) {
        int prefix = 0;
        for (byte b : mask) {
            int value = b & 0xff;
            for (int bit = 7; bit >= 0; bit--) {
                if ((value & (1 << bit)) == 0) return prefix;
                prefix++;
            }
        }
        return prefix;
    }

    private static long ipv4(byte[] value) {
        return ((long) (value[0] & 0xff) << 24)
                | ((long) (value[1] & 0xff) << 16)
                | ((long) (value[2] & 0xff) << 8)
                | (long) (value[3] & 0xff);
    }

    private static String formatIpv4(long value) {
        return ((value >>> 24) & 0xff) + "."
                + ((value >>> 16) & 0xff) + "."
                + ((value >>> 8) & 0xff) + "."
                + (value & 0xff);
    }
}
