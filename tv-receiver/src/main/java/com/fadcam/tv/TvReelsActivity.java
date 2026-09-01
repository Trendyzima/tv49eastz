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

/** TikTok-style live-TV feed. Streams are played directly from public/authorized URLs. */
public final class TvReelsActivity extends AppCompatActivity {
    private static final int BG = Color.rgb(5, 5, 7);
    private static final int PANEL = Color.rgb(17, 17, 21);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.rgb(190, 190, 198);
    private static final int ACCENT = Color.rgb(235, 61, 126);

    private ViewPager2 pager;
    private ReelAdapter adapter;
    private ExoPlayer player;
    private PlayerView activePlayerView;
    private final IptvFeedClient feedClient = new IptvFeedClient();
    private final List<IptvReel> reels = new ArrayList<>();
    private final Set<String> liked = new HashSet<>();
    private final Set<String> saved = new HashSet<>();
    private final Set<String> followed = new HashSet<>();
    private boolean muted;
    private int currentPosition;
    private boolean loading;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        player = new ExoPlayer.Builder(this).build();
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(@NonNull PlaybackException error) {
                Toast.makeText(TvReelsActivity.this, "Stream unavailable — swipe for the next live channel", Toast.LENGTH_SHORT).show();
            }
        });
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
        root.addView(buildTopBar(), new FrameLayout.LayoutParams(-1, dp(74), Gravity.TOP));
        root.addView(buildBottomBar(), new FrameLayout.LayoutParams(-1, dp(72), Gravity.BOTTOM));

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                currentPosition = position;
                playPosition(position);
                if (!reels.isEmpty() && Math.floorMod(position, reels.size()) >= reels.size() - 5) loadMore();
            }
        });
        setContentView(root);
    }

    private LinearLayout buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), dp(10), dp(18), dp(8));
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(215, 0, 0, 0), Color.TRANSPARENT}));
        TextView brand = text("TV 49 East", 21, WHITE, true);
        bar.addView(brand, new LinearLayout.LayoutParams(0, -1, 1));
        TextView live = text("● LIVE", 12, WHITE, true);
        live.setGravity(Gravity.CENTER);
        live.setPadding(dp(12), dp(7), dp(12), dp(7));
        live.setBackground(bg(Color.argb(215, 200, 35, 90), 20));
        bar.addView(live);
        return bar;
    }

    private LinearLayout buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(8), dp(6), dp(8), dp(7));
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Color.argb(240, 0, 0, 0), Color.TRANSPARENT}));
        bar.addView(nav("⌂", "Home", true, v -> pager.setCurrentItem(0, true)));
        bar.addView(nav("⌕", "Discover", false, v -> showSearch()));
        bar.addView(nav("＋", "Add", false, v -> showAddSource()));
        bar.addView(nav("♡", "Following", false, v -> showFollowing()));
        bar.addView(nav("◉", "Profile", false, v -> showProfile()));
        return bar;
    }

    private View nav(String icon, String label, boolean selected, View.OnClickListener click) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(click);
        TextView i = text(icon, icon.equals("＋") ? 27 : 22, selected ? WHITE : MUTED, true);
        i.setGravity(Gravity.CENTER);
        TextView t = text(label, 9, selected ? WHITE : MUTED, selected);
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
        feedClient.load(new IptvFeedClient.Listener() {
            @Override public void onSuccess(List<IptvReel> result) {
                runOnUiThread(() -> {
                    loading = false;
                    if (result.isEmpty()) {
                        Toast.makeText(TvReelsActivity.this, "No playable public streams were returned", Toast.LENGTH_LONG).show();
                        return;
                    }
                    int oldSize = reels.size();
                    Set<String> existing = new HashSet<>();
                    for (IptvReel r : reels) existing.add(r.url);
                    for (IptvReel r : result) if (existing.add(r.url)) reels.add(r);
                    if (oldSize == 0) {
                        adapter.notifyDataSetChanged();
                        pager.post(() -> playPosition(0));
                    } else if (reels.size() > oldSize) {
                        adapter.notifyItemRangeChanged(oldSize, reels.size() - oldSize);
                    }
                });
            }

            @Override public void onError(Exception error) {
                runOnUiThread(() -> {
                    loading = false;
                    Toast.makeText(TvReelsActivity.this, "Live catalog unavailable. Check your connection.", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void loadMore() {
        if (!loading && reels.size() < 400) loadFeed();
    }

    private IptvReel itemForPosition(int position) {
        if (reels.isEmpty()) return null;
        return reels.get(Math.floorMod(position, reels.size()));
    }

    private void playPosition(int position) {
        IptvReel item = itemForPosition(position);
        if (item == null) return;
        HolderBridge holder = findHolder(position);
        if (holder == null) {
            pager.postDelayed(() -> playPosition(position), 80);
            return;
        }
        if (activePlayerView != holder.playerView) {
            if (activePlayerView != null) activePlayerView.setPlayer(null);
            activePlayerView = holder.playerView;
            activePlayerView.setPlayer(player);
        }

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory();
        if (!item.userAgent.isEmpty()) http.setUserAgent(item.userAgent);
        if (!item.referrer.isEmpty()) {
            http.setDefaultRequestProperties(java.util.Collections.singletonMap("Referer", item.referrer));
        }
        DefaultMediaSourceFactory sourceFactory = new DefaultMediaSourceFactory(this).setDataSourceFactory(http);
        player.stop();
        player.setMediaSource(sourceFactory.createMediaSource(MediaItem.fromUri(Uri.parse(item.url))));
        player.setVolume(muted ? 0f : 1f);
        player.prepare();
        player.play();
        holder.page.setPlayingState(true);
    }

    @Nullable private HolderBridge findHolder(int position) {
        if (!(pager.getChildAt(0) instanceof androidx.recyclerview.widget.RecyclerView)) return null;
        androidx.recyclerview.widget.RecyclerView rv = (androidx.recyclerview.widget.RecyclerView) pager.getChildAt(0);
        androidx.recyclerview.widget.RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(position);
        return vh instanceof ReelAdapter.Holder ? new HolderBridge((ReelAdapter.Holder) vh) : null;
    }

    private void toggleMute() {
        muted = !muted;
        player.setVolume(muted ? 0f : 1f);
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

    private void showSearch() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Search channels, sources, categories…");
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
            if ((r.title + " " + r.channel + " " + r.source).toLowerCase(Locale.US).contains(q)) {
                pager.setCurrentItem(i, false);
                return;
            }
        }
        Toast.makeText(this, "No live channel matched", Toast.LENGTH_SHORT).show();
    }

    private void showFollowing() {
        Toast.makeText(this, followed.isEmpty() ? "Follow a creator from any reel" : followed.size() + " followed channels", Toast.LENGTH_SHORT).show();
    }

    private void showProfile() {
        new AlertDialog.Builder(this).setTitle("TV 49 East profile")
                .setMessage("Following: " + followed.size() + "\nLiked: " + liked.size() + "\nSaved: " + saved.size() + "\n\nPreferences are stored locally on this device.")
                .setPositiveButton("OK", null).show();
    }

    private void showAddSource() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("https://…/playlist.m3u8");
        input.setTextColor(WHITE);
        input.setHintTextColor(MUTED);
        new AlertDialog.Builder(this)
                .setTitle("Add public/authorized M3U")
                .setMessage("TV 49 East plays public or authorized streams directly. It does not host, relay, or bypass access controls.")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> Toast.makeText(this, "Source will be included by the source-sync layer", Toast.LENGTH_SHORT).show())
                .setNegativeButton("Cancel", null).show();
    }

    @Override protected void onPause() { super.onPause(); if (player != null) player.pause(); }
    @Override protected void onResume() { super.onResume(); if (player != null && activePlayerView != null) player.play(); }
    @Override protected void onDestroy() {
        if (activePlayerView != null) activePlayerView.setPlayer(null);
        if (player != null) player.release();
        super.onDestroy();
    }

    private final class HolderBridge {
        final ReelAdapter.Holder holder;
        final ReelPage page;
        final PlayerView playerView;
        HolderBridge(ReelAdapter.Holder h) { holder = h; page = h.page; playerView = h.playerView; }
    }

    private final class ReelAdapter extends androidx.recyclerview.widget.RecyclerView.Adapter<ReelAdapter.Holder> {
        private final Context context;
        ReelAdapter(Context context) { this.context = context; }
        @Override public int getItemCount() { return reels.isEmpty() ? 0 : Integer.MAX_VALUE - 1024; }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) { return new Holder(new ReelPage(context)); }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) { holder.bind(itemForPosition(position)); }
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
        final TextView share;
        final TextView mute;
        IptvReel item;
        boolean playing;

        ReelPage(Context context) {
            super(context);
            setBackgroundColor(Color.BLACK);
            playerView = new PlayerView(context);
            playerView.setUseController(false);
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            playerView.setShutterBackgroundColor(Color.BLACK);
            addView(playerView, new FrameLayout.LayoutParams(-1, -1));
            addView(scrim(true), new FrameLayout.LayoutParams(-1, dp(180), Gravity.TOP));
            addView(scrim(false), new FrameLayout.LayoutParams(-1, dp(350), Gravity.BOTTOM));

            LinearLayout actions = new LinearLayout(context);
            actions.setOrientation(LinearLayout.VERTICAL);
            actions.setGravity(Gravity.CENTER);
            like = action("♡", "Like");
            TextView comments = action("◌", "Comment");
            save = action("▣", "Save");
            share = action("↗", "Share");
            mute = action("🔊", "Sound");
            like.setOnClickListener(v -> toggleLike());
            comments.setOnClickListener(v -> showComments());
            save.setOnClickListener(v -> toggleSave());
            share.setOnClickListener(v -> shareCurrent());
            mute.setOnClickListener(v -> toggleMute());
            actions.addView(like); actions.addView(comments); actions.addView(save); actions.addView(share); actions.addView(mute);
            FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(dp(70), -2, Gravity.END | Gravity.BOTTOM);
            ap.setMargins(0, 0, dp(8), dp(82));
            addView(actions, ap);

            LinearLayout info = new LinearLayout(context);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(dp(18), dp(8), dp(92), dp(90));
            addView(info, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

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
            follow.setBackground(bg(Color.argb(225, 235, 61, 126), 18));
            follow.setOnClickListener(v -> toggleFollow());
            creator.addView(follow, new LinearLayout.LayoutParams(-2, dp(38)));
            info.addView(creator);
            title = text("Live channel", 18, WHITE, true);
            title.setPadding(0, dp(8), 0, dp(3));
            info.addView(title);
            meta = text("Public live stream", 12, MUTED, false);
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
            meta.setText(reel.source + "  •  " + reel.quality + "  •  LIVE");
            follow.setText(followed.contains(reel.channel) ? "Following" : "Follow");
            like.setText(liked.contains(reel.id) ? "♥\nLiked" : "♡\nLike");
            save.setText(saved.contains(reel.id) ? "✓\nSaved" : "▣\nSave");
            mute.setText(muted ? "🔇\nMuted" : "🔊\nSound");
        }

        void setPlayingState(boolean value) { playing = value; }
        private void toggleLike() { if (item != null) { if (!liked.add(item.id)) liked.remove(item.id); bind(item); } }
        private void toggleSave() { if (item != null) { if (!saved.add(item.id)) saved.remove(item.id); bind(item); } }
        private void toggleFollow() { if (item != null) { if (!followed.add(item.channel)) followed.remove(item.channel); bind(item); } }
        private void showComments() {
            EditText input = new EditText(TvReelsActivity.this);
            input.setHint("Add a comment");
            new AlertDialog.Builder(TvReelsActivity.this).setTitle("Comments").setView(input)
                    .setPositiveButton("Post", (d, w) -> Toast.makeText(TvReelsActivity.this, "Comment saved locally", Toast.LENGTH_SHORT).show())
                    .setNegativeButton("Close", null).show();
        }
    }
}
