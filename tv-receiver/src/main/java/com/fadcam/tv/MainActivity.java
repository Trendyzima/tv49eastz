package com.fadcam.tv;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.tv49.com.BuildConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * TV 49 East receiver surface.
 *
 * The video viewport deliberately has no forced 16:9 parent. It fills the actual app window
 * in portrait and landscape. Media3 keeps the source fitted inside that viewport so a camera
 * stream is not geometrically stretched. The receiver catalog is FadCam-only.
 */
public final class MainActivity extends AppCompatActivity {
    private static final int BG = Color.rgb(12, 11, 16);
    private static final int SURFACE = Color.rgb(29, 23, 45);
    private static final int SURFACE_2 = Color.rgb(39, 31, 58);
    private static final int SURFACE_3 = Color.rgb(49, 39, 70);
    private static final int ACCENT = Color.rgb(207, 186, 253);
    private static final int TEXT = Color.WHITE;
    private static final int MUTED = Color.rgb(190, 184, 205);
    private static final int GOOD = Color.rgb(119, 221, 119);
    private static final int ERROR = Color.rgb(244, 91, 91);

    private ExoPlayer player;
    private PlayerView playerView;
    private TextView status;
    private Button stop;
    private LinearLayout channelList;
    private ScrollView channelScroll;
    private CatalogClient catalogClient;
    private ChannelStore store;
    private final List<ChannelStore.Channel> channels = new ArrayList<>();

    private View leftPanel;
    private View rightPanel;
    private View leftToggle;
    private View rightToggle;
    private View bottomNav;
    private boolean leftOpen;
    private boolean rightOpen;
    private boolean bottomNavHidden;
    private GestureDetector gestureDetector;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(Color.rgb(7, 7, 9));
        if (state != null) {
            leftOpen = state.getBoolean("left_open", false);
            rightOpen = state.getBoolean("right_open", false);
            bottomNavHidden = state.getBoolean("bottom_hidden", false);
        }
        store = new ChannelStore(this);
        catalogClient = new CatalogClient(BuildConfig.TV_EAST_CATALOG_URL);
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                toggleBottomNav();
                return true;
            }
        });
        buildUi();
        refreshCatalog();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        try { gestureDetector.onTouchEvent(event); } catch (Throwable ignored) { }
        return super.dispatchTouchEvent(event);
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle out) {
        out.putBoolean("left_open", leftOpen);
        out.putBoolean("right_open", rightOpen);
        out.putBoolean("bottom_hidden", bottomNavHidden);
        super.onSaveInstanceState(out);
    }

    @Override protected void onPause() {
        stopPlayback();
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopPlayback();
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView label(String value, float size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return v;
    }

    private GradientDrawable surface(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private TextView pill(String value, int color) {
        TextView v = label(value, 10f, color, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(11), dp(6), dp(11), dp(6));
        v.setBackground(surface(Color.argb(42, Color.red(color), Color.green(color), Color.blue(color)), 18));
        return v;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        // Full physical-window viewport. There is intentionally no AspectRatioFrameLayout here:
        // the device/window dimensions define the screen area. PlayerView FIT protects the
        // actual stream from stretching while allowing the black viewport to match the phone.
        FrameLayout videoFrame = new FrameLayout(this);
        videoFrame.setBackgroundColor(Color.BLACK);
        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setControllerShowTimeoutMs(2500);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setShutterBackgroundColor(Color.BLACK);
        videoFrame.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(videoFrame, new FrameLayout.LayoutParams(-1, -1));

        leftPanel = buildLeftPanel();
        rightPanel = buildRightPanel();
        root.addView(leftPanel, panelParams(Gravity.LEFT));
        root.addView(rightPanel, panelParams(Gravity.RIGHT));

        // Transparent edge targets: the controls remain usable without visible arrows or tabs.
        leftToggle = edgeToggle(true);
        rightToggle = edgeToggle(false);
        root.addView(leftToggle, edgeParams(Gravity.LEFT));
        root.addView(rightToggle, edgeParams(Gravity.RIGHT));

        bottomNav = buildBottomNav();
        root.addView(bottomNav, new FrameLayout.LayoutParams(-1, dp(76), Gravity.BOTTOM));
        setContentView(root);
        applyChrome(false);
        applyBottomNav(false);
    }

    /** Left drawer contains identity and only FadCam-originated streams. */
    private LinearLayout buildLeftPanel() {
        LinearLayout p = sidePanel();
        p.addView(label("TV 49 EAST", 17f, TEXT, true), new LinearLayout.LayoutParams(-1, dp(32)));
        p.addView(label("FadCam-originated streams", 13f, ACCENT, true), new LinearLayout.LayoutParams(-1, dp(30)));
        status = pill("SERVER NOT CONFIGURED", ERROR);
        p.addView(status, new LinearLayout.LayoutParams(-2, dp(32)));
        TextView note = label("Only authorized FadCam creator streams are shown. IPTV aggregation is not part of this receiver.", 12f, MUTED, false);
        note.setPadding(0, dp(14), 0, dp(12));
        p.addView(note, new LinearLayout.LayoutParams(-1, -2));

        TextView heading = label("FADCAM CHANNELS", 10f, ACCENT, true);
        heading.setLetterSpacing(0.12f);
        p.addView(heading, new LinearLayout.LayoutParams(-1, dp(28)));

        channelScroll = new ScrollView(this);
        channelList = new LinearLayout(this);
        channelList.setOrientation(LinearLayout.VERTICAL);
        channelScroll.addView(channelList, new ScrollView.LayoutParams(-1, -2));
        p.addView(channelScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        addPanelButton(p, "Close", v -> { leftOpen = false; applyChrome(true); });
        return p;
    }

    /** Right drawer contains receiver controls, not IPTV controls. */
    private LinearLayout buildRightPanel() {
        LinearLayout p = sidePanel();
        p.addView(label("CONTROLS", 17f, TEXT, true), new LinearLayout.LayoutParams(-1, dp(32)));
        addPanelButton(p, "＋  Add FadCam channel", v -> showAddChannelDialog());
        addPanelButton(p, "Refresh FadCam catalog", v -> refreshCatalog());
        TextView protocol = label("Authorized HTTPS FadCam relay only", 11f, MUTED, false);
        protocol.setGravity(Gravity.CENTER);
        protocol.setPadding(0, dp(8), 0, dp(8));
        p.addView(protocol, new LinearLayout.LayoutParams(-1, dp(48)));
        stop = actionButton("STOP PLAYBACK", SURFACE_2);
        stop.setEnabled(false);
        stop.setOnClickListener(v -> stopPlayback());
        p.addView(stop, new LinearLayout.LayoutParams(-1, dp(50)));
        addPanelButton(p, "Close", v -> { rightOpen = false; applyChrome(true); });
        return p;
    }

    private LinearLayout buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(8), dp(6), dp(8), dp(7));
        nav.setBackground(surface(Color.rgb(20, 18, 25), 22));
        nav.addView(navItem("⌂", "Home", true, v -> { leftOpen = false; rightOpen = false; applyChrome(true); }));
        nav.addView(navItem("TV", "FadCam", false, v -> { leftOpen = true; applyChrome(true); }));
        nav.addView(navItem("＋", "Add", false, v -> { rightOpen = true; applyChrome(true); }));
        nav.addView(navItem("■", "Stop", false, v -> stopPlayback()));
        return nav;
    }

    private LinearLayout navItem(String icon, String title, boolean selected, View.OnClickListener click) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(4), dp(2), dp(4), dp(2));
        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(click);
        if (selected) item.setBackground(surface(SURFACE_3, 16));
        TextView iconView = label(icon, icon.equals("TV") ? 12f : 21f, selected ? TEXT : MUTED, true);
        iconView.setGravity(Gravity.CENTER);
        item.addView(iconView, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView text = label(title, 9f, selected ? ACCENT : MUTED, selected);
        text.setGravity(Gravity.CENTER);
        item.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return item;
    }

    private Button actionButton(String value, int color) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextColor(TEXT);
        b.setTextSize(13f);
        b.setAllCaps(false);
        b.setMinHeight(dp(48));
        b.setMinWidth(0);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setBackground(surface(color, 17));
        return b;
    }

    private LinearLayout sidePanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(16), dp(18), dp(16), dp(18));
        p.setBackground(surface(SURFACE, 22));
        p.setElevation(dp(8));
        return p;
    }

    private void addPanelButton(LinearLayout panel, String title, View.OnClickListener listener) {
        Button b = actionButton(title, SURFACE_2);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48));
        p.topMargin = dp(8);
        panel.addView(b, p);
    }

    private FrameLayout.LayoutParams panelParams(int gravity) {
        int width = Math.min(dp(320), Math.max(dp(220), (int) (getResources().getDisplayMetrics().widthPixels * 0.78f)));
        return new FrameLayout.LayoutParams(width, -1, gravity);
    }

    /** Completely transparent touch target. There is no visible arrow, pill or ripple. */
    private View edgeToggle(boolean left) {
        View hit = new View(this);
        hit.setBackgroundColor(Color.TRANSPARENT);
        hit.setClickable(true);
        hit.setFocusable(true);
        hit.setContentDescription(left ? "Information panel toggle" : "Controls panel toggle");
        hit.setOnClickListener(v -> {
            if (left) leftOpen = !leftOpen; else rightOpen = !rightOpen;
            applyChrome(true);
        });
        return hit;
    }

    private FrameLayout.LayoutParams edgeParams(int gravity) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(46), dp(120), gravity | Gravity.CENTER_VERTICAL);
        if (gravity == Gravity.LEFT) p.leftMargin = 0; else p.rightMargin = 0;
        return p;
    }

    private void applyChrome(boolean animate) {
        if (leftPanel == null || rightPanel == null) return;
        int leftDistance = leftPanel.getLayoutParams().width + dp(8);
        int rightDistance = rightPanel.getLayoutParams().width + dp(8);
        float lx = leftOpen ? 0f : -leftDistance;
        float rx = rightOpen ? 0f : rightDistance;
        if (animate) {
            leftPanel.animate().translationX(lx).setDuration(190).start();
            rightPanel.animate().translationX(rx).setDuration(190).start();
        } else {
            leftPanel.setTranslationX(lx);
            rightPanel.setTranslationX(rx);
        }
    }

    private void toggleBottomNav() {
        bottomNavHidden = !bottomNavHidden;
        applyBottomNav(true);
    }

    private void applyBottomNav(boolean animate) {
        if (bottomNav == null) return;
        bottomNav.animate().cancel();
        if (bottomNavHidden) {
            bottomNav.setVisibility(View.VISIBLE);
            if (!animate) {
                bottomNav.setTranslationY(dp(96));
                bottomNav.setAlpha(0f);
                bottomNav.setVisibility(View.INVISIBLE);
                return;
            }
            bottomNav.animate().translationY(bottomNav.getHeight() + dp(12)).alpha(0f).setDuration(180)
                    .withEndAction(() -> bottomNav.setVisibility(View.INVISIBLE)).start();
        } else {
            bottomNav.setVisibility(View.VISIBLE);
            if (!animate) {
                bottomNav.setTranslationY(0f);
                bottomNav.setAlpha(1f);
                return;
            }
            bottomNav.animate().translationY(0f).alpha(1f).setDuration(180).start();
        }
    }

    private void refreshCatalog() {
        if (store == null || catalogClient == null) return;
        // Local store remains available for explicitly authorized FadCam relays added by the user.
        renderChannels(store.load());
        catalogClient.load(new CatalogClient.Listener() {
            @Override public void onSuccess(List<ChannelStore.Channel> remote) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    channels.clear();
                    if (remote != null) channels.addAll(remote);
                    renderChannels(channels);
                    updateStatus(channels.size());
                });
            }
            @Override public void onError(Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    channels.clear();
                    channels.addAll(store.load());
                    renderChannels(channels);
                    updateStatus(channels.size());
                });
            }
        });
    }

    private void updateStatus(int count) {
        if (status == null) return;
        status.setText(count > 0 ? "● READY • " + count : "SERVER NOT CONFIGURED");
        status.setTextColor(count > 0 ? GOOD : ERROR);
    }

    private void renderChannels(List<ChannelStore.Channel> list) {
        if (channelList == null) return;
        channelList.removeAllViews();
        if (list == null || list.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(22), dp(16), dp(22));
            empty.setBackground(surface(SURFACE_2, 18));
            TextView title = label("No FadCam streams", 16f, TEXT, true);
            title.setGravity(Gravity.CENTER);
            empty.addView(title);
            TextView detail = label("Authorized creator streams will appear here.", 12f, MUTED, false);
            detail.setGravity(Gravity.CENTER);
            detail.setPadding(0, dp(6), 0, 0);
            empty.addView(detail);
            channelList.addView(empty, new LinearLayout.LayoutParams(-1, dp(116)));
            return;
        }
        for (ChannelStore.Channel channel : list) addChannelCard(channel);
    }

    private void addChannelCard(ChannelStore.Channel channel) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(9), dp(8), dp(9));
        card.setBackground(surface(SURFACE_2, 17));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> play(channel));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(label(channel.name, 14f, TEXT, true));
        text.addView(label(channel.owner, 10f, MUTED, false));
        card.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView play = label("▶", 17f, ACCENT, true);
        play.setGravity(Gravity.CENTER);
        card.addView(play, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(66));
        p.topMargin = dp(7);
        channelList.addView(card, p);
    }

    private void play(ChannelStore.Channel channel) {
        if (channel == null || channel.url == null || channel.url.trim().isEmpty()) {
            toast("FadCam stream has no relay");
            return;
        }
        Uri uri;
        try {
            uri = Uri.parse(channel.url.trim());
            if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) throw new IllegalArgumentException();
        } catch (Throwable t) {
            toast("Invalid FadCam relay URL");
            return;
        }
        stopPlayback();
        try {
            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);
            player.addListener(new Player.Listener() {
                @Override public void onPlayerError(@NonNull PlaybackException error) {
                    if (status != null) { status.setText("● STREAM ERROR"); status.setTextColor(ERROR); }
                    toast("FadCam stream unavailable");
                }
            });
            player.setMediaItem(MediaItem.fromUri(uri));
            player.prepare();
            player.play();
            if (stop != null) stop.setEnabled(true);
            if (status != null) { status.setText("● PLAYING • " + channel.name); status.setTextColor(GOOD); }
        } catch (Throwable t) {
            stopPlayback();
            if (status != null) { status.setText("● PLAYER ERROR"); status.setTextColor(ERROR); }
            toast("Playback could not start");
        }
    }

    private void stopPlayback() {
        if (player != null) {
            try { if (playerView != null) playerView.setPlayer(null); } catch (Throwable ignored) { }
            try { player.stop(); } catch (Throwable ignored) { }
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
        if (stop != null) stop.setEnabled(false);
    }

    private void showAddChannelDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        EditText name = new EditText(this);
        name.setHint("FadCam channel name");
        name.setSingleLine(true);
        box.addView(name, new LinearLayout.LayoutParams(-1, dp(52)));
        EditText owner = new EditText(this);
        owner.setHint("FadCam creator");
        owner.setSingleLine(true);
        box.addView(owner, new LinearLayout.LayoutParams(-1, dp(52)));
        EditText url = new EditText(this);
        url.setHint("Authorized HTTPS FadCam relay URL");
        url.setSingleLine(true);
        box.addView(url, new LinearLayout.LayoutParams(-1, dp(52)));
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Add FadCam channel")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    String stream = url.getText().toString().trim();
                    if (stream.isEmpty()) { toast("A FadCam relay URL is required"); return; }
                    try {
                        Uri parsed = Uri.parse(stream);
                        if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null) throw new IllegalArgumentException();
                    } catch (Throwable t) {
                        toast("Only authorized HTTPS FadCam relays are accepted");
                        return;
                    }
                    store.upsert(new ChannelStore.Channel(
                            UUID.randomUUID().toString(),
                            name.getText().toString().trim().isEmpty() ? "FadCam Channel" : name.getText().toString().trim(),
                            owner.getText().toString().trim().isEmpty() ? "FadCam creator" : owner.getText().toString().trim(),
                            stream,
                            false));
                    refreshCatalog();
                }).show();
    }

    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
}
