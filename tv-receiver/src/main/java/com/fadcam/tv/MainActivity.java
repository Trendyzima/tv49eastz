package com.fadcam.tv;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
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
import androidx.media3.ui.PlayerView;

import java.util.List;
import java.util.UUID;

/**
 * TV 49 East global receiver. FadCam remains the first-class featured source;
 * creator channels are imported through the signed/deep-link channel contract
 * and stored locally for reliable offline discovery.
 */
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

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(Color.rgb(9, 9, 11));
        store = new ChannelStore(this);
        buildUi();
        handleIntent(getIntent());
        renderChannels();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
        renderChannels();
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
        titles.addView(label("FadCam creator network • worldwide", 13f, MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        status = label("READY", 12f, ACCENT, true);
        status.setGravity(Gravity.CENTER);
        header.addView(status);
        content.addView(header);

        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setPlayer(null);
        LinearLayout.LayoutParams videoParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.58f);
        videoParams.topMargin = 16;
        content.addView(playerView, videoParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout catalog = new LinearLayout(this);
        catalog.setOrientation(LinearLayout.VERTICAL);
        catalog.setPadding(0, 14, 0, 8);

        catalog.addView(label("FEATURED", 12f, ACCENT, true));
        catalog.addView(label("FadCam Local", 21f, TEXT, true));
        TextView featuredHint = label("The original FadCam stream is always presented first.", 13f, MUTED, false);
        featuredHint.setPadding(0, 2, 0, 10);
        catalog.addView(featuredHint);

        channelList = new LinearLayout(this);
        channelList.setOrientation(LinearLayout.VERTICAL);
        catalog.addView(channelList);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button add = actionButton("＋  Add TV East channel");
        add.setOnClickListener(v -> showAddChannelDialog());
        actions.addView(add, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button refresh = actionButton("Refresh");
        refresh.setOnClickListener(v -> renderChannels());
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        refreshParams.leftMargin = 10;
        actions.addView(refresh, refreshParams);
        catalog.addView(actions);

        TextView protocol = label("TV East channels are accepted from FadCam using tv49east://channel links. Playback requires HTTPS; no camera or local-network permission is needed.", 12f, MUTED, false);
        protocol.setPadding(0, 14, 0, 8);
        catalog.addView(protocol);

        scroll.addView(catalog);
        content.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.42f));

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.addView(label("TV East • secure receiver", 12f, MUTED, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
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

    private void renderChannels() {
        if (channelList == null || store == null) return;
        channelList.removeAllViews();
        List<ChannelStore.Channel> channels = store.load();
        if (channels.isEmpty()) {
            TextView empty = label("No creator channels registered yet. When a FadCam creator publishes a TV East channel, its registration link can open this app automatically.", 13f, MUTED, false);
            empty.setPadding(0, 4, 0, 12);
            channelList.addView(empty);
            return;
        }

        TextView title = label("TV EAST • FADCAM CREATORS", 12f, ACCENT, true);
        title.setPadding(0, 6, 0, 8);
        channelList.addView(title);
        for (ChannelStore.Channel channel : channels) {
            LinearLayout card = new LinearLayout(this);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(18, 12, 10, 12);
            card.setBackground(surface(SURFACE, 22));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = 8;
            channelList.addView(card, cardParams);

            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.addView(label(channel.name, 17f, TEXT, true));
            text.addView(label("TV East • " + channel.owner, 12f, MUTED, false));
            card.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button play = actionButton("WATCH");
            play.setOnClickListener(v -> startPlayback(channel.url, channel.name));
            card.addView(play);
        }
    }

    private void showAddChannelDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = 8;
        form.setPadding(pad, pad, pad, pad);

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
                .setTitle("Add TV East channel")
                .setMessage("Only add streams you own or are authorized to watch/distribute.")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    String n = name.getText().toString().trim();
                    String o = owner.getText().toString().trim();
                    String u = url.getText().toString().trim();
                    if (n.isEmpty()) n = "TV East Channel";
                    if (o.isEmpty()) o = "FadCam creator";
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
            store.upsert(new ChannelStore.Channel(id, name, owner, mediaUrl, false));
            startPlayback(mediaUrl, name);
            setStatus("TV EAST • LIVE", GOOD);
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
