package com.fadcam.tv;

import android.app.Activity;
import android.app.Application;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.tv49.com.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native cross-screen polish layer. It never replaces the Media3/FadCam player. */
public final class Tv49ExperienceApplication extends Application {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                if (activity instanceof SocialActivity) MatchFeedInjector.attach((SocialActivity) activity, executor);
                if (activity instanceof MainActivity) StreamChromeInjector.attach((MainActivity) activity);
            }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }

    static int dp(Activity a, int v) { return Math.round(v * a.getResources().getDisplayMetrics().density); }
    static GradientDrawable bg(int color, int radius) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius); return d; }
    static TextView label(Activity a, String s, float size, int color, boolean bold) { TextView t=new TextView(a);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL);return t; }

    static final class MatchFeedInjector {
        private static final int GREEN = Color.rgb(0,186,124);
        private static final int INK = Color.rgb(15,20,25);
        private static final int MUTED = Color.rgb(83,100,113);
        private static final int SURFACE = Color.rgb(247,250,249);
        private static final String TAG = "tv49_match_feed";

        static void attach(SocialActivity activity, ExecutorService executor) {
            View existing=activity.findViewById(TAG.hashCode());
            if(existing!=null) return;
            final LinearLayout card=new LinearLayout(activity); card.setId(TAG.hashCode()); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(activity,16),dp(activity,13),dp(activity,16),dp(activity,13)); card.setBackground(bg(SURFACE,dp(activity,16)));
            TextView head=label(activity,"MATCH CENTER",12,GREEN,true); card.addView(head,new LinearLayout.LayoutParams(-1,dp(activity,24)));
            TextView loading=label(activity,"Live scores and fixtures",14,MUTED,false); card.addView(loading,new LinearLayout.LayoutParams(-1,dp(activity,32)));
            Button open=new Button(activity); open.setText("Open Live Scores"); open.setTextSize(12); open.setAllCaps(false); open.setTextColor(Color.WHITE); open.setBackground(bg(GREEN,dp(activity,18))); open.setOnClickListener(v->activity.startActivity(new android.content.Intent(activity,FootballActivity.class))); card.addView(open,new LinearLayout.LayoutParams(-1,dp(activity,42)));
            try {
                java.lang.reflect.Field f=SocialActivity.class.getDeclaredField("content"); f.setAccessible(true); Object value=f.get(activity); if(!(value instanceof LinearLayout)) return; LinearLayout content=(LinearLayout)value; content.addView(card,0,new LinearLayout.LayoutParams(-1,-2));
            } catch(Exception ignored) { return; }
            executor.execute(()->loadMatches(activity,card));
        }

        private static void loadMatches(Activity activity, LinearLayout card) {
            HttpURLConnection c=null;
            try {
                String base=BuildConfig.FOOTBALL_API_URL; if(base==null||base.trim().isEmpty()) return;
                String url=base.endsWith("/")?base+"eng.1/scoreboard?limit=6":base+"/eng.1/scoreboard?limit=6";
                c=(HttpURLConnection)new URL(url).openConnection(); c.setConnectTimeout(6000); c.setReadTimeout(8000); c.connect();
                if(c.getResponseCode()<200||c.getResponseCode()>=300) return;
                BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream())); StringBuilder b=new StringBuilder(); String line; while((line=r.readLine())!=null)b.append(line); JSONArray events=new JSONObject(b.toString()).optJSONArray("events");
                if(events==null) return;
                activity.runOnUiThread(()->{
                    while(card.getChildCount()>3) card.removeViewAt(1);
                    int count=Math.min(4,events.length());
                    for(int i=0;i<count;i++){
                        JSONObject e=events.optJSONObject(i); if(e==null)continue; JSONObject c=e.optJSONObject("competitions")!=null?e.optJSONArray("competitions").optJSONObject(0):null; if(c==null)continue; JSONArray comps=c.optJSONArray("competitors"); if(comps==null||comps.length()<2)continue;
                        JSONObject home=comps.optJSONObject(0), away=comps.optJSONObject(1); String hn=home==null?"Home":home.optJSONObject("team").optString("displayName","Home"); String an=away==null?"Away":away.optJSONObject("team").optString("displayName","Away"); String hs=home==null?"-":home.optString("score","-"); String as=away==null?"-":away.optString("score","-");
                        TextView m=label(activity,hn+"  "+hs+"   ·   "+as+"  "+an,13,INK,true); m.setPadding(dp(activity,2),dp(activity,5),0,dp(activity,5)); card.addView(m,1,new LinearLayout.LayoutParams(-1,dp(activity,30)));
                    }
                    TextView foot=label(activity,"Scores refresh when the feed opens • powered by the configured football adapter",10,MUTED,false); card.addView(foot,new LinearLayout.LayoutParams(-1,dp(activity,24)));
                });
            }catch(Exception ignored){} finally {if(c!=null)c.disconnect();}
        }
    }

    static final class StreamChromeInjector {
        private static final int BG=Color.argb(220,20,18,25); private static final int ACCENT=Color.rgb(207,186,253); private static final String TAG="tv49_stream_chrome";
        static void attach(MainActivity activity) {
            if(activity.findViewById(TAG.hashCode())!=null)return;
            FrameLayout root=(FrameLayout)activity.getWindow().getDecorView().findViewById(android.R.id.content); if(root==null)return;
            LinearLayout bar=new LinearLayout(activity); bar.setId(TAG.hashCode()); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(activity,14),dp(activity,8),dp(activity,10),dp(activity,8)); bar.setBackground(bg(BG,dp(activity,20)));
            TextView brand=label(activity,"TV 49 EAST",14,Color.WHITE,true);bar.addView(brand,new LinearLayout.LayoutParams(0,dp(activity,42),1));
            TextView live=label(activity,"● FadCam LIVE",12,Color.rgb(130,240,150),true);live.setGravity(Gravity.CENTER);bar.addView(live,new LinearLayout.LayoutParams(dp(activity,120),dp(activity,42)));
            Button social=new Button(activity);social.setText("Social");social.setTextSize(11);social.setAllCaps(false);social.setTextColor(Color.WHITE);social.setBackground(bg(Color.rgb(45,38,61),dp(activity,17)));social.setOnClickListener(v->activity.startActivity(new android.content.Intent(activity,SocialActivity.class)));bar.addView(social,new LinearLayout.LayoutParams(dp(activity,88),dp(activity,42)));
            FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-1,dp(activity,58),Gravity.TOP);p.setMargins(dp(activity,12),dp(activity,12),dp(activity,12),0);root.addView(bar,p);
        }
    }
}
