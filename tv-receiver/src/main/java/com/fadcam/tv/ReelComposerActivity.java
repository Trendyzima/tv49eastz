package com.fadcam.tv;

import android.content.Intent;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.fadcam.tv.social.SupabaseSocialRepository;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.tv49.com.BuildConfig;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Native short-video composer. Spotify selection is metadata/attribution only; licensed/original audio is the reel soundtrack. */
public class ReelComposerActivity extends AppCompatActivity {
    private static final int PICK_VIDEO=710;
    private static final int PICK_SPOTIFY=711;
    private Uri video;
    private long durationMs;
    private String spotifyId, spotifyName, spotifyArtist, spotifyUrl;
    private TextView selected;
    private EditText caption;
    private SupabaseSocialRepository repo;
    private final OkHttpClient http=new OkHttpClient();
    private final Gson gson=new Gson();

    @Override protected void onCreate(@Nullable Bundle state){super.onCreate(state);repo=new SupabaseSocialRepository(this);build();}
    private void build(){LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(20),dp(20),dp(20));root.setBackgroundColor(Color.WHITE);TextView title=new TextView(this);title.setText("Create a Reel");title.setTextSize(26);title.setTextColor(Color.rgb(15,20,25));root.addView(title,new LinearLayout.LayoutParams(-1,dp(54)));Button pick=new Button(this);pick.setText("Choose video");pick.setOnClickListener(v->pickVideo());root.addView(pick,new LinearLayout.LayoutParams(-1,dp(52)));selected=new TextView(this);selected.setText("No video selected");selected.setTextSize(14);selected.setTextColor(Color.DKGRAY);selected.setPadding(0,dp(10),0,dp(10));root.addView(selected);caption=new EditText(this);caption.setHint("Caption, hashtags and context");caption.setMinLines(4);root.addView(caption,new LinearLayout.LayoutParams(-1,dp(120)));Button music=new Button(this);music.setText("＋ Find a sound on Spotify");music.setOnClickListener(v->startActivityForResult(new Intent(this,SpotifyMusicActivity.class),PICK_SPOTIFY));root.addView(music,new LinearLayout.LayoutParams(-1,dp(52)));TextView policy=new TextView(this);policy.setText("Spotify tracks can be attached as a reference/attribution. Use original or separately licensed audio as the actual synchronized Reel soundtrack.");policy.setTextSize(12);policy.setTextColor(Color.DKGRAY);policy.setPadding(0,dp(8),0,dp(12));root.addView(policy);Button publish=new Button(this);publish.setText("Publish Reel");publish.setOnClickListener(v->publish());root.addView(publish,new LinearLayout.LayoutParams(-1,dp(54)));setContentView(root);}
    private void pickVideo(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("video/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK_VIDEO);}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(result!=RESULT_OK||data==null)return;if(req==PICK_VIDEO){video=data.getData();durationMs=readDuration(video);selected.setText("Video selected • "+Math.max(1,durationMs/1000)+"s");}else if(req==PICK_SPOTIFY){spotifyId=data.getStringExtra("spotify_track_id");spotifyName=data.getStringExtra("spotify_track_name");spotifyArtist=data.getStringExtra("spotify_artist");spotifyUrl=data.getStringExtra("spotify_url");selected.setText("Video selected • sound reference: "+spotifyName);}}
    private void publish(){if(video==null){toast("Choose a video first");return;}if(!repo.isSignedIn()){toast("Sign in first");return;}selected.setText("Uploading Reel…");repo.uploadMedia(video,"reels",r->{if(r.getError()!=null){runOnUiThread(()->toast("Upload failed: "+r.getError().getMessage()));return;}createReel(r.getValue());});}
    private void createReel(String url){new Thread(()->{try{Map<String,Object> body=new HashMap<>();body.put("author_id",repo.currentUserId());body.put("video_url",url);body.put("caption",caption.getText().toString().trim());body.put("duration_ms",Math.max(1,durationMs));Request req=new Request.Builder().url(BuildConfig.SUPABASE_URL.trim().replaceAll("/$","")+"/rest/v1/reels").header("apikey",BuildConfig.SUPABASE_ANON_KEY).header("Authorization","Bearer "+repo.currentAccessToken()).header("Prefer","return=representation").post(RequestBody.create(gson.toJson(body),MediaType.parse("application/json"))).build();Response resp=http.newCall(req).execute();String text=resp.body()==null?"":resp.body().string();if(!resp.isSuccessful())throw new IOException("Reel create "+resp.code()+" "+text);JsonObject reel=gson.fromJson(text.substring(1,text.length()-1),JsonObject.class);String id=reel.get("id").getAsString();if(spotifyId!=null&&!spotifyId.isEmpty()){Map<String,Object> sound=new HashMap<>();sound.put("reel_id",id);sound.put("source","spotify_reference");sound.put("title",spotifyName==null?"":spotifyName);sound.put("artist",spotifyArtist==null?"":spotifyArtist);sound.put("spotify_track_id",spotifyId);sound.put("spotify_uri","spotify:track:"+spotifyId);sound.put("spotify_url",spotifyUrl);Request sr=new Request.Builder().url(BuildConfig.SUPABASE_URL.trim().replaceAll("/$","")+"/rest/v1/reel_sounds").header("apikey",BuildConfig.SUPABASE_ANON_KEY).header("Authorization","Bearer "+repo.currentAccessToken()).post(RequestBody.create(gson.toJson(sound),MediaType.parse("application/json"))).build();http.newCall(sr).execute().close();}runOnUiThread(()->{toast("Reel published");finish();});}catch(Throwable t){runOnUiThread(()->toast("Publish failed: "+t.getMessage()));}}).start();}
    private long readDuration(Uri u){try{MediaMetadataRetriever r=new MediaMetadataRetriever();r.setDataSource(this,u);String s=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);r.release();return s==null?1:Long.parseLong(s);}catch(Throwable t){return 1;}}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
