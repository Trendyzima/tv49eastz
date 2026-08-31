package com.fadcam.tv;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

/** TV/display destination. Camera capture is intentionally absent from this module. */
public final class MainActivity extends AppCompatActivity {
    private ExoPlayer player;
    private PlayerView playerView;
    private TextView status;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setKeepScreenOn(true);

        status = new TextView(this);
        status.setText("FadCam TV • Ready");
        status.setTextSize(18f);
        status.setPadding(24, 16, 24, 16);

        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setPlayer(player);

        Button stop = new Button(this);
        stop.setText("Stop stream");
        stop.setOnClickListener(v -> stopPlayback());

        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(playerView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(stop, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = intent.getData();
        if (uri == null || !"fadcam".equals(uri.getScheme()) || !"stream".equals(uri.getHost())) {
            return;
        }
        String mediaUrl = uri.getQueryParameter("url");
        if (mediaUrl == null || mediaUrl.isBlank()) {
            status.setText("FadCam TV • Stream request missing URL");
            return;
        }
        // The URL must be an already-authorized gateway capability. This client does not
        // manufacture credentials or bypass the secure gateway.
        Uri parsed = Uri.parse(mediaUrl);
        String scheme = parsed.getScheme();
        if (!"https".equalsIgnoreCase(scheme)) {
            status.setText("FadCam TV • Secure stream required");
            return;
        }
        startPlayback(mediaUrl);
    }

    private void startPlayback(String url) {
        stopPlayback();
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.play();
        status.setText("FadCam TV • Connected");
    }

    private void stopPlayback() {
        if (player != null) {
            player.release();
            player = null;
        }
        if (playerView != null) playerView.setPlayer(null);
        if (status != null) status.setText("FadCam TV • Ready");
    }

    @Override
    protected void onDestroy() {
        stopPlayback();
        super.onDestroy();
    }
}
