package com.fadcam.tv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Crash-isolation launcher for the TV receiver. */
public final class HomeActivity extends Activity {
    private static final int BG = Color.rgb(9, 9, 12);
    private static final int CARD = Color.rgb(25, 23, 31);
    private static final int TEXT = Color.WHITE;
    private static final int MUTED = Color.rgb(190, 188, 198);
    private static final int ACCENT = Color.rgb(207, 186, 253);

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        buildHome();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView label(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private void buildHome() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(24), dp(40), dp(24), dp(24));

        TextView logo = label("49", 42, ACCENT, true);
        logo.setGravity(Gravity.CENTER);
        content.addView(logo, new LinearLayout.LayoutParams(-1, dp(70)));
        TextView title = label("TV 49 East", 30, TEXT, true);
        title.setGravity(Gravity.CENTER);
        content.addView(title, new LinearLayout.LayoutParams(-1, dp(48)));
        TextView subtitle = label("Live TV • FadCam • Worldwide channels", 14, MUTED, false);
        subtitle.setGravity(Gravity.CENTER);
        content.addView(subtitle, new LinearLayout.LayoutParams(-1, dp(40)));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(22), dp(22), dp(22), dp(22));
        card.setBackgroundColor(CARD);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = dp(28);
        content.addView(card, cp);

        TextView ready = label("RECEIVER READY", 13, ACCENT, true);
        ready.setGravity(Gravity.CENTER);
        card.addView(ready, new LinearLayout.LayoutParams(-1, dp(30)));
        TextView detail = label("Homepage startup is kept independent from playback and network services.", 13, MUTED, false);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(0, dp(6), 0, dp(16));
        card.addView(detail, new LinearLayout.LayoutParams(-1, -2));

        Button live = new Button(this);
        live.setText("OPEN LIVE TV");
        live.setTextColor(Color.BLACK);
        live.setTextSize(14);
        live.setAllCaps(false);
        live.setBackgroundColor(ACCENT);
        live.setOnClickListener(v -> openLiveTv());
        card.addView(live, new LinearLayout.LayoutParams(-1, dp(52)));

        Button legacy = new Button(this);
        legacy.setText("OPEN RECEIVER");
        legacy.setTextColor(TEXT);
        legacy.setTextSize(13);
        legacy.setAllCaps(false);
        legacy.setOnClickListener(v -> openReceiver());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
        lp.topMargin = dp(10);
        card.addView(legacy, lp);

        TextView footer = label("Safe startup mode • playback loads on demand", 11, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.topMargin = dp(28);
        content.addView(footer, fp);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    private void openLiveTv() {
        try {
            startActivity(new android.content.Intent(this, TvReelsActivityHardened.class));
        } catch (Throwable e) {
            openReceiver();
        }
    }

    private void openReceiver() {
        try {
            startActivity(new android.content.Intent(this, MainActivity.class));
        } catch (Throwable ignored) { }
    }
}
