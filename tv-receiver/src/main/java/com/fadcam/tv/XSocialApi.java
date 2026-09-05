package com.fadcam.tv;

import android.content.Context;
import com.google.gson.Gson;
import com.tv49.com.BuildConfig;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Small authenticated REST facade for the X-style social surfaces. */
public final class XSocialApi {
    public interface CallbackResult { void done(String body, Throwable error); }
    private final Context context;
    private final android.content.SharedPreferences prefs;
    private final Gson gson = new Gson();
    private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    public XSocialApi(Context c){ context=c.getApplicationContext(); prefs=context.getSharedPreferences("tv49_social_session", Context.MODE_PRIVATE); }
    public boolean configured(){ return BuildConfig.SUPABASE_URL.startsWith("https://") && !BuildConfig.SUPABASE_ANON_KEY.isBlank(); }
    public String uid(){ return prefs.getString("user_id", null); }
    public String token(){ return prefs.getString("access_token", null); }
    public void get(String path, CallbackResult cb){ request(path,"GET",null,cb); }
    public void post(String path,Object body,CallbackResult cb){ request(path,"POST",body==null?null:gson.toJson(body),cb); }
    public void patch(String path,Object body,CallbackResult cb){ request(path,"PATCH",body==null?null:gson.toJson(body),cb); }
    public void delete(String path,CallbackResult cb){ request(path,"DELETE",null,cb); }
    public void updateProfile(String username,String name,String bio,String avatar,String cover,String website,String location,CallbackResult cb){
        java.util.Map<String,Object> m=new java.util.HashMap<>();m.put("username",username.trim());m.put("display_name",name.trim());m.put("bio",bio.trim());m.put("website",website.trim());m.put("location",location.trim());if(avatar!=null)m.put("avatar_url",avatar);if(cover!=null)m.put("cover_url",cover);patch("/rest/v1/profiles?id=eq."+enc(uid()),m,cb);
    }
    public void createConversation(String[] memberIds,CallbackResult cb){
        java.util.List<String> ids=new java.util.ArrayList<>();for(String id:memberIds)if(id!=null&&!id.isBlank())ids.add(id);post("/rest/v1/rpc/create_conversation_atomic",java.util.Collections.singletonMap("p_member_ids",ids),cb);
    }
    public void sendMessage(String conversationId,String body,CallbackResult cb){
        java.util.Map<String,Object> m=new java.util.HashMap<>();m.put("conversation_id",conversationId);m.put("body",body.trim());m.put("sender_id",uid());post("/rest/v1/messages",m,cb);
    }
    private void request(String path,String method,String payload,CallbackResult cb){
        if(!configured()){cb.done("",new IOException("Social backend is not configured"));return;}
        Request.Builder b=new Request.Builder().url(BuildConfig.SUPABASE_URL.trimEnd('/')+path).header("apikey",BuildConfig.SUPABASE_ANON_KEY);String t=token();if(t!=null&&!t.isBlank())b.header("Authorization","Bearer "+t);RequestBody body=payload==null?null:RequestBody.create(JSON,payload);b.method(method,body);
        http.newCall(b.build()).enqueue(new Callback(){public void onFailure(Call c,IOException e){cb.done("",e);}public void onResponse(Call c,Response r){try(r){String s=r.body()==null?"":r.body().string();cb.done(s,r.isSuccessful()?null:new IOException("Supabase "+r.code()+": "+s));}}});
    }
    private String enc(String v){try{return java.net.URLEncoder.encode(v,"UTF-8");}catch(Exception e){return v;}}
}
