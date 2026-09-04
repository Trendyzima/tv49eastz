package com.fadcam.tv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.fadcam.tv.football.FootballMatch;
import com.fadcam.tv.football.FootballRepository;
import java.util.List;

/** Native football hub: live scores, fixtures, results and competition tables. */
public final class FootballActivity extends Activity {
    private final FootballRepository repository = new FootballRepository();
    private final Handler handler = new Handler();
    private LinearLayout content;
    private TextView status;
    private String competition = "Premier League";
    private String mode = "live";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        buildUi(); load();
        handler.postDelayed(new Runnable() { @Override public void run() { load(); handler.postDelayed(this, 30000); } }, 30000);
    }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private TextView text(String value, float size, int color, boolean bold) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL); return t; }
    private Button button(String value, View.OnClickListener listener) { Button b = new Button(this); b.setText(value); b.setTextSize(13); b.setAllCaps(false); b.setOnClickListener(listener); b.setFocusable(true); return b; }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.WHITE);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(16), dp(8), dp(16), dp(4));
        TextView title = text("⚽ Live Football", 22, Color.rgb(15,20,25), true); header.addView(title, new LinearLayout.LayoutParams(0, dp(50), 1));
        header.addView(button("Refresh", v -> load()), new LinearLayout.LayoutParams(dp(100), dp(46))); root.addView(header);

        HorizontalScrollView competitions = new HorizontalScrollView(this); competitions.setHorizontalScrollBarEnabled(false);
        LinearLayout c = new LinearLayout(this);
        String[] leagues = {"Premier League","Championship","LaLiga","Bundesliga","Serie A","Ligue 1","Eredivisie","Primeira Liga","Brazil Série A","Argentina Primera","Liga MX","MLS","UEFA Champions League","UEFA Europa League","Copa Libertadores","FIFA World Cup"};
        for (String league : leagues) { Button b = button(league, v -> { competition = league; load(); }); c.addView(b, new LinearLayout.LayoutParams(-2, dp(48))); }
        competitions.addView(c); root.addView(competitions, new LinearLayout.LayoutParams(-1, dp(56)));

        LinearLayout modes = new LinearLayout(this); modes.setGravity(Gravity.CENTER); String[] tabs = {"LIVE","FIXTURES","RESULTS","TABLE"};
        for (String tab : tabs) { Button b = button(tab, v -> { mode = tab.equals("LIVE") ? "live" : tab.equals("FIXTURES") ? "fixtures" : tab.equals("RESULTS") ? "results" : "standings"; load(); }); modes.addView(b, new LinearLayout.LayoutParams(0, dp(46), 1)); }
        root.addView(modes); status = text("Loading…", 12, Color.DKGRAY, false); status.setPadding(dp(18), dp(3), dp(18), dp(5)); root.addView(status);
        ScrollView scroll = new ScrollView(this); content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(10), dp(4), dp(10), dp(20)); scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void load() {
        status.setText(competition + " • " + modeLabel() + " • refreshing every 30s");
        repository.loadMatches(competition, mode, (matches, error) -> runOnUiThread(() -> {
            content.removeAllViews();
            if (error != null) { content.addView(text("Football feed unavailable: " + error.getMessage(), 15, Color.DKGRAY, false)); return; }
            if (matches == null || matches.isEmpty()) { content.addView(text("No matches in this view right now.", 16, Color.DKGRAY, false)); return; }
            int index = 1; for (FootballMatch match : matches) addMatch(match, index++);
        }));
    }
    private String modeLabel() { return "live".equals(mode) ? "Live" : "fixtures".equals(mode) ? "Fixtures" : "results".equals(mode) ? "Results" : "Table"; }
    private void addMatch(FootballMatch m, int index) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(10), dp(16), dp(10)); card.setBackgroundColor(Color.rgb(248,250,251));
        if ("table".equals(m.state)) { TextView row = text(index + ".  " + m.away + "    " + m.status, 15, Color.rgb(15,20,25), true); row.setPadding(0, dp(6), 0, dp(6)); card.addView(row); }
        else { card.addView(text(m.competition + "  •  " + m.status, 11, Color.DKGRAY, false)); TextView teams = text(m.home + "    " + m.homeScore + "  –  " + m.awayScore + "    " + m.away, 16, Color.rgb(15,20,25), true); teams.setPadding(0, dp(5), 0, dp(2)); card.addView(teams); card.addView(text(m.kickoff, 11, Color.DKGRAY, false)); }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, 0, 0, dp(7)); content.addView(card, lp);
    }
}
