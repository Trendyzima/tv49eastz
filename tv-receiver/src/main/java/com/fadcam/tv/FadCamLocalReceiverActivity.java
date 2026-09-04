package com.fadcam.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native FadCam local-broadcast connector.
 *
 * Connects TV 49 East directly to the FadCam LiveM3U8Server HLS endpoint.
 * It first probes localhost ports 8080-8089 (useful when both apps are on one
 * Android device), and also accepts a LAN URL copied from FadCam Server Room
 * when FadCam and TV 49 East are running on different devices.
 */
public final class FadCamLocalReceiverActivity extends Activity {
    private static final int BG = Color.rgb(5, 5, 7);
    private static final int TEXT = Color.WHITE;
    private static final int MUTED = Color.rgb(190, 188, 198);
    private static final int ACCENT = Color.rgb(207, 186, 253);
    private static final int FIRST_PORT = 8080;
    private static final int PORT_COUNT = 10;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private EditText urlInput;
    private TextView status;
    private Button scan;
    private boolean destroyed;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        buildUi();
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        executor.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(24), dp(32), dp(24), dp(24));

        TextView title = text("FadCam → TV 49 East", 28, TEXT, true);
        title.setGravity(Gravity.CENTER);
        panel.addView(title, new LinearLayout.LayoutParams(-1, dp(48)));

        TextView subtitle = text(
                "Live HLS broadcast from the FadCam Server Room.\nSame device: scan 8080–8089. Different device: enter FadCam's LAN URL.",
                13, MUTED, false);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sub = new LinearLayout.LayoutParams(-1, -2);
        sub.topMargin = dp(8);
        panel.addView(subtitle, sub);

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setText("http://127.0.0.1:8080/live.m3u8");
        urlInput.setTextColor(TEXT);
        urlInput.setHintTextColor(MUTED);
        urlInput.setHint("http://192.168.x.x:8080/live.m3u8");
        urlInput.setTextSize(14);
        LinearLayout.LayoutParams input = new LinearLayout.LayoutParams(-1, dp(56));
        input.topMargin = dp(24);
        panel.addView(urlInput, input);

        Button connect = button("CONNECT TO FADCAM", v -> connect(urlInput.getText().toString()));
        connect.setBackgroundColor(ACCENT);
        connect.setTextColor(Color.BLACK);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(52));
        cp.topMargin = dp(12);
        panel.addView(connect, cp);

        scan = button("SCAN LOCAL FADCAM SERVER", v -> scanLocalPorts());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(52));
        sp.topMargin = dp(10);
        panel.addView(scan, sp);

        status = text("Ready — start FadCam local streaming first.", 13, MUTED, false);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams st = new LinearLayout.LayoutParams(-1, -2);
        st.topMargin = dp(20);
        panel.addView(status, st);

        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(android.graphics.Typeface.DEFAULT, bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        return t;
    }

    private Button button(String title, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(title);
        b.setTextSize(13);
        b.setTextColor(TEXT);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        b.setFocusable(true);
        b.setClickable(true);
        return b;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String raw = intent.getStringExtra("stream_url");
        Uri data = intent.getData();
        if (raw == null && data != null && "tv49east".equalsIgnoreCase(data.getScheme())) {
            raw = data.getQueryParameter("url");
        }
        if (raw != null && !raw.trim().isEmpty()) {
            urlInput.setText(raw.trim());
            connect(raw.trim());
        }
    }

    private void scanLocalPorts() {
        scan.setEnabled(false);
        status.setText("Scanning FadCam local server…");
        executor.execute(() -> {
            String found = null;
            for (int port = FIRST_PORT; port < FIRST_PORT + PORT_COUNT && !destroyed; port++) {
                String candidate = "http://127.0.0.1:" + port + "/live.m3u8";
                if (probe(candidate)) {
                    found = candidate;
                    break;
                }
            }
            final String result = found;
            main.post(() -> {
                if (destroyed) return;
                scan.setEnabled(true);
                if (result == null) {
                    status.setText("No FadCam server found on localhost:8080–8089. If FadCam is on another device, enter its LAN /live.m3u8 URL.");
                    return;
                }
                urlInput.setText(result);
                status.setText("FadCam server found — connecting…");
                connect(result);
            });
        });
    }

    private boolean probe(String value) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(value);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(700);
            connection.setReadTimeout(1200);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.apple.mpegurl,*/*");
            int code = connection.getResponseCode();
            return code == 200 || code == 206 || code == 503;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void connect(String raw) {
        final String url = raw == null ? "" : raw.trim();
        if (!isAllowedHlsUrl(url)) {
            status.setText("Enter a valid HTTP/HTTPS FadCam /live.m3u8 URL.");
            return;
        }
        status.setText("Opening FadCam live stream…");
        Intent player = new Intent(this, FadCamDirectStreamActivity.class);
        player.putExtra("stream_url", url);
        startActivity(player);
    }

    private boolean isAllowedHlsUrl(String raw) {
        try {
            Uri uri = Uri.parse(raw);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getPath();
            if (host == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) return false;
            return path != null && (path.endsWith("/live.m3u8") || path.endsWith("/stream.m3u8"));
        } catch (Exception ignored) {
            return false;
        }
    }
}
