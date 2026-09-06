package com.fadcam.tv;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.fadcam.tv.social.SocialFeatureRepository;
import com.fadcam.tv.social.SocialPost;
import com.fadcam.tv.social.SocialUser;
import com.fadcam.tv.social.SupabaseSocialRepository;
import java.util.List;

/** Own-post management surface: reads the authenticated feed and edits only the current user's posts. */
public final class PostManagerActivity extends AppCompatActivity {
    private SupabaseSocialRepository repo;
    private SocialFeatureRepository features;
    private LinearLayout list;
    private TextView status;

    private static final int INK = Color.rgb(38, 29, 48);
    private static final int MUTED = Color.rgb(118, 108, 126);
    private static final int SURFACE = Color.rgb(248, 245, 251);
    private static final int SOFT = Color.rgb(245, 242, 248);
    private static final int ACCENT = Color.rgb(123, 92, 255);

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        repo = new SupabaseSocialRepository(this);
        features = new SocialFeatureRepository(this);
        if (!repo.isSignedIn()) { finish(); return; }
        build();
        refresh();
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), 0);
        root.setBackgroundColor(SURFACE);

        TextView title = text("Your posts", 25, INK, true);
        root.addView(title, lp(42, 0));

        TextView subtitle = text("Manage the posts you have published.", 13, MUTED, false);
        root.addView(subtitle, lp(30, 2));

        status = text("Loading…", 13, MUTED, false);
        root.addView(status, lp(34, 4));

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(8), 0, dp(24));
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button refresh = pillButton("Refresh", SOFT, INK);
        refresh.setOnClickListener(v -> refresh());
        Button create = pillButton("Create post", ACCENT, Color.WHITE);
        create.setOnClickListener(v -> startActivity(new Intent(this, ModernSocialActivity.class)));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, dp(50), 1);
        ap.setMargins(0, 0, dp(8), dp(10));
        actions.addView(refresh, ap);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(50), 1);
        cp.setMargins(dp(8), 0, 0, dp(10));
        actions.addView(create, cp);
        root.addView(actions);

        setContentView(root);
    }

    private void refresh() {
        status.setText("Loading your posts…");
        list.removeAllViews();
        repo.loadFeed(100, r -> runOnUiThread(() -> {
            if (r.getError() != null) {
                status.setText("Couldn't load posts: " + message(r.getError().getMessage()));
                return;
            }
            String uid = repo.currentUserId();
            int count = 0;
            List<SocialPost> posts = r.getValue();
            if (posts != null) {
                for (SocialPost post : posts) {
                    SocialUser author = post.getAuthor();
                    if (author == null || !safe(uid, author.getId())) continue;
                    addPost(post);
                    count++;
                }
            }
            status.setText(count == 0 ? "You haven't published a post yet."
                    : count + " published post" + (count == 1 ? "" : "s"));
        }));
    }

    private void addPost(SocialPost post) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(12));
        card.setBackground(round(Color.WHITE, 18));

        String bodyText = post.getBody() == null || post.getBody().trim().isEmpty()
                ? "Media post" : post.getBody().trim();
        TextView body = text(bodyText, 16, INK, false);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setMaxLines(6);
        card.addView(body, lp(80, 0));

        TextView date = text("Posted " + (post.getCreatedAt() == null ? "recently" : post.getCreatedAt()),
                11, MUTED, false);
        card.addView(date, lp(30, 6));

        Button edit = pillButton("Edit post", SOFT, INK);
        edit.setOnClickListener(v -> editPost(post));
        card.addView(edit, lp(46, 7));

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, 0, 0, dp(12));
        list.addView(card, cp);
    }

    private void editPost(SocialPost post) {
        final EditText input = new EditText(this);
        input.setHint("Write your post…");
        input.setText(post.getBody() == null ? "" : post.getBody());
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setTextSize(15);
        input.setGravity(Gravity.TOP);
        input.setMinLines(5);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setBackground(round(SOFT, 16));

        final androidx.appcompat.app.AlertDialog dialog =
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Edit post")
                        .setMessage("Only the post owner can update this content.")
                        .setView(input)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Save", null)
                        .create();

        dialog.setOnShowListener(ignored -> {
            Button save = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
            save.setTextColor(ACCENT);
            save.setOnClickListener(v -> {
                String body = input.getText().toString().trim();
                if (body.isEmpty() && post.mediaUrlForJava() == null) {
                    input.setError("Add text or keep the existing media");
                    return;
                }
                save.setEnabled(false);
                features.editPost(post.getId(), body, r -> runOnUiThread(() -> {
                    if (r.getError() != null) {
                        input.setError(message(r.getError().getMessage()));
                        save.setEnabled(true);
                    } else {
                        dialog.dismiss();
                        status.setText("Post updated successfully.");
                        refresh();
                    }
                }));
            });
        });
        dialog.show();
    }

    private Button pillButton(String label, int background, int foreground) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(foreground);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setBackground(round(background, 26));
        return b;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private boolean safe(String a, String b) { return a != null && a.equals(b); }
    private String message(String s) { return s == null || s.trim().isEmpty() ? "Please try again." : s; }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, 1);
        return t;
    }

    private LinearLayout.LayoutParams lp(int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(h));
        p.topMargin = dp(top);
        return p;
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
