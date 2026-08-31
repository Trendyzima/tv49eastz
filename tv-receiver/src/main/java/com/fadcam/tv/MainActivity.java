package com.fadcam.tv;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

/** TV 49 East display destination. Camera capture is intentionally absent from this module. */
public final class MainActivity extends AppCompatActivity {
    private static final int BG = Color.rgb(17, 17, 19);
    private static final int SURFACE = Color.rgb(35, 25, 66);
    private static final int ACCENT = Color.rgb(207, 186, 253);
    private static final int TEXT = Color.WHITE;
    private static final int MUTED = Color.rgb(190, 184, 205);

    private ExoPlayer player;
    private PlayerView playerView;
    private TextView status;
    private Button stop;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(Color.rgb(11, 11, 13));
        buildUi();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private TextView label(String text, float size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return v;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setKeepScreenOn(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(32, 24, 32, 24);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(24, 18, 24, 18);
        header.setBackgroundColor(SURFACE);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView brand = label("TV 49 East", 26f, TEXT, true);
        TextView subtitle = label("Powered by FadCam technology", 13f, MUTED, false);
        titles.addView(brand);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        status = label("READY TO CONNECT", 13f, ACCENT, true);
        status.setGravity(Gravity.CENTER);
        header.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(header);

        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setPlayer(null);
        LinearLayout.LayoutParams videoParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        videoParams.topMargin = 20;
        videoParams.bottomMargin = 16;
        content.addView(playerView, videoParams);

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        TextView hint = label("Secure stream receiver", 13f, MUTED, false);
        footer.addView(hint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        stop = new Button(this);
        stop.setText("STOP STREAM");
        stop.setTextColor(TEXT);
        stop.setAllCaps(false);
        stop.setEnabled(false);
        stop.setOnClickListener(v -> stopPlayback());
        footer.addView(stop, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(footer);

        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(content, contentParams);
        setContentView(root);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = intent.getData();
        if (uri == null || !"fadcam".equals(uri.getScheme()) || !"stream".equals(uri.getHost())) return;
        String mediaUrl = uri.getQueryParameter("url");
        if (mediaUrl == null || mediaUrl.isBlank()) {
            setStatus("STREAM REQUEST INVALID", Color.rgb(244, 67, 54));
            return;
        }
        Uri parsed = Uri.parse(mediaUrl);
        if (!"https".equalsIgnoreCase(parsed.getScheme())) {
            setStatus("SECURE HTTPS STREAM REQUIRED", Color.rgb(244, 67, 54));
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
        stop.setEnabled(true);
        setStatus("CONNECTED • LIVE", Color.rgb(119, 221, 119));
    }

    private void setStatus(String text, int color) {
        if (status != null) {
            status.setText(text);
            status.setTextColor(color);
        }
    }

    private void stopPlayback() {
        if (player != null) {
            player.release();
            player = null;
        }
        if (playerView != null) playerView.setPlayer(null);
        if (stop != null) stop.setEnabled(false);
        setStatus("READY TO CONNECT", ACCENT);
    }

    @Override
    protected void onDestroy() {
        stopPlayback();
        super.onDestroy();
    }
}
