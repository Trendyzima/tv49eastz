package com.fadcam.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/** Crash-isolated, responsive IPTV reels surface. */
public final class TvReelsActivityHardened extends Activity {
    private static final int BG = Color.rgb(5, 5, 7);
    private static final int PANEL = Color.rgb(24, 19, 34);
    private static final int BUTTON = Color.rgb(43, 33, 59);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.rgb(190, 188, 198);
    private static final int ACCENT = Color.rgb(207, 186, 253);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<IptvReel> reels = new ArrayList<>();
    private final List<IptvReel> visible = new ArrayList<>();

    private ViewPager2 pager;
    private ReelAdapter adapter;
    private IptvFeedClientV2 feedClient;
    private ExoPlayer player;
    private TextureView activeTexture;
    private FrameLayout activeVideoContainer;
    private TextView status;
    private TextView feedMessage;
    private TextView retry;
    private View leftPanel;
    private View rightPanel;
    private TextView leftToggle;
    private TextView rightToggle;
    private int activePosition = RecyclerView.NO_POSITION;
    private boolean loading;
    private boolean destroyed;
    private boolean muted;
    private boolean leftOpen;
    private boolean rightOpen;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        destroyed = false;
        if (state != null) {
            leftOpen = state.getBoolean("left_open", false);
            rightOpen = state.getBoolean("right_open", false);
            muted = state.getBoolean("muted", false);
        }
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        try {
            feedClient = new IptvFeedClientV2(getApplicationContext());
            buildUi();
            main.postDelayed(this::loadInitialFeed, 250L);
        } catch (Throwable t) {
            showSafeFallback();
        }
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle out) {
        out.putBoolean("left_open", leftOpen);
        out.putBoolean("right_open", rightOpen);
        out.putBoolean("muted", muted);
        super.onSaveInstanceState(out);
    }

    @Override protected void onPause() {
        releasePlayer();
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        if (feedClient != null) feedClient.cancel();
        releasePlayer();
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        pager = new ViewPager2(this);
        pager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        pager.setOffscreenPageLimit(1);
        pager.setUserInputEnabled(true);
        adapter = new ReelAdapter();
        pager.setAdapter(adapter);
        root.addView(pager, new FrameLayout.LayoutParams(-1, -1));

        root.addView(buildTopBar(), new FrameLayout.LayoutParams(-1, dp(70), Gravity.TOP));
        root.addView(buildFeedState(), new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        leftPanel = buildLeftPanel();
        rightPanel = buildRightPanel();
        root.addView(leftPanel, panelParams(Gravity.LEFT));
        root.addView(rightPanel, panelParams(Gravity.RIGHT));

        leftToggle = edgeToggle("›", true);
        rightToggle = edgeToggle("‹", false);
        root.addView(leftToggle, edgeParams(Gravity.LEFT));
        root.addView(rightToggle, edgeParams(Gravity.RIGHT));
        setContentView(root);
        applyPanels(false);

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                releasePlayer();
                if (!visible.isEmpty() && position >= visible.size() - 2) loadMoreFeed();
                setStatus(visible.isEmpty() ? "● NO CHANNELS" : "● READY • " + (position + 1) + "/" + visible.size());
            }
        });
    }

    private LinearLayout buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), dp(8), dp(18), dp(8));
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(235, 0, 0, 0), Color.TRANSPARENT}));
        TextView title = text("TV 49 East", 20, WHITE, true);
        bar.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
        status = text("● LIVE TV", 11, WHITE, true);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(10), dp(5), dp(10), dp(5));
        status.setBackground(rounded(Color.argb(215, 72, 45, 95), 18));
        bar.addView(status);
        return bar;
    }

    private View buildFeedState() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(22), dp(18), dp(22), dp(18));
        box.setBackground(rounded(Color.argb(235, 24, 19, 34), 20));
        feedMessage = text("Loading live channels…", 15, WHITE, true);
        feedMessage.setGravity(Gravity.CENTER);
        box.addView(feedMessage, new LinearLayout.LayoutParams(-1, -2));
        retry = text("RETRY", 12, Color.BLACK, true);
        retry.setGravity(Gravity.CENTER);
        retry.setBackground(rounded(ACCENT, 16));
        retry.setOnClickListener(v -> loadInitialFeed());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(44));
        p.topMargin = dp(12);
        box.addView(retry, p);
        retry.setVisibility(View.GONE);
        return box;
    }

    private LinearLayout buildLeftPanel() {
        LinearLayout panel = basePanel();
        addTitle(panel, "NAVIGATION", "Hidden until you need it");
        addButton(panel, "⌂  Receiver home", v -> openReceiver());
        addButton(panel, "↻  Refresh channels", v -> loadInitialFeed());
        addButton(panel, "＋  Add authorized channel", v -> toast("Add authorized channel from the receiver"));
        addButton(panel, "×  Close Live TV", v -> finish());
        return panel;
    }

    private LinearLayout buildRightPanel() {
        LinearLayout panel = basePanel();
        addTitle(panel, "PLAYER", "Safe playback controls");
        addButton(panel, "▶  Play selected", v -> playPosition(pager.getCurrentItem()));
        addButton(panel, "↻  Retry selected", v -> retryCurrent());
        addButton(panel, "Mute / unmute", v -> toggleMute());
        addButton(panel, "□  Fit video to screen", v -> fitActiveVideo());
        return panel;
    }

    private LinearLayout basePanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(16), dp(16), dp(16), dp(16));
        p.setBackground(rounded(PANEL, 22));
        return p;
    }

    private void addTitle(LinearLayout panel, String title, String subtitle) {
        TextView a = text(title, 17, WHITE, true);
        a.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(a, new LinearLayout.LayoutParams(-1, dp(34)));
        TextView b = text(subtitle, 11, MUTED, false);
        panel.addView(b, new LinearLayout.LayoutParams(-1, dp(28)));
    }

    private void addButton(LinearLayout panel, String label, View.OnClickListener listener) {
        TextView b = text(label, 13, WHITE, true);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(13), 0, dp(13), 0);
        b.setBackground(rounded(BUTTON, 15));
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48));
        p.topMargin = dp(8);
        panel.addView(b, p);
    }

    private FrameLayout.LayoutParams panelParams(int gravity) {
        int width = Math.min(dp(320), Math.max(dp(210), (int)(getResources().getDisplayMetrics().widthPixels * 0.76f)));
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(width, -1, gravity);
        p.topMargin = dp(62);
        p.bottomMargin = dp(8);
        return p;
    }

    private FrameLayout.LayoutParams edgeParams(int gravity) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(42), dp(54), gravity | Gravity.CENTER_VERTICAL);
        p.setMargins(dp(4), 0, dp(4), 0);
        return p;
    }

    private TextView edgeToggle(String symbol, boolean left) {
        TextView t = text(symbol, 28, WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(Color.argb(225, 43, 33, 59), 17));
        t.setElevation(dp(5));
        t.setOnClickListener(v -> {
            if (left) leftOpen = !leftOpen; else rightOpen = !rightOpen;
            applyPanels(true);
        });
        return t;
    }

    private void applyPanels(boolean animate) {
        int leftDistance = leftPanel.getLayoutParams().width + dp(10);
        int rightDistance = rightPanel.getLayoutParams().width + dp(10);
        float lx = leftOpen ? 0f : -leftDistance;
        float rx = rightOpen ? 0f : rightDistance;
        if (animate) {
            leftPanel.animate().translationX(lx).setDuration(180).start();
            rightPanel.animate().translationX(rx).setDuration(180).start();
        } else {
            leftPanel.setTranslationX(lx);
            rightPanel.setTranslationX(rx);
        }
        leftToggle.setText(leftOpen ? "‹" : "›");
        rightToggle.setText(rightOpen ? "›" : "‹");
    }

    private void loadInitialFeed() {
        if (destroyed || loading || feedClient == null) return;
        loading = true;
        showFeedState("Loading live channels…", false);
        try {
            feedClient.loadInitial(new IptvFeedClientV2.Listener() {
                @Override public void onSuccess(List<IptvReel> batch) { main.post(() -> acceptBatch(batch, true)); }
                @Override public void onError(Exception error) { main.post(() -> feedError()); }
            });
        } catch (Throwable t) { feedError(); }
    }

    private void loadMoreFeed() {
        if (destroyed || loading || feedClient == null) return;
        loading = true;
        try {
            feedClient.loadMore(new IptvFeedClientV2.Listener() {
                @Override public void onSuccess(List<IptvReel> batch) { main.post(() -> acceptBatch(batch, false)); }
                @Override public void onError(Exception error) { main.post(() -> loading = false); }
            });
        } catch (Throwable t) { loading = false; }
    }

    private void acceptBatch(List<IptvReel> batch, boolean initial) {
        if (destroyed) return;
        loading = false;
        if (batch != null) for (IptvReel r : batch) addIfValid(r);
        if (adapter != null) adapter.notifyDataSetChanged();
        if (visible.isEmpty() && initial) feedError();
        else if (!visible.isEmpty()) {
            hideFeedState();
            setStatus("● READY • " + visible.size() + " CHANNELS");
        }
    }

    private void addIfValid(IptvReel reel) {
        if (reel == null || reel.url == null || !IptvFeedClientV2.isPlayableHls(reel.url)) return;
        String normalized = reel.url.trim();
        for (IptvReel old : reels) if (old != null && old.url != null && normalized.equals(old.url.trim())) return;
        reels.add(reel);
        visible.add(reel);
    }

    /** Only the selected page gets a video surface. No Media3 UI surface is created while browsing the catalog. */
    private void playPosition(int position) {
        if (destroyed || position < 0 || position >= visible.size()) return;
        IptvReel item = visible.get(position);
        if (item == null || !IptvFeedClientV2.isPlayableHls(item.url)) {
            channelError("Invalid IPTV stream");
            return;
        }
        ReelAdapter.Holder holder = findHolder(position);
        if (holder == null) {
            channelError("Video surface is not ready");
            return;
        }
        releasePlayer();
        try {
            Uri uri = Uri.parse(item.url.trim());
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("Bad HTTP URI");
            }

            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(8000)
                    .setReadTimeoutMs(15000)
                    .setUserAgent(nonEmpty(item.userAgent, "TV49East/4.0 Android"));
            if (item.referrer != null && !item.referrer.trim().isEmpty()) {
                HashMap<String, String> headers = new HashMap<>();
                headers.put("Referer", item.referrer.trim());
                http.setDefaultRequestProperties(headers);
            }

            MediaItem media = new MediaItem.Builder()
                    .setUri(uri)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(3000)
                            .setMinPlaybackSpeed(0.98f)
                            .setMaxPlaybackSpeed(1.02f)
                            .build())
                    .build();

            DefaultLoadControl control = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(12000, 30000, 1500, 3000)
                    .build();
            DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                    .setEnableDecoderFallback(true);
            ExoPlayer next = new ExoPlayer.Builder(this)
                    .setLoadControl(control)
                    .setRenderersFactory(renderers)
                    .build();
            next.setVolume(muted ? 0f : 1f);
            next.addListener(new Player.Listener() {
                @Override public void onPlayerError(@NonNull PlaybackException error) {
                    if (destroyed || next != player) return;
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                        try {
                            next.seekToDefaultPosition();
                            next.prepare();
                            next.play();
                            return;
                        } catch (Throwable ignored) { }
                    }
                    channelError("Channel unavailable • swipe to continue");
                    detach(holder);
                    if (player == next) releasePlayer();
                }
            });

            TextureView texture = new TextureView(this);
            texture.setOpaque(false);
            texture.setBackgroundColor(Color.BLACK);
            holder.videoContainer.removeAllViews();
            holder.videoContainer.addView(texture, new FrameLayout.LayoutParams(-1, -1));

            player = next;
            activePosition = position;
            activeTexture = texture;
            activeVideoContainer = holder.videoContainer;
            next.setVideoTextureView(texture);
            next.setMediaSource(new HlsMediaSource.Factory(http).createMediaSource(media));
            next.prepare();
            next.play();
            holder.play.setVisibility(View.GONE);
            setStatus("● LIVE • " + safe(item.title));
        } catch (Throwable t) {
            detach(holder);
            releasePlayer();
            channelError("Channel could not start • swipe to continue");
        }
    }

    private String nonEmpty(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value.trim(); }
    private String safe(String value) { return value == null ? "" : value; }

    private void retryCurrent() {
        int p = activePosition == RecyclerView.NO_POSITION ? pager.getCurrentItem() : activePosition;
        playPosition(p);
    }

    private void toggleMute() {
        muted = !muted;
        if (player != null) try { player.setVolume(muted ? 0f : 1f); } catch (Throwable ignored) { }
        toast(muted ? "Muted" : "Sound on");
    }

    private void fitActiveVideo() {
        if (activeTexture != null) activeTexture.setScaleX(1f);
        if (activeTexture != null) activeTexture.setScaleY(1f);
        toast("Video fitted to screen");
    }

    private void detach(ReelAdapter.Holder holder) {
        if (holder == null) return;
        try {
            if (holder.videoContainer != null) holder.videoContainer.removeAllViews();
            if (holder.play != null) holder.play.setVisibility(View.VISIBLE);
        } catch (Throwable ignored) { }
    }

    private void releasePlayer() {
        TextureView texture = activeTexture;
        activeTexture = null;
        activeVideoContainer = null;
        if (texture != null) {
            try { texture.setSurfaceTextureListener(null); } catch (Throwable ignored) { }
        }
        if (player != null) {
            ExoPlayer old = player;
            player = null;
            try { old.clearVideoSurface(); } catch (Throwable ignored) { }
            try { old.stop(); } catch (Throwable ignored) { }
            try { old.release(); } catch (Throwable ignored) { }
        }
        activePosition = RecyclerView.NO_POSITION;
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @Nullable private ReelAdapter.Holder findHolder(int position) {
        if (pager == null) return null;
        View child = pager.getChildAt(0);
        if (!(child instanceof RecyclerView)) return null;
        RecyclerView.ViewHolder holder = ((RecyclerView) child).findViewHolderForAdapterPosition(position);
        return holder instanceof ReelAdapter.Holder ? (ReelAdapter.Holder) holder : null;
    }

    private void feedError() {
        loading = false;
        setStatus("● FEED UNAVAILABLE");
        showFeedState("No live channels available right now.\nThe receiver is still safe.", true);
    }

    private void channelError(String message) {
        setStatus("● CHANNEL ERROR");
        toast(message);
    }

    private void setStatus(String value) { if (status != null) status.setText(value); }

    private void showFeedState(String value, boolean canRetry) {
        if (feedMessage == null || retry == null) return;
        feedMessage.setText(value);
        retry.setVisibility(canRetry ? View.VISIBLE : View.GONE);
        View parent = (View) feedMessage.getParent();
        if (parent != null) parent.setVisibility(View.VISIBLE);
    }

    private void hideFeedState() {
        if (feedMessage != null) {
            View parent = (View) feedMessage.getParent();
            if (parent != null) parent.setVisibility(View.GONE);
        }
    }

    private void showSafeFallback() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setBackgroundColor(BG);
        TextView title = text("Live TV is temporarily unavailable", 20, WHITE, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView detail = text("The optional player surface failed safely. Your receiver remains available.", 13, MUTED, false);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(0, dp(10), 0, dp(18));
        root.addView(detail, new LinearLayout.LayoutParams(-1, -2));
        TextView open = text("OPEN RECEIVER", 13, Color.BLACK, true);
        open.setGravity(Gravity.CENTER);
        open.setBackground(rounded(ACCENT, 18));
        open.setOnClickListener(v -> openReceiver());
        root.addView(open, new LinearLayout.LayoutParams(-1, dp(52)));
        setContentView(root);
    }

    private void openReceiver() {
        try { startActivity(new Intent(this, MainActivity.class)); finish(); }
        catch (Throwable t) { toast("Receiver could not be opened"); }
    }

    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }

    private final class ReelAdapter extends RecyclerView.Adapter<ReelAdapter.Holder> {
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout root = new FrameLayout(parent.getContext());
            root.setBackgroundColor(Color.BLACK);

            FrameLayout video = new FrameLayout(parent.getContext());
            video.setBackgroundColor(Color.BLACK);
            root.addView(video, new FrameLayout.LayoutParams(-1, -1));

            LinearLayout overlay = new LinearLayout(parent.getContext());
            overlay.setOrientation(LinearLayout.VERTICAL);
            overlay.setGravity(Gravity.BOTTOM);
            overlay.setPadding(dp(18), dp(40), dp(18), dp(90));
            overlay.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{Color.argb(215, 0, 0, 0), Color.TRANSPARENT}));
            TextView title = text("", 18, WHITE, true);
            overlay.addView(title, new LinearLayout.LayoutParams(-1, -2));
            TextView hint = text("Tap to play • swipe for next", 11, MUTED, false);
            hint.setPadding(0, dp(6), 0, 0);
            overlay.addView(hint, new LinearLayout.LayoutParams(-1, -2));
            root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

            TextView play = text("▶", 28, WHITE, true);
            play.setGravity(Gravity.CENTER);
            play.setBackground(rounded(Color.argb(175, 43, 33, 59), 40));
            root.addView(play, new FrameLayout.LayoutParams(dp(70), dp(70), Gravity.CENTER));
            return new Holder(root, video, title, play);
        }

        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            IptvReel item = visible.get(position);
            holder.title.setText(safe(item.title).isEmpty() ? safe(item.channel) : item.title);
            holder.play.setVisibility(activePosition == position && player != null ? View.GONE : View.VISIBLE);
            holder.play.setOnClickListener(v -> {
                int p = holder.getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) playPosition(p);
            });
            holder.itemView.setOnClickListener(v -> {
                int p = holder.getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) playPosition(p);
            });
        }

        @Override public void onViewRecycled(@NonNull Holder holder) {
            if (activePosition == holder.getBindingAdapterPosition()) releasePlayer();
            detach(holder);
            super.onViewRecycled(holder);
        }

        @Override public int getItemCount() { return visible.size(); }

        final class Holder extends RecyclerView.ViewHolder {
            final FrameLayout videoContainer;
            final TextView title;
            final TextView play;
            Holder(View root, FrameLayout videoContainer, TextView title, TextView play) {
                super(root);
                this.videoContainer = videoContainer;
                this.title = title;
                this.play = play;
            }
        }
    }
}