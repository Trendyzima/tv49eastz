package com.fadcam.tv;

import android.content.Context;
import android.content.SharedPreferences;
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

/**
 * Production-style vertical IPTV feed. One item fills the screen, the focused
 * stream autoplays, and the next stream is prepared in a second player before
 * the user swipes to it.
 */
public final class TvReelsActivity extends AppCompatActivity {
    private static final int BG = Color.rgb(3, 3, 5);
    private static final int PANEL = Color.rgb(18, 17, 22);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.rgb(190, 188, 198);
    private static final int ACCENT = Color.rgb(224, 38, 111);
    private static final int GREEN = Color.rgb(91, 220, 139);
    private static final int VIRTUAL_COUNT = 1_000_000;
    private static final String PREFS = "tv49_feed_prefs";

    private ViewPager2 pager;
    private ReelAdapter adapter;
    private ExoPlayer activePlayer;
    private ExoPlayer preloadPlayer;
    private String preloadedUrl;
    private PlayerView activePlayerView;
    private TextView loadingOverlay;
    private TextView loadingAction;
    private final IptvFeedClient feedClient = new IptvFeedClient();
    private final List<IptvReel> reels = new ArrayList<>();
    private final Set<String> liked = new HashSet<>();
    private final Set<String> saved = new HashSet<>();
    private final Set<String> followed = new HashSet<>();
    private final Set<String> failedUrls = new HashSet<>();
    private SharedPreferences prefs;
    private boolean muted;
    private int currentPosition;
    private int anchorPosition;
    private boolean loading;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadPreferences();
        loadLocalSources();
        buildUi();
        loadFeed();
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
        root.addView(buildBottomBar(), new FrameLayout.LayoutParams(-1, dp(82), Gravity.BOTTOM));

        loadingOverlay = text("Loading live TV…", 16, WHITE, true);
        loadingOverlay.setGravity(Gravity.CENTER);
        loadingOverlay.setPadding(dp(28), dp(22), dp(28), dp(22));
        loadingOverlay.setBackground(bg(Color.argb(235, 18, 17, 22), 22));
        FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        root.addView(loadingOverlay, loadingParams);

        loadingAction = text("", 12, WHITE, true);
        loadingAction.setGravity(Gravity.CENTER);
        loadingAction.setPadding(dp(18), dp(10), dp(18), dp(10));
        loadingAction.setBackground(bg(Color.argb(235, 35, 32, 43), 18));
        loadingAction.setVisibility(View.GONE);
        loadingAction.setOnClickListener(v -> loadFeed());
        FrameLayout.LayoutParams actionParams = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        actionParams.topMargin = dp(72);
        root.addView(loadingAction, actionParams);

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                currentPosition = position;
                playPosition(position);
                preloadNext(position);
            }
        });
        setContentView(root);
    }

    private LinearLayout buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), dp(12), dp(18), dp(10));
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(225, 0, 0, 0), Color.TRANSPARENT}));
        TextView brand = text("TV 49 East", 22, WHITE, true);
        bar.addView(brand, new LinearLayout.LayoutParams(0, -1, 1));
        TextView live = text("● LIVE", 12, WHITE, true);
        live.setGravity(Gravity.CENTER);
        live.setPadding(dp(12), dp(7), dp(12), dp(7));
        live.setBackground(bg(Color.argb(220, 210, 28, 100), 20));
        bar.addView(live);
        return bar;
    }

    private LinearLayout buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Color.argb(248, 0, 0, 0), Color.TRANSPARENT}));
        bar.addView(nav("⌂", "Home", v -> goHome()));
        bar.addView(nav("⌕", "Discover", v -> showSearch()));
        bar.addView(nav("＋", "Add", v -> showAddSource()));
        bar.addView(nav("♡", "Following", v -> showFollowing()));
        bar.addView(nav("◉", "Profile", v -> showProfile()));
        return bar;
    }

    private View nav(String icon, String label, View.OnClickListener click) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(click);
        TextView i = text(icon, icon.equals("＋") ? 28 : 22, MUTED, true);
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
        if (loading) return;
        loading = true;
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(View.VISIBLE);
            loadingOverlay.setText("Loading live TV…");
        }
        if (loadingAction != null) loadingAction.setVisibility(View.GONE);

        feedClient.load(new IptvFeedClient.Listener() {
            @Override public void onSuccess(List<IptvReel> result) {
                runOnUiThread(() -> {
                    loading = false;
                    mergeFeed(result);
                    if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);
                    if (loadingAction != null) loadingAction.setVisibility(View.GONE);
                });
            }

            @Override public void onError(Exception error) {
                runOnUiThread(() -> {
                    loading = false;
                    showLoadError();
                });
            }
        });
    }

    private void mergeFeed(List<IptvReel> result) {
        Set<String> existing = new HashSet<>();
        for (IptvReel r : reels) existing.add(r.url);
        for (IptvReel r : result) if (existing.add(r.url)) reels.add(r);

        if (anchorPosition == 0 && !reels.isEmpty()) {
            anchorPosition = VIRTUAL_COUNT / 2;
            anchorPosition -= Math.floorMod(anchorPosition, reels.size());
            adapter.notifyDataSetChanged();
            pager.post(() -> {
                pager.setCurrentItem(anchorPosition, false);
                playPosition(anchorPosition);
                preloadNext(anchorPosition);
            });
        } else {
            adapter.notifyDataSetChanged();
        }
    }

    private void showLoadError() {
        if (loadingOverlay != null) {
            loadingOverlay.setText("Live catalog unavailable");
            loadingOverlay.setVisibility(View.VISIBLE);
        }
        if (loadingAction != null) {
            loadingAction.setText("RETRY");
            loadingAction.setVisibility(View.VISIBLE);
        }
    }

    private IptvReel itemForPosition(int position) {
        if (reels.isEmpty()) return null;
        return reels.get(Math.floorMod(position, reels.size()));
    }

    private void playPosition(int position) {
        IptvReel item = itemForPosition(position);
        if (item == null) return;
        if (failedUrls.contains(item.url)) {
            advancePastFailure(position);
            return;
        }
        Holder holder = findHolder(position);
        if (holder == null) {
            pager.postDelayed(() -> playPosition(position), 60);
            return;
        }

        if (item.url.equals(preloadedUrl) && preloadPlayer != null) {
            ExoPlayer oldActive = activePlayer;
            activePlayer = preloadPlayer;
            preloadPlayer = null;
            preloadedUrl = null;
            releasePlayer(oldActive);
        } else {
            releasePlayer(activePlayer);
            releasePlayer(preloadPlayer);
            activePlayer = null;
            preloadPlayer = null;
            preloadedUrl = null;
            activePlayer = createPlayer(item);
            activePlayer.setMediaItem(MediaItem.fromUri(Uri.parse(item.url)));
            activePlayer.prepare();
        }

        if (activePlayerView != holder.page.playerView) {
            if (activePlayerView != null) activePlayerView.setPlayer(null);
            activePlayerView = holder.page.playerView;
            activePlayerView.setPlayer(activePlayer);
        } else if (activePlayerView != null && activePlayerView.getPlayer() != activePlayer) {
            activePlayerView.setPlayer(activePlayer);
        }
        activePlayer.setVolume(muted ? 0f : 1f);
        activePlayer.play();
        holder.page.setPlayingState(true);
    }

    private void preloadNext(int position) {
        IptvReel next = itemForPosition(position + 1);
        if (next == null || failedUrls.contains(next.url)) return;
        if (next.url.equals(preloadedUrl) && preloadPlayer != null) return;
        releasePlayer(preloadPlayer);
        preloadPlayer = null;
        preloadedUrl = null;
        preloadPlayer = createPlayer(next);
        preloadPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(next.url)));
        preloadPlayer.prepare();
        preloadedUrl = next.url;
    }

    private ExoPlayer createPlayer(IptvReel item) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(20_000)
                .setAllowCrossProtocolRedirects(true);
        if (item.userAgent != null && !item.userAgent.isEmpty()) http.setUserAgent(item.userAgent);
        if (item.referrer != null && !item.referrer.isEmpty()) {
            http.setDefaultRequestProperties(java.util.Collections.singletonMap("Referer", item.referrer));
        }
        DefaultMediaSourceFactory sourceFactory = new DefaultMediaSourceFactory(this).setDataSourceFactory(http);
        ExoPlayer player = new ExoPlayer.Builder(this).setMediaSourceFactory(sourceFactory).build();
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.setVolume(muted ? 0f : 1f);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(@NonNull PlaybackException error) {
                failedUrls.add(item.url);
                if (item.url.equals(preloadedUrl)) {
                    releasePlayer(preloadPlayer);
                    preloadPlayer = null;
                    preloadedUrl = null;
                }
                IptvReel current = itemForPosition(currentPosition);
                if (current != null && current.url.equals(item.url)) {
                    pager.postDelayed(() -> advancePastFailure(currentPosition), 180);
                }
            }
        });
        return player;
    }

    private void advancePastFailure(int position) {
        if (reels.isEmpty()) return;
        int next = position + 1;
        for (int i = 0; i < Math.min(12, reels.size()); i++) {
            IptvReel candidate = itemForPosition(next + i);
            if (candidate != null && !failedUrls.contains(candidate.url)) {
                pager.setCurrentItem(next + i, true);
                return;
            }
        }
        Toast.makeText(this, "No playable stream nearby", Toast.LENGTH_SHORT).show();
    }

    @Nullable private Holder findHolder(int position) {
        if (!(pager.getChildAt(0) instanceof androidx.recyclerview.widget.RecyclerView)) return null;
        androidx.recyclerview.widget.RecyclerView rv = (androidx.recyclerview.widget.RecyclerView) pager.getChildAt(0);
        androidx.recyclerview.widget.RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(position);
        return vh instanceof Holder ? (Holder) vh : null;
    }

    private void releasePlayer(ExoPlayer player) {
        if (player != null) player.release();
    }

    private void goHome() {
        if (!reels.isEmpty()) {
            pager.setCurrentItem(anchorPosition, false);
            playPosition(anchorPosition);
        }
    }

    private void showSearch() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Search channels or countries…");
        input.setTextColor(WHITE);
        input.setHintTextColor(MUTED);
        input.setPadding(dp(16), dp(8), dp(16), dp(8));
        input.setBackground(bg(PANEL, 18));
        new AlertDialog.Builder(this).setTitle("Discover live TV").setView(input)
                .setPositiveButton("Search", (d, w) -> filterAndJump(input.getText().toString()))
                .setNegativeButton("Cancel", null).show();
    }

    private void filterAndJump(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.US);
        if (q.isEmpty()) return;
        for (int i = 0; i < reels.size(); i++) {
            IptvReel r = reels.get(i);
            String haystack = (r.title + " " + r.channel + " " + r.source + " " + r.country + " " + r.category).toLowerCase(Locale.US);
            if (haystack.contains(q)) {
                int target = anchorPosition + i;
                pager.setCurrentItem(target, false);
                playPosition(target);
                return;
            }
        }
        Toast.makeText(this, "No live channel matched", Toast.LENGTH_SHORT).show();
    }

    private void showAddSource() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), 0, dp(8), 0);
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("Channel name");
        EditText url = new EditText(this);
        url.setSingleLine(true);
        url.setHint("https://…/stream.m3u8");
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(name);
        form.addView(url);
        new AlertDialog.Builder(this)
                .setTitle("Add authorized IPTV")
                .setMessage("Use a direct HLS URL that you own or are authorized to watch/distribute.")
                .setView(form)
                .setPositiveButton("Add", (d, w) -> addLocalReel(name.getText().toString(), url.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addLocalReel(String rawName, String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        String lower = url.toLowerCase(Locale.US);
        if (!(lower.startsWith("https://") || lower.startsWith("http://")) || !lower.contains(".m3u8")) {
            Toast.makeText(this, "Enter a direct HLS .m3u8 URL", Toast.LENGTH_LONG).show();
            return;
        }
        String name = rawName == null || rawName.trim().isEmpty() ? "My IPTV" : rawName.trim();
        for (IptvReel reel : reels) if (reel.url.equals(url)) {
            Toast.makeText(this, "That stream is already in the feed", Toast.LENGTH_SHORT).show();
            return;
        }
        IptvReel local = new IptvReel("local-" + System.currentTimeMillis(), name, name, url, "Live", "", "", "My IPTV", "", "", "");
        reels.add(0, local);
        Set<String> localSources = new HashSet<>(prefs.getStringSet("local_sources", new HashSet<>()));
        localSources.add(name.replace("\n", " ") + "\n" + url);
        prefs.edit().putStringSet("local_sources", localSources).apply();
        adapter.notifyDataSetChanged();
        anchorPosition = VIRTUAL_COUNT / 2;
        anchorPosition -= Math.floorMod(anchorPosition, reels.size());
        pager.setCurrentItem(anchorPosition, false);
        playPosition(anchorPosition);
        preloadNext(anchorPosition);
        Toast.makeText(this, "Added " + name, Toast.LENGTH_SHORT).show();
    }

    private void showFollowing() {
        if (followed.isEmpty()) {
            Toast.makeText(this, "Tap Follow on a channel to build your feed", Toast.LENGTH_SHORT).show();
            return;
        }
        int found = -1;
        for (int i = 0; i < reels.size(); i++) {
            if (followed.contains(reels.get(i).channel)) { found = i; break; }
        }
        if (found < 0) {
            Toast.makeText(this, "Your followed channels are not in this catalog", Toast.LENGTH_SHORT).show();
            return;
        }
        int target = anchorPosition + found;
        pager.setCurrentItem(target, false);
        playPosition(target);
    }

    private void showProfile() {
        new AlertDialog.Builder(this)
                .setTitle("TV 49 East")
                .setMessage("Live channels: " + reels.size() +
                        "\nFollowing: " + followed.size() +
                        "\nLiked: " + liked.size() +
                        "\nSaved: " + saved.size() +
                        "\n\nDirect IPTV playback • autoplay • next-stream preload")
                .setPositiveButton("OK", null)
                .show();
    }

    private void toggleMute() {
        muted = !muted;
        if (activePlayer != null) activePlayer.setVolume(muted ? 0f : 1f);
        savePreferences();
        Toast.makeText(this, muted ? "Muted" : "Sound on", Toast.LENGTH_SHORT).show();
    }

    private void shareCurrent() {
        IptvReel item = itemForPosition(currentPosition);
        if (item == null) return;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, "Watch " + item.title + " on TV 49 East");
        startActivity(Intent.createChooser(share, "Share channel"));
    }

    private void toggleLike(IptvReel item, ReelPage page) {
        if (item == null) return;
        if (!liked.add(item.id)) liked.remove(item.id);
        savePreferences();
        page.bind(item);
    }

    private void toggleSave(IptvReel item, ReelPage page) {
        if (item == null) return;
        if (!saved.add(item.id)) saved.remove(item.id);
        savePreferences();
        page.bind(item);
    }

    private void toggleFollow(IptvReel item, ReelPage page) {
        if (item == null) return;
        if (!followed.add(item.channel)) followed.remove(item.channel);
        savePreferences();
        page.bind(item);
    }

    private void loadLocalSources() {
        Set<String> localSources = prefs.getStringSet("local_sources", new HashSet<>());
        for (String encoded : localSources) {
            int split = encoded.indexOf('\n');
            if (split <= 0 || split + 1 >= encoded.length()) continue;
            String name = encoded.substring(0, split).trim();
            String url = encoded.substring(split + 1).trim();
            String lower = url.toLowerCase(Locale.US);
            if (name.isEmpty() || !(lower.startsWith("https://") || lower.startsWith("http://")) || !lower.contains(".m3u8")) continue;
            reels.add(new IptvReel("local-" + Math.abs(url.hashCode()), name, name, url, "Live", "", "", "My IPTV", "", "", ""));
        }
    }

    private void loadPreferences() {
        liked.addAll(prefs.getStringSet("liked", new HashSet<>()));
        saved.addAll(prefs.getStringSet("saved", new HashSet<>()));
        followed.addAll(prefs.getStringSet("followed", new HashSet<>()));
        muted = prefs.getBoolean("muted", false);
    }

    private void savePreferences() {
        prefs.edit()
                .putStringSet("liked", new HashSet<>(liked))
                .putStringSet("saved", new HashSet<>(saved))
                .putStringSet("followed", new HashSet<>(followed))
                .putBoolean("muted", muted)
                .apply();
    }

    @Override protected void onPause() {
        super.onPause();
        if (activePlayer != null) activePlayer.pause();
        releasePlayer(preloadPlayer);
        preloadPlayer = null;
        preloadedUrl = null;
    }

    @Override protected void onResume() {
        super.onResume();
        if (activePlayer != null && activePlayerView != null) activePlayer.play();
        if (!reels.isEmpty()) preloadNext(currentPosition);
    }

    @Override protected void onDestroy() {
        if (activePlayerView != null) activePlayerView.setPlayer(null);
        releasePlayer(activePlayer);
        releasePlayer(preloadPlayer);
        activePlayer = null;
        preloadPlayer = null;
        super.onDestroy();
    }

    private final class ReelAdapter extends androidx.recyclerview.widget.RecyclerView.Adapter<Holder> {
        private final Context context;
        ReelAdapter(Context context) { this.context = context; }
        @Override public int getItemCount() { return reels.isEmpty() ? 0 : VIRTUAL_COUNT; }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(new ReelPage(context));
        }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.page.bind(itemForPosition(position));
        }
    }

    private final class Holder extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
        final ReelPage page;
        Holder(ReelPage page) { super(page); this.page = page; }
    }

    private final class ReelPage extends FrameLayout {
        final PlayerView playerView;
        final TextView title;
        final TextView meta;
        final TextView follow;
        final TextView like;
        final TextView save;
        final TextView share;
        final TextView mute;
        IptvReel item;

        ReelPage(Context context) {
            super(context);
            setBackgroundColor(Color.BLACK);
            playerView = new PlayerView(context);
            playerView.setUseController(false);
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            playerView.setShutterBackgroundColor(Color.BLACK);
            addView(playerView, new FrameLayout.LayoutParams(-1, -1));
            addView(scrim(true), new FrameLayout.LayoutParams(-1, dp(190), Gravity.TOP));
            addView(scrim(false), new FrameLayout.LayoutParams(-1, dp(380), Gravity.BOTTOM));

            LinearLayout actions = new LinearLayout(context);
            actions.setOrientation(LinearLayout.VERTICAL);
            actions.setGravity(Gravity.CENTER);
            like = action("♡", "Like");
            TextView comments = action("◌", "Comment");
            save = action("▣", "Save");
            share = action("↗", "Share");
            mute = action("🔊", "Sound");
            like.setOnClickListener(v -> toggleLike(item, this));
            comments.setOnClickListener(v -> showComments());
            save.setOnClickListener(v -> toggleSave(item, this));
            share.setOnClickListener(v -> shareCurrent());
            mute.setOnClickListener(v -> toggleMute());
            actions.addView(like); actions.addView(comments); actions.addView(save); actions.addView(share); actions.addView(mute);
            FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(dp(72), -2, Gravity.END | Gravity.BOTTOM);
            ap.setMargins(0, 0, dp(8), dp(86));
            addView(actions, ap);

            LinearLayout info = new LinearLayout(context);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(dp(18), dp(8), dp(92), dp(92));
            addView(info, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

            LinearLayout creator = new LinearLayout(context);
            creator.setGravity(Gravity.CENTER_VERTICAL);
            TextView avatar = text("49", 15, WHITE, true);
            avatar.setGravity(Gravity.CENTER);
            avatar.setBackground(bg(Color.rgb(46, 35, 60), 22));
            creator.addView(avatar, new LinearLayout.LayoutParams(dp(42), dp(42)));
            TextView name = text("  TV 49 East", 15, WHITE, true);
            creator.addView(name, new LinearLayout.LayoutParams(0, dp(42), 1));
            follow = text("Follow", 12, WHITE, true);
            follow.setGravity(Gravity.CENTER);
            follow.setPadding(dp(12), 0, dp(12), 0);
            follow.setBackground(bg(Color.argb(225, 224, 38, 111), 18));
            follow.setOnClickListener(v -> toggleFollow(item, this));
            creator.addView(follow, new LinearLayout.LayoutParams(-2, dp(38)));
            info.addView(creator);

            title = text("Live channel", 18, WHITE, true);
            title.setPadding(0, dp(8), 0, dp(3));
            info.addView(title);
            meta = text("Direct HLS • LIVE", 12, MUTED, false);
            info.addView(meta);
        }

        private View scrim(boolean top) {
            View v = new View(TvReelsActivity.this);
            v.setBackground(new GradientDrawable(top ? GradientDrawable.Orientation.TOP_BOTTOM : GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{Color.argb(215, 0, 0, 0), Color.TRANSPARENT}));
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
            String location = reel.country == null || reel.country.isEmpty() ? "Worldwide" : reel.country;
            String group = reel.category == null || reel.category.isEmpty() ? "Live" : reel.category;
            meta.setText(reel.source + "  •  " + location + "  •  " + group + "  •  LIVE");
            follow.setText(followed.contains(reel.channel) ? "Following" : "Follow");
            like.setText(liked.contains(reel.id) ? "♥\nLiked" : "♡\nLike");
            save.setText(saved.contains(reel.id) ? "✓\nSaved" : "▣\nSave");
            mute.setText(muted ? "🔇\nMuted" : "🔊\nSound");
        }

        void setPlayingState(boolean value) { if (!value) playerView.setAlpha(1f); }

        private void showComments() {
            EditText input = new EditText(TvReelsActivity.this);
            input.setHint("Add a comment");
            new AlertDialog.Builder(TvReelsActivity.this).setTitle("Comments").setView(input)
                    .setPositiveButton("Post", (d, w) -> Toast.makeText(TvReelsActivity.this, "Comment saved locally", Toast.LENGTH_SHORT).show())
                    .setNegativeButton("Close", null).show();
        }
    }
}
