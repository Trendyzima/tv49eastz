package com.fadcam.tv;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.tv49.com.BuildConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Standalone TV 49 East receiver. All catalog playback is served through the TV East relay. */
public final class MainActivity extends AppCompatActivity {
    private static final int BG = Color.rgb(15, 15, 18);
    private static final int SURFACE = Color.rgb(31, 24, 49);
    private static final int SURFACE_2 = Color.rgb(42, 32, 64);
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
    private ChannelStore store;
    private CatalogClient catalogClient;
    private final List<ChannelStore.Channel> remoteChannels = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(Color.rgb(9, 9, 11));
        store = new ChannelStore(this);
        catalogClient = new CatalogClient(BuildConfig.TV_EAST_CATALOG_URL);
        buildUi();
        handleIntent(getIntent());
        refreshCatalog();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
        refreshCatalog();
    }

    private TextView label(String text, float size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return v;
    }

    private GradientDrawable surface(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setKeepScreenOn(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(28, 20, 28, 20);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(22, 16, 22, 16);
        header.setBackground(surface(SURFACE, 28));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(label("TV 49 East", 27f, TEXT, true));
        titles.addView(label("FadCam creators • TV East • worldwide", 13f, MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        status = label("CONNECTING", 12f, ACCENT, true);
        status.setGravity(Gravity.CENTER);
        header.addView(status);
        content.addView(header);

        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        LinearLayout.LayoutParams videoParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.56f);
        videoParams.topMargin = 16;
        content.addView(playerView, videoParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout catalog = new LinearLayout(this);
        catalog.setOrientation(LinearLayout.VERTICAL);
        catalog.setPadding(0, 14, 0, 8);
        catalog.addView(label("FEATURED", 12f, ACCENT, true));
        catalog.addView(label("FadCam Local", 21f, TEXT, true));
        TextView featuredHint = label("FadCam-originated channels are surfaced first, followed by TV East creators and global variety.", 13f, MUTED, false);
        featuredHint.setPadding(0, 2, 0, 10);
        catalog.addView(featuredHint);
        channelList = new LinearLayout(this);
        channelList.setOrientation(LinearLayout.VERTICAL);
        catalog.addView(channelList);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button add = actionButton("＋  Add authorized channel");
        add.setOnClickListener(v -> showAddChannelDialog());
        actions.addView(add, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button refresh = actionButton("Refresh");
        refresh.setOnClickListener(v -> refreshCatalog());
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        refreshParams.leftMargin = 10;
        actions.addView(refresh, refreshParams);
        catalog.addView(actions);

        TextView protocol = label("Catalog channels play through the TV 49 East HTTPS relay. FadCam publishers register through the authenticated TV East publishing API.", 12f, MUTED, false);
        protocol.setPadding(0, 14, 0, 8);
        catalog.addView(protocol);
        scroll.addView(catalog);
        content.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.44f));

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.addView(label("TV East • secure standalone receiver", 12f, MUTED, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        stop = new Button(this);
        stop.setText("STOP");
        stop.setTextColor(TEXT);
        stop.setAllCaps(false);
        stop.setEnabled(false);
        stop.setOnClickListener(v -> stopPlayback());
        footer.addView(stop);
        content.addView(footer);
        root.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private Button actionButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(TEXT);
        b.setAllCaps(false);
        b.setMinHeight(52);
        b.setBackground(surface(SURFACE_2, 22));
        return b;
    }

    private void refreshCatalog() {
        renderChannels();
        catalogClient.load(new CatalogClient.Listener() {
            @Override
            public void onSuccess(List<ChannelStore.Channel> channels) {
                runOnUiThread(() -> {
                    remoteChannels.clear();
                    remoteChannels.addAll(channels);
                    setStatus("CATALOG LIVE", GOOD);
                    renderChannels();
                });
            }

            @Override
            public void onError(Exception error) {
                runOnUiThread(() -> {
                    setStatus(BuildConfig.TV_EAST_CATALOG_URL.isEmpty() ? "SERVER NOT CONFIGURED" : "OFFLINE • LOCAL CHANNELS", BuildConfig.TV_EAST_CATALOG_URL.isEmpty() ? ERROR : MUTED);
                    renderChannels();
                });
            }
        });
    }

    private void renderChannels() {
        if (channelList == null) return;
        channelList.removeAllViews();
        LinkedHashMap<String, ChannelStore.Channel> merged = new LinkedHashMap<>();
        for (ChannelStore.Channel c : store.load()) {
            if (c.featured) merged.put(c.id, c);
        }
        for (ChannelStore.Channel c : remoteChannels) merged.putIfAbsent(c.id, c);
        for (ChannelStore.Channel c : store.load()) merged.putIfAbsent(c.id, c);

        if (merged.isEmpty()) {
            TextView empty = label("No channels cached yet. Connect to the TV East catalog server or publish a FadCam channel.", 13f, MUTED, false);
            empty.setPadding(0, 4, 0, 12);
            channelList.addView(empty);
            return;
        }

        boolean first = true;
        for (ChannelStore.Channel channel : merged.values()) {
            String prefix = first && channel.featured ? "FADCAM LOCAL • " : channel.owner.startsWith("TV East") ? "TV EAST • " : "";
            addChannelCard(channel, prefix);
            first = false;
        }
    }

    private void addChannelCard(ChannelStore.Channel channel, String prefix) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(18, 12, 10, 12);
        card.setBackground(surface(SURFACE, 22));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = 8;
        channelList.addView(card, cardParams);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(label(prefix + channel.name, 17f, TEXT, true));
        text.addView(label(channel.owner, 12f, MUTED, false));
        card.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button play = actionButton("WATCH");
        play.setOnClickListener(v -> startPlayback(channel.url, channel.name));
        card.addView(play);
    }

    private void showAddChannelDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(8, 8, 8, 8);
        EditText name = new EditText(this);
        name.setHint("Channel name");
        name.setSingleLine(true);
        form.addView(name);
        EditText owner = new EditText(this);
        owner.setHint("Creator / station name");
        owner.setSingleLine(true);
        form.addView(owner);
        EditText url = new EditText(this);
        url.setHint("HTTPS HLS stream URL");
        url.setSingleLine(true);
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(url);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Add authorized channel")
                .setMessage("Only add streams you own or are authorized to watch/distribute. Catalog channels should use the TV East relay.")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    String n = safe(name.getText().toString(), "TV East Channel");
                    String o = safe(owner.getText().toString(), "Authorized source");
                    String u = url.getText().toString().trim();
                    if (!isSecureUrl(u)) {
                        setStatus("HTTPS REQUIRED", ERROR);
                        return;
                    }
                    store.upsert(new ChannelStore.Channel(UUID.randomUUID().toString(), n, o, u, false));
                    renderChannels();
                    setStatus("CHANNEL SAVED", GOOD);
                }).show();
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = intent.getData();
        if (uri == null) return;
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if ("fadcam".equalsIgnoreCase(scheme) && "stream".equalsIgnoreCase(host)) {
            String mediaUrl = uri.getQueryParameter("url");
            if (!isSecureUrl(mediaUrl)) {
                setStatus("SECURE HTTPS STREAM REQUIRED", ERROR);
                return;
            }
            store.upsert(new ChannelStore.Channel("fadcam-local", "FadCam Local", "FadCam", mediaUrl, true));
            startPlayback(mediaUrl, "FadCam Local");
            return;
        }
        if ("tv49east".equalsIgnoreCase(scheme) && "channel".equalsIgnoreCase(host)) {
            String mediaUrl = uri.getQueryParameter("url");
            if (!isSecureUrl(mediaUrl)) {
                setStatus("CHANNEL REJECTED • HTTPS REQUIRED", ERROR);
                return;
            }
            String name = safe(uri.getQueryParameter("name"), "TV East Channel");
            String owner = safe(uri.getQueryParameter("owner"), "FadCam creator");
            String id = safe(uri.getQueryParameter("id"), UUID.randomUUID().toString());
            store.upsert(new ChannelStore.Channel(id, name, "TV East • " + owner, mediaUrl, false));
            startPlayback(mediaUrl, name);
        }
    }

    private String safe(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private boolean isSecureUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        try {
            Uri parsed = Uri.parse(value.trim());
            return "https".equalsIgnoreCase(parsed.getScheme()) && parsed.getHost() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void startPlayback(String url, String channelName) {
        if (!isSecureUrl(url)) {
            setStatus("INVALID SECURE STREAM", ERROR);
            return;
        }
        stopPlayback();
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.play();
        stop.setEnabled(true);
        setStatus("LIVE • " + channelName, GOOD);
        Toast.makeText(this, "Playing " + channelName, Toast.LENGTH_SHORT).show();
    }

    private void setStatus(String text, int color) {
        if (status != null) {
            status.setText(text);
            status.setTextColor(color);
        }
    }

    private void stopPlayback() {
        if (player != null) {
            player.release();
            player = null;
        }
        if (playerView != null) playerView.setPlayer(null);
        if (stop != null) stop.setEnabled(false);
        setStatus("READY", ACCENT);
    }

    @Override
    protected void onDestroy() {
        stopPlayback();
        super.onDestroy();
    }
}
