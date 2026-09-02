package com.fadcam.tv;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Stable full-screen vertical IPTV feed. Keeps one active decoder and one look-ahead player. */
public final class TvReelsActivity extends AppCompatActivity {
    private static final int BG = Color.rgb(5, 5, 7);
    private static final int PANEL = Color.rgb(18, 18, 23);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.rgb(190, 190, 198);
    private static final int ACCENT = Color.rgb(235, 61, 126);

    private ViewPager2 pager;
    private ReelAdapter adapter;
    private final IptvFeedClientV2 feedClient = new IptvFeedClientV2(this);
    private final List<IptvReel> reels = new ArrayList<>();
    private final List<IptvReel> visible = new ArrayList<>();
    private final Set<String> liked = new HashSet<>();
    private final Set<String> saved = new HashSet<>();
    private final Set<String> followed = new HashSet<>();

    private ExoPlayer activePlayer;
    private ExoPlayer preloadPlayer;
    private int preloadPosition = -1;
    private int currentPosition;
    private boolean muted;
    private boolean loading;
    private boolean followingOnly;
    private boolean destroyed;
    private String activeQuery = "";
    private long lastLoadAt;
    private TextView status;
    private TextView feedMessage;
    private TextView retry;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        Window w = getWindow();
        w.setStatusBarColor(Color.BLACK);
        w.setNavigationBarColor(Color.BLACK);
        buildUi();
        // Do not start network/decoder work inside Activity construction. Let the first
        // frame render before the IPTV catalog and Media3 are touched.
        pager.postDelayed(() -> loadFeed(true), 250);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return v;
    }

    private GradientDrawable bg(int color, int radius) {
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
        adapter = new ReelAdapter(this);
        pager.setAdapter(adapter);
        root.addView(pager, new FrameLayout.LayoutParams(-1, -1));
        root.addView(buildTopBar(), new FrameLayout.LayoutParams(-1, dp(78), Gravity.TOP));
        root.addView(buildBottomBar(), new FrameLayout.LayoutParams(-1, dp(78), Gravity.BOTTOM));
        root.addView(buildFeedState(), new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                currentPosition = position;
                activatePosition(position);
                if (!visible.isEmpty() && position >= visible.size() - 5) loadFeed(false);
            }
        });
        setContentView(root);
        handleIntent(getIntent());
    }

    private View buildFeedState() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(24), dp(18), dp(24), dp(18));
        box.setBackground(bg(Color.argb(225, 18, 18, 23), 22));
        feedMessage = text("Loading live channels…", 15, WHITE, true);
        feedMessage.setGravity(Gravity.CENTER);
        box.addView(feedMessage);
        retry = text("Retry", 13, WHITE, true);
        retry.setGravity(Gravity.CENTER);
        retry.setPadding(dp(18), dp(10), dp(18), dp(10));
        retry.setBackground(bg(ACCENT, 18));
        retry.setOnClickListener(v -> loadFeed(true));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, dp(44));
        rp.topMargin = dp(12);
        box.addView(retry, rp);
        retry.setVisibility(View.GONE);
        return box;
    }

    private LinearLayout buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), dp(10), dp(18), dp(8));
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(225, 0, 0, 0), Color.TRANSPARENT}));
        TextView brand = text("TV 49 East", 21, WHITE, true);
        bar.addView(brand, new LinearLayout.LayoutParams(0, -1, 1));
        status = text("● LIVE", 12, WHITE, true);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(12), dp(7), dp(12), dp(7));
        status.setBackground(bg(Color.argb(215, 200, 35, 90), 20));
        bar.addView(status);
        return bar;
    }

    private LinearLayout buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(8), dp(5), dp(8), dp(7));
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Color.argb(245, 0, 0, 0), Color.TRANSPARENT}));
        bar.addView(nav("⌂", "Home", v -> showHome()));
        bar.addView(nav("⌕", "Discover", v -> showSearch()));
        bar.addView(nav("＋", "Add", v -> showAddSource()));
        bar.addView(nav("♡", "Following", v -> showFollowing()));
        bar.addView(nav("◉", "Profile", v -> showProfile()));
        return bar;
    }

    private View nav(String icon, String label, View.OnClickListener listener) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(listener);
        TextView i = text(icon, icon.equals("＋") ? 27 : 22, WHITE, true);
        i.setGravity(Gravity.CENTER);
        TextView t = text(label, 9, MUTED, false);
        t.setGravity(Gravity.CENTER);
        item.addView(i, new LinearLayout.LayoutParams(-1, dp(34)));
        item.addView(t, new LinearLayout.LayoutParams(-1, dp(20)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
        p.setMargins(dp(2), 0, dp(2), 0);
        item.setLayoutParams(p);
        return item;
    }

    private void handleIntent(@Nullable Intent intent) {
        if (intent == null || intent.getData() == null) return;
        Uri uri = intent.getData();
        if (!"tv49east".equalsIgnoreCase(uri.getScheme()) || !"channel".equalsIgnoreCase(uri.getHost())) return;
        String url = uri.getQueryParameter("url");
        if (!IptvFeedClientV2.isPlayableHls(url)) {
            setStatus("CHANNEL REJECTED");
            return;
        }
        String name = uri.getQueryParameter("name");
        addIfNew(new IptvReel("handoff-" + url.hashCode(), "FadCam",
                name == null || name.trim().isEmpty() ? "FadCam Live" : name.trim(),
                url, "Live", "", "", "FadCam"), true);
    }

    private void loadFeed(boolean force) {
        if (destroyed) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (loading || (!force && now - lastLoadAt < 8000) || followingOnly) return;
        loading = true;
        lastLoadAt = now;
        if (visible.isEmpty()) showFeedState("Loading live channels…", false);
        try {
            feedClient.load(new IptvFeedClientV2.Listener() {
                @Override public void onSuccess(List<IptvReel> result) {
                    runOnUiThread(() -> {
                        if (destroyed) return;
                        loading = false;
                        if (result != null) for (IptvReel r : result) addIfNew(r, false);
                        rebuildVisible();
                        adapter.notifyDataSetChanged();
                        if (!visible.isEmpty()) {
                            hideFeedState();
                            pager.post(() -> activatePosition(currentPosition));
                            setStatus("LIVE • " + visible.size());
                        }
                    });
                }

                @Override public void onError(Exception error) {
                    runOnUiThread(() -> {
                        if (destroyed) return;
                        loading = false;
                        setStatus("LIVE • RETRY");
                        if (visible.isEmpty()) showFeedState("No playable live channels were returned.", true);
                    });
                }
            });
        } catch (RuntimeException error) {
            loading = false;
            setStatus("LIVE • RETRY");
            if (visible.isEmpty()) showFeedState("Unable to load the live catalog.", true);
        }
    }

    private void addIfNew(IptvReel reel, boolean first) {
        if (reel == null || !IptvFeedClientV2.isPlayableHls(reel.url)) return;
        for (IptvReel existing : reels) if (existing.url.equals(reel.url)) return;
        if (first) reels.add(0, reel); else reels.add(reel);
    }

    private void rebuildVisible() {
        visible.clear();
        String q = activeQuery.toLowerCase(Locale.US).trim();
        for (IptvReel r : reels) {
            if (followingOnly && !followed.contains(r.channel)) continue;
            String searchable = (r.title + " " + r.channel + " " + r.source).toLowerCase(Locale.US);
            if (!q.isEmpty() && !searchable.contains(q)) continue;
            visible.add(r);
        }
    }

    private IptvReel itemForPosition(int position) {
        return visible.isEmpty() ? null : visible.get(Math.floorMod(position, visible.size()));
    }

    private void activatePosition(int position) {
        if (destroyed || pager == null || visible.isEmpty()) return;
        IptvReel item = itemForPosition(position);
        if (item == null) return;
        ReelAdapter.Holder holder = findHolder(position);
        if (holder == null) {
            pager.postDelayed(() -> activatePosition(position), 120);
            return;
        }
        try {
            ExoPlayer next = takePreloaded(position, item);
            if (activePlayer != null && activePlayer != next) {
                activePlayer.stop();
                activePlayer.release();
            }
            activePlayer = next;
            holder.playerView.setPlayer(activePlayer);
            activePlayer.setVolume(muted ? 0f : 1f);
            activePlayer.play();
            prepareNext(position + 1);
            updateStatus(item.title);
        } catch (RuntimeException error) {
            // A bad upstream stream must never terminate the Activity.
            if (activePlayer != null) {
                try { activePlayer.release(); } catch (RuntimeException ignored) { }
                activePlayer = null;
            }
            holder.playerView.setPlayer(null);
            setStatus("STREAM ERROR • SWIPE");
        }
    }

    private ExoPlayer takePreloaded(int position, IptvReel item) {
        if (preloadPlayer != null && preloadPosition == position) {
            ExoPlayer result = preloadPlayer;
            preloadPlayer = null;
            preloadPosition = -1;
            return result;
        }
        releasePreload();
        ExoPlayer result = buildPlayer();
        result.setMediaSource(sourceFor(item));
        result.prepare();
        return result;
    }

    private void prepareNext(int position) {
        if (destroyed || visible.isEmpty()) return;
        IptvReel item = itemForPosition(position);
        if (item == null) return;
        if (preloadPlayer != null && preloadPosition == position) return;
        releasePreload();
        try {
            ExoPlayer p = buildPlayer();
            p.setMediaSource(sourceFor(item));
            p.prepare();
            p.setPlayWhenReady(false);
            preloadPlayer = p;
            preloadPosition = position;
        } catch (RuntimeException error) {
            releasePreload();
        }
    }

    private ExoPlayer buildPlayer() {
        // Deliberately use Media3's validated defaults here. Custom buffer values were
        // able to crash the release binary before the first frame on some Media3 builds.
        ExoPlayer p = new ExoPlayer.Builder(this).build();
        p.setRepeatMode(Player.REPEAT_MODE_OFF);
        p.addListener(new Player.Listener() {
            @Override public void onPlayerError(@NonNull PlaybackException error) {
                if (p == activePlayer && !destroyed && visible.size() > 1) {
                    setStatus("STREAM ERROR • NEXT");
                    pager.postDelayed(() -> {
                        if (!destroyed && pager != null) pager.setCurrentItem(pager.getCurrentItem() + 1, true);
                    }, 200);
                }
            }
        });
        return p;
    }

    private androidx.media3.exoplayer.source.MediaSource sourceFor(IptvReel item) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(7000)
                .setReadTimeoutMs(12000)
                .setUserAgent(item.userAgent == null || item.userAgent.trim().isEmpty()
                        ? "TV49East/3.0 Android" : item.userAgent.trim());
        if (item.referrer != null && !item.referrer.trim().isEmpty()) {
            http.setDefaultRequestProperties(java.util.Collections.singletonMap("Referer", item.referrer.trim()));
        }
        MediaItem.LiveConfiguration live = new MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(2500)
                .setMinPlaybackSpeed(0.98f)
                .setMaxPlaybackSpeed(1.02f)
                .build();
        MediaItem media = new MediaItem.Builder()
                .setUri(Uri.parse(item.url))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .setLiveConfiguration(live)
                .build();
        return new DefaultMediaSourceFactory(this)
                .setDataSourceFactory(http)
                .createMediaSource(media);
    }

    private void releasePreload() {
        if (preloadPlayer != null) {
            try { preloadPlayer.release(); } catch (RuntimeException ignored) { }
            preloadPlayer = null;
        }
        preloadPosition = -1;
    }

    @Nullable private ReelAdapter.Holder findHolder(int position) {
        View child = pager.getChildAt(0);
        if (!(child instanceof androidx.recyclerview.widget.RecyclerView)) return null;
        androidx.recyclerview.widget.RecyclerView rv = (androidx.recyclerview.widget.RecyclerView) child;
        androidx.recyclerview.widget.RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(position);
        return vh instanceof ReelAdapter.Holder ? (ReelAdapter.Holder) vh : null;
    }

    private void showFeedState(String message, boolean canRetry) {
        if (feedMessage != null) feedMessage.setText(message);
        if (retry != null) retry.setVisibility(canRetry ? View.VISIBLE : View.GONE);
        if (feedMessage != null && feedMessage.getParent() instanceof View) {
            ((View) feedMessage.getParent()).setVisibility(View.VISIBLE);
        }
    }

    private void hideFeedState() {
        if (feedMessage != null && feedMessage.getParent() instanceof View) {
            ((View) feedMessage.getParent()).setVisibility(View.GONE);
        }
    }

    private void setStatus(String value) {
        if (status != null) status.setText("● " + value);
    }

    private void updateStatus(String title) {
        setStatus("LIVE • " + title);
    }

    private void showHome() {
        followingOnly = false;
        activeQuery = "";
        rebuildVisible();
        adapter.notifyDataSetChanged();
        if (!visible.isEmpty()) pager.setCurrentItem(0, false); else loadFeed(true);
    }

    private void showSearch() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Channel, station, country…");
        input.setTextColor(WHITE);
        input.setHintTextColor(MUTED);
        input.setPadding(dp(16), dp(8), dp(16), dp(8));
        input.setBackground(bg(PANEL, 18));
        new AlertDialog.Builder(this).setTitle("Discover live TV").setView(input)
                .setPositiveButton("Search", (d, w) -> {
                    activeQuery = input.getText().toString().trim();
                    followingOnly = false;
                    rebuildVisible();
                    adapter.notifyDataSetChanged();
                    if (!visible.isEmpty()) pager.setCurrentItem(0, false);
                    else showFeedState("No live channel matched.", false);
                }).setNegativeButton("Cancel", null).show();
    }

    private void showAddSource() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("https://…/playlist.m3u or .m3u8");
        input.setTextColor(WHITE);
        input.setHintTextColor(MUTED);
        new AlertDialog.Builder(this).setTitle("Add authorized IPTV source")
                .setMessage("Add a public or authorized M3U/M3U8 source. Streams play directly on this device.")
                .setView(input)
                .setPositiveButton("Add", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        Toast.makeText(this, "Enter a valid HTTP(S) playlist URL", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    feedClient.loadSource(url, "Added source", new IptvFeedClientV2.Listener() {
                        @Override public void onSuccess(List<IptvReel> result) {
                            runOnUiThread(() -> {
                                if (result != null) for (IptvReel r : result) addIfNew(r, false);
                                activeQuery = "";
                                followingOnly = false;
                                rebuildVisible();
                                adapter.notifyDataSetChanged();
                                hideFeedState();
                                if (!visible.isEmpty()) pager.setCurrentItem(0, false);
                            });
                        }
                        @Override public void onError(Exception error) {
                            runOnUiThread(() -> Toast.makeText(TvReelsActivity.this,
                                    "No playable HLS channels found", Toast.LENGTH_LONG).show());
                        }
                    });
                }).setNegativeButton("Cancel", null).show();
    }

    private void showFollowing() {
        followingOnly = true;
        activeQuery = "";
        rebuildVisible();
        adapter.notifyDataSetChanged();
        if (visible.isEmpty()) {
            followingOnly = false;
            rebuildVisible();
            Toast.makeText(this, "Follow channels from the reel to build this list", Toast.LENGTH_SHORT).show();
            return;
        }
        pager.setCurrentItem(0, false);
    }

    private void showProfile() {
        new AlertDialog.Builder(this).setTitle("TV 49 East")
                .setMessage("Live channels: " + reels.size()
                        + "\nFollowing: " + followed.size()
                        + "\nLiked: " + liked.size()
                        + "\nSaved: " + saved.size()
                        + "\n\nAutoplay: ON\nPreload: next channel\nFeed: vertical infinite paging")
                .setPositiveButton("OK", null).show();
    }

    private void toggleMute() {
        muted = !muted;
        if (activePlayer != null) activePlayer.setVolume(muted ? 0f : 1f);
        if (preloadPlayer != null) preloadPlayer.setVolume(0f);
    }

    private void toggleLike(IptvReel item) { if (item != null && !liked.add(item.id)) liked.remove(item.id); }
    private void toggleSave(IptvReel item) { if (item != null && !saved.add(item.id)) saved.remove(item.id); }
    private void toggleFollow(IptvReel item) { if (item != null && !followed.add(item.channel)) followed.remove(item.channel); }

    private void share(IptvReel item) {
        if (item == null) return;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, "Watch " + item.title + " on TV 49 East");
        startActivity(Intent.createChooser(share, "Share channel"));
    }

    private final class ReelAdapter extends androidx.recyclerview.widget.RecyclerView.Adapter<ReelAdapter.Holder> {
        private final Context context;
        ReelAdapter(Context context) { this.context = context; }
        @Override public int getItemCount() { return visible.isEmpty() ? 0 : Integer.MAX_VALUE; }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(new ReelPage(context));
        }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(itemForPosition(position));
        }
        final class Holder extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
            final ReelPage page;
            final PlayerView playerView;
            Holder(ReelPage page) { super(page); this.page = page; this.playerView = page.playerView; }
            void bind(IptvReel item) { page.bind(item); }
        }
    }

    private final class ReelPage extends FrameLayout {
        final PlayerView playerView;
        final TextView title;
        final TextView meta;
        final TextView follow;
        final TextView like;
        final TextView save;
        final TextView mute;
        IptvReel item;

        ReelPage(Context context) {
            super(context);
            setBackgroundColor(Color.BLACK);
            playerView = new PlayerView(context);
            playerView.setUseController(false);
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            playerView.setShutterBackgroundColor(Color.BLACK);
            playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
            addView(playerView, new FrameLayout.LayoutParams(-1, -1));
            addView(scrim(true), new FrameLayout.LayoutParams(-1, dp(190), Gravity.TOP));
            addView(scrim(false), new FrameLayout.LayoutParams(-1, dp(370), Gravity.BOTTOM));

            LinearLayout actions = new LinearLayout(context);
            actions.setOrientation(LinearLayout.VERTICAL);
            actions.setGravity(Gravity.CENTER);
            like = action("♡", "Like");
            TextView info = action("◌", "Info");
            save = action("▣", "Save");
            TextView share = action("↗", "Share");
            mute = action("🔊", "Sound");
            like.setOnClickListener(v -> { toggleLike(item); bind(item); });
            info.setOnClickListener(v -> showProfile());
            save.setOnClickListener(v -> { toggleSave(item); bind(item); });
            share.setOnClickListener(v -> TvReelsActivity.this.share(item));
            mute.setOnClickListener(v -> { toggleMute(); bind(item); });
            actions.addView(like);
            actions.addView(info);
            actions.addView(save);
            actions.addView(share);
            actions.addView(mute);
            FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(dp(72), -2, Gravity.END | Gravity.BOTTOM);
            ap.setMargins(0, 0, dp(8), dp(82));
            addView(actions, ap);

            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(18), dp(8), dp(92), dp(90));
            addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

            LinearLayout creator = new LinearLayout(context);
            creator.setGravity(Gravity.CENTER_VERTICAL);
            TextView avatar = text("49", 15, WHITE, true);
            avatar.setGravity(Gravity.CENTER);
            avatar.setBackground(bg(Color.rgb(45, 35, 60), 22));
            creator.addView(avatar, new LinearLayout.LayoutParams(dp(42), dp(42)));
            TextView name = text("  TV 49 East", 15, WHITE, true);
            creator.addView(name, new LinearLayout.LayoutParams(0, dp(42), 1));
            follow = text("Follow", 12, WHITE, true);
            follow.setGravity(Gravity.CENTER);
            follow.setPadding(dp(12), 0, dp(12), 0);
            follow.setBackground(bg(ACCENT, 18));
            follow.setOnClickListener(v -> { toggleFollow(item); bind(item); });
            creator.addView(follow, new LinearLayout.LayoutParams(-2, dp(38)));
            content.addView(creator);
            title = text("Live channel", 18, WHITE, true);
            title.setPadding(0, dp(8), 0, dp(3));
            content.addView(title);
            meta = text("Public live stream", 12, MUTED, false);
            content.addView(meta);
        }

        private View scrim(boolean top) {
            View v = new View(TvReelsActivity.this);
            v.setBackground(new GradientDrawable(top ? GradientDrawable.Orientation.TOP_BOTTOM : GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{Color.argb(220, 0, 0, 0), Color.TRANSPARENT}));
            return v;
        }

        private TextView action(String icon, String label) {
            TextView v = text(icon + "\n" + label, 13, WHITE, true);
            v.setGravity(Gravity.CENTER);
            v.setPadding(0, dp(5), 0, dp(5));
            v.setMinHeight(dp(58));
            return v;
        }

        void bind(IptvReel reel) {
            item = reel;
            if (reel == null) return;
            title.setText(reel.title);
            meta.setText(reel.source + "  •  " + reel.quality + "  •  LIVE");
            follow.setText(followed.contains(reel.channel) ? "Following" : "Follow");
            like.setText(liked.contains(reel.id) ? "♥\nLiked" : "♡\nLike");
            save.setText(saved.contains(reel.id) ? "✓\nSaved" : "▣\nSave");
            mute.setText(muted ? "🔇\nMuted" : "🔊\nSound");
        }
    }

    @Override protected void onPause() {
        super.onPause();
        if (activePlayer != null) activePlayer.pause();
        if (preloadPlayer != null) preloadPlayer.pause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!destroyed && activePlayer != null && !visible.isEmpty()) {
            pager.post(() -> activatePosition(currentPosition));
        }
    }

    @Override protected void onDestroy() {
        destroyed = true;
        releasePreload();
        if (activePlayer != null) {
            try { activePlayer.release(); } catch (RuntimeException ignored) { }
            activePlayer = null;
        }
        super.onDestroy();
    }
}
