package com.fadcam.tv;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.fadcam.tv.social.SupabaseSocialRepository;
import com.tv49.com.BuildConfig;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/** Creator monetization hub: earnings, tips, subscriptions, shopping and payout request entry points. */
public class CreatorStudioActivity extends AppCompatActivity {
    private SupabaseSocialRepository repo;
    private final OkHttpClient http=new OkHttpClient();
    @Override protected void onCreate(@Nullable Bundle state){super.onCreate(state);repo=new SupabaseSocialRepository(this);render();}
    private void render(){LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(20),dp(20),dp(20));root.setBackgroundColor(Color.WHITE);TextView title=text("Creator Studio",27,Color.rgb(15,20,25),true);root.addView(title,new LinearLayout.LayoutParams(-1,dp(54)));TextView sub=text("Build an audience. Measure engagement. Earn from eligible creator features.",14,Color.DKGRAY,false);root.addView(sub,new LinearLayout.LayoutParams(-1,dp(54)));LinearLayout cards=new LinearLayout(this);cards.setOrientation(LinearLayout.HORIZONTAL);String[] values={"$0.00\nAvailable","0\nSubscribers","0\nTips","0\nViews"};for(String v:values){TextView c=text(v,16,Color.rgb(15,20,25),true);c.setGravity(Gravity.CENTER);c.setBackgroundColor(Color.rgb(245,248,248));cards.addView(c,new LinearLayout.LayoutParams(0,dp(92),1));}root.addView(cards);Button reels=new Button(this);reels.setText("Create Reel");reels.setOnClickListener(v->startActivity(new android.content.Intent(this,ReelComposerActivity.class)));root.addView(reels,new LinearLayout.LayoutParams(-1,dp(52)));Button profile=new Button(this);profile.setText("Professional profile");profile.setOnClickListener(v->startActivity(new android.content.Intent(this,ProfessionalProfileActivity.class)));root.addView(profile,new LinearLayout.LayoutParams(-1,dp(52)));Button payout=new Button(this);payout.setText("Request payout");payout.setOnClickListener(v->requestPayout());root.addView(payout,new LinearLayout.LayoutParams(-1,dp(52)));Button wallet=new Button(this);wallet.setText("Open wallet");wallet.setOnClickListener(v->startActivity(new android.content.Intent(this,WalletActivity.class)));root.addView(wallet,new LinearLayout.LayoutParams(-1,dp(52)));setContentView(root);}
    private void requestPayout(){if(!repo.isSignedIn()){toast("Sign in first");return;}new Thread(()->{try{Map<String,Object>b=new HashMap<>();b.put("user_id",repo.currentUserId());b.put("amount_cents",1000);b.put("currency","USD");Request r=new Request.Builder().url(BuildConfig.SUPABASE_URL.trim().replaceAll("/$","")+"/rest/v1/payout_requests").header("apikey",BuildConfig.SUPABASE_ANON_KEY).header("Authorization","Bearer "+repo.currentAccessToken()).post(RequestBody.create(new com.google.gson.Gson().toJson(b),MediaType.parse("application/json"))).build();okhttp3.Response x=http.newCall(r).execute();if(!x.isSuccessful())throw new IOException("Payout request "+x.code());runOnUiThread(()->toast("Payout request submitted"));}catch(Throwable t){runOnUiThread(()->toast("Payout unavailable: "+t.getMessage()));}}).start();}
    private TextView text(String s,float z,int c,boolean b){TextView t=new TextView(this);t.setText(s);t.setTextSize(z);t.setTextColor(c);if(b)t.setTypeface(null,1);return t;}private void toast(String s){android.widget.Toast.makeText(this,s,android.widget.Toast.LENGTH_SHORT).show();}private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
