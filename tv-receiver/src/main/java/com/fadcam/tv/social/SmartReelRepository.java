package com.fadcam.tv.social;

import android.content.Context;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tv49.com.BuildConfig;
import java.io.IOException;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/** Calls the server-side personalized ranking RPC; falls back to the regular feed when unavailable. */
public final class SmartReelRepository {
    public interface Callback { void done(List<SmartReel> reels, Throwable error); }
    private final SupabaseSocialRepository session;
    private final OkHttpClient http = new OkHttpClient();
    private final Gson gson = new Gson();
    public SmartReelRepository(Context context){session=new SupabaseSocialRepository(context);}
    public void load(int limit,int offset,Callback callback){
        String token=session.currentAccessToken();
        if(token==null||token.isBlank()){callback.done(List.of(),new IllegalStateException("Sign in required"));return;}
        new Thread(()->{try{
            String body=gson.toJson(java.util.Map.of("p_limit",Math.min(30,Math.max(1,limit)),"p_offset",Math.max(0,offset)));
            Request req=new Request.Builder().url(BuildConfig.SUPABASE_URL.trim().replaceAll("/$","")+"/rest/v1/rpc/get_personalized_reels").header("apikey",BuildConfig.SUPABASE_ANON_KEY).header("Authorization","Bearer "+token).header("Content-Type","application/json").post(okhttp3.RequestBody.create(body,okhttp3.MediaType.parse("application/json"))).build();
            okhttp3.Response response=http.newCall(req).execute();String text=response.body()==null?"":response.body().string();if(!response.isSuccessful())throw new IOException("Ranking RPC "+response.code()+" "+text);
            List<SmartReel> out=gson.fromJson(text,new TypeToken<List<SmartReel>>(){}.getType());callback.done(out==null?List.of():out,null);
        }catch(Throwable t){callback.done(List.of(),t);}}).start();
    }
}
