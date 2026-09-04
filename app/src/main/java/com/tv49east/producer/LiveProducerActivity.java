package com.fadcam;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.fadcam.dualcam.service.DualCameraRecordingService;
import com.fadcam.streaming.CloudRelayTunnel;
import com.fadcam.streaming.RemoteStreamService;

import java.net.HttpURLConnection;
import java.net.URL;

/** TV 49 East producer console. */
public final class LiveProducerActivity extends Activity {
    public static final String EXTRA_VIDEO_URI = "producer_video_uri";
    private static final String PREF_LIVE_INTERVIEW = "fadcam_live_interview_active";
    private static final String PREF_PRODUCER_VIDEO_URI = "fadcam_producer_video_uri";
    private static final String PREF_STREAMING_MODE = "FadCamCloudPrefs";
    private static final int REQUEST_VIDEO = 4907;
    private static final int REQUEST_CAPTURE_PERMISSIONS = 4908;
    private static final long SERVER_POLL_MS = 250L;
    private static final long SERVER_TIMEOUT_MS = 8_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Uri selectedVideoUri;
    private TextView selectionLabel;
    private TextView stateLabel;
    private Button startButton;
    private boolean starting;
    private CloudRelayTunnel cloudRelayTunnel;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        String existing = getIntent() != null ? getIntent().getStringExtra(EXTRA_VIDEO_URI) : null;
        if (existing != null && !existing.isEmpty()) {
            try { selectedVideoUri = Uri.parse(existing); } catch (Exception ignored) { selectedVideoUri = null; }
            updateSelectionLabel();
        }
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        // The foreground RemoteStreamService owns the live process. Do not stop the
        // WebSocket here, otherwise the normal Activity finish() would kill a live stream.
        super.onDestroy();
    }

    private void buildUi() {
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(0xFF080808);

        TextView title = new TextView(this);
        title.setText("TV 49 East • Live Producer");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(25f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView description = new TextView(this);
        description.setText("Program video is full screen. Your camera is live PiP and your microphone carries commentary audio. TV 49 East receives the single composed HLS stream.");
        description.setTextColor(0xFFCCCCCC);
        description.setTextSize(16f);
        description.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(-1, -2);
        descLp.topMargin = pad / 2;
        root.addView(description, descLp);

        selectionLabel = new TextView(this);
        selectionLabel.setTextColor(0xFF9FE7FF);
        selectionLabel.setTextSize(15f);
        selectionLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams selectionLp = new LinearLayout.LayoutParams(-1, -2);
        selectionLp.topMargin = pad;
        root.addView(selectionLabel, selectionLp);

        stateLabel = new TextView(this);
        stateLabel.setText("READY");
        stateLabel.setTextColor(0xFF77DD77);
        stateLabel.setTextSize(13f);
        stateLabel.setGravity(Gravity.CENTER);
        root.addView(stateLabel, new LinearLayout.LayoutParams(-1, -2));

        Button chooseButton = new Button(this);
        chooseButton.setText("LOAD PROGRAM VIDEO");
        chooseButton.setOnClickListener(v -> openVideoPicker());
        LinearLayout.LayoutParams chooseLp = new LinearLayout.LayoutParams(-1, -2);
        chooseLp.topMargin = pad;
        root.addView(chooseButton, chooseLp);

        startButton = new Button(this);
        startButton.setText("START LIVE COMMENTARY");
        startButton.setEnabled(false);
        startButton.setOnClickListener(v -> startLiveCommentary());
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(-1, -2);
        startLp.topMargin = pad / 2;
        root.addView(startButton, startLp);

        setContentView(root);
        updateSelectionLabel();
    }

    private void openVideoPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, REQUEST_VIDEO); }
        catch (RuntimeException e) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            fallback.setType("video/*");
            fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(fallback, REQUEST_VIDEO);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_VIDEO || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if ((data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0 && takeFlags != 0)
                getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (SecurityException ignored) { }
        selectedVideoUri = uri;
        updateSelectionLabel();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CAPTURE_PERMISSIONS) return;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                setState("Camera + microphone permission required", false);
                Toast.makeText(this, "Allow camera and microphone, then start the producer again.", Toast.LENGTH_LONG).show();
                return;
            }
        }
        launchProducerPipeline();
    }

    private void updateSelectionLabel() {
        if (selectionLabel == null || startButton == null) return;
        if (selectedVideoUri == null) {
            selectionLabel.setText("No program video loaded");
            startButton.setEnabled(false);
        } else {
            String name = selectedVideoUri.getLastPathSegment();
            selectionLabel.setText("Loaded program: " + (name == null ? selectedVideoUri.toString() : name));
            startButton.setEnabled(!starting);
        }
    }

    private void startLiveCommentary() {
        if (selectedVideoUri == null || starting) return;
        if (!hasCapturePermissions()) { requestCapturePermissions(); return; }
        launchProducerPipeline();
    }

    private boolean hasCapturePermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCapturePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQUEST_CAPTURE_PERMISSIONS);
    }

    private void launchProducerPipeline() {
        starting = true;
        startButton.setEnabled(false);
        setState("Starting local TV 49 East stream…", true);

        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(this);
        prefs.sharedPreferences.edit()
                .putBoolean(PREF_LIVE_INTERVIEW, true)
                .putString(PREF_PRODUCER_VIDEO_URI, selectedVideoUri.toString())
                .apply();
        // The local HLS server is the private producer origin. CloudRelayTunnel carries it to Cloudflare.
        getSharedPreferences(PREF_STREAMING_MODE, MODE_PRIVATE).edit().putInt("streaming_mode", 0).apply();

        try {
            Intent stream = new Intent(this, RemoteStreamService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(this, stream);
            else startService(stream);
            waitForStreamServer(System.currentTimeMillis());
        } catch (RuntimeException e) { failStart("Unable to start the TV 49 East stream service"); }
    }

    private void waitForStreamServer(long startedAt) {
        if (isFinishing() || isDestroyed()) return;
        int port = getSharedPreferences("FadCamPrefs", MODE_PRIVATE).getInt("stream_server_port", -1);
        if (port > 0 && probeLocalServer(port)) { startCloudRelayTunnel(port); return; }
        if (System.currentTimeMillis() - startedAt >= SERVER_TIMEOUT_MS) {
            failStart("TV 49 East stream server did not become ready"); return;
        }
        main.postDelayed(() -> waitForStreamServer(startedAt), SERVER_POLL_MS);
    }

    private void startCloudRelayTunnel(int port) {
        if (cloudRelayTunnel != null) cloudRelayTunnel.stop();
        cloudRelayTunnel = new CloudRelayTunnel(this);
        setState("Authenticating TV 49 East cloud tunnel…", true);
        cloudRelayTunnel.start(port, new CloudRelayTunnel.Listener() {
            @Override public void onReady() {
                main.post(() -> { if (starting && !isFinishing() && !isDestroyed()) startDualProducerService(); });
            }
            @Override public void onError(String message) {
                main.post(() -> { if (starting && !isFinishing() && !isDestroyed()) failStart(message); });
            }
        });
    }

    private boolean probeLocalServer(int port) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://127.0.0.1:" + port + "/status");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(300);
            connection.setReadTimeout(500);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            return connection.getResponseCode() >= 200 && connection.getResponseCode() < 500;
        } catch (Exception ignored) { return false; }
        finally { if (connection != null) connection.disconnect(); }
    }

    private void startDualProducerService() {
        try {
            Intent dual = new Intent(this, DualCameraRecordingService.class);
            dual.setAction(Constants.INTENT_ACTION_START_DUAL_RECORDING);
            dual.putExtra("producer_video_uri", selectedVideoUri.toString());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(this, dual);
            else startService(dual);
            setState("LIVE • Cloudflare tunnel connected • Video fullscreen • Camera PiP • Mic commentary", true);
            Toast.makeText(this, "TV 49 East producer is live", Toast.LENGTH_SHORT).show();
            main.postDelayed(this::finish, 350L);
        } catch (RuntimeException e) { failStart("Unable to start producer camera"); }
    }

    private void failStart(String message) {
        main.removeCallbacksAndMessages(null);
        if (cloudRelayTunnel != null) { cloudRelayTunnel.stop(); cloudRelayTunnel = null; }
        try { stopService(new Intent(this, RemoteStreamService.class)); } catch (Exception ignored) { }
        SharedPreferencesManager.getInstance(this).sharedPreferences.edit()
                .putBoolean(PREF_LIVE_INTERVIEW, false)
                .remove(PREF_PRODUCER_VIDEO_URI)
                .apply();
        starting = false;
        setState(message, false);
        updateSelectionLabel();
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void setState(String message, boolean good) {
        if (stateLabel == null) return;
        stateLabel.setText(message);
        stateLabel.setTextColor(good ? 0xFF77DD77 : 0xFFF45B5B);
    }
}
