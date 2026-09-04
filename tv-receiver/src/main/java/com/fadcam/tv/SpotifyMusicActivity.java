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
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tv49.com.BuildConfig;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/** Spotify catalog/discovery surface. It stores metadata/deep-links, never Spotify audio in a Reel. */
public class SpotifyMusicActivity extends AppCompatActivity {
    private final OkHttpClient http = new OkHttpClient.Builder().readTimeout(15, TimeUnit.SECONDS).build();
    private final Gson gson = new Gson();
    private LinearLayout list;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(18),dp(18),dp(18),dp(18)); root.setBackgroundColor(Color.WHITE);
        TextView title = new TextView(this); title.setText("Music for your Reels"); title.setTextSize(25); title.setTextColor(Color.rgb(15,20,25)); title.setGravity(Gravity.CENTER_VERTICAL); root.addView(title,new LinearLayout.LayoutParams(-1,dp(52)));
        TextView note = new TextView(this); note.setText("Search Spotify to discover a track. TV 49 East saves the track reference/attribution; Spotify audio is not copied into videos."); note.setTextSize(13); note.setTextColor(Color.DKGRAY); root.addView(note,new LinearLayout.LayoutParams(-1,dp(60)));
        LinearLayout searchRow = new LinearLayout(this); searchRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText query = new EditText(this); query.setHint("Artist, song or album"); searchRow.addView(query,new LinearLayout.LayoutParams(0,dp(52),1));
        Button search = new Button(this); search.setText("Search"); search.setOnClickListener(v -> search(query.getText().toString())); searchRow.addView(search,new LinearLayout.LayoutParams(dp(110),dp(52))); root.addView(searchRow);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }

    private void search(String q) {
        list.removeAllViews(); TextView loading = new TextView(this); loading.setText("Searching Spotify…"); loading.setTextSize(14); list.addView(loading);
        if (BuildConfig.SUPABASE_URL.trim().isEmpty()) { loading.setText("Supabase is not configured."); return; }
        new Thread(() -> {
            try {
                String endpoint = BuildConfig.SUPABASE_URL.trim().replaceAll("/$", "") + "/functions/v1/spotify-catalog?q=" + Uri.encode(q.trim());
                Request req = new Request.Builder().url(endpoint).header("apikey", BuildConfig.SUPABASE_ANON_KEY).get().build();
                okhttp3.Response response = http.newCall(req).execute();
                String body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) throw new IOException("Spotify search " + response.code());
                JsonObject root = gson.fromJson(body, JsonObject.class); JsonArray tracks = root.getAsJsonObject("tracks").getAsJsonArray("items");
                runOnUiThread(() -> render(tracks));
            } catch (Throwable t) { runOnUiThread(() -> loading.setText("Spotify unavailable: " + t.getMessage())); }
        }).start();
    }

    private void render(JsonArray tracks) {
        list.removeAllViews();
        for (int i=0; i<tracks.size(); i++) {
            JsonObject t=tracks.get(i).getAsJsonObject(); String id=t.get("id").getAsString(); String name=t.get("name").getAsString();
            JsonArray artists=t.getAsJsonArray("artists"); String artist=artists.size()>0?artists.get(0).getAsJsonObject().get("name").getAsString():"";
            String spotifyUrl=t.getAsJsonObject("external_urls").get("spotify").getAsString();
            LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0,dp(7),0,dp(7));
            TextView info=new TextView(this); info.setText(name+"\n"+artist); info.setTextSize(15); info.setTextColor(Color.rgb(15,20,25)); row.addView(info,new LinearLayout.LayoutParams(0,dp(62),1));
            Button open=new Button(this); open.setText("Spotify"); open.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(spotifyUrl)))); row.addView(open,new LinearLayout.LayoutParams(dp(105),dp(48)));
            Button use=new Button(this); use.setText("Use"); use.setOnClickListener(v -> { Intent data=new Intent(); data.putExtra("spotify_track_id",id); data.putExtra("spotify_track_name",name); data.putExtra("spotify_artist",artist); data.putExtra("spotify_url",spotifyUrl); setResult(RESULT_OK,data); finish(); }); row.addView(use,new LinearLayout.LayoutParams(dp(85),dp(48)));
            list.addView(row);
        }
        if (tracks.size()==0) { TextView empty=new TextView(this); empty.setText("No tracks found."); list.addView(empty); }
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
