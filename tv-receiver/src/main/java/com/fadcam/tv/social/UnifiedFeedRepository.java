package com.fadcam.tv.social;

import android.content.Context;
import android.util.Base64;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tv49.com.BuildConfig;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Unified feed client. Local and ActivityPub objects are ranked by one server-side feed contract. */
public final class UnifiedFeedRepository {
    private static final String FEDERATION_API="https://federation.testagram.site/v1/federation/action";
    private final SupabaseSocialRepository social;
    private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build();
    private final Gson gson = new Gson();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    public UnifiedFeedRepository(Context context) { social = new SupabaseSocialRepository(context); }

    public void load(String mode, int limit, int offset, SupabaseSocialRepository.ResultCallback<List<SocialPost>> callback) {
        String token = social.currentAccessToken();
        if (token == null || token.trim().isEmpty()) { callback.onComplete(new SocialResult<List<SocialPost>>(null, new IllegalStateException("Sign in required"))); return; }
        Map<String,Object> body = new HashMap<>(); body.put("p_limit", Math.max(1, Math.min(50, limit))); body.put("p_offset", Math.max(0, offset)); body.put("p_mode", mode == null ? "for_you" : mode);
        Request request = new Request.Builder().url(BuildConfig.SUPABASE_URL.replaceAll("/$", "") + "/rest/v1/rpc/get_unified_feed").header("apikey", BuildConfig.SUPABASE_ANON_KEY).header("Authorization", "Bearer " + token).post(RequestBody.create(JSON, gson.toJson(body))).build();
        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) { callback.onComplete(new SocialResult<List<SocialPost>>(null, e)); }
            @Override public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    String text = r.body() == null ? "" : r.body().string();
                    if (!r.isSuccessful()) { callback.onComplete(new SocialResult<List<SocialPost>>(null, new IOException("Unified feed " + r.code() + ": " + text.substring(0, Math.min(300,text.length()))))); return; }
                    JsonArray rows = gson.fromJson(text, JsonArray.class); List<SocialPost> out = new ArrayList<>();
                    for (int i=0;i<rows.size();i++) { JsonObject x=rows.get(i).getAsJsonObject(); boolean remote="federated".equals(get(x,"source")); String key=get(x,"object_key"); String id=remote ? encodeRemote(key) : key.replaceFirst("^local:",""); String authorId=remote ? get(x,"author_uri") : get(x,"author_id"); String username=get(x,"author_username"); String display=get(x,"author_display_name"); String avatar=get(x,"author_avatar_url"); SocialUser user=new SocialUser(authorId, username==null?"remote":username, display==null?(username==null?"Federated user":username):display, avatar, null); out.add(new SocialPost(id,user,clean(get(x,"body")),get(x,"media_url"),get(x,"media_type"),get(x,"created_at"),integer(x,"like_count"),integer(x,"reply_count"),integer(x,"repost_count"),false,false)); }
                    callback.onComplete(new SocialResult<List<SocialPost>>(out,null));
                } catch(Throwable t) { callback.onComplete(new SocialResult<List<SocialPost>>(null,t)); }
            }
        });
    }
    public void recordEvent(SocialPost post, String eventType, long dwellMs, SupabaseSocialRepository.ResultCallback<Boolean> callback) { if (!isRemote(post)) { callback.onComplete(new SocialResult<Boolean>(true,null)); return; } rpc("record_federated_feed_event", map("p_object_uri", remoteUri(post), "p_event_type", eventType, "p_dwell_ms", dwellMs), callback); }
    public void like(SocialPost post, boolean enabled, SupabaseSocialRepository.ResultCallback<Boolean> callback) { if (!isRemote(post)) { social.likePost(post.getId(), enabled, callback); return; } federationAction(enabled?"like":"unlike",null,remoteUri(post),callback); }
    public void repost(SocialPost post, boolean enabled, SupabaseSocialRepository.ResultCallback<Boolean> callback) { if (!isRemote(post)) { social.repostPost(post.getId(), enabled, callback); return; } federationAction(enabled?"repost":"unrepost",null,remoteUri(post),callback); }
    public void save(SocialPost post, boolean enabled, SupabaseSocialRepository.ResultCallback<Boolean> callback) { if (!isRemote(post)) { social.bookmarkPost(post.getId(), enabled, callback); return; } rpc("set_federated_reaction", map("p_object_uri", remoteUri(post), "p_reaction_type", "save", "p_active", enabled), callback); }
    public void follow(SocialUser user, boolean enabled, SupabaseSocialRepository.ResultCallback<Boolean> callback) { if (user == null || user.getId() == null || !user.getId().startsWith("http")) { callback.onComplete(new SocialResult<Boolean>(null,new IllegalArgumentException("Not a federated actor"))); return; } federationAction(enabled?"follow":"unfollow",user.getId(),null,callback); }
    public static boolean isRemote(SocialPost post) { return post != null && post.getId() != null && post.getId().startsWith("federated:"); }
    public static String remoteUri(SocialPost post) { if (!isRemote(post)) return null; try { return new String(Base64.decode(post.getId().substring("federated:".length()), Base64.URL_SAFE | Base64.NO_WRAP), StandardCharsets.UTF_8); } catch(Throwable t) { return null; } }
    private void federationAction(String action,String target,String objectUri,SupabaseSocialRepository.ResultCallback<Boolean> callback){String token=social.currentAccessToken();if(token==null){callback.onComplete(new SocialResult<Boolean>(null,new IllegalStateException("Sign in required")));return;}Map<String,Object> b=map("action",action,"target_uri",target,"object_uri",objectUri);Request q=new Request.Builder().url(FEDERATION_API).header("Authorization","Bearer "+token).header("Content-Type","application/json").post(RequestBody.create(JSON,gson.toJson(b))).build();http.newCall(q).enqueue(new Callback(){@Override public void onFailure(Call c,IOException e){callback.onComplete(new SocialResult<Boolean>(null,e));}@Override public void onResponse(Call c,Response r){try{if(r.isSuccessful())callback.onComplete(new SocialResult<Boolean>(true,null));else callback.onComplete(new SocialResult<Boolean>(null,new IOException("Federation action "+r.code())));}finally{r.close();}}});}
    private static String encodeRemote(String key) { String raw=key==null?"":(key.startsWith("remote:")?key.substring(7):key); return "federated:"+Base64.encodeToString(raw.getBytes(StandardCharsets.UTF_8),Base64.URL_SAFE|Base64.NO_WRAP); }
    private <T> void rpc(String fn, Map<String,Object> payload, SupabaseSocialRepository.ResultCallback<T> callback) { String token=social.currentAccessToken(); if(token==null){callback.onComplete(new SocialResult<T>(null,new IllegalStateException("Sign in required")));return;} Request q=new Request.Builder().url(BuildConfig.SUPABASE_URL.replaceAll("/$","")+"/rest/v1/rpc/"+fn).header("apikey",BuildConfig.SUPABASE_ANON_KEY).header("Authorization","Bearer "+token).post(RequestBody.create(JSON,gson.toJson(payload))).build(); http.newCall(q).enqueue(new Callback(){@Override public void onFailure(Call c,IOException e){callback.onComplete(new SocialResult<T>(null,e));}@Override public void onResponse(Call c,Response r){try{if(!r.isSuccessful())callback.onComplete(new SocialResult<T>(null,new IOException(fn+" "+r.code())));else callback.onComplete(new SocialResult<T>((T)Boolean.TRUE,null));}finally{r.close();}}}); }
    private static Map<String,Object> map(Object... values){Map<String,Object> m=new HashMap<>();for(int i=0;i+1<values.length;i+=2)m.put(String.valueOf(values[i]),values[i+1]);return m;}
    private static String get(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():null;}
    private static int integer(JsonObject o,String k){try{return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsInt():0;}catch(Throwable t){return 0;}}
    private static String clean(String s){return s==null?"":s;}
}
