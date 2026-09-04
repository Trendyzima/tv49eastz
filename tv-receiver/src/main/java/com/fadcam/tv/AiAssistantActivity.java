package com.fadcam.tv;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.fadcam.tv.social.SupabaseSocialRepository;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.io.IOException;

/** Native AI client. The Vercel AI Gateway key is server-side in Supabase Edge Functions. */
public final class AiAssistantActivity extends android.app.Activity {
    private final OkHttpClient http=new OkHttpClient(); private final Gson gson=new Gson(); private SupabaseSocialRepository repo; private EditText prompt; private TextView output;
    @Override protected void onCreate(Bundle state){super.onCreate(state);repo=new SupabaseSocialRepository(this);build();}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void build(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(25),dp(20),dp(20));root.setBackgroundColor(Color.rgb(10,10,14));
        TextView title=new TextView(this);title.setText("TV 49 East AI");title.setTextColor(Color.WHITE);title.setTextSize(28);title.setGravity(Gravity.CENTER_VERTICAL);root.addView(title,new LinearLayout.LayoutParams(-1,dp(58)));
        TextView sub=new TextView(this);sub.setText("AI assistant • recommendations • creator help • post ideas");sub.setTextColor(Color.LTGRAY);sub.setTextSize(13);root.addView(sub,new LinearLayout.LayoutParams(-1,dp(45)));
        prompt=new EditText(this);prompt.setHint("Ask TV 49 East AI…");prompt.setTextColor(Color.WHITE);prompt.setHintTextColor(Color.GRAY);prompt.setGravity(Gravity.TOP);root.addView(prompt,new LinearLayout.LayoutParams(-1,dp(110)));
        Button ask=new Button(this);ask.setText("ASK AI");ask.setAllCaps(false);ask.setFocusable(true);ask.setOnClickListener(v->ask());root.addView(ask,new LinearLayout.LayoutParams(-1,dp(55)));
        output=new TextView(this);output.setTextColor(Color.WHITE);output.setTextSize(15);output.setPadding(dp(8),dp(15),dp(8),dp(15));ScrollView scroll=new ScrollView(this);scroll.addView(output);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        Button back=new Button(this);back.setText("BACK");back.setAllCaps(false);back.setOnClickListener(v->finish());root.addView(back,new LinearLayout.LayoutParams(-1,dp(50)));setContentView(root);
    }
    private void ask(){String token=repo.currentAccessToken();String text=prompt.getText().toString().trim();if(token==null||text.isEmpty()){output.setText("Sign in and enter a question first.");return;}output.setText("Thinking…");JsonObject body=new JsonObject();body.addProperty("feature","social_assistant");body.addProperty("prompt",text);RequestBody rb=RequestBody.create(gson.toJson(body),okhttp3.MediaType.parse("application/json"));Request r=new Request.Builder().url(com.tv49.com.BuildConfig.SUPABASE_URL.replaceAll("/$","")+"/functions/v1/ai-assistant").post(rb).header("Authorization","Bearer "+token).header("apikey",com.tv49.com.BuildConfig.SUPABASE_ANON_KEY).build();http.newCall(r).enqueue(new Callback(){public void onFailure(Call c,IOException e){runOnUiThread(()->output.setText("AI unavailable: "+e.getMessage()));}public void onResponse(Call c,Response r)throws IOException{try{String s=r.body()==null?"{}":r.body().string();JsonObject o=gson.fromJson(s,JsonObject.class);String result=o.has("text")?o.get("text").getAsString():o.toString();runOnUiThread(()->output.setText(result));}finally{r.close();}}});}
}
