package com.fadcam.tv;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.fadcam.tv.social.SocialPost;
import com.fadcam.tv.social.SocialResult;
import com.fadcam.tv.social.SupabaseSocialRepository;

public class SocialActivity extends AppCompatActivity {
    private SupabaseSocialRepository repo;
    private LinearLayout feed;
    private TextView state;
    private float downX;
    private float downY;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repo = new SupabaseSocialRepository(this);
        buildUi();
        loadFeed();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(8));
        root.setBackgroundColor(Color.BLACK);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        Button live = button("← Live TV");
        live.setOnClickListener(v -> goLive());
        header.addView(live, new LinearLayout.LayoutParams(dp(150), dp(56)));
        TextView title = text("TV 49 East • Social", 24);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
        titleLp.gravity = android.view.Gravity.CENTER_VERTICAL;
        title.setLayoutParams(titleLp);
        header.addView(title);
        Button refresh = button("Refresh");
        refresh.setOnClickListener(v -> loadFeed());
        header.addView(refresh, new LinearLayout.LayoutParams(dp(140), dp(56)));
        root.addView(header);

        state = text("Loading…", 14);
        root.addView(state, new LinearLayout.LayoutParams(-1, dp(42)));

        EditText composer = new EditText(this);
        composer.setHint("Share something with the TV 49 East community…");
        composer.setTextColor(Color.WHITE);
        composer.setHintTextColor(Color.LTGRAY);
        composer.setMinLines(2);
        composer.setGravity(android.view.Gravity.TOP);
        root.addView(composer, new LinearLayout.LayoutParams(-1, dp(100)));
        Button post = button("Post");
        post.setOnClickListener(v -> {
            String body = composer.getText().toString().trim();
            if (body.isEmpty()) { state.setText("Write something first."); return; }
            state.setText("Publishing…");
            repo.createPost(body, result -> runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (result.getError() != null) state.setText("Post failed: " + safeMessage(result.getError()));
                else { composer.setText(""); state.setText("Posted"); loadFeed(); }
            }));
        });
        root.addView(post, new LinearLayout.LayoutParams(-1, dp(52)));

        ScrollView scroll = new ScrollView(this);
        feed = new LinearLayout(this);
        feed.setOrientation(LinearLayout.VERTICAL);
        feed.setPadding(0, dp(12), 0, dp(30));
        scroll.addView(feed, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    private void goLive() {
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right);
        finish();
    }

    private void loadFeed() {
        state.setText(repo.isConfigured() ? "Loading social feed…" : "Supabase is not configured — showing the native shell");
        repo.loadFeed(30, new SupabaseSocialRepository.ResultCallback<java.util.List<SocialPost>>() {
            @Override public void onComplete(SocialResult<java.util.List<SocialPost>> result) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    feed.removeAllViews();
                    if (result.getError() != null) {
                        state.setText("Feed unavailable: " + safeMessage(result.getError()));
                        addEmpty("Could not load cloud posts", "Check Supabase URL/key and RLS policies.");
                        return;
                    }
                    java.util.List<SocialPost> posts = result.getValue();
                    if (posts == null) posts = java.util.Collections.emptyList();
                    state.setText(posts.isEmpty() ? "No posts yet • be the first creator" : posts.size() + " recent posts");
                    for (SocialPost post : posts) addPost(post);
                });
            }
        });
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private void addPost(SocialPost post) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackgroundColor(Color.rgb(25, 25, 25));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(lp);
        String author = post.getAuthor() == null ? "TV 49 East user" : post.getAuthor().getDisplayName();
        if (author == null || author.trim().isEmpty()) author = post.getAuthor() == null ? "TV 49 East user" : post.getAuthor().getUsername();
        card.addView(text(author == null ? "TV 49 East user" : author, 18));
        card.addView(text(post.getBody(), 16), new LinearLayout.LayoutParams(-1, -2));
        card.addView(text("♥ " + post.getLikeCount() + "   ↩ " + post.getReplyCount() + "   ⟳ " + post.getRepostCount(), 13));
        feed.addView(card);
    }

    private void addEmpty(String title, String subtitle) { feed.addView(text(title + "\n" + subtitle, 16)); }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        return b;
    }

    private TextView text(String value, float size) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(Color.WHITE);
        v.setTextSize(size);
        v.setPadding(dp(4), dp(4), dp(4), dp(4));
        return v;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            float dx = event.getX() - downX;
            float dy = event.getY() - downY;
            if (dx > dp(80) && Math.abs(dx) > Math.abs(dy) * 1.25f) goLive();
        }
        return super.dispatchTouchEvent(event);
    }
}
