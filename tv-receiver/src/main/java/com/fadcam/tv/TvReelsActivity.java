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
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Production live-TV reel screen: vertical paging, autoplay, one-item look-ahead preload. */
public final class TvReelsActivity extends AppCompatActivity {
    private static final int BG = Color.rgb(5, 5, 7);
    private static final int PANEL = Color.rgb(18, 18, 23);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.rgb(190, 190, 198);

    private ViewPager2 pager;
    private ReelAdapter adapter;
    private final IptvFeedClientV2 feedClient = new IptvFeedClientV2(this);
    private final List<IptvReel> reels = new ArrayList<>();
    private final List<IptvReel> visible = new ArrayList<>();
    private final Set<String> liked = new HashSet<>();
    private final Set<String> saved = new HashSet<>();
    private final Set<String> followed = new HashSet<>();
    private final Map<Integer, ExoPlayer> players = new HashMap<>();

    private int currentPosition;
    private boolean muted;
    private boolean loading;
    private boolean followingOnly;
    private String activeQuery = "";
    private TextView status;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        Window w = getWindow();
        w.setStatusBarColor(Color.BLACK);
        w.setNavigationBarColor(Color.BLACK);
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        buildUi();
        loadFeed();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

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

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                currentPosition = position;
                activatePosition(position);
                if (!visible.isEmpty() && position >= visible.size() - 6) loadFeed();
            }
        });
        setContentView(root);
        handleIntent(getIntent());
    }

    private void handleIntent(@Nullable Intent intent) {
        if (intent == null) return;
        Uri uri = intent.getData();
        if (uri == null) return;
        if ("tv49east".equalsIgnoreCase(uri.getScheme()) && "channel".equalsIgnoreCase(uri.getHost())) {
            String url = uri.getQueryParameter("url");
            if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
                setStatus("CHANNEL REJECTED");
                return;
            }
            String name = uri.getQueryParameter("name");
            IptvReel reel = new IptvReel("handoff-" + url.hashCode(), "FadCam",
                    name == null || name.trim().isEmpty() ? "FadCam Live" : name.trim(),
                    url, "Live", "", "", "FadCam");
            reels.add(0, reel);
            rebuildVisible();
            adapter.notifyDataSetChanged();
            pager.post(() -> activatePosition(0));
        }
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

    private void loadFeed() {
        if (loading || followingOnly) return;
        loading = true;
        feedClient.load(new IptvFeedClientV2.Listener() {
            @Override public void onSuccess(List<IptvReel> result) {
                runOnUiThread(() -> {
                    loading = false;
                    Set<String> seen = new HashSet<>();
                    for (IptvReel r : reels) seen.add(r.url);
                    for (IptvReel r : result) if (seen.add(r.url)) reels.add(r);
                    rebuildVisible();
                    adapter.notifyDataSetChanged();
                    if (!visible.isEmpty()) pager.post(() -> activatePosition(currentPosition));
                    setStatus(reels.isEmpty() ? "NO STREAMS" : "LIVE • " + reels.size());
                });
            }

            @Override public void onError(Exception error) {
                runOnUiThread(() -> {
                    loading = false;
                    setStatus("LIVE • RETRY");
                    if (reels.isEmpty()) Toast.makeText(TvReelsActivity.this,
                            "Live catalog unavailable. Check your connection.", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void rebuildVisible() {
        visible.clear();
        String q = activeQuery.toLowerCase(Locale.US).trim();
        for (IptvReel r : reels) {
            if (followingOnly && !followed.contains(r.channel)) continue;
            if (!q.isEmpty() && !(r.title + " " + r.channel + " " + r.source).toLowerCase(Locale.US).contains(q)) continue;
            visible.add(r);
        }
    }

    private IptvReel itemForPosition(int position) {
        if (visible.isEmpty()) return null;
        return visible.get(Math.floorMod(position, visible.size()));
    }

    private void activatePosition(int position) {
        IptvReel item = itemForPosition(position);
        if (item == null) return;
        ReelAdapter.Holder holder = findHolder(position);
        if (holder == null) {
            pager.postDelayed(() -> activatePosition(position), 80);
            return;
        }
        releaseExcept(position, position + 1);
        ExoPlayer active = playerFor(position, item);
        holder.playerView.setPlayer(active);
        active.setVolume(muted ? 0f : 1f);
        active.play();
        preload(position + 1);
        updateStatus(item.title);
    }

    private void preload(int position) {
        IptvReel item = itemForPosition(position);
        if (item == null || players.containsKey(position)) return;
        ExoPlayer p = buildPlayer(false);
        p.setMediaSource(sourceFor(item));
        p.prepare();
        p.setPlayWhenReady(false);
        players.put(position, p);
    }

    private ExoPlayer playerFor(int position, IptvReel item) {
        ExoPlayer p = players.get(position);
        if (p != null) return p;
        p = buildPlayer(true);
        p.setMediaSource(sourceFor(item));
        p.prepare();
        players.put(position, p);
        return p;
    }

    private ExoPlayer buildPlayer(boolean active) {
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(1200, active ? 12000 : 5000, 700, 1200)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        ExoPlayer p = new ExoPlayer.Builder(this).setLoadControl(loadControl).build();
        p.setRepeatMode(Player.REPEAT_MODE_OFF);
        p.addListener(new Player.Listener() {
            @Override public void onPlayerError(@NonNull PlaybackException error) {
                if (p == players.get(currentPosition)) {
                    setStatus("STREAM ERROR • SKIPPING");
                    pager.postDelayed(() -> {
                        if (visible.size() > 1 && currentPosition == pager.getCurrentItem()) {
                            pager.setCurrentItem(currentPosition + 1, true);
                        }
                    }, 350);
                }
            }
        });
        return p;
    }

    private androidx.media3.exoplayer.source.MediaSource sourceFor(IptvReel item) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(15000);
        if (item.userAgent != null && !item.userAgent.trim().isEmpty()) http.setUserAgent(item.userAgent.trim());
        if (item.referrer != null && !item.referrer.trim().isEmpty()) {
            http.setDefaultRequestProperties(java.util.Collections.singletonMap("Referer", item.referrer.trim()));
        }
        MediaItem.LiveConfiguration live = new MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(3000)
                .setMinPlaybackSpeed(0.98f)
                .setMaxPlaybackSpeed(1.02f)
                .build();
        MediaItem media = new MediaItem.Builder().setUri(Uri.parse(item.url)).setLiveConfiguration(live).build();
        return new DefaultMediaSourceFactory(this).setDataSourceFactory(http).createMediaSource(media);
    }

    private void releaseExcept(int keepA, int keepB) {
        ArrayList<Integer> remove = new ArrayList<>();
        for (Integer key : players.keySet()) if (key != keepA && key != keepB) remove.add(key);
        for (Integer key : remove) {
            ExoPlayer p = players.remove(key);
            if (p != null) p.release();
        }
    }

    @Nullable private ReelAdapter.Holder findHolder(int position) {
        View child = pager.getChildAt(0);
        if (!(child instanceof androidx.recyclerview.widget.RecyclerView)) return null;
        androidx.recyclerview.widget.RecyclerView rv = (androidx.recyclerview.widget.RecyclerView) child;
        androidx.recyclerview.widget.RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(position);
        return vh instanceof ReelAdapter.Holder ? (ReelAdapter.Holder) vh : null;
    }

    private void setStatus(String value) { if (status != null) status.setText("● " + value); }
    private void updateStatus(String title) { setStatus("LIVE • " + title); }

    private void showHome() {
        followingOnly = false;
        activeQuery = "";
        rebuildVisible();
        adapter.notifyDataSetChanged();
        if (!visible.isEmpty()) pager.setCurrentItem(0, false);
        else loadFeed();
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
                    else Toast.makeText(this, "No live channel matched", Toast.LENGTH_SHORT).show();
                }).setNegativeButton("Cancel", null).show();
    }

    private void showAddSource() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("https://…/playlist.m3u8 or .m3u");
        input.setTextColor(WHITE);
        input.setHintTextColor(MUTED);
        new AlertDialog.Builder(this).setTitle("Add authorized IPTV source")
                .setMessage("Use a public or authorized M3U/M3U8 source. The APK plays the streams directly.")
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
                                Set<String> seen = new HashSet<>();
                                for (IptvReel r : reels) seen.add(r.url);
                                for (IptvReel r : result) if (seen.add(r.url)) reels.add(r);
                                activeQuery = "";
                                followingOnly = false;
                                rebuildVisible();
                                adapter.notifyDataSetChanged();
                                Toast.makeText(TvReelsActivity.this, result.size() + " channels added", Toast.LENGTH_SHORT).show();
                            });
                        }
                        @Override public void onError(Exception error) {
                            runOnUiThread(() -> Toast.makeText(TvReelsActivity.this, "Could not load source", Toast.LENGTH_LONG).show());
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
                .setMessage("Live channels: " + reels.size() + "\nFollowing: " + followed.size()
                        + "\nLiked: " + liked.size() + "\nSaved: " + saved.size()
                        + "\n\nPlayback: autoplay + next-channel preload\nLayout: vertical live reels")
                .setPositiveButton("OK", null).show();
    }

    private void toggleMute() {
        muted = !muted;
        for (ExoPlayer p : players.values()) p.setVolume(muted ? 0f : 1f);
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
        @Override public int getItemCount() { return visible.isEmpty() ? 0 : Integer.MAX_VALUE - 1024; }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) { return new Holder(new ReelPage(context)); }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) { holder.bind(itemForPosition(position)); }
        final class Holder extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
            final ReelPage page; final PlayerView playerView;
            Holder(ReelPage page) { super(page); this.page = page; this.playerView = page.playerView; }
            void bind(IptvReel item) { page.bind(item); }
        }
    }

    private final class ReelPage extends FrameLayout {
        final PlayerView playerView;
        final TextView title; final TextView meta; final TextView follow; final TextView like; final TextView save; final TextView mute;
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
            actions.setOrientation(LinearLayout.VERTICAL); actions.setGravity(Gravity.CENTER);
            like = action("♡", "Like");
            TextView comment = action("◌", "Info");
            save = action("▣", "Save");
            TextView share = action("↗", "Share");
            mute = action("🔊", "Sound");
            like.setOnClickListener(v -> { toggleLike(item); bind(item); });
            comment.setOnClickListener(v -> showProfile());
            save.setOnClickListener(v -> { toggleSave(item); bind(item); });
            share.setOnClickListener(v -> TvReelsActivity.this.share(item));
            mute.setOnClickListener(v -> { toggleMute(); bind(item); });
            actions.addView(like); actions.addView(comment); actions.addView(save); actions.addView(share); actions.addView(mute);
            FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(dp(72), -2, Gravity.END | Gravity.BOTTOM);
            ap.setMargins(0, 0, dp(8), dp(82));
            addView(actions, ap);

            LinearLayout info = new LinearLayout(context);
            info.setOrientation(LinearLayout.VERTICAL); info.setPadding(dp(18), dp(8), dp(92), dp(90));
            addView(info, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            LinearLayout creator = new LinearLayout(context); creator.setGravity(Gravity.CENTER_VERTICAL);
            TextView avatar = text("49", 15, WHITE, true); avatar.setGravity(Gravity.CENTER); avatar.setBackground(bg(Color.rgb(45,35,60),22));
            creator.addView(avatar, new LinearLayout.LayoutParams(dp(42), dp(42)));
            TextView name = text("  TV 49 East", 15, WHITE, true); creator.addView(name, new LinearLayout.LayoutParams(0, dp(42), 1));
            follow = text("Follow", 12, WHITE, true); follow.setGravity(Gravity.CENTER); follow.setPadding(dp(12),0,dp(12),0); follow.setBackground(bg(Color.argb(225,235,61,126),18));
            follow.setOnClickListener(v -> { toggleFollow(item); bind(item); }); creator.addView(follow, new LinearLayout.LayoutParams(-2, dp(38))); info.addView(creator);
            title = text("Live channel",18,WHITE,true); title.setPadding(0,dp(8),0,dp(3)); info.addView(title);
            meta = text("Public live stream",12,MUTED,false); info.addView(meta);
        }

        private View scrim(boolean top) {
            View v = new View(TvReelsActivity.this);
            v.setBackground(new GradientDrawable(top ? GradientDrawable.Orientation.TOP_BOTTOM : GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{Color.argb(220,0,0,0),Color.TRANSPARENT}));
            return v;
        }

        private TextView action(String icon, String label) {
            TextView v = text(icon + "\n" + label,13,WHITE,true); v.setGravity(Gravity.CENTER); v.setPadding(0,dp(5),0,dp(5)); v.setMinHeight(dp(58)); return v;
        }

        void bind(IptvReel reel) {
            item = reel; if (reel == null) return;
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
        for (ExoPlayer p : players.values()) p.pause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!players.isEmpty()) activatePosition(currentPosition);
    }

    @Override protected void onDestroy() {
        for (ExoPlayer p : players.values()) p.release();
        players.clear();
        super.onDestroy();
    }
}
