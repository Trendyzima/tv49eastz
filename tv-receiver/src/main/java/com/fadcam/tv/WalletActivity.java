package com.fadcam.tv;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.fadcam.tv.social.SocialResult;
import com.fadcam.tv.social.SupabaseSocialRepository;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.RequestBody;
import java.io.IOException;

/** Wallet UI. Payment credentials remain on the server; Android only receives an approval URL. */
public final class WalletActivity extends android.app.Activity {
    private SupabaseSocialRepository repo;
    private TextView balance;
    private EditText amount;
    private final OkHttpClient http = new OkHttpClient();
    private final Gson gson = new Gson();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        repo = new SupabaseSocialRepository(this);
        build();
        if ("tv49".equals(getIntent().getScheme())) captureIfNeeded(getIntent().getData());
        else refreshBalance();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private TextView text(String s, float size) { TextView t=new TextView(this); t.setText(s); t.setTextColor(Color.WHITE); t.setTextSize(size); t.setPadding(dp(8),dp(8),dp(8),dp(8)); return t; }

    private void build() {
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(24),dp(30),dp(24),dp(24)); root.setGravity(Gravity.CENTER_HORIZONTAL); root.setBackgroundColor(Color.rgb(10,10,14));
        root.addView(text("TV 49 East Wallet",28), new LinearLayout.LayoutParams(-1,dp(60)));
        balance=text("Balance: loading…",20); root.addView(balance,new LinearLayout.LayoutParams(-1,dp(55)));
        root.addView(text("Top up securely with PayPal. The PayPal secret never enters the APK.",14),new LinearLayout.LayoutParams(-1,dp(60)));
        amount=new EditText(this); amount.setHint("Amount in USD (e.g. 10.00)"); amount.setTextColor(Color.WHITE); amount.setHintTextColor(Color.LTGRAY); amount.setInputType(2|8192); root.addView(amount,new LinearLayout.LayoutParams(-1,dp(58)));
        Button pay=new Button(this); pay.setText("PAY WITH PAYPAL"); pay.setAllCaps(false); pay.setFocusable(true); pay.setOnClickListener(v->createOrder()); root.addView(pay,new LinearLayout.LayoutParams(-1,dp(58)));
        Button back=new Button(this); back.setText("BACK TO SOCIAL"); back.setAllCaps(false); back.setOnClickListener(v->finish()); root.addView(back,new LinearLayout.LayoutParams(-1,dp(52)));
        setContentView(root);
    }

    private void refreshBalance() {
        String uid=repo.currentUserId(); if(uid==null){balance.setText("Sign in to use your wallet"); return;}
        Request r=new Request.Builder().url(com.tv49.com.BuildConfig.SUPABASE_URL.replaceAll("/$","")+"/rest/v1/wallets?user_id=eq."+uid+"&select=balance_cents,currency").header("apikey",com.tv49.com.BuildConfig.SUPABASE_ANON_KEY).header("Authorization","Bearer "+repo.currentAccessToken()).build();
        http.newCall(r).enqueue(new Callback(){public void onFailure(Call c,IOException e){runOnUiThread(()->balance.setText("Balance unavailable"));} public void onResponse(Call c,Response r)throws IOException{try{String s=r.body()==null?"[]":r.body().string(); if(r.isSuccessful()){var a=gson.fromJson(s,com.google.gson.JsonArray.class); if(a.size()>0){long cents=a.get(0).getAsJsonObject().get("balance_cents").getAsLong();String cur=a.get(0).getAsJsonObject().get("currency").getAsString();runOnUiThread(()->balance.setText(String.format("Balance: %s %.2f",cur,cents/100.0)));}else runOnUiThread(()->balance.setText("Balance: USD 0.00"));}}finally{r.close();}}});
    }

    private void createOrder() {
        if(repo.currentAccessToken()==null){Toast.makeText(this,"Sign in first",Toast.LENGTH_SHORT).show();return;}
        double value; try{value=Double.parseDouble(amount.getText().toString().trim());}catch(Exception e){value=0;}
        long cents=Math.round(value*100); if(cents<100){Toast.makeText(this,"Minimum top-up is $1.00",Toast.LENGTH_SHORT).show();return;}
        callServer("create", cents, null);
    }

    private void callServer(String action,long cents,String orderId) {
        JsonObject p=new JsonObject();p.addProperty("action",action);if("create".equals(action)){p.addProperty("amount_cents",cents);p.addProperty("currency","USD");}else p.addProperty("order_id",orderId);
        RequestBody b=RequestBody.create(gson.toJson(p),okhttp3.MediaType.parse("application/json"));
        Request r=new Request.Builder().url(com.tv49.com.BuildConfig.SUPABASE_URL.replaceAll("/$","")+"/functions/v1/paypal-wallet").post(b).header("Authorization","Bearer "+repo.currentAccessToken()).header("apikey",com.tv49.com.BuildConfig.SUPABASE_ANON_KEY).build();
        http.newCall(r).enqueue(new Callback(){public void onFailure(Call c,IOException e){runOnUiThread(()->Toast.makeText(WalletActivity.this,"Payment service unavailable",Toast.LENGTH_LONG).show());}public void onResponse(Call c,Response r)throws IOException{try{String s=r.body()==null?"{}":r.body().string();JsonObject o=gson.fromJson(s,JsonObject.class);if(r.isSuccessful()&&"create".equals(action)){String url=o.has("approval_url")&&!o.get("approval_url").isJsonNull()?o.get("approval_url").getAsString():null;if(url!=null)runOnUiThread(()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url))));else runOnUiThread(()->Toast.makeText(WalletActivity.this,"PayPal approval URL missing",Toast.LENGTH_LONG).show());}else if(!r.isSuccessful())runOnUiThread(()->Toast.makeText(WalletActivity.this,"PayPal: "+o.toString(),Toast.LENGTH_LONG).show());}finally{r.close();}}});
    }

    private void captureIfNeeded(Uri data) {
        if(data==null)return; String order=data.getQueryParameter("token"); if(order==null)order=data.getQueryParameter("order_id"); if(order==null){refreshBalance();return;} final String id=order; callServer("capture",0,id); Toast.makeText(this,"Confirming PayPal payment…",Toast.LENGTH_SHORT).show();
    }
}
