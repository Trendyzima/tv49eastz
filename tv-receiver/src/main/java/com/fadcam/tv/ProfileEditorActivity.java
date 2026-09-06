package com.fadcam.tv;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.fadcam.tv.social.SocialUser;
import com.fadcam.tv.social.SupabaseSocialRepository;

/** Native profile editor backed directly by Supabase profiles RLS. */
public final class ProfileEditorActivity extends AppCompatActivity {
    private static final int INK = Color.rgb(38, 29, 48);
    private static final int MUTED = Color.rgb(118, 108, 126);
    private static final int SURFACE = Color.rgb(248, 245, 251);
    private static final int FIELD = Color.rgb(245, 242, 248);
    private static final int ACCENT = Color.rgb(123, 92, 255);

    private SupabaseSocialRepository repo;
    private EditText username;
    private EditText displayName;
    private EditText bio;
    private TextView status;
    private Button save;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        repo = new SupabaseSocialRepository(this);
        if (!repo.isSignedIn()) { finish(); return; }
        renderLoading();
        repo.loadProfile(r -> runOnUiThread(() -> {
            if (r.getError() != null) {
                status.setText("Couldn't load your profile: " + message(r.getError().getMessage()));
                return;
            }
            renderEditor(r.getValue());
        }));
    }

    private void renderLoading() {
        LinearLayout root = base();
        root.addView(text("Edit profile", 25, INK, true), lp(42, 0));
        status = text("Loading your profile…", 13, MUTED, false);
        root.addView(status, lp(34, 4));
        setContentView(root);
    }

    private void renderEditor(SocialUser profile) {
        LinearLayout root = base();
        root.addView(text("Edit profile", 25, INK, true), lp(42, 0));
        root.addView(text("Keep your profile current so people know who they are connecting with.", 13, MUTED, false), lp(36, 2));

        username = field("Username", profile == null ? "" : profile.getUsername());
        displayName = field("Display name", profile == null ? "" : profile.getDisplayName());
        bio = field("Bio", profile == null ? "" : profile.getBio());
        bio.setMinLines(4);
        bio.setGravity(Gravity.TOP);
        root.addView(username, lp(58, 12));
        root.addView(displayName, lp(58, 10));
        root.addView(bio, lp(116, 10));

        status = text("Username: 3–32 letters, numbers or underscores", 12, MUTED, false);
        root.addView(status, lp(40, 8));

        save = pillButton("Save profile", ACCENT, Color.WHITE);
        save.setOnClickListener(v -> saveProfile());
        root.addView(save, lp(50, 10));

        Button cancel = pillButton("Cancel", FIELD, INK);
        cancel.setOnClickListener(v -> finish());
        root.addView(cancel, lp(50, 8));
        setContentView(root);
    }

    private void saveProfile() {
        save.setEnabled(false);
        status.setText("Saving…");
        repo.updateProfile(username.getText().toString(), displayName.getText().toString(), bio.getText().toString(), null, r -> runOnUiThread(() -> {
            save.setEnabled(true);
            if (r.getError() != null) {
                status.setText("Couldn't save: " + message(r.getError().getMessage()));
                return;
            }
            status.setText("Profile saved successfully.");
            setResult(RESULT_OK);
        }));
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(MUTED);
        e.setText(value == null ? "" : value);
        e.setTextColor(INK);
        e.setTextSize(15);
        e.setGravity(Gravity.CENTER_VERTICAL);
        e.setPadding(dp(16), dp(4), dp(16), dp(4));
        e.setBackground(round(FIELD, 16));
        return e;
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

    private LinearLayout base() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(18), dp(22), dp(18), dp(22));
        l.setBackgroundColor(SURFACE);
        return l;
    }

    private LinearLayout.LayoutParams lp(int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(h));
        p.topMargin = dp(top);
        return p;
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, 1);
        return t;
    }

    private String message(String s) { return s == null || s.trim().isEmpty() ? "Please try again." : s; }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
