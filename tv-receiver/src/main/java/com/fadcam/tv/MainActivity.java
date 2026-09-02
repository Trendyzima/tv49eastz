package com.fadcam.tv;

import android.content.res.Configuration;
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
 * Responsive standalone TV 49 East receiver.
 *
 * The primary surface stays visually clean. Informational copy, empty-catalog state,
 * actions and receiver metadata live inside collapsible side panels. The side toggles
 * are deliberately transparent touch targets: there are no visible arrow glyphs.
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
    private ScrollView pageScroll;
    private LinearLayout channelSection;
    private CatalogClient catalogClient;
    private ChannelStore store;
    private final List<ChannelStore.Channel> channels = new ArrayList<>();

    private View leftPanel;
    private View rightPanel;
    private View leftToggle;
    private View rightToggle;
    private TextView emptyTitle;
    private TextView emptyDetail;
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

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

    private boolean landscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void buildUi() {
        if (landscape()) buildLandscapeUi(); else buildPortraitUi();
    }

    private void buildPortraitUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(true);
        pageScroll.setClipToPadding(false);
        pageScroll.setBackgroundColor(BG);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(96));

        addPlayer(content);
        channelSection = buildChannelSection(false);
        content.addView(channelSection, new LinearLayout.LayoutParams(-1, -2));

        pageScroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(pageScroll, new FrameLayout.LayoutParams(-1, -1));

        leftPanel = buildLeftPanel();
        rightPanel = buildRightPanel();
        root.addView(leftPanel, panelParams(Gravity.LEFT));
        root.addView(rightPanel, panelParams(Gravity.RIGHT));
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

    private void buildLandscapeUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.HORIZONTAL);
        shell.setPadding(dp(10), dp(10), dp(10), dp(10));

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(dp(10), 0, dp(10), 0);

        LinearLayout split = new LinearLayout(this);
        split.setOrientation(LinearLayout.HORIZONTAL);
        split.setGravity(Gravity.TOP);
        main.addView(split, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout video = new LinearLayout(this);
        video.setOrientation(LinearLayout.VERTICAL);
        addPlayer(video);
        TextView fit = label("16:9  •  FIT", 10f, MUTED, false);
        fit.setGravity(Gravity.CENTER);
        fit.setPadding(0, dp(5), 0, 0);
        video.addView(fit, new LinearLayout.LayoutParams(-1, dp(24)));
        split.addView(video, new LinearLayout.LayoutParams(0, -2, 1.55f));

        channelSection = buildChannelSection(true);
        LinearLayout catalog = new LinearLayout(this);
        catalog.setOrientation(LinearLayout.VERTICAL);
        catalog.setPadding(dp(12), 0, 0, 0);
        catalog.addView(channelSection, new LinearLayout.LayoutParams(-1, 0, 1f));
        split.addView(catalog, new LinearLayout.LayoutParams(0, -1, 1f));

        shell.addView(buildLandscapeRail(), new LinearLayout.LayoutParams(dp(66), -1));
        shell.addView(main, new LinearLayout.LayoutParams(0, -1, 1f));
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));

        leftPanel = buildLeftPanel();
        rightPanel = buildRightPanel();
        root.addView(leftPanel, panelParams(Gravity.LEFT));
        root.addView(rightPanel, panelParams(Gravity.RIGHT));
        leftToggle = edgeToggle(true);
        rightToggle = edgeToggle(false);
        root.addView(leftToggle, edgeParams(Gravity.LEFT));
        root.addView(rightToggle, edgeParams(Gravity.RIGHT));
        setContentView(root);
        applyChrome(false);
    }

    private AspectRatioFrameLayout makeVideoFrame() {
        AspectRatioFrameLayout frame = new AspectRatioFrameLayout(this);
        frame.setAspectRatio(16f / 9f);
        frame.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        frame.setBackground(surface(Color.BLACK, 20));
        frame.setClipToOutline(true);
        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setControllerShowTimeoutMs(2500);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setShutterBackgroundColor(Color.BLACK);
        frame.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        return frame;
    }

    private void addPlayer(LinearLayout parent) {
        AspectRatioFrameLayout frame = makeVideoFrame();
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(12);
        parent.addView(frame, p);
    }

    private LinearLayout buildChannelSection(boolean compact) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(compact ? dp(10) : 0, dp(10), compact ? dp(10) : 0, dp(8));
        if (compact) section.setBackground(surface(SURFACE, 20));

        TextView hint = label("LIVE CHANNELS", 10f, ACCENT, true);
        hint.setLetterSpacing(0.12f);
        section.addView(hint, new LinearLayout.LayoutParams(-1, dp(24)));

        channelList = new LinearLayout(this);
        channelList.setOrientation(LinearLayout.VERTICAL);
        section.addView(channelList, new LinearLayout.LayoutParams(-1, -2));
        return section;
    }

    private LinearLayout buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(8), dp(6), dp(8), dp(7));
        nav.setBackground(surface(Color.rgb(20, 18, 25), 22));
        nav.addView(navItem("⌂", "Home", true, v -> scrollToTop()));
        nav.addView(navItem("TV", "Channels", false, v -> scrollToChannels()));
        nav.addView(navItem("＋", "Add", false, v -> showAddChannelDialog()));
        nav.addView(navItem("▣", "Library", false, v -> scrollToChannels()));
        return nav;
    }

    private LinearLayout buildLandscapeRail() {
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        rail.setPadding(0, dp(8), 0, dp(8));
        rail.setBackground(surface(SURFACE, 22));
        TextView logo = label("49", 20f, TEXT, true);
        logo.setGravity(Gravity.CENTER);
        rail.addView(logo, new LinearLayout.LayoutParams(dp(52), dp(52)));
        rail.addView(navItem("⌂", "Home", true, v -> scrollToTop()), new LinearLayout.LayoutParams(dp(60), dp(66)));
        rail.addView(navItem("TV", "Live", false, v -> scrollToChannels()), new LinearLayout.LayoutParams(dp(60), dp(66)));
        rail.addView(navItem("＋", "Add", false, v -> showAddChannelDialog()), new LinearLayout.LayoutParams(dp(60), dp(66)));
        View spacer = new View(this);
        rail.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        TextView live = label("LIVE", 9f, GOOD, true);
        live.setGravity(Gravity.CENTER);
        rail.addView(live, new LinearLayout.LayoutParams(dp(52), dp(40)));
        return rail;
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

    /** Left drawer contains screenshot blocks 1 and 2. */
    private LinearLayout buildLeftPanel() {
        LinearLayout p = sidePanel();
        p.addView(label("INFO", 17f, TEXT, true), new LinearLayout.LayoutParams(-1, dp(32)));

        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.addView(label("TV 49 East", 24f, TEXT, true));
        brand.addView(label("FadCam creators • TV East • worldwide", 12f, MUTED, false));
        brandRow.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));
        status = pill("SERVER NOT CONFIGURED", ERROR);
        brandRow.addView(status);
        p.addView(brandRow, new LinearLayout.LayoutParams(-1, -2));

        TextView featured = label("FEATURED", 10f, ACCENT, true);
        featured.setLetterSpacing(0.12f);
        featured.setPadding(0, dp(14), 0, 0);
        p.addView(featured, new LinearLayout.LayoutParams(-1, dp(34)));
        p.addView(label("FadCam Local", 22f, TEXT, true), new LinearLayout.LayoutParams(-1, dp(36)));
        p.addView(label("FadCam-originated channels first, followed by TV East creators and global variety.", 12f, MUTED, false), new LinearLayout.LayoutParams(-1, -2));

        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(12), dp(18), dp(12), dp(18));
        empty.setBackground(surface(SURFACE, 20));
        emptyTitle = label("No channels cached yet", 16f, TEXT, true);
        emptyTitle.setGravity(Gravity.CENTER);
        empty.addView(emptyTitle);
        emptyDetail = label("Connect to the TV East catalog or add an authorized channel.", 12f, MUTED, false);
        emptyDetail.setGravity(Gravity.CENTER);
        emptyDetail.setPadding(0, dp(6), 0, 0);
        empty.addView(emptyDetail);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, dp(126));
        ep.topMargin = dp(18);
        p.addView(empty, ep);

        addPanelButton(p, "Close info", v -> { leftOpen = false; applyChrome(true); });
        return p;
    }

    /** Right drawer contains screenshot blocks 3 and 4. */
    private LinearLayout buildRightPanel() {
        LinearLayout p = sidePanel();
        p.addView(label("CONTROLS", 17f, TEXT, true), new LinearLayout.LayoutParams(-1, dp(32)));
        addPanelButton(p, "＋  Add authorized channel", v -> showAddChannelDialog());
        addPanelButton(p, "Refresh", v -> refreshCatalog());

        TextView protocol = label("Secure receiver  •  authorized HTTPS relay", 11f, MUTED, false);
        protocol.setGravity(Gravity.CENTER);
        protocol.setPadding(0, dp(8), 0, dp(8));
        p.addView(protocol, new LinearLayout.LayoutParams(-1, dp(48)));

        TextView footer = label("TV East • standalone receiver", 11f, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        p.addView(footer, new LinearLayout.LayoutParams(-1, dp(38)));

        stop = actionButton("STOP", SURFACE_2);
        stop.setEnabled(false);
        stop.setOnClickListener(v -> stopPlayback());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(50));
        sp.topMargin = dp(8);
        p.addView(stop, sp);

        addPanelButton(p, "Close controls", v -> { rightOpen = false; applyChrome(true); });
        return p;
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
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(width, -1, gravity);
        p.topMargin = dp(8);
        p.bottomMargin = dp(8);
        return p;
    }

    /**
     * A completely transparent edge hit target. It has no glyph, background, ripple,
     * elevation or alpha animation, so there is nothing visible to identify as a toggle.
     */
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
        // Keep a generous invisible touch target, but no visible affordance.
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(46), dp(112), gravity | Gravity.CENTER_VERTICAL);
        if (gravity == Gravity.LEFT) {
            p.leftMargin = 0;
        } else {
            p.rightMargin = 0;
        }
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
            if (!animate) {
                bottomNav.setVisibility(View.INVISIBLE);
                bottomNav.setTranslationY(dp(96));
                bottomNav.setAlpha(0f);
                return;
            }
            bottomNav.setVisibility(View.VISIBLE);
            bottomNav.animate()
                    .translationY(bottomNav.getHeight() + dp(12))
                    .alpha(0f)
                    .setDuration(180)
                    .withEndAction(() -> bottomNav.setVisibility(View.INVISIBLE))
                    .start();
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
        renderChannels(store.load());
        catalogClient.load(new CatalogClient.Listener() {
            @Override public void onSuccess(List<ChannelStore.Channel> remote) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    channels.clear();
                    if (remote != null) channels.addAll(remote);
                    for (ChannelStore.Channel local : store.load()) {
                        boolean duplicate = false;
                        for (ChannelStore.Channel existing : channels) {
                            if (existing.id.equals(local.id) || existing.url.equals(local.url)) {
                                duplicate = true;
                                break;
                            }
                        }
                        if (!duplicate) channels.add(local);
                    }
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
        if (count > 0) {
            status.setText("● READY • " + count);
            status.setTextColor(GOOD);
            if (emptyTitle != null) emptyTitle.setText("Channels available");
            if (emptyDetail != null) emptyDetail.setText("Open the channel list on the receiver surface.");
        } else {
            status.setText("SERVER NOT CONFIGURED");
            status.setTextColor(ERROR);
            if (emptyTitle != null) emptyTitle.setText("No channels cached yet");
            if (emptyDetail != null) emptyDetail.setText("Connect to the TV East catalog or add an authorized channel.");
        }
    }

    private void renderChannels(List<ChannelStore.Channel> list) {
        if (channelList == null) return;
        channelList.removeAllViews();
        boolean empty = list == null || list.isEmpty();
        if (channelSection != null) channelSection.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) return;
        for (ChannelStore.Channel channel : list) addChannelCard(channel);
    }

    private void addChannelCard(ChannelStore.Channel channel) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(10), dp(10), dp(10));
        card.setBackground(surface(SURFACE, 18));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> play(channel));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(label(channel.name, 15f, TEXT, true));
        text.addView(label(channel.owner, 10f, MUTED, false));
        card.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView play = label("▶", 18f, ACCENT, true);
        play.setGravity(Gravity.CENTER);
        card.addView(play, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(70));
        p.topMargin = dp(7);
        channelList.addView(card, p);
    }

    private void play(ChannelStore.Channel channel) {
        if (channel == null || channel.url == null || channel.url.trim().isEmpty()) {
            toast("Channel has no playable relay");
            return;
        }
        Uri uri;
        try {
            uri = Uri.parse(channel.url.trim());
            if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("bad uri");
            }
        } catch (Throwable t) {
            toast("Invalid channel URL");
            return;
        }
        stopPlayback();
        try {
            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);
            player.addListener(new Player.Listener() {
                @Override public void onPlayerError(@NonNull PlaybackException error) {
                    if (status != null) {
                        status.setText("● CHANNEL ERROR");
                        status.setTextColor(ERROR);
                    }
                    toast("Channel unavailable");
                }
            });
            player.setMediaItem(MediaItem.fromUri(uri));
            player.prepare();
            player.play();
            if (stop != null) stop.setEnabled(true);
            if (status != null) {
                status.setText("● PLAYING • " + channel.name);
                status.setTextColor(GOOD);
            }
        } catch (Throwable t) {
            stopPlayback();
            if (status != null) {
                status.setText("● PLAYER ERROR");
                status.setTextColor(ERROR);
            }
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
        name.setHint("Channel name");
        name.setSingleLine(true);
        box.addView(name, new LinearLayout.LayoutParams(-1, dp(52)));
        EditText owner = new EditText(this);
        owner.setHint("Creator / owner");
        owner.setSingleLine(true);
        box.addView(owner, new LinearLayout.LayoutParams(-1, dp(52)));
        EditText url = new EditText(this);
        url.setHint("Authorized HTTPS relay URL");
        url.setSingleLine(true);
        box.addView(url, new LinearLayout.LayoutParams(-1, dp(52)));
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Add authorized channel")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    String stream = url.getText().toString().trim();
                    if (stream.isEmpty()) {
                        toast("A relay URL is required");
                        return;
                    }
                    try {
                        Uri parsed = Uri.parse(stream);
                        if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null) {
                            throw new IllegalArgumentException();
                        }
                    } catch (Throwable t) {
                        toast("Only authorized HTTPS relay URLs are accepted");
                        return;
                    }
                    store.upsert(new ChannelStore.Channel(
                            UUID.randomUUID().toString(),
                            name.getText().toString().trim().isEmpty() ? "TV East Channel" : name.getText().toString().trim(),
                            owner.getText().toString().trim().isEmpty() ? "Authorized creator" : owner.getText().toString().trim(),
                            stream,
                            false));
                    refreshCatalog();
                }).show();
    }

    private void scrollToTop() {
        if (pageScroll != null) pageScroll.smoothScrollTo(0, 0);
    }

    private void scrollToChannels() {
        if (pageScroll != null && channelSection != null && channelSection.getVisibility() == View.VISIBLE) {
            pageScroll.post(() -> pageScroll.smoothScrollTo(0, Math.max(0, channelSection.getTop() - dp(8))));
        } else {
            // With an empty catalog the state lives in the hidden left drawer.
            leftOpen = true;
            applyChrome(true);
        }
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }
}
