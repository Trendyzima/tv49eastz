package com.fadcam.tv;

import android.content.Intent;
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
import android.view.ViewGroup;
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
 * The screen is intentionally chrome-light. Informational copy and action copy live in
 * collapsible left/right panels, while the bottom navigation can be hidden with a double tap.
 * This keeps the receiver usable as a clean full-screen TV surface without removing features.
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
    private TextView leftToggle;
    private TextView rightToggle;
    private View infoChrome;
    private View actionChrome;
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

        infoChrome = buildInfoChrome(true);
        content.addView(infoChrome, new LinearLayout.LayoutParams(-1, -2));
        addPlayer(content);
        channelSection = buildChannelSection(false);
        content.addView(channelSection, new LinearLayout.LayoutParams(-1, -2));
        actionChrome = buildActionChrome();
        content.addView(actionChrome, new LinearLayout.LayoutParams(-1, -2));

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
        infoChrome = buildInfoChrome(false);
        main.addView(infoChrome, new LinearLayout.LayoutParams(-1, -2));

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
        actionChrome = buildActionChrome();
        catalog.addView(actionChrome, new LinearLayout.LayoutParams(-1, -2));
        split.addView(catalog, new LinearLayout.LayoutParams(0, -1, 1f));

        main.addView(buildLandscapeHint(), new LinearLayout.LayoutParams(-1, dp(34)));
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

    private View buildInfoChrome(boolean portrait) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(portrait ? 18 : 14), dp(12), dp(12), dp(10));
        box.setBackground(surface(SURFACE, 22));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView title = label("TV 49 East", portrait ? 25f : 21f, TEXT, true);
        TextView sub = label("FadCam creators  •  TV East  •  worldwide", 11f, MUTED, false);
        brand.addView(title);
        brand.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        row.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));
        status = pill("SERVER NOT CONFIGURED", ERROR);
        row.addView(status);
        box.addView(row);

        TextView featured = label("FEATURED", 10f, ACCENT, true);
        featured.setLetterSpacing(0.12f);
        box.addView(featured, new LinearLayout.LayoutParams(-1, dp(24)));
        TextView heading = label("FadCam Local", portrait ? 22f : 18f, TEXT, true);
        box.addView(heading, new LinearLayout.LayoutParams(-1, dp(32)));
        TextView description = label("FadCam-originated channels first, followed by TV East creators and global variety.", 11f, MUTED, false);
        box.addView(description, new LinearLayout.LayoutParams(-1, -2));
        return box;
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
        ScrollView list = new ScrollView(this);
        list.setFillViewport(false);
        list.addView(channelList, new ScrollView.LayoutParams(-1, -2));
        section.addView(list, new LinearLayout.LayoutParams(-1, compact ? 0 : -2, compact ? 1f : 0f));
        return section;
    }

    private LinearLayout buildActionChrome() {
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, dp(6), 0, 0);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Button add = actionButton("＋  Add authorized channel", SURFACE_2);
        add.setOnClickListener(v -> showAddChannelDialog());
        row.addView(add, new LinearLayout.LayoutParams(0, dp(50), 1f));
        Button refresh = actionButton("Refresh", SURFACE_3);
        refresh.setOnClickListener(v -> refreshCatalog());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, dp(50));
        rp.leftMargin = dp(8);
        row.addView(refresh, rp);
        actions.addView(row);

        TextView protocol = label("Secure receiver  •  authorized HTTPS relay", 10f, MUTED, false);
        protocol.setGravity(Gravity.CENTER);
        actions.addView(protocol, new LinearLayout.LayoutParams(-1, dp(32)));

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.addView(label("TV East • standalone receiver", 10f, MUTED, false), new LinearLayout.LayoutParams(0, -2, 1f));
        stop = actionButton("STOP", SURFACE_2);
        stop.setEnabled(false);
        stop.setOnClickListener(v -> stopPlayback());
        footer.addView(stop);
        actions.addView(footer, new LinearLayout.LayoutParams(-1, dp(52)));
        return actions;
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

    private TextView buildLandscapeHint() {
        TextView v = label("Double tap anywhere to hide/show bottom navigation • edge toggles reveal controls", 9f, MUTED, false);
        v.setGravity(Gravity.CENTER);
        return v;
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

    private LinearLayout buildLeftPanel() {
        LinearLayout p = sidePanel();
        p.addView(label("INFO", 17f, TEXT, true), new LinearLayout.LayoutParams(-1, dp(32)));
        p.addView(label("TV 49 East", 24f, ACCENT, true), new LinearLayout.LayoutParams(-1, dp(40)));
        p.addView(label("FadCam creators • TV East • worldwide", 12f, MUTED, false));
        p.addView(label("Featured\nFadCam Local\n\nFadCam-originated channels first, followed by TV East creators and global variety.", 13f, TEXT, false));
        addPanelButton(p, "Hide info", v -> { leftOpen = false; applyChrome(true); });
        return p;
    }

    private LinearLayout buildRightPanel() {
        LinearLayout p = sidePanel();
        p.addView(label("CONTROLS", 17f, TEXT, true), new LinearLayout.LayoutParams(-1, dp(32)));
        addPanelButton(p, "＋  Add channel", v -> showAddChannelDialog());
        addPanelButton(p, "↻  Refresh", v -> refreshCatalog());
        addPanelButton(p, "■  Stop playback", v -> stopPlayback());
        addPanelButton(p, "Hide controls", v -> { rightOpen = false; applyChrome(true); });
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
        p.topMargin = dp(62);
        p.bottomMargin = dp(8);
        return p;
    }

    private FrameLayout.LayoutParams edgeParams(int gravity) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(42), dp(54), gravity | Gravity.CENTER_VERTICAL);
        p.setMargins(dp(4), 0, dp(4), 0);
        return p;
    }

    private TextView edgeToggle(boolean left) {
        TextView t = label(left ? "›" : "‹", 28f, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(surface(Color.argb(235, 43, 33, 59), 17));
        t.setElevation(dp(10));
        t.setContentDescription(left ? "Show or hide information" : "Show or hide controls");
        t.setOnClickListener(v -> {
            if (left) leftOpen = !leftOpen; else rightOpen = !rightOpen;
            applyChrome(true);
        });
        return t;
    }

    private void applyChrome(boolean animate) {
        if (leftPanel == null || rightPanel == null) return;
        int leftDistance = leftPanel.getLayoutParams().width + dp(12);
        int rightDistance = rightPanel.getLayoutParams().width + dp(12);
        float lx = leftOpen ? 0f : -leftDistance;
        float rx = rightOpen ? 0f : rightDistance;
        if (animate) {
            leftPanel.animate().translationX(lx).setDuration(190).start();
            rightPanel.animate().translationX(rx).setDuration(190).start();
        } else {
            leftPanel.setTranslationX(lx);
            rightPanel.setTranslationX(rx);
        }
        if (leftToggle != null) leftToggle.setText(leftOpen ? "‹" : "›");
        if (rightToggle != null) rightToggle.setText(rightOpen ? "›" : "‹");
        if (infoChrome != null) infoChrome.setVisibility(leftOpen ? View.GONE : View.VISIBLE);
        if (actionChrome != null) actionChrome.setVisibility(rightOpen ? View.GONE : View.VISIBLE);
    }

    private void toggleBottomNav() {
        bottomNavHidden = !bottomNavHidden;
        applyBottomNav(true);
    }

    private void applyBottomNav(boolean animate) {
        if (bottomNav == null) return;
        if (bottomNavHidden) {
            if (animate) bottomNav.animate().translationY(bottomNav.getHeight() + dp(12)).alpha(0f).setDuration(180).start();
            else { bottomNav.setTranslationY(dp(96)); bottomNav.setAlpha(0f); }
            bottomNav.setVisibility(View.INVISIBLE);
        } else {
            bottomNav.setVisibility(View.VISIBLE);
            if (animate) bottomNav.animate().translationY(0f).alpha(1f).setDuration(180).start();
            else { bottomNav.setTranslationY(0f); bottomNav.setAlpha(1f); }
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
                            if (existing.id.equals(local.id) || existing.url.equals(local.url)) { duplicate = true; break; }
                        }
                        if (!duplicate) channels.add(local);
                    }
                    renderChannels(channels);
                    status.setText("● READY • " + channels.size());
                    status.setTextColor(GOOD);
                });
            }
            @Override public void onError(Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    channels.clear();
                    channels.addAll(store.load());
                    renderChannels(channels);
                    status.setText(channels.isEmpty() ? "SERVER NOT CONFIGURED" : "● LOCAL CHANNELS");
                    status.setTextColor(channels.isEmpty() ? ERROR : ACCENT);
                });
            }
        });
    }

    private void renderChannels(List<ChannelStore.Channel> list) {
        if (channelList == null) return;
        channelList.removeAllViews();
        if (list == null || list.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(22), dp(20), dp(22));
            empty.setBackground(surface(SURFACE, 20));
            TextView title = label("No channels cached yet", 16f, TEXT, true);
            title.setGravity(Gravity.CENTER);
            empty.addView(title);
            TextView detail = label("Connect to the TV East catalog or add an authorized channel.", 12f, MUTED, false);
            detail.setGravity(Gravity.CENTER);
            detail.setPadding(0, dp(6), 0, 0);
            empty.addView(detail);
            channelList.addView(empty, new LinearLayout.LayoutParams(-1, dp(126)));
            return;
        }
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
            if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) throw new IllegalArgumentException("bad uri");
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
                    status.setText("● CHANNEL ERROR");
                    status.setTextColor(ERROR);
                    toast("Channel unavailable");
                }
            });
            player.setMediaItem(MediaItem.fromUri(uri));
            player.prepare();
            player.play();
            stop.setEnabled(true);
            status.setText("● PLAYING • " + channel.name);
            status.setTextColor(GOOD);
        } catch (Throwable t) {
            stopPlayback();
            status.setText("● PLAYER ERROR");
            status.setTextColor(ERROR);
            toast("Playback could not start");
        }
    }

    private void stopPlayback() {
        if (player != null) {
            try { playerView.setPlayer(null); } catch (Throwable ignored) { }
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
                    if (stream.isEmpty()) { toast("A relay URL is required"); return; }
                    try {
                        Uri parsed = Uri.parse(stream);
                        if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null) throw new IllegalArgumentException();
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
        if (pageScroll != null && channelSection != null) {
            pageScroll.post(() -> pageScroll.smoothScrollTo(0, Math.max(0, channelSection.getTop() - dp(8))));
        }
    }

    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
}
