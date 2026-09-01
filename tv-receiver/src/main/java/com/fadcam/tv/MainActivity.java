package com.fadcam.tv;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
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
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.tv49.com.BuildConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Responsive standalone TV 49 East receiver. */
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
    private ChannelStore store;
    private CatalogClient catalogClient;
    private final List<ChannelStore.Channel> remoteChannels = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(Color.rgb(7, 7, 9));
        getWindow().getDecorView().setSystemUiVisibility(0);

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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView label(String text, float size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return v;
    }

    private GradientDrawable surface(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private TextView pill(String text, int color) {
        TextView v = label(text, 11f, color, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(12), dp(7), dp(12), dp(7));
        v.setBackground(surface(Color.argb(38, Color.red(color), Color.green(color), Color.blue(color)), 18));
        return v;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.setClipToPadding(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(24));

        // Header: compact enough for phones, spacious enough for tablets.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(15), dp(14), dp(15));
        header.setBackground(surface(SURFACE, 24));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView title = label("TV 49 East", 25f, TEXT, true);
        TextView subtitle = label("FadCam creators  •  TV East  •  worldwide", 12f, MUTED, false);
        subtitle.setPadding(0, dp(3), 0, 0);
        brand.addView(title);
        brand.addView(subtitle);
        header.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        status = pill("CONNECTING", ACCENT);
        header.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(header);

        SpaceGap gap = new SpaceGap(this, dp(14));
        content.addView(gap);

        // A real 16:9 frame prevents portrait phones from stretching the video.
        // PlayerView itself uses FIT so the video never crops or zooms unexpectedly.
        AspectVideoFrame videoFrame = new AspectVideoFrame(this, 16f / 9f);
        videoFrame.setBackground(surface(Color.BLACK, 20));
        videoFrame.setClipToOutline(true);

        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setControllerShowTimeoutMs(2500);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setShutterBackgroundColor(Color.BLACK);
        videoFrame.addView(playerView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(videoFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView videoHint = label("16:9 player  •  adapts automatically to your screen", 11f, MUTED, false);
        videoHint.setGravity(Gravity.CENTER);
        videoHint.setPadding(0, dp(6), 0, dp(8));
        content.addView(videoHint);

        // Featured section.
        LinearLayout sectionHeader = new LinearLayout(this);
        sectionHeader.setOrientation(LinearLayout.VERTICAL);
        TextView featured = label("FEATURED", 11f, ACCENT, true);
        featured.setLetterSpacing(0.12f);
        sectionHeader.addView(featured);
        TextView heading = label("FadCam Local", 22f, TEXT, true);
        heading.setPadding(0, dp(2), 0, 0);
        sectionHeader.addView(heading);
        TextView description = label("FadCam-originated channels first, followed by TV East creators and global variety.", 13f, MUTED, false);
        description.setPadding(0, dp(3), 0, dp(10));
        sectionHeader.addView(description);
        content.addView(sectionHeader);

        channelList = new LinearLayout(this);
        channelList.setOrientation(LinearLayout.VERTICAL);
        content.addView(channelList);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button add = actionButton("＋  Add authorized channel", SURFACE_2);
        add.setOnClickListener(v -> showAddChannelDialog());
        actions.addView(add, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button refresh = actionButton("Refresh", SURFACE_3);
        refresh.setOnClickListener(v -> refreshCatalog());
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        refreshParams.leftMargin = dp(8);
        actions.addView(refresh, refreshParams);
        content.addView(actions);

        TextView protocol = label("Secure receiver  •  catalog and playback use authorized HTTPS sources", 11f, MUTED, false);
        protocol.setGravity(Gravity.CENTER);
        protocol.setPadding(0, dp(14), 0, 0);
        content.addView(protocol);

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(0, dp(10), 0, 0);
        footer.addView(label("TV East • standalone receiver", 11f, MUTED, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        stop = actionButton("STOP", SURFACE_2);
        stop.setEnabled(false);
        stop.setOnClickListener(v -> stopPlayback());
        footer.addView(stop);
        content.addView(footer);

        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    private Button actionButton(String text, int backgroundColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(TEXT);
        b.setTextSize(13f);
        b.setAllCaps(false);
        b.setMinHeight(dp(48));
        b.setMinWidth(0);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setBackground(surface(backgroundColor, 18));
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
                    boolean configured = !BuildConfig.TV_EAST_CATALOG_URL.isEmpty();
                    setStatus(configured ? "OFFLINE • LOCAL" : "SERVER NOT CONFIGURED", configured ? MUTED : ERROR);
                    renderChannels();
                });
            }
        });
    }

    private void renderChannels() {
        if (channelList == null) return;
        channelList.removeAllViews();
        LinkedHashMap<String, ChannelStore.Channel> merged = new LinkedHashMap<>();
        for (ChannelStore.Channel c : store.load()) if (c.featured) merged.put(c.id, c);
        for (ChannelStore.Channel c : remoteChannels) merged.putIfAbsent(c.id, c);
        for (ChannelStore.Channel c : store.load()) merged.putIfAbsent(c.id, c);

        if (merged.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(22), dp(20), dp(22));
            empty.setBackground(surface(SURFACE, 18));
            TextView icon = label("TV", 18f, ACCENT, true);
            icon.setGravity(Gravity.CENTER);
            empty.addView(icon);
            TextView message = label("No channels cached yet", 15f, TEXT, true);
            message.setGravity(Gravity.CENTER);
            message.setPadding(0, dp(6), 0, 0);
            empty.addView(message);
            TextView detail = label("Connect to the TV East catalog or add an authorized channel.", 12f, MUTED, false);
            detail.setGravity(Gravity.CENTER);
            detail.setPadding(0, dp(3), 0, 0);
            empty.addView(detail);
            channelList.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
        card.setPadding(dp(14), dp(11), dp(10), dp(11));
        card.setBackground(surface(SURFACE, 18));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(8);
        channelList.addView(card, cardParams);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = label(prefix + channel.name, 16f, TEXT, true);
        TextView owner = label(channel.owner, 12f, MUTED, false);
        owner.setPadding(0, dp(3), 0, 0);
        text.addView(name);
        text.addView(owner);
        card.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button play = actionButton("WATCH", SURFACE_2);
        play.setOnClickListener(v -> startPlayback(channel.url, channel.name));
        card.addView(play);
    }

    private void showAddChannelDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), dp(4), dp(8), dp(4));
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
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(url);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Add authorized channel")
                .setMessage("Only add streams you own or are authorized to watch/distribute.")
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

    /** Enforces a stable 16:9 video surface on every phone/tablet width. */
    private static final class AspectVideoFrame extends FrameLayout {
        private final float ratio;

        AspectVideoFrame(android.content.Context context, float ratio) {
            super(context);
            this.ratio = ratio;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int availableWidth = Math.max(0, width - getPaddingLeft() - getPaddingRight());
            int height = Math.round(availableWidth / ratio) + getPaddingTop() + getPaddingBottom();
            int exactHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
            super.onMeasure(widthMeasureSpec, exactHeight);
        }
    }

    /** Tiny spacer view that keeps spacing density-aware without magic pixels. */
    private static final class SpaceGap extends View {
        private final int size;

        SpaceGap(android.content.Context context, int size) {
            super(context);
            this.size = size;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(0, size);
        }
    }
}
