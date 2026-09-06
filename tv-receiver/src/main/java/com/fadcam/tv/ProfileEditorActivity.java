package com.fadcam.tv;

import android.graphics.Color;
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
            if (r.getError() != null) { status.setText("Couldn't load your profile: " + message(r.getError().getMessage())); return; }
            renderEditor(r.getValue());
        }));
    }

    private void renderLoading() {
        LinearLayout root = base();
        root.addView(text("Edit profile", 24, Color.rgb(38,29,48), true));
        status = text("Loading your profile…", 13, Color.DKGRAY, false);
        root.addView(status);
        setContentView(root);
    }

    private void renderEditor(SocialUser profile) {
        LinearLayout root = base();
        root.addView(text("Edit profile", 24, Color.rgb(38,29,48), true));
        root.addView(text("Your changes are saved to the authenticated Supabase profile row.", 13, Color.DKGRAY, false));

        username = field("Username", profile == null ? "" : profile.getUsername());
        displayName = field("Display name", profile == null ? "" : profile.getDisplayName());
        bio = field("Bio", profile == null ? "" : profile.getBio());
        bio.setMinLines(4);
        bio.setGravity(Gravity.TOP);
        root.addView(username, lp(56, 0));
        root.addView(displayName, lp(56, 0));
        root.addView(bio, lp(110, 0));

        status = text("Username: 3–32 letters, numbers or underscores", 12, Color.DKGRAY, false);
        root.addView(status, lp(42, 0));
        save = new Button(this);
        save.setText("Save profile");
        save.setOnClickListener(v -> saveProfile());
        root.addView(save, lp(52, 0));
        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setOnClickListener(v -> finish());
        root.addView(cancel, lp(52, 0));
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
        e.setText(value == null ? "" : value);
        e.setTextColor(Color.rgb(38,29,48));
        e.setTextSize(15);
        e.setPadding(dp(14), 0, dp(14), 0);
        e.setBackgroundColor(Color.rgb(245,242,248));
        return e;
    }

    private LinearLayout base() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(18), dp(22), dp(18), dp(22));
        l.setBackgroundColor(Color.WHITE);
        return l;
    }

    private LinearLayout.LayoutParams lp(int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(h));
        p.topMargin = dp(top);
        return p;
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(null, 1);
        return t;
    }

    private String message(String s) { return s == null || s.trim().isEmpty() ? "Please try again." : s; }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
