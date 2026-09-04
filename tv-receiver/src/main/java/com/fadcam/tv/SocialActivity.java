package com.fadcam.tv;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.fadcam.tv.social.SocialPost;
import com.fadcam.tv.social.SocialResult;
import com.fadcam.tv.social.SocialSession;
import com.fadcam.tv.social.SupabaseSocialRepository;

public final class SocialActivity extends AppCompatActivity {
    private SupabaseSocialRepository repo;
    private LinearLayout feed;
    private TextView state;
    private float downX;
    private float downY;
    private boolean navigating;

    private final int bg = Color.rgb(10, 10, 14);
    private final int card = Color.rgb(27, 24, 35);
    private final int card2 = Color.rgb(39, 34, 51);
    private final int accent = Color.rgb(207, 186, 253);
    private final int textColor = Color.WHITE;
    private final int muted = Color.rgb(190, 184, 205);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repo = new SupabaseSocialRepository(this);
        buildUi();
        loadFeed();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getRawX();
            downY = event.getRawY();
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP && !navigating) {
            float dx = event.getRawX() - downX;
            float dy = event.getRawY() - downY;
            if (dx > dp(80) && Math.abs(dx) > Math.abs(dy) * 1.25f) {
                navigating = true;
                goLive();
                return true;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView label(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }

    private Button button(String value, View.OnClickListener listener) {
        Button view = new Button(this);
        view.setText(value);
        view.setTextSize(13f);
        view.setTextColor(textColor);
        view.setAllCaps(false);
        view.setOnClickListener(listener);
        view.setFocusable(true);
        view.setClickable(true);
        view.setMinHeight(dp(48));
        view.setPadding(dp(12), 0, dp(12), 0);
        view.setBackgroundColor(card2);
        return view;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(dp(18), dp(16), dp(18), dp(12));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(label("TV 49", 22f, accent, true), new LinearLayout.LayoutParams(0, dp(48), 1f));
        header.addView(button("LIVE TV", v -> goLive()), new LinearLayout.LayoutParams(dp(105), dp(48)));
        root.addView(header);

        root.addView(label("Social", 30f, textColor, true), new LinearLayout.LayoutParams(-1, dp(48)));
        state = label(repo.isConfigured() ? "Native feed • Supabase connected" : "Native feed • configure Supabase to enable cloud data", 12f, muted, false);
        root.addView(state, new LinearLayout.LayoutParams(-1, dp(34)));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, dp(50), 1f);
        actions.addView(button("CREATE POST", v -> showComposer()), actionParams);
        LinearLayout.LayoutParams signInParams = new LinearLayout.LayoutParams(0, dp(50), 1f);
        signInParams.leftMargin = dp(8);
        actions.addView(button("SIGN IN", v -> showAuth()), signInParams);
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(0, dp(50), 1f);
        refreshParams.leftMargin = dp(8);
        actions.addView(button("REFRESH", v -> loadFeed()), refreshParams);
        root.addView(actions, new LinearLayout.LayoutParams(-1, dp(58)));

        TextView hint = label("← Swipe right to Live TV", 11f, accent, true);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, new LinearLayout.LayoutParams(-1, dp(30)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
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
        repo.loadFeed(new SupabaseSocialRepository.ResultCallback<java.util.List<SocialPost>>() {
            @Override
            public void onComplete(SocialResult<java.util.List<SocialPost>> result) {
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
        if (message == null) return "unknown error";
        return message.length() > 110 ? message.substring(0, 110) : message;
    }

    private void addPost(SocialPost post) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackgroundColor(card);
        c.setFocusable(true);
        c.setClickable(true);

        String author = post.getAuthor().getDisplayName();
        if (author == null || author.trim().isEmpty()) author = post.getAuthor().getUsername();
        if (author == null || author.trim().isEmpty()) author = "TV 49 creator";
        c.addView(label(author + "  •  @" + post.getAuthor().getUsername(), 14f, accent, true), new LinearLayout.LayoutParams(-1, dp(28)));
        c.addView(label(post.getBody(), 16f, textColor, false), new LinearLayout.LayoutParams(-1, -2));
        TextView stats = label("♥ " + post.getLikeCount() + "    ↩ " + post.getReplyCount() + "    ⟳ " + post.getRepostCount(), 12f, muted, false);
        stats.setPadding(0, dp(12), 0, 0);
        c.addView(stats, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(10);
        feed.addView(c, params);
    }

    private void addEmpty(String title, String detail) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(20), dp(40), dp(20), dp(40));
        c.setBackgroundColor(card);
        TextView titleView = label(title, 18f, textColor, true);
        titleView.setGravity(Gravity.CENTER);
        c.addView(titleView, new LinearLayout.LayoutParams(-1, -2));
        TextView detailView = label(detail, 13f, muted, false);
        detailView.setGravity(Gravity.CENTER);
        detailView.setPadding(0, dp(8), 0, 0);
        c.addView(detailView, new LinearLayout.LayoutParams(-1, -2));
        feed.addView(c, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showComposer() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        EditText input = new EditText(this);
        input.setHint("What is happening in TV 49 East?");
        input.setTextColor(textColor);
        input.setHintTextColor(muted);
        input.setMinLines(4);
        input.setGravity(Gravity.TOP);
        box.addView(input, new LinearLayout.LayoutParams(-1, dp(140)));
        final EditText postInput = input;
        new AlertDialog.Builder(this)
                .setTitle("Create post")
                .setView(box)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("POST", (dialog, which) -> {
                    String body = postInput.getText().toString().trim();
                    if (body.isEmpty()) return;
                    repo.createPost(body, new SupabaseSocialRepository.ResultCallback<SocialPost>() {
                        @Override
                        public void onComplete(SocialResult<SocialPost> result) {
                            runOnUiThread(() -> {
                                if (result.getError() != null) Toast.makeText(SocialActivity.this, safeMessage(result.getError()), Toast.LENGTH_LONG).show();
                                else loadFeed();
                            });
                        }
                    });
                })
                .show();
    }

    private void showAuth() {
        if (!repo.isConfigured()) {
            Toast.makeText(this, "Add -PsupabaseUrl and -PsupabaseAnonKey when building the APK", Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), 0, dp(22), 0);
        EditText email = new EditText(this);
        email.setHint("Email");
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText password = new EditText(this);
        password.setHint("Password");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(email, new LinearLayout.LayoutParams(-1, dp(56)));
        box.addView(password, new LinearLayout.LayoutParams(-1, dp(56)));
        final EditText emailInput = email;
        final EditText passwordInput = password;
        new AlertDialog.Builder(this)
                .setTitle("TV 49 account")
                .setView(box)
                .setNegativeButton("CANCEL", null)
                .setNeutralButton("CREATE ACCOUNT", (dialog, which) -> repo.signUp(emailInput.getText().toString(), passwordInput.getText().toString(), authCallback()))
                .setPositiveButton("SIGN IN", (dialog, which) -> repo.signIn(emailInput.getText().toString(), passwordInput.getText().toString(), authCallback()))
                .show();
    }

    private SupabaseSocialRepository.ResultCallback<SocialSession> authCallback() {
        return new SupabaseSocialRepository.ResultCallback<SocialSession>() {
            @Override
            public void onComplete(SocialResult<SocialSession> result) {
                runOnUiThread(() -> {
                    if (result.getError() != null) {
                        Toast.makeText(SocialActivity.this, safeMessage(result.getError()), Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(SocialActivity.this, "Signed in", Toast.LENGTH_SHORT).show();
                        loadFeed();
                    }
                });
            }
        };
    }
}
