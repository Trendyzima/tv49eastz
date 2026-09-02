package com.fadcam.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Production-hardened Live TV surface.
 *
 * Design rules:
 *  - Network/catalog work is asynchronous and bounded.
 *  - No player is created while the feed is loading.
 *  - A maximum of one ExoPlayer exists at a time.
 *  - A bad IPTV stream is a channel error, never an Activity crash.
 *  - Left/right controls are independent, collapsible overlays.
 *  - Player uses FIT sizing so portrait and landscape preserve the stream aspect ratio.
 */
public final class TvReelsActivityHardened extends Activity {
    private static final int BG = Color.rgb(5, 5, 7);
    private static final int PANEL = Color.rgb(24, 19, 34);
    private static final int PANEL_2 = Color.rgb(39, 30, 54);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.rgb(190, 188, 198);
    private static final int ACCENT = Color.rgb(207, 186, 253);
    private static final int ERROR = Color.rgb(245, 100, 110);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<IptvReel> reels = new ArrayList<>();
    private final List<IptvReel> visible = new ArrayList<>();
    private final Set<String> followed = new HashSet<>();

    private ViewPager2 pager;
    private ReelAdapter adapter;
    private IptvFeedClientV2 feedClient;
    private ExoPlayer player;
    private PlayerView playerView;
    private int activePosition = RecyclerView.NO_POSITION;
    private boolean destroyed;
    private boolean loading;
    private boolean muted;
    private boolean leftOpen;
    private boolean rightOpen;
    private boolean followingOnly;
    private String query = "";
    private TextView status;
    private TextView message;
    private TextView retry;
    private View leftPanel;
    private View rightPanel;
    private TextView leftToggle;
    private TextView rightToggle;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        destroyed = false;
        if (state != null) {
            leftOpen = state.getBoolean("left_open", false);
            rightOpen = state.getBoolean("right_open", false);
            muted = state.getBoolean("muted", false);
            followingOnly = state.getBoolean("following_only", false);
            query = state.getString("query", "");
        }
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        try {
            feedClient = new IptvFeedClientV2(getApplicationContext());
            buildUi();
            main.postDelayed(this::loadInitialFeed, 250L);
        } catch (Throwable t) {
            showFatalSurface();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        out.putBoolean("left_open", leftOpen);
        out.putBoolean("right_open", rightOpen);
        out.putBoolean("muted", muted);
        out.putBoolean("following_only", followingOnly);
        out.putString("query", query);
        super.onSaveInstanceState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null && !destroyed && activePosition != RecyclerView.NO_POSITION) {
            try { player.play(); } catch (Throwable ignored) { }
        }
    }

    @Override
    protected void onPause() {
        pausePlayer();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        releasePlayer();
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
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
        pager.setUserInputEnabled(true);
        pager.setOffscreenPageLimit(1);
        adapter = new ReelAdapter();
        pager.setAdapter(adapter);
        root.addView(pager, new FrameLayout.LayoutParams(-1, -1));

        root.addView(buildTopStatus(), new FrameLayout.LayoutParams(-1, dp(72), Gravity.TOP));
        root.addView(buildFeedMessage(), centeredParams());

        leftPanel = buildLeftPanel();
        rightPanel = buildRightPanel();
        root.addView(leftPanel, panelParams(Gravity.LEFT));
        root.addView(rightPanel, panelParams(Gravity.RIGHT));

        leftToggle = edgeToggle("‹", true);
        rightToggle = edgeToggle("›", false);
        root.addView(leftToggle, edgeToggleParams(Gravity.LEFT));
        root.addView(rightToggle, edgeToggleParams(Gravity.RIGHT));

        setContentView(root);
        applyPanelState(false);

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                releasePlayer();
                activePosition = RecyclerView.NO_POSITION;
                if (!visible.isEmpty() && position >= visible.size() - 2) loadMoreFeed();
                updateStatus(position);
            }
        });
    }

    private FrameLayout.LayoutParams centeredParams() {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        p.leftMargin = dp(46);
        p.rightMargin = dp(46);
        return p;
    }

    private FrameLayout.LayoutParams panelParams(int gravity) {
        int width = Math.min(dp(320), Math.max(dp(220), (int) (getResources().getDisplayMetrics().widthPixels * 0.78f)));
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(width, -1, gravity);
        p.topMargin = dp(62);
        p.bottomMargin = dp(10);
        return p;
    }

    private FrameLayout.LayoutParams edgeToggleParams(int gravity) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(44), dp(56), gravity | Gravity.CENTER_VERTICAL);
        p.setMargins(dp(4), 0, dp(4), 0);
        return p;
    }

    private TextView edgeToggle(String symbol, boolean left) {
        TextView t = text(symbol, 30, WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(Color.argb(225, 39, 30, 54), 18));
        t.setElevation(dp(6));
        t.setOnClickListener(v -> {
            if (left) leftOpen = !leftOpen; else rightOpen = !rightOpen;
            applyPanelState(true);
        });
        return t;
    }

    private LinearLayout buildTopStatus() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), dp(8), dp(18), dp(8));
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(235, 0, 0, 0), Color.TRANSPARENT}));

        TextView title = text("TV 49 East", 20, WHITE, true);
        bar.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
        status = text("● LIVE TV", 11, WHITE, true);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(10), dp(6), dp(10), dp(6));
        status.setBackground(rounded(Color.argb(210, 72, 45, 95), 18));
        bar.addView(status);
        return bar;
    }

    private LinearLayout buildFeedMessage() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(22), dp(18), dp(22), dp(18));
        box.setBackground(rounded(Color.argb(235, 24, 19, 34), 20));

        message = text("Loading live channels…", 15, WHITE, true);
        message.setGravity(Gravity.CENTER);
        box.addView(message, new LinearLayout.LayoutParams(-1, -2));

        retry = text("RETRY", 12, Color.BLACK, true);
        retry.setGravity(Gravity.CENTER);
        retry.setBackground(rounded(ACCENT, 16));
        retry.setPadding(dp(18), dp(8), dp(18), dp(8));
        retry.setOnClickListener(v -> loadInitialFeed());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, dp(44));
        rp.topMargin = dp(12);
        box.addView(retry, rp);
        retry.setVisibility(View.GONE);
        return box;
    }

    private LinearLayout buildLeftPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(18), dp(18), dp(18));
        panel.setBackground(rounded(Color.argb(245, 24, 19, 34), 24));
        addPanelTitle(panel, "LIVE TV", "Navigation");
        addAction(panel, "⌂  Home", v -> openReceiverHome());
        addAction(panel, "⌕  Discover", v -> showInfo("Discover", "Use the IPTV feed sources from the TV catalog."));
        addAction(panel, "♡  Following", v -> {
            followingOnly = !followingOnly;
            rebuildVisible();
            adapter.notifyDataSetChanged();
            showInfo("Following", followingOnly ? "Showing followed channels." : "Showing all channels.");
        });
        addAction(panel, "＋  Add channel", v -> showInfo("Add channel", "Authorized channel sources can be added from the receiver.");
        return panel;
    }

    private LinearLayout buildRightPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(18), dp(18), dp(18));
        panel.setBackground(rounded(Color.argb(245, 24, 19, 34), 24));
        addPanelTitle(panel, "PLAYER", "Playback controls");
        addAction(panel, "🔇  Mute / unmute", v -> toggleMute());
        addAction(panel, "↻  Retry channel", v -> retryCurrent());
        addAction(panel, "□  Fit screen", v -> {
            if (playerView != null) playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        });
        addAction(panel, "▣  Open receiver", v -> openReceiverHome());
        addAction(panel, "×  Close Live TV", v -> finish());
        return panel;
    }

    private void addPanelTitle(LinearLayout panel, String title, String subtitle) {
        TextView a = text(title, 18, WHITE, true);
        panel.addView(a, new LinearLayout.LayoutParams(-1, dp(34)));
        TextView b = text(subtitle, 12, MUTED, false);
        panel.addView(b, new LinearLayout.LayoutParams(-1, dp(30)));
    }

    private void addAction(LinearLayout panel, String label, View.OnClickListener listener) {
        TextView b = text(label, 14, WHITE, true);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setBackground(rounded(PANEL_2, 16));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(50));
        p.topMargin = dp(9);
        panel.addView(b, p);
    }

    private void applyPanelState(boolean animate) {
        if (leftPanel == null || rightPanel == null) return;
        int distanceLeft = leftPanel.getLayoutParams().width + dp(12);
        int distanceRight = rightPanel.getLayoutParams().width + dp(12);
        float leftTarget = leftOpen ? 0f : -distanceLeft;
        float rightTarget = rightOpen ? 0f : distanceRight;
        if (animate) {
            leftPanel.animate().translationX(leftTarget).setDuration(180).start();
            rightPanel.animate().translationX(rightTarget).setDuration(180).start();
        } else {
            leftPanel.setTranslationX(leftTarget);
            rightPanel.setTranslationX(rightTarget);
        }
        if (leftToggle != null) leftToggle.setText(leftOpen ? "‹" : "›");
        if (rightToggle != null) rightToggle.setText(rightOpen ? "›" : "‹");
    }

    private void loadInitialFeed() {
        if (destroyed || loading || feedClient == null) return;
        loading = true;
        showMessage("Loading live channels…", false);
        try {
            feedClient.loadInitial(new IptvFeedClientV2.Listener() {
                @Override public void onSuccess(List<IptvReel> batch) {
                    main.post(() -> acceptBatch(batch, true));
                }
                @Override public void onError(Exception error) {
                    main.post(() -> feedError());
                }
            });
        } catch (Throwable t) {
            feedError();
        }
    }

    private void loadMoreFeed() {
        if (destroyed || loading || followingOnly || feedClient == null) return;
        loading = true;
        try {
            feedClient.loadMore(new IptvFeedClientV2.Listener() {
                @Override public void onSuccess(List<IptvReel> batch) { main.post(() -> acceptBatch(batch, false)); }
                @Override public void onError(Exception error) { main.post(() -> loading = false); }
            });
        } catch (Throwable t) {
            loading = false;
        }
    }

    private void acceptBatch(List<IptvReel> batch, boolean initial) {
        if (destroyed) return;
        loading = false;
        if (batch != null) {
            for (IptvReel r : batch) addIfValid(r);
        }
        rebuildVisible();
        adapter.notifyDataSetChanged();
        if (visible.isEmpty()) {
            if (initial) feedError();
        } else {
            hideMessage();
            updateStatus(pager.getCurrentItem());
        }
    }

    private void addIfValid(IptvReel r) {
        if (r == null || r.url == null || !IptvFeedClientV2.isPlayableHls(r.url)) return;
        String url = r.url.trim();
        for (IptvReel existing : reels) if (existing != null && url.equals(existing.url)) return;
        reels.add(r);
    }

    private void rebuildVisible() {
        visible.clear();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.US);
        for (IptvReel r : reels) {
            if (r == null) continue;
            if (followingOnly && !followed.contains(safe(r.channel))) continue;
            String searchable = (safe(r.title) + " " + safe(r.channel) + " " + safe(r.source)).toLowerCase(Locale.US);
            if (!q.isEmpty() && !searchable.contains(q)) continue;
            visible.add(r);
        }
    }

    private String safe(String value) { return value == null ? "" : value; }

    /** Starts Media3 HLS playback only after an explicit user tap. */
    private void playPosition(int position) {
        if (destroyed || position < 0 || position >= visible.size()) return;
        IptvReel item = visible.get(position);
        if (item == null || !IptvFeedClientV2.isPlayableHls(item.url)) {
            showChannelError("Invalid channel URL");
            return;
        }
        ReelAdapter.Holder holder = findHolder(position);
        if (holder == null) {
            showChannelError("Player surface is not ready");
            return;
        }

        releasePlayer();
        try {
            Uri uri = Uri.parse(item.url.trim());
            if (uri.getScheme() == null || uri.getHost() == null) throw new IllegalArgumentException("Invalid stream URI");

            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(8000)
                    .setReadTimeoutMs(15000)
                    .setUserAgent(nonEmpty(item.userAgent, "TV49East/4.0 Android"));
            if (!safe(item.referrer).trim().isEmpty()) {
                java.util.HashMap<String, String> headers = new java.util.HashMap<>();
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

            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(12000, 30000, 1500, 3000)
                    .build();

            ExoPlayer next = new ExoPlayer.Builder(this)
                    .setLoadControl(loadControl)
                    .build();
            next.setRepeatMode(Player.REPEAT_MODE_OFF);
            next.setVolume(muted ? 0f : 1f);
            next.addListener(new Player.Listener() {
                @Override public void onPlayerError(@NonNull PlaybackException error) {
                    if (destroyed || next != player) return;
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                        try {
                            next.seekToDefaultPosition();
                            next.prepare();
                            next.play();
                            setStatus("● LIVE • RECONNECTING");
                            return;
                        } catch (Throwable ignored) { }
                    }
                    showChannelError("Channel unavailable • swipe to continue");
                    safeDetach(holder);
                }
            });

            player = next;
            activePosition = position;
            playerView = holder.playerView;
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            playerView.setPlayer(next);
            next.setMediaSource(new HlsMediaSource.Factory(http).createMediaSource(media));
            next.prepare();
            next.play();
            setStatus("● LIVE • " + safe(item.title));
            hideMessage();
        } catch (Throwable t) {
            safeDetach(holder);
            releasePlayer();
            showChannelError("Channel could not start • swipe to continue");
        }
    }

    private String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private void safeDetach(ReelAdapter.Holder holder) {
        try { if (holder != null && holder.playerView != null) holder.playerView.setPlayer(null); } catch (Throwable ignored) { }
    }

    private void retryCurrent() {
        int position = activePosition == RecyclerView.NO_POSITION ? pager.getCurrentItem() : activePosition;
        if (position >= 0 && position < visible.size()) playPosition(position);
    }

    private void toggleMute() {
        muted = !muted;
        if (player != null) {
            try { player.setVolume(muted ? 0f : 1f); } catch (Throwable ignored) { }
        }
        Toast.makeText(this, muted ? "Muted" : "Sound on", Toast.LENGTH_SHORT).show();
    }

    private void pausePlayer() {
        if (player != null) try { player.pause(); } catch (Throwable ignored) { }
    }

    private void releasePlayer() {
        if (playerView != null) {
            try { playerView.setPlayer(null); } catch (Throwable ignored) { }
        }
        playerView = null;
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) { }
            try { player.release(); } catch (Throwable ignored) { }
        }
        player = null;
        activePosition = RecyclerView.NO_POSITION;
    }

    @Nullable
    private ReelAdapter.Holder findHolder(int position) {
        if (pager == null) return null;
        View child = pager.getChildAt(0);
        if (!(child instanceof RecyclerView)) return null;
        RecyclerView rv = (RecyclerView) child;
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(position);
        return vh instanceof ReelAdapter.Holder ? (ReelAdapter.Holder) vh : null;
    }

    private void updateStatus(int position) {
        if (status == null) return;
        if (visible.isEmpty()) setStatus("● NO CHANNELS");
        else if (position >= 0 && position < visible.size()) setStatus("● READY • " + (position + 1) + "/" + visible.size());
        else setStatus("● LIVE TV • " + visible.size());
    }

    private void setStatus(String value) {
        if (status != null) status.setText(value);
    }

    private void showMessage(String value, boolean canRetry) {
        if (message == null) return;
        message.setText(value);
        retry.setVisibility(canRetry ? View.VISIBLE : View.GONE);
        View parent = (View) message.getParent();
        if (parent != null) parent.setVisibility(View.VISIBLE);
    }

    private void hideMessage() {
        if (message == null) return;
        View parent = (View) message.getParent();
        if (parent != null) parent.setVisibility(View.GONE);
    }

    private void feedError() {
        if (destroyed) return;
        loading = false;
        setStatus("● FEED UNAVAILABLE");
        showMessage("No live channels are available right now.\nYour receiver is still safe.", true);
    }

    private void showChannelError(String value) {
        setStatus("● ERROR");
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private void showFatalSurface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setBackgroundColor(BG);
        TextView title = text("Live TV is temporarily unavailable", 20, WHITE, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView detail = text("The player surface could not start. Nothing was streamed and the receiver remains available.", 13, MUTED, false);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(0, dp(10), 0, dp(18));
        root.addView(detail, new LinearLayout.LayoutParams(-1, -2));
        TextView open = text("OPEN RECEIVER", 13, Color.BLACK, true);
        open.setGravity(Gravity.CENTER);
        open.setBackground(rounded(ACCENT, 18));
        open.setOnClickListener(v -> openReceiverHome());
        root.addView(open, new LinearLayout.LayoutParams(-1, dp(52)));
        setContentView(root);
    }

    private void openReceiverHome() {
        try {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        } catch (Throwable t) {
            Toast.makeText(this, "Receiver could not be opened", Toast.LENGTH_LONG).show();
        }
    }

    private void showInfo(String title, String body) {
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(body)
                .setPositiveButton("OK", null)
                .create();
        try { dialog.show(); } catch (Throwable ignored) { }
    }

    private final class ReelAdapter extends RecyclerView.Adapter<ReelAdapter.Holder> {
        @NonNull
        @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout root = new FrameLayout(parent.getContext());
            root.setBackgroundColor(Color.BLACK);
            PlayerView pv = new PlayerView(parent.getContext());
            pv.setUseController(false);
            pv.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            pv.setBackgroundColor(Color.BLACK);
            root.addView(pv, new FrameLayout.LayoutParams(-1, -1));

            LinearLayout shade = new LinearLayout(parent.getContext());
            shade.setOrientation(LinearLayout.VERTICAL);
            shade.setGravity(Gravity.BOTTOM);
            shade.setPadding(dp(20), dp(40), dp(20), dp(94));
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{Color.argb(215, 0, 0, 0), Color.TRANSPARENT}));
            TextView title = text("", 19, WHITE, true);
            shade.addView(title, new LinearLayout.LayoutParams(-1, -2));
            TextView hint = text("Tap to play • swipe for next channel", 12, MUTED, false);
            hint.setPadding(0, dp(7), 0, 0);
            shade.addView(hint, new LinearLayout.LayoutParams(-1, -2));
            root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

            TextView play = text("▶", 30, WHITE, true);
            play.setGravity(Gravity.CENTER);
            play.setBackground(rounded(Color.argb(175, 35, 28, 48), 40));
            FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER);
            root.addView(play, pp);
            return new Holder(root, pv, title, play);
        }

        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            IptvReel item = visible.get(position);
            holder.title.setText(safe(item.title).isEmpty() ? safe(item.channel) : item.title);
            holder.play.setVisibility(View.VISIBLE);
            holder.play.setOnClickListener(v -> playPosition(holder.getBindingAdapterPosition()));
            holder.itemView.setOnClickListener(v -> {
                int p = holder.getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) playPosition(p);
            });
        }

        @Override public void onViewRecycled(@NonNull Holder holder) {
            safeDetach(holder);
            super.onViewRecycled(holder);
        }

        @Override public int getItemCount() { return visible.size(); }

        final class Holder extends RecyclerView.ViewHolder {
            final PlayerView playerView;
            final TextView title;
            final TextView play;
            Holder(View root, PlayerView playerView, TextView title, TextView play) {
                super(root);
                this.playerView = playerView;
                this.title = title;
                this.play = play;
            }
        }
    }
}
