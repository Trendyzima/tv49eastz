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

/** Native TV 49 East launcher. Keeps Live TV, Social, Football, Wallet and AI as first-class native modes. */
public final class HomeActivity extends Activity {
    private static final int BG=Color.rgb(9,9,12), CARD=Color.rgb(25,23,31), TEXT=Color.WHITE, MUTED=Color.rgb(190,188,198), ACCENT=Color.rgb(207,186,253);
    @Override protected void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);buildHome();}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private TextView label(String value,float size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL);return t;}
    private Button action(String title,View.OnClickListener listener){Button b=new Button(this);b.setText(title);b.setTextSize(13);b.setTextColor(TEXT);b.setAllCaps(false);b.setOnClickListener(listener);b.setFocusable(true);b.setClickable(true);return b;}
    private void buildHome(){
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(BG);LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setGravity(Gravity.CENTER_HORIZONTAL);content.setPadding(dp(24),dp(40),dp(24),dp(24));
        TextView logo=label("49",42,ACCENT,true);logo.setGravity(Gravity.CENTER);content.addView(logo,new LinearLayout.LayoutParams(-1,dp(70)));
        TextView title=label("TV 49 East",30,TEXT,true);title.setGravity(Gravity.CENTER);content.addView(title,new LinearLayout.LayoutParams(-1,dp(48)));
        TextView subtitle=label("Television + social + live football + wallet + AI",14,MUTED,false);subtitle.setGravity(Gravity.CENTER);content.addView(subtitle,new LinearLayout.LayoutParams(-1,dp(40)));
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER);card.setPadding(dp(22),dp(22),dp(22),dp(22));card.setBackgroundColor(CARD);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.topMargin=dp(28);content.addView(card,cp);
        TextView ready=label("CHOOSE A MODE",13,ACCENT,true);ready.setGravity(Gravity.CENTER);card.addView(ready,new LinearLayout.LayoutParams(-1,dp(30)));
        TextView detail=label("Everything here is native Android. Social does not embed or depend on XClone.",13,MUTED,false);detail.setGravity(Gravity.CENTER);detail.setPadding(0,dp(6),0,dp(16));card.addView(detail,new LinearLayout.LayoutParams(-1,-2));
        Button live=action("OPEN LIVE TV / FADCAM",v->open(MainActivity.class));live.setBackgroundColor(ACCENT);live.setTextColor(Color.BLACK);card.addView(live,new LinearLayout.LayoutParams(-1,dp(52)));
        Button football=action("OPEN LIVE SCORES / FIXTURES",v->open(FootballActivity.class));LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(-1,dp(52));fp.topMargin=dp(10);card.addView(football,fp);
        Button social=action("OPEN SOCIAL",v->open(SocialActivity.class));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(52));sp.topMargin=dp(10);card.addView(social,sp);
        Button wallet=action("OPEN WALLET / PAYPAL",v->open(WalletActivity.class));LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(-1,dp(52));wp.topMargin=dp(10);card.addView(wallet,wp);
        Button ai=action("OPEN TV 49 EAST AI",v->open(AiAssistantActivity.class));LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(52));ap.topMargin=dp(10);card.addView(ai,ap);
        TextView footer=label("Touch + D-pad ready • streaming boundary preserved • secrets stay server-side",11,MUTED,false);footer.setGravity(Gravity.CENTER);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(28);content.addView(footer,lp);
        root.addView(content,new FrameLayout.LayoutParams(-1,-1));setContentView(root);
    }
    private void open(Class<?> c){try{startActivity(new android.content.Intent(this,c));}catch(Throwable ignored){}}
}
