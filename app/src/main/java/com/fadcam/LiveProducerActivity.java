package com.fadcam;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

/**
 * Producer console for TV 49 East.
 *
 * <p>The producer selects a local video, then starts a live camera commentary
 * session. The selected video becomes the full-screen program source while the
 * live FadCam camera is composited as the PiP presenter/commentator.</p>
 */
public final class LiveProducerActivity extends Activity {
    public static final String EXTRA_VIDEO_URI = "producer_video_uri";
    private static final int REQUEST_VIDEO = 4907;

    private Uri selectedVideoUri;
    private TextView selectionLabel;
    private Button startButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        String existing = getIntent() != null ? getIntent().getStringExtra(EXTRA_VIDEO_URI) : null;
        if (existing != null && !existing.isEmpty()) {
            try {
                selectedVideoUri = Uri.parse(existing);
                updateSelectionLabel();
            } catch (Exception ignored) {
                selectedVideoUri = null;
            }
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
        description.setText("Load a video to play full-screen on TV, then comment on it live from the FadCam camera. Your microphone carries the live commentary.");
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

        TextView note = new TextView(this);
        note.setText("Tip: keep the producer phone on the same LAN as the TV 49 East receiver. The program video is muted so your live microphone commentary remains the broadcast audio.");
        note.setTextColor(0xFF888888);
        note.setTextSize(13f);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
        noteLp.topMargin = pad;
        root.addView(note, noteLp);

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
            // Some OEM builds do not expose ACTION_OPEN_DOCUMENT video providers.
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            fallback.setType("video/*");
            fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(fallback, REQUEST_VIDEO);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_VIDEO || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if ((data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            } catch (SecurityException ignored) {
                // A provider may grant only a transient read permission; the current
                // foreground session can still use it.
            }
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
        Intent intent = new Intent(this, RecordingStartActivity.class);
        intent.putExtra(RecordingStartActivity.EXTRA_SHORTCUT_CAMERA_MODE,
                RecordingStartActivity.CAMERA_MODE_INTERVIEW_VIDEO);
        intent.putExtra(RecordingStartActivity.EXTRA_PRODUCER_VIDEO_URI,
                selectedVideoUri.toString());
        try {
            ContextCompat.startActivity(this, intent, null);
            finish();
        } catch (RuntimeException e) {
            Utils.showQuickToast(this, "Unable to start live producer mode");
        }
    }
}
