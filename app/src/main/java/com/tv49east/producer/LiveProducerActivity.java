package com.fadcam;

import android.app.Activity;
import android.content.Intent;
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
import com.fadcam.streaming.RemoteStreamService;

/** TV 49 East producer console kept outside the protected FadCam source boundary. */
public final class LiveProducerActivity extends Activity {
    public static final String EXTRA_VIDEO_URI = "producer_video_uri";
    private static final String PREF_LIVE_INTERVIEW = "fadcam_live_interview_active";
    private static final String PREF_PRODUCER_VIDEO_URI = "fadcam_producer_video_uri";
    private static final int REQUEST_VIDEO = 4907;
    private static final long STREAM_SERVER_WARMUP_MS = 1200L;

    private Uri selectedVideoUri;
    private TextView selectionLabel;
    private Button startButton;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        String existing = getIntent() != null ? getIntent().getStringExtra(EXTRA_VIDEO_URI) : null;
        if (existing != null && !existing.isEmpty()) {
            try { selectedVideoUri = Uri.parse(existing); } catch (Exception ignored) { selectedVideoUri = null; }
            updateSelectionLabel();
        }
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
        description.setText("Load a video for the full-screen program and use the phone camera as the live PiP commentator. The microphone carries the commentary audio.");
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

        Button chooseButton = new Button(this);
        chooseButton.setText("LOAD VIDEO");
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
        try {
            startActivityForResult(intent, REQUEST_VIDEO);
        } catch (RuntimeException e) {
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
        if ((data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            } catch (SecurityException ignored) { }
        }
        selectedVideoUri = uri;
        updateSelectionLabel();
    }

    private void updateSelectionLabel() {
        if (selectionLabel == null || startButton == null) return;
        if (selectedVideoUri == null) {
            selectionLabel.setText("No program video loaded");
            startButton.setEnabled(false);
        } else {
            String name = selectedVideoUri.getLastPathSegment();
            selectionLabel.setText("Loaded program: " + (name == null ? selectedVideoUri.toString() : name));
            startButton.setEnabled(true);
        }
    }

    private void startLiveCommentary() {
        if (selectedVideoUri == null) return;
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(this);
        prefs.sharedPreferences.edit()
                .putBoolean(PREF_LIVE_INTERVIEW, true)
                .putString(PREF_PRODUCER_VIDEO_URI, selectedVideoUri.toString())
                .apply();
        getSharedPreferences("FadCamCloudPrefs", MODE_PRIVATE).edit().putInt("streaming_mode", 0).apply();

        try {
            Intent stream = new Intent(this, RemoteStreamService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(this, stream);
            else startService(stream);

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    Intent dual = new Intent(this, DualCameraRecordingService.class);
                    dual.setAction(Constants.INTENT_ACTION_START_DUAL_RECORDING);
                    dual.putExtra("producer_video_uri", selectedVideoUri.toString());
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(this, dual);
                    else startService(dual);
                } catch (RuntimeException e) {
                    Toast.makeText(this, "Unable to start producer camera", Toast.LENGTH_LONG).show();
                }
            }, STREAM_SERVER_WARMUP_MS);
            finish();
        } catch (RuntimeException e) {
            prefs.sharedPreferences.edit().putBoolean(PREF_LIVE_INTERVIEW, false).apply();
            Toast.makeText(this, "Unable to start live producer mode", Toast.LENGTH_LONG).show();
        }
    }
}
