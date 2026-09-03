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
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.HashMap;

/**
 * Dedicated FadCam handoff player.
 *
 * The player is deliberately isolated from the receiver catalog UI. A signed FadCam
 * handoff can open this activity directly, and transient network/HLS failures are
 * recovered with bounded retries instead of taking down the process.
 */
public final class FadCamDirectStreamActivity extends Activity {
    private static final int BG = Color.rgb(5, 5, 7);
    private static final int TEXT = Color.WHITE;
    private static final int MUTED = Color.rgb(190, 188, 198);
    private static final int MAX_RETRIES = 8;
    private static final long WATCHDOG_INTERVAL_MS = 5000L;
    private static final long STALL_TIMEOUT_MS = 25000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private PlayerView playerView;
    private TextView status;
    private TextView error;
    private Uri streamUri;
    private boolean destroyed;
    private boolean paused;
    private int retryCount;
    private long bufferingSince;
    private long lastPositionMs = -1L;
    private int lastWindowIndex = -1;

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (destroyed || paused) return;
            inspectPlaybackHealth();
            main.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        destroyed = false;
        paused = false;
        if (state != null) retryCount = state.getInt("retry_count", 0);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        buildUi();
        readIntent(getIntent());
        main.postDelayed(this::startPlaybackIfValid, 100L);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readIntent(intent);
        retryCount = 0;
        startPlaybackIfValid();
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle out) {
        out.putInt("retry_count", retryCount);
        super.onSaveInstanceState(out);
    }

    @Override protected void onResume() {
        super.onResume();
        paused = false;
        if (streamUri != null && player == null) startPlaybackIfValid();
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
    }

    @Override protected void onPause() {
        paused = true;
        main.removeCallbacks(watchdog);
        releasePlayer();
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        releasePlayer();
        super.onDestroy();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setControllerShowTimeoutMs(2500);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setShutterBackgroundColor(Color.BLACK);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

        status = text("● CONNECTING", 12, TEXT, true);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(Color.argb(180, 0, 0, 0));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, dp(46), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        sp.topMargin = dp(12);
        root.addView(status, sp);

        error = text("", 13, MUTED, false);
        error.setGravity(Gravity.CENTER);
        error.setPadding(dp(18), dp(12), dp(18), dp(12));
        error.setVisibility(View.GONE);
        FrameLayout.LayoutParams ep = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        ep.leftMargin = dp(24);
        ep.rightMargin = dp(24);
        root.addView(error, ep);

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

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void readIntent(Intent intent) {
        Uri candidate = intent == null ? null : intent.getData();
        if (candidate == null && intent != null) {
            String raw = intent.getStringExtra("stream_url");
            if (raw != null) candidate = Uri.parse(raw.trim());
        }
        if (candidate != null && "tv49east".equalsIgnoreCase(candidate.getScheme()) && "channel".equalsIgnoreCase(candidate.getHost())) {
            String raw = candidate.getQueryParameter("url");
            candidate = raw == null ? null : Uri.parse(raw.trim());
        }
        if (candidate != null && isHttpUri(candidate)) streamUri = candidate;
        else streamUri = null;
    }

    private boolean isHttpUri(Uri uri) {
        String scheme = uri.getScheme();
        return uri.getHost() != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
    }

    private void startPlaybackIfValid() {
        if (destroyed || paused) return;
        if (streamUri == null) {
            setStatus("● INVALID STREAM");
            showError("FadCam supplied an invalid stream URL.");
            return;
        }
        releasePlayer();
        try {
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(false)
                    .setConnectTimeoutMs(8000)
                    .setReadTimeoutMs(15000)
                    .setUserAgent("TV49East-FadCamReceiver/2");
            HashMap<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/vnd.apple.mpegurl,application/x-mpegURL,*/*;q=0.8");
            headers.put("Cache-Control", "no-cache");
            http.setDefaultRequestProperties(headers);

            MediaItem media = new MediaItem.Builder()
                    .setUri(streamUri)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(5000)
                            .setMinPlaybackSpeed(0.98f)
                            .setMaxPlaybackSpeed(1.02f)
                            .build())
                    .build();
            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15000, 45000, 1500, 3000)
                    .build();
            DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                    .setEnableDecoderFallback(true);
            ExoPlayer next = new ExoPlayer.Builder(this)
                    .setLoadControl(loadControl)
                    .setRenderersFactory(renderers)
                    .build();
            next.setMediaSource(new HlsMediaSource.Factory(http)
                    .setAllowChunklessPreparation(false)
                    .createMediaSource(media));
            next.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int playbackState) {
                    if (playbackState == Player.STATE_READY) {
                        bufferingSince = 0L;
                        lastPositionMs = next.getCurrentPosition();
                        lastWindowIndex = next.getCurrentMediaItemIndex();
                        retryCount = 0;
                        setStatus("● LIVE");
                        hideError();
                    } else if (playbackState == Player.STATE_BUFFERING) {
                        if (bufferingSince == 0L) bufferingSince = android.os.SystemClock.elapsedRealtime();
                        setStatus("● BUFFERING");
                    } else if (playbackState == Player.STATE_ENDED) {
                        scheduleRetry("Stream ended unexpectedly");
                    }
                }

                @Override public void onPlayerError(@NonNull PlaybackException playbackError) {
                    if (destroyed || paused || next != player) return;
                    if (playbackError.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                        try {
                            next.seekToDefaultPosition();
                            next.prepare();
                            next.play();
                            setStatus("● RESYNCING");
                            return;
                        } catch (Throwable ignored) { }
                    }
                    scheduleRetry("Connection lost • recovering…");
                }
            });
            player = next;
            playerView.setPlayer(next);
            bufferingSince = 0L;
            lastPositionMs = -1L;
            lastWindowIndex = -1;
            setStatus("● CONNECTING");
            hideError();
            next.prepare();
            next.play();
        } catch (Throwable t) {
            releasePlayer();
            scheduleRetry("Unable to start FadCam stream");
        }
    }

    private void inspectPlaybackHealth() {
        ExoPlayer current = player;
        if (current == null) return;
        try {
            int state = current.getPlaybackState();
            long position = current.getCurrentPosition();
            int window = current.getCurrentMediaItemIndex();
            if (state == Player.STATE_BUFFERING) {
                long now = android.os.SystemClock.elapsedRealtime();
                if (bufferingSince == 0L) bufferingSince = now;
                if (now - bufferingSince >= STALL_TIMEOUT_MS) {
                    scheduleRetry("Stream stalled • reconnecting…");
                    return;
                }
            } else if (state == Player.STATE_READY) {
                if (lastPositionMs == position && lastWindowIndex == window && !current.isPlaying()) {
                    return;
                }
                lastPositionMs = position;
                lastWindowIndex = window;
            }
        } catch (Throwable ignored) {
            scheduleRetry("Player health check failed • recovering…");
        }
    }

    private void scheduleRetry(String message) {
        if (destroyed || paused) return;
        if (retryCount >= MAX_RETRIES) {
            setStatus("● OFFLINE");
            showError("FadCam is temporarily unavailable. Tap the receiver to retry.");
            return;
        }
        retryCount++;
        long delay = Math.min(15000L, 1000L << Math.min(retryCount - 1, 4));
        setStatus("● RECONNECTING " + retryCount + "/" + MAX_RETRIES);
        showError(message);
        releasePlayer();
        main.removeCallbacksAndMessages(null);
        main.postDelayed(() -> {
            if (!destroyed && !paused) startPlaybackIfValid();
        }, delay);
        main.postDelayed(watchdog, WATCHDOG_INTERVAL_MS + delay);
    }

    private void releasePlayer() {
        if (playerView != null) {
            try { playerView.setPlayer(null); } catch (Throwable ignored) { }
        }
        ExoPlayer old = player;
        player = null;
        bufferingSince = 0L;
        if (old != null) {
            try { old.stop(); } catch (Throwable ignored) { }
            try { old.clearVideoSurface(); } catch (Throwable ignored) { }
            try { old.release(); } catch (Throwable ignored) { }
        }
    }

    private void setStatus(String value) { if (status != null) status.setText(value); }
    private void showError(String value) { if (error != null) { error.setText(value); error.setVisibility(View.VISIBLE); } }
    private void hideError() { if (error != null) error.setVisibility(View.GONE); }
}
