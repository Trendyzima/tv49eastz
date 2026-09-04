package com.fadcam.tv;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.fadcam.tv.social.SocialPost;
import com.fadcam.tv.social.SocialResult;
import com.fadcam.tv.social.SocialSession;
import com.fadcam.tv.social.SocialUser;
import com.fadcam.tv.social.SupabaseSocialRepository;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native TV 49 East social UI: familiar microblog layout without WebView or embedded third-party apps. */
public class SocialActivity extends AppCompatActivity {
    private static final int PICK_MEDIA = 401;
    private static final int MAX_MEDIA = 4;
    private static final String DOMAIN = "testagram.site";
    private SupabaseSocialRepository repo;
    private LinearLayout content;
    private TextView status;
    private TextView pageTitle;
    private final ArrayList<Uri> pendingMedia = new ArrayList<>();
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(3);
    private float downX, downY;

    private int ink() { return Color.rgb(15, 20, 25); }
    private int muted() { return Color.rgb(83, 100, 113); }
    private int line() { return Color.rgb(239, 243, 244); }
    private int accent() { return Color.rgb(0, 186, 124); }

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().setStatusBarColor(Color.WHITE);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        repo = new SupabaseSocialRepository(this);
        buildUi();
        handleDeepLink(getIntent());
        loadHome();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDeepLink(intent);
    }

    private void buildUi() {
        LinearLayout root = column();
        root.setBackgroundColor(Color.WHITE);

        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(7), dp(14), dp(5));
        top.addView(circleLabel("T", accent(), Color.WHITE, 18), new LinearLayout.LayoutParams(dp(42), dp(42)));
        pageTitle = text("TV 49 East", 20, ink(), true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, dp(42), 1);
        titleLp.leftMargin = dp(10);
        top.addView(pageTitle, titleLp);
        Button account = iconButton(repo.isSignedIn() ? "◎" : "○");
        account.setContentDescription("Account");
        account.setOnClickListener(v -> showAccount());
        top.addView(account, new LinearLayout.LayoutParams(dp(46), dp(46)));
        root.addView(top);

        EditText search = new EditText(this);
        search.setHint("Search TV 49 East");
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setTextColor(ink());
        search.setHintTextColor(muted());
        search.setPadding(dp(18), 0, dp(18), 0);
        search.setBackground(round(line(), 28));
        search.setOnEditorActionListener((v, actionId, event) -> { doSearch(v.getText().toString()); return true; });
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, dp(46));
        searchLp.setMargins(dp(14), dp(2), dp(14), dp(5));
        root.addView(search, searchLp);

        root.addView(categoryTabs());
        status = text("Home • For you", 12, muted(), false);
        status.setPadding(dp(18), dp(5), dp(18), dp(5));
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(30)));
        root.addView(divider());

        ScrollView body = new ScrollView(this);
        body.setFillViewport(true);
        content = column();
        content.setPadding(0, 0, 0, dp(78));
        body.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(bottomNavigation());
        setContentView(root);

        Button compose = button("＋", Color.WHITE, accent(), true);
        compose.setTextSize(26);
        compose.setElevation(dp(10));
        compose.setOnClickListener(v -> openComposer());
        addContentView(compose, floatingParams());
    }

    private View categoryTabs() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = row();
        tabs.setPadding(dp(8), 0, dp(8), 0);
        String[] labels = {"For you", "Following", "News", "Sports", "Entertainment", "Federated"};
        for (String label : labels) {
            final String selected = label;
            TextView tab = text(selected, 14, ink(), "For you".equals(selected));
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(14), 0, dp(14), 0);
            tab.setMinWidth(dp(80));
            tab.setOnClickListener(v -> {
                pageTitle.setText(selected);
                if ("For you".equals(selected) || "Following".equals(selected)) loadHome();
                else if ("Federated".equals(selected)) loadFederated();
                else loadTopic(selected);
            });
            tabs.addView(tab, new LinearLayout.LayoutParams(-2, dp(48)));
        }
        hs.addView(tabs, new HorizontalScrollView.LayoutParams(-2, dp(48)));
        return hs;
    }

    private View bottomNavigation() {
        LinearLayout nav = row();
        nav.setGravity(Gravity.CENTER);
        nav.setBackgroundColor(Color.WHITE);
        nav.setPadding(dp(5), dp(3), dp(5), dp(3));
        String[][] items = {{"⌂", "Home"}, {"⌕", "Explore"}, {"◉", "Alerts"}, {"✉", "Messages"}, {"☻", "Profile"}};
        for (String[] item : items) {
            final String destination = item[1];
            LinearLayout cell = column();
            cell.setGravity(Gravity.CENTER);
            TextView icon = text(item[0], 23, ink(), false); icon.setGravity(Gravity.CENTER);
            TextView label = text(item[1], 10, muted(), false); label.setGravity(Gravity.CENTER);
            cell.addView(icon, new LinearLayout.LayoutParams(-1, dp(30)));
            cell.addView(label, new LinearLayout.LayoutParams(-1, dp(19)));
            cell.setOnClickListener(v -> navigate(destination));
            cell.setFocusable(true);
            nav.addView(cell, new LinearLayout.LayoutParams(0, dp(53), 1));
        }
        return nav;
    }

    private void loadHome() {
        statusLine("Home • For you");
        content.removeAllViews();
        addSportsRail();
        addSectionTitle("Today's pulse", "Kenya + worldwide");
        addTrends();
        addSectionTitle("Who to follow", "Discover creators");
        repo.loadFeed(30, r -> runOnUiThread(() -> {
            List<SocialPost> posts = r.getValue() == null ? new ArrayList<>() : r.getValue();
            addWhoToFollow(posts);
            addSectionTitle("Posts for you", "Latest conversations");
            if (r.getError() != null) addEmpty("Connect your account to load the live social feed.");
            else if (posts.isEmpty()) addEmpty("No posts yet. Be the first to start the conversation.");
            else for (SocialPost p : posts) addPost(p);
        }));
    }

    private void addSportsRail() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout rail = row();
        rail.setPadding(dp(12), dp(8), dp(12), dp(10));
        String[] games = {"EPL  •  LIVE", "Kenya  •  Sports", "Football  •  Today", "Cricket  •  Today"};
        for (String game : games) {
            TextView chip = text(game, 13, ink(), true);
            chip.setGravity(Gravity.CENTER);
            chip.setBackground(round(Color.rgb(248, 250, 251), 14));
            chip.setPadding(dp(16), 0, dp(16), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(170), dp(58));
            lp.setMargins(0, 0, dp(8), 0);
            rail.addView(chip, lp);
        }
        hs.addView(rail, new HorizontalScrollView.LayoutParams(-2, dp(76)));
        content.addView(hs);
    }

    private void addTrends() {
        String[][] trends = {
                {"Trending in Kenya", "Ronald Karauri"},
                {"Trending in Kenya", "Morara Kebaso"},
                {"Trending in Kenya", "Chrome East Africa"},
                {"Explore now", "#KituBrandy"},
                {"Explore now", "#JudicialIndependenceKE"}
        };
        for (String[] trend : trends) {
            final String query = trend[1];
            LinearLayout item = row();
            item.setPadding(dp(18), dp(8), dp(14), dp(8));
            LinearLayout words = column();
            words.addView(text(trend[0], 12, muted(), false));
            words.addView(text(query, 16, ink(), true));
            item.addView(words, new LinearLayout.LayoutParams(0, dp(58), 1));
            TextView more = text("•••", 15, muted(), true); more.setGravity(Gravity.CENTER);
            item.addView(more, new LinearLayout.LayoutParams(dp(44), dp(58)));
            item.setOnClickListener(v -> doSearch(query));
            content.addView(item);
        }
    }

    private void addWhoToFollow(List<SocialPost> posts) {
        Set<String> seen = new HashSet<>();
        int shown = 0;
        for (SocialPost p : posts) {
            SocialUser u = p.getAuthor();
            if (u == null || u.getId() == null || !seen.add(u.getId()) || shown >= 3) continue;
            final SocialUser followUser = u;
            LinearLayout item = row(); item.setGravity(Gravity.CENTER_VERTICAL); item.setPadding(dp(18), dp(8), dp(14), dp(8));
            addAvatar(item, followUser, dp(46));
            LinearLayout info = column();
            info.addView(text(clean(followUser.getDisplayName(), clean(followUser.getUsername(), "TV 49 East creator")), 15, ink(), true));
            info.addView(text("@" + clean(followUser.getUsername(), "creator"), 13, muted(), false));
            item.addView(info, new LinearLayout.LayoutParams(0, dp(58), 1));
            Button follow = button("Follow", Color.WHITE, ink(), true);
            follow.setOnClickListener(v -> repo.followUser(followUser.getId(), true, feedback("Following @" + clean(followUser.getUsername(), "creator"))));
            item.addView(follow, new LinearLayout.LayoutParams(dp(92), dp(40)));
            content.addView(item);
            shown++;
        }
        if (shown == 0) addEmpty("Creators you may want to follow will appear here.");
    }

    private void loadTopic(String topic) {
        statusLine(topic + " • Explore");
        content.removeAllViews();
        addSectionTitle(topic, "Latest conversations");
        repo.loadTrending(30, r -> runOnUiThread(() -> {
            List<SocialPost> posts = r.getValue() == null ? new ArrayList<>() : r.getValue();
            if (posts.isEmpty()) addEmpty("Nothing is trending here yet."); else for (SocialPost p : posts) addPost(p);
        }));
    }

    private void loadFederated() {
        statusLine("Federated • Fediverse");
        content.removeAllViews();
        addSectionTitle("The open social web", "One native feed");
        TextView intro = text("TV 49 East can combine local posts with ActivityPub content from compatible servers. Remote accounts remain on their own instances; the app interoperates through open protocols.", 15, ink(), false);
        intro.setPadding(dp(18), dp(6), dp(18), dp(14)); content.addView(intro);
        String[][] instances = {
                {"Mastodon", "Microblogging • ActivityPub"},
                {"Pixelfed", "Photos • ActivityPub"},
                {"PeerTube", "Video channels • ActivityPub"},
                {"Misskey", "Microblogging • ActivityPub"},
                {"Pleroma-compatible", "Microblogging • ActivityPub"},
                {"More instances", "Discover dynamically by domain"}
        };
        for (String[] instance : instances) addInstance(instance[0], instance[1]);
        addSectionTitle("Local + federated posts", "Unified timeline");
        repo.loadTrending(20, r -> runOnUiThread(() -> { if (r.getValue() != null) for (SocialPost p : r.getValue()) addPost(p); }));
    }

    private void addInstance(String name, String subtitle) {
        LinearLayout card = row(); card.setGravity(Gravity.CENTER_VERTICAL); card.setPadding(dp(18), dp(9), dp(18), dp(9));
        card.addView(circleLabel(name.substring(0, 1), Color.rgb(232, 238, 240), ink(), 16), new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout words = column(); words.addView(text(name, 15, ink(), true)); words.addView(text(subtitle, 12, muted(), false));
        LinearLayout.LayoutParams wordsLp = new LinearLayout.LayoutParams(0, dp(54), 1); wordsLp.leftMargin = dp(12); card.addView(words, wordsLp);
        TextView arrow = text("›", 28, muted(), false); arrow.setGravity(Gravity.CENTER); card.addView(arrow, new LinearLayout.LayoutParams(dp(32), dp(54)));
        card.setOnClickListener(v -> Toast.makeText(this, "Federation discovery will resolve a remote instance by domain.", Toast.LENGTH_SHORT).show());
        content.addView(card);
    }

    private void addSectionTitle(String title, String subtitle) {
        LinearLayout block = column(); block.setPadding(dp(18), dp(13), dp(18), dp(5));
        block.addView(text(title, 20, ink(), true)); block.addView(text(subtitle, 12, muted(), false)); content.addView(block);
    }

    private void addPost(SocialPost p) {
        LinearLayout card = column(); card.setPadding(dp(18), dp(13), dp(18), dp(10)); card.setBackgroundColor(Color.WHITE);
        LinearLayout header = row(); header.setGravity(Gravity.TOP);
        SocialUser a = p.getAuthor(); addAvatar(header, a, dp(46));
        LinearLayout who = column();
        String name = a == null ? "TV 49 East user" : clean(a.getDisplayName(), clean(a.getUsername(), "TV 49 East user"));
        String handle = a == null ? "@tv49east" : "@" + clean(a.getUsername(), "creator");
        who.addView(text(name + "  ·  now", 15, ink(), true)); who.addView(text(handle, 13, muted(), false));
        LinearLayout.LayoutParams whoLp = new LinearLayout.LayoutParams(0, -2, 1); whoLp.leftMargin = dp(10); header.addView(who, whoLp);
        header.addView(text("•••", 16, muted(), true), new LinearLayout.LayoutParams(dp(40), dp(35))); card.addView(header);
        TextView body = text(clean(p.getBody(), ""), 16, ink(), false); body.setPadding(dp(56), dp(5), dp(2), dp(8)); card.addView(body);
        if (p.getMediaUrl() != null && !p.getMediaUrl().trim().isEmpty()) {
            ImageView media = new ImageView(this); media.setScaleType(ImageView.ScaleType.CENTER_CROP); media.setBackground(round(Color.rgb(241, 244, 245), 16)); media.setContentDescription("Post media");
            media.setImageDrawable(new android.graphics.drawable.ColorDrawable(Color.rgb(241, 244, 245)));
            LinearLayout.LayoutParams mediaLp = new LinearLayout.LayoutParams(-1, dp(220)); mediaLp.setMargins(dp(56), dp(2), 0, dp(8)); card.addView(media, mediaLp); loadImage(media, p.getMediaUrl());
        }
        LinearLayout actions = row(); actions.setPadding(dp(50), 0, 0, 0);
        Button reply = actionButton("↩  " + p.getReplyCount()); reply.setOnClickListener(v -> reply(p)); actions.addView(reply, weightLp());
        Button repost = actionButton("⟳  " + p.getRepostCount()); repost.setOnClickListener(v -> repo.repostPost(p.getId(), true, feedback("Reposted"))); actions.addView(repost, weightLp());
        Button like = actionButton("♡  " + p.getLikeCount()); like.setOnClickListener(v -> repo.likePost(p.getId(), true, feedback("Liked"))); actions.addView(like, weightLp());
        Button save = actionButton("□"); save.setOnClickListener(v -> repo.bookmarkPost(p.getId(), true, feedback("Saved"))); actions.addView(save, weightLp());
        card.addView(actions); content.addView(card); content.addView(divider());
    }

    private void addAvatar(LinearLayout parent, SocialUser user, int size) {
        ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        String name = user == null ? "T" : clean(user.getDisplayName(), clean(user.getUsername(), "T"));
        image.setImageDrawable(circleDrawable(initials(name), Color.rgb(210, 230, 225)));
        if (user != null && user.getAvatarUrl() != null && !user.getAvatarUrl().trim().isEmpty()) loadImage(image, user.getAvatarUrl());
        parent.addView(image, new LinearLayout.LayoutParams(size, size));
    }

    private void openComposer() {
        LinearLayout box = column(); box.setPadding(dp(4), dp(4), dp(4), 0);
        EditText e = new EditText(this); e.setHint("What's happening?"); e.setTextSize(17); e.setMinLines(4); e.setGravity(Gravity.TOP); e.setTextColor(ink()); box.addView(e, new LinearLayout.LayoutParams(-1, dp(125)));
        Button media = button("＋ Add photos or video", Color.rgb(0, 120, 88), Color.rgb(236, 250, 245), false); media.setOnClickListener(v -> pickMedia()); box.addView(media, new LinearLayout.LayoutParams(-1, dp(46)));
        box.addView(text("Supports up to 4 media items", 12, muted(), false));
        new AlertDialog.Builder(this).setTitle("Create post").setView(box).setPositiveButton("Post", (d, w) -> publish(e.getText().toString())).setNegativeButton("Cancel", null).show();
    }

    private void publish(String bodyText) {
        String body = bodyText.trim(); if (body.isEmpty() && pendingMedia.isEmpty()) { statusLine("Add text or media first"); return; }
        statusLine("Publishing…");
        repo.createPost(body.isEmpty() ? "Media post" : body, r -> runOnUiThread(() -> {
            if (r.getError() != null) { statusLine("Post failed: " + safe(r.getError())); return; }
            SocialPost p = r.getValue(); if (p == null) { statusLine("Published"); loadHome(); return; } uploadPending(p.getId(), 0);
        }));
    }

    private void uploadPending(String postId, int index) {
        if (index >= pendingMedia.size()) { pendingMedia.clear(); statusLine("Published"); loadHome(); return; }
        Uri uri = pendingMedia.get(index); long size = -1;
        try (android.content.res.AssetFileDescriptor a = getContentResolver().openAssetFileDescriptor(uri, "r")) { if (a != null) size = a.getLength(); } catch (Exception ignored) {}
        String mime = getContentResolver().getType(uri); String type = mime != null && mime.startsWith("video") ? "video" : "image"; final long bytes = size;
        repo.uploadMedia(uri, "posts", r -> {
            if (r.getError() != null) { runOnUiThread(() -> statusLine("Media upload failed: " + safe(r.getError()))); return; }
            repo.attachMedia(postId, r.getValue(), type, mime, bytes, index, rr -> uploadPending(postId, index + 1));
        });
    }

    private void pickMedia() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("*/*"); i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"}); i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i, PICK_MEDIA);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); if (request != PICK_MEDIA || result != RESULT_OK || data == null) return;
        pendingMedia.clear();
        if (data.getClipData() != null) for (int i = 0; i < Math.min(MAX_MEDIA, data.getClipData().getItemCount()); i++) pendingMedia.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) pendingMedia.add(data.getData());
        statusLine(pendingMedia.size() + " media selected • max " + MAX_MEDIA);
    }

    private void navigate(String destination) {
        if ("Home".equals(destination)) loadHome();
        else if ("Explore".equals(destination)) { pageTitle.setText("Explore"); loadFederated(); }
        else if ("Alerts".equals(destination)) loadAlerts();
        else if ("Messages".equals(destination)) showMessageCenter();
        else showAccount();
    }

    private void loadAlerts() {
        pageTitle.setText("Notifications"); content.removeAllViews(); addSectionTitle("Notifications", "Your social activity");
        repo.loadNotifications(50, r -> runOnUiThread(() -> { if (r.getError() != null) addEmpty("Sign in to see your notifications."); else addEmpty(clean(r.getValue(), "No new notifications.")); }));
    }

    private void showMessageCenter() { pageTitle.setText("Messages"); content.removeAllViews(); addSectionTitle("Messages", "Private conversations"); addEmpty("Your conversations will appear here. Message delivery is backed by Supabase RLS + Realtime."); }

    private void showAccount() {
        if (!repo.isSignedIn()) {
            LinearLayout f = column(); EditText email = new EditText(this); email.setHint("Email"); EditText pass = new EditText(this); pass.setHint("Password"); pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); f.addView(email); f.addView(pass);
            new AlertDialog.Builder(this).setTitle("Sign in to TV 49 East").setView(f).setPositiveButton("Sign in", (d, w) -> repo.signIn(email.getText().toString(), pass.getText().toString(), r -> runOnUiThread(() -> { statusLine(r.getError() == null ? "Signed in" : "Sign in failed: " + safe(r.getError())); loadHome(); }))).setNeutralButton("Create account", (d, w) -> showSignup()).setNegativeButton("Cancel", null).show();
            return;
        }
        repo.loadProfile(r -> runOnUiThread(() -> showProfileEditor(r.getValue())));
    }

    private void showSignup() {
        LinearLayout f = column(); EditText email = new EditText(this); email.setHint("Email"); EditText user = new EditText(this); user.setHint("Username"); EditText pass = new EditText(this); pass.setHint("Password"); pass.setInputType(129); f.addView(email); f.addView(user); f.addView(pass);
        new AlertDialog.Builder(this).setTitle("Create TV 49 East account").setView(f).setPositiveButton("Create", (d, w) -> repo.signUp(email.getText().toString(), pass.getText().toString(), user.getText().toString(), user.getText().toString(), r -> runOnUiThread(() -> statusLine(r.getError() == null ? "Account created" : "Signup failed: " + safe(r.getError()))))).setNegativeButton("Cancel", null).show();
    }

    private void showProfileEditor(SocialUser p) {
        if (p == null) { statusLine("Profile not found"); return; }
        LinearLayout f = column(); EditText u = new EditText(this); u.setText(clean(p.getUsername(), "")); EditText n = new EditText(this); n.setText(clean(p.getDisplayName(), "")); EditText b = new EditText(this); b.setHint("Bio"); b.setText(clean(p.getBio(), "")); f.addView(u); f.addView(n); f.addView(b);
        new AlertDialog.Builder(this).setTitle("Profile").setView(f).setPositiveButton("Save", (d, w) -> repo.updateProfile(u.getText().toString(), n.getText().toString(), b.getText().toString(), p.getAvatarUrl(), r -> runOnUiThread(() -> statusLine(r.getError() == null ? "Profile updated" : safe(r.getError()))))).setNeutralButton("Sign out", (d, w) -> { repo.signOut(); statusLine("Signed out"); loadHome(); }).setNegativeButton("Cancel", null).show();
    }

    private void reply(SocialPost p) {
        EditText e = new EditText(this); e.setHint("Write a reply"); e.setMinLines(3);
        new AlertDialog.Builder(this).setTitle("Reply").setView(e).setPositiveButton("Reply", (d, w) -> repo.replyToPost(p.getId(), e.getText().toString(), feedback("Reply sent"))).setNegativeButton("Cancel", null).show();
    }

    private void doSearch(String query) {
        if (query == null || query.trim().isEmpty()) return;
        pageTitle.setText("Search"); content.removeAllViews(); addSectionTitle("Search", query.trim());
        repo.searchPosts(query.trim(), 30, r -> runOnUiThread(() -> { if (r.getError() != null) { addEmpty("Search unavailable: " + safe(r.getError())); return; } List<SocialPost> posts = r.getValue() == null ? new ArrayList<>() : r.getValue(); if (posts.isEmpty()) addEmpty("No posts matched that search."); else for (SocialPost p : posts) addPost(p); }));
    }

    private void handleDeepLink(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        Uri u = intent.getData(); String host = u.getHost(); if (!DOMAIN.equalsIgnoreCase(host) && !("www." + DOMAIN).equalsIgnoreCase(host)) return;
        String path = u.getPath() == null ? "/" : u.getPath();
        if (path.startsWith("/tags/") || path.startsWith("/search/")) doSearch(Uri.decode(path.substring(path.indexOf('/', 1) + 1)));
        else if (path.startsWith("/instances/")) { loadFederated(); statusLine("Instance: " + Uri.decode(path.substring("/instances/".length()))); }
        else if (path.startsWith("/@")) { pageTitle.setText(path.substring(1)); content.removeAllViews(); addSectionTitle(path.substring(1), "Federated profile"); addEmpty("Remote actor resolution is handled by the federation gateway."); }
        else if (path.startsWith("/posts/")) { pageTitle.setText("Post"); content.removeAllViews(); addSectionTitle("Post", "Deep link"); addEmpty("Post " + path.substring("/posts/".length()) + " will be resolved by the native social API."); }
    }

    private void statusLine(String value) { if (status != null) status.setText(value); }
    private void addEmpty(String message) { TextView t = text(message, 14, muted(), false); t.setGravity(Gravity.CENTER); t.setPadding(dp(24), dp(25), dp(24), dp(25)); content.addView(t); }
    private SupabaseSocialRepository.ResultCallback<Boolean> feedback(String message) { return r -> runOnUiThread(() -> Toast.makeText(this, r.getError() == null ? message : safe(r.getError()), Toast.LENGTH_SHORT).show()); }

    private void loadImage(ImageView view, String url) {
        imageExecutor.execute(() -> {
            Bitmap bitmap = null; HttpURLConnection connection = null;
            try { connection = (HttpURLConnection) new URL(url).openConnection(); connection.setConnectTimeout(8000); connection.setReadTimeout(12000); connection.setInstanceFollowRedirects(true); connection.connect(); if (connection.getResponseCode() >= 200 && connection.getResponseCode() < 300) { try (InputStream input = connection.getInputStream()) { bitmap = BitmapFactory.decodeStream(input); } } } catch (Exception ignored) { } finally { if (connection != null) connection.disconnect(); }
            Bitmap result = bitmap; if (result != null) runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) view.setImageBitmap(result); });
        });
    }

    private Button actionButton(String label) { return button(label, muted(), Color.TRANSPARENT, false); }
    private Button iconButton(String label) { return button(label, ink(), Color.TRANSPARENT, false); }
    private Button button(String label, int foreground, int background, boolean bold) { Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(13); b.setTextColor(foreground); b.setGravity(Gravity.CENTER); b.setPadding(dp(7), 0, dp(7), 0); b.setBackground(round(background, 22)); b.setFocusable(true); if (bold) b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return b; }
    private TextView text(String value, float size, int color, boolean bold) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t; }
    private TextView circleLabel(String value, int background, int foreground, float size) { TextView t = text(value, size, foreground, true); t.setGravity(Gravity.CENTER); t.setBackground(round(background, 22)); return t; }
    private android.graphics.drawable.Drawable circleDrawable(String value, int background) { android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable(); d.setShape(android.graphics.drawable.GradientDrawable.OVAL); d.setColor(background); return d; }
    private android.graphics.drawable.Drawable round(int color, float radius) { android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable(); d.setColor(color); d.setCornerRadius(dp((int) radius)); return d; }
    private View divider() { View v = new View(this); v.setBackgroundColor(line()); v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1))); return v; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout.LayoutParams weightLp() { return new LinearLayout.LayoutParams(0, dp(40), 1); }
    private LinearLayout.LayoutParams floatingParams() { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(58), dp(58)); lp.gravity = Gravity.RIGHT | Gravity.BOTTOM; lp.setMargins(0, 0, dp(18), dp(72)); return lp; }
    private String initials(String s) { String[] p = s.trim().split("\\s+"); return p.length == 0 || p[0].isEmpty() ? "T" : p[0].substring(0, 1).toUpperCase(); }
    private String clean(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value.trim(); }
    private String safe(Throwable t) { return t == null ? "Unknown error" : clean(t.getMessage(), t.getClass().getSimpleName()); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { downX = e.getX(); downY = e.getY(); }
        else if (e.getActionMasked() == MotionEvent.ACTION_UP) { float dx = e.getX() - downX, dy = e.getY() - downY; if (dx > dp(100) && Math.abs(dx) > Math.abs(dy) * 1.25f) { goLive(); return true; } }
        return super.dispatchTouchEvent(e);
    }
    private void goLive() { startActivity(new Intent(this, MainActivity.class)); overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right); finish(); }
    @Override protected void onDestroy() { imageExecutor.shutdownNow(); super.onDestroy(); }
}
