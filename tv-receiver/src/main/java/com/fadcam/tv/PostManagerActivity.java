package com.fadcam.tv;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.fadcam.tv.social.SocialPost;
import com.fadcam.tv.social.SocialUser;
import com.fadcam.tv.social.SupabaseSocialRepository;
import java.util.List;

/** Own-post management surface: reads the authenticated feed and edits only the current user's posts. */
public final class PostManagerActivity extends AppCompatActivity {
    private SupabaseSocialRepository repo;
    private LinearLayout list;
    private TextView status;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        repo = new SupabaseSocialRepository(this);
        if (!repo.isSignedIn()) { finish(); return; }
        build(); refresh();
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(16), dp(18), dp(16), 0); root.setBackgroundColor(Color.rgb(248,245,251));
        root.addView(text("Your posts", 24, Color.rgb(38,29,48), true), lp(48, 0));
        status = text("Loading…", 13, Color.DKGRAY, false); root.addView(status, lp(34, 0));
        ScrollView scroll = new ScrollView(this); list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(0, dp(8), 0, dp(24)); scroll.addView(list, new ScrollView.LayoutParams(-1, -2)); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout actions = new LinearLayout(this); Button refresh = new Button(this); refresh.setText("Refresh"); refresh.setOnClickListener(v -> refresh()); Button create = new Button(this); create.setText("Create post"); create.setOnClickListener(v -> startActivity(new android.content.Intent(this, ModernSocialActivity.class))); actions.addView(refresh, new LinearLayout.LayoutParams(0, dp(52), 1)); actions.addView(create, new LinearLayout.LayoutParams(0, dp(52), 1)); root.addView(actions);
        setContentView(root);
    }

    private void refresh() {
        status.setText("Loading your posts…"); list.removeAllViews();
        repo.loadFeed(100, r -> runOnUiThread(() -> {
            if (r.getError() != null) { status.setText("Couldn't load posts: " + message(r.getError().getMessage())); return; }
            String uid = repo.currentUserId(); int count = 0; List<SocialPost> posts = r.getValue();
            if (posts != null) for (SocialPost post : posts) { SocialUser author = post.getAuthor(); if (author == null || !safe(uid, author.getId())) continue; addPost(post); count++; }
            status.setText(count == 0 ? "You haven't published a post yet." : count + " published post" + (count == 1 ? "" : "s"));
        }));
    }

    private void addPost(SocialPost post) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(14), dp(14), dp(14), dp(10)); card.setBackgroundColor(Color.WHITE);
        TextView body = text(post.getBody() == null || post.getBody().trim().isEmpty() ? "Media post" : post.getBody(), 16, Color.rgb(38,29,48), false); body.setGravity(Gravity.CENTER_VERTICAL); card.addView(body, lp(80, 0));
        card.addView(text("Posted " + (post.getCreatedAt() == null ? "recently" : post.getCreatedAt()), 11, Color.DKGRAY, false), lp(30, 0));
        Button edit = new Button(this); edit.setText("Edit post"); edit.setOnClickListener(v -> editPost(post)); card.addView(edit, lp(50, 6));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2); cp.setMargins(0, 0, 0, dp(10)); list.addView(card, cp);
    }

    private void editPost(SocialPost post) {
        final EditText input = new EditText(this); input.setText(post.getBody() == null ? "" : post.getBody()); input.setTextColor(Color.rgb(38,29,48)); input.setGravity(Gravity.TOP); input.setMinLines(5); input.setPadding(dp(14), dp(12), dp(14), dp(12));
        final androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Edit post").setMessage("Only the post owner can update this content.").setView(input).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String body = input.getText().toString().trim();
            if (body.isEmpty() && post.mediaUrlForJava() == null) { input.setError("Add text or keep the existing media"); return; }
            Button save = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE); save.setEnabled(false);
            repo.editPost(post.getId(), body, r -> runOnUiThread(() -> { if (r.getError() != null) { input.setError(message(r.getError().getMessage())); save.setEnabled(true); } else { dialog.dismiss(); status.setText("Post updated successfully."); refresh(); } }));
        }));
        dialog.show();
    }

    private boolean safe(String a, String b) { return a != null && a.equals(b); }
    private String message(String s) { return s == null || s.trim().isEmpty() ? "Please try again." : s; }
    private TextView text(String s, float size, int color, boolean bold) { TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); if (bold) t.setTypeface(null, 1); return t; }
    private LinearLayout.LayoutParams lp(int h, int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(h)); p.topMargin = dp(top); return p; }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
