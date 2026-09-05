package com.fadcam.tv.social

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.tv49.com.BuildConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

class SupabaseSocialRepository(context: Context) {
    interface ResultCallback<T> { fun onComplete(result: SocialResult<T>) }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("tv49_social_session", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun isConfigured(): Boolean = BuildConfig.SUPABASE_URL.trim().startsWith("https://") && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()
    fun currentAccessToken(): String? = prefs.getString("access_token", null)
    fun currentRefreshToken(): String? = prefs.getString("refresh_token", null)
    fun currentUserId(): String? = prefs.getString("user_id", null)
    fun isSignedIn(): Boolean = !currentAccessToken().isNullOrBlank() && !currentUserId().isNullOrBlank()
    fun signOut() { prefs.edit().clear().apply() }

    fun signIn(email: String, password: String, callback: ResultCallback<SocialSession>) {
        if (!isConfigured()) return fail(callback, "TV 49 East is not connected to the social backend")
        val e=email.trim();if(!android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches())return fail(callback,"Enter a valid email address");if(password.length<6)return fail(callback,"Password must be at least 6 characters")
        request("/auth/v1/token?grant_type=password","POST",json(mapOf("email" to e,"password" to password)),null,null){completeSession(it,callback)}
    }

    fun signUp(email:String,password:String,username:String,displayName:String,callback:ResultCallback<SocialSession>){
        if(!isConfigured())return fail(callback,"TV 49 East is not connected to the social backend")
        val e=email.trim();val u=username.trim();if(!android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches())return fail(callback,"Enter a valid email address");if(!u.matches(Regex("[A-Za-z0-9_]{3,32}")))return fail(callback,"Username must be 3-32 letters, numbers or underscores");if(password.length<6)return fail(callback,"Password must be at least 6 characters")
        val name=displayName.trim().ifEmpty{u};request("/auth/v1/signup","POST",json(mapOf("email" to e,"password" to password,"data" to mapOf("username" to u,"display_name" to name))),null,null){raw->
            if(raw.error!=null)return@request callback.onComplete(SocialResult(error=raw.error))
            try{val o=gson.fromJson(raw.value,JsonObject::class.java);val userId=o.getAsJsonObject("user")?.get("id")?.asString.orEmpty();val token=o.get("access_token")?.takeUnless{it.isJsonNull}?.asString.orEmpty();val refresh=o.get("refresh_token")?.takeUnless{it.isJsonNull}?.asString.orEmpty();if(token.isBlank())callback.onComplete(SocialResult(value=SocialSession("","",userId)))else{val s=SocialSession(token,refresh,userId);saveSession(s);callback.onComplete(SocialResult(value=s))}}catch(t:Throwable){callback.onComplete(SocialResult(error=t))}
        }
    }
    fun signUp(email:String,password:String,callback:ResultCallback<SocialSession>)=signUp(email,password,"user_${UUID.randomUUID().toString().replace("-","").take(10)}","",callback)
    fun resetPassword(email:String,callback:ResultCallback<Boolean>){if(!isConfigured())return fail(callback,"TV 49 East is not connected to the social backend");val e=email.trim();if(!android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches())return fail(callback,"Enter a valid email address");request("/auth/v1/recover","POST",json(mapOf("email" to e)),null,null){r->callback.onComplete(if(r.error==null)SocialResult(value=true)else SocialResult(error=r.error))}}
    fun refreshSession(callback:ResultCallback<SocialSession>){val refresh=currentRefreshToken()?:return fail(callback,"No refresh token");request("/auth/v1/token?grant_type=refresh_token","POST",json(mapOf("refresh_token" to refresh)),null,null){completeSession(it,callback)}}
    fun restoreSession(callback:ResultCallback<SocialSession>){if(currentAccessToken().isNullOrBlank()||currentRefreshToken().isNullOrBlank()||currentUserId().isNullOrBlank())return callback.onComplete(SocialResult(value=SocialSession("","","")));refreshSession(callback)}

    fun loadProfile(callback:ResultCallback<SocialUser?>){val id=currentUserId()?:return callback.onComplete(SocialResult(value=null));get("/rest/v1/profiles?id=eq.${enc(id)}&select=id,username,display_name,avatar_url,bio",callback){parseProfiles(it).firstOrNull()}}
    fun updateProfile(username:String,displayName:String,bio:String,avatarUrl:String?,callback:ResultCallback<SocialUser?>){val id=currentUserId()?:return fail(callback,"Sign in required");val u=username.trim();if(!u.matches(Regex("[A-Za-z0-9_]{3,32}")))return fail(callback,"Invalid username");val fields=mutableMapOf<String,Any>("username" to u,"display_name" to displayName.trim(),"bio" to bio.trim());if(avatarUrl!=null)fields["avatar_url"]=avatarUrl;mutate("/rest/v1/profiles?id=eq.${enc(id)}&select=id,username,display_name,avatar_url,bio","PATCH",json(fields),callback){parseProfiles(it).firstOrNull()}}
    fun loadFeed(limit:Int=30,callback:ResultCallback<List<SocialPost>>)=get(postsPath(limit),callback){parsePosts(it)}
    fun loadTrending(limit:Int=20,callback:ResultCallback<List<SocialPost>>)=get("/rest/v1/trending_posts?select=id,body,media_url,media_type,created_at,like_count,reply_count,repost_count&limit=${limit.coerceIn(1,50)}",callback){parsePosts(it)}
    fun searchPosts(query:String,limit:Int=30,callback:ResultCallback<List<SocialPost>>){val q=URLEncoder.encode(query.trim(),"UTF-8");get("${postsPath(limit)}&body=ilike.*$q*",callback){parsePosts(it)}}

    fun createPost(bodyText:String,callback:ResultCallback<SocialPost?>)=createPost(bodyText,null,null,callback)
    fun createPost(bodyText:String,mediaUrl:String?,mediaType:String?,callback:ResultCallback<SocialPost?>){
        val uid=currentUserId()?:return fail(callback,"Sign in required")
        val text=bodyText.trim();val media=mediaUrl?.trim().takeUnless{it.isNullOrEmpty()}
        if(text.isEmpty()&&media==null)return fail(callback,"Add some text or attach a photo/video")
        if(text.length>5000)return fail(callback,"Post text is limited to 5000 characters")
        if(mediaType!=null&&mediaType !in setOf("image","video"))return fail(callback,"Unsupported post media type")
        val payload=mutableMapOf<String,Any>("author_id" to uid,"body" to text)
        if(media!=null)payload["media_url"]=media
        if(mediaType!=null)payload["media_type"]=mediaType
        // A successful INSERT must not be coupled to a representation SELECT. Supabase/PostgREST
        // can evaluate SELECT/RLS again while constructing return=representation. The row is valid
        // even when that response projection is denied or malformed, so publish with minimal return
        // and let the feed perform the normal authenticated SELECT afterwards.
        raw("/rest/v1/posts","POST",json(payload)){result->
            if(result.error!=null)callback.onComplete(SocialResult(error=result.error))
            else callback.onComplete(SocialResult(value=null))
        }
    }
    fun likePost(postId:String,enabled:Boolean,callback:ResultCallback<Boolean>)=toggle("post_likes","post_id",postId,"user_id",enabled,callback)
    fun repostPost(postId:String,enabled:Boolean,callback:ResultCallback<Boolean>)=toggle("post_reposts","post_id",postId,"user_id",enabled,callback)
    fun bookmarkPost(postId:String,enabled:Boolean,callback:ResultCallback<Boolean>)=toggle("bookmarks","post_id",postId,"user_id",enabled,callback)
    fun followUser(userId:String,enabled:Boolean,callback:ResultCallback<Boolean>)=toggle("follows","following_id",userId,"follower_id",enabled,callback)
    fun replyToPost(postId:String,bodyText:String,callback:ResultCallback<Boolean>){val uid=currentUserId()?:return fail(callback,"Sign in required");val text=bodyText.trim();if(text.isEmpty())return fail(callback,"Reply cannot be empty");raw("/rest/v1/post_replies","POST",json(mapOf("post_id" to postId,"author_id" to uid,"body" to text)),callback)}
    fun loadNotifications(limit:Int=50,callback:ResultCallback<String>)=getText("/rest/v1/notifications?select=id,kind,post_id,read_at,created_at,data&order=created_at.desc&limit=${limit.coerceIn(1,100)}",callback)
    fun markNotificationRead(id:String,callback:ResultCallback<Boolean>)=raw("/rest/v1/notifications?id=eq.${enc(id)}","PATCH",json(mapOf("read_at" to "now()")),callback)
    fun sendMessage(conversationId:String,bodyText:String,callback:ResultCallback<Boolean>){val uid=currentUserId()?:return fail(callback,"Sign in required");val text=bodyText.trim();if(text.isEmpty())return fail(callback,"Message cannot be empty");raw("/rest/v1/messages","POST",json(mapOf("conversation_id" to conversationId,"sender_id" to uid,"body" to text)),callback)}
    fun loadMessages(conversationId:String,limit:Int=50,callback:ResultCallback<String>)=getText("/rest/v1/messages?conversation_id=eq.${enc(conversationId)}&select=id,sender_id,body,media_url,media_type,created_at,edited_at&order=created_at.desc&limit=${limit.coerceIn(1,100)}",callback)

    fun uploadMedia(uri:Uri,kind:String,callback:ResultCallback<String>){
        val token=currentAccessToken()?:return fail(callback,"Sign in required")
        val uid=currentUserId()?:return fail(callback,"Session user id missing")
        val size=try{app.contentResolver.openAssetFileDescriptor(uri,"r")?.use{it.length}?:-1L}catch(_:Throwable){-1L}
        if(size<0L)return fail(callback,"Unable to determine media size")
        if(size>95L*1024L*1024L)return fail(callback,"Media exceeds the 95 MB upload limit")
        val mime=app.contentResolver.getType(uri)?.substringBefore(';')?.lowercase()?:return fail(callback,"Unsupported media type")
        if(!mime.startsWith("image/")&&!mime.startsWith("video/"))return fail(callback,"Unsupported media type")
        val normalizedKind=when(kind.lowercase()){"post-image","post-video","posts"->"posts";"avatar","avatars"->"avatars";"cover","covers"->"covers";else->return fail(callback,"Unsupported media destination")}
        val base=BuildConfig.SOCIAL_MEDIA_URL.trimEnd('/')
        if(!base.startsWith("https://"))return fail(callback,"Cloudflare media service is not configured")
        if((normalizedKind=="avatars"||normalizedKind=="covers")&&!mime.startsWith("image/"))return fail(callback,"Profile media must be an image")
        val rb=object:RequestBody(){override fun contentType()=mime.toMediaType();override fun contentLength()=size;override fun writeTo(sink:BufferedSink){app.contentResolver.openInputStream(uri)?.use{input->input.copyTo(sink.outputStream())}?:throw IOException("Cannot open media")}}
        val req=Request.Builder().url("$base/v1/media?kind=${enc(normalizedKind)}").header("Authorization","Bearer $token").header("Content-Type",mime).header("Content-Length",size.toString()).post(rb).build()
        http.newCall(req).enqueue(object:Callback{
            override fun onFailure(call:Call,e:IOException){callback.onComplete(SocialResult(error=e))}
            override fun onResponse(call:Call,response:Response){response.use{val text=it.body?.string().orEmpty();if(!it.isSuccessful)callback.onComplete(SocialResult(error=IOException("Cloudflare media ${it.code}: ${text.take(300)}")))else try{val o=gson.fromJson(text,JsonObject::class.java);val url=o.get("url")?.takeUnless{x->x.isJsonNull}?.asString;val expectedPrefix="$base/media/";if(url.isNullOrBlank()||!url.startsWith(expectedPrefix))callback.onComplete(SocialResult(error=IOException("Cloudflare media Worker returned an invalid URL")))else callback.onComplete(SocialResult(value=url))}catch(t:Throwable){callback.onComplete(SocialResult(error=t))}}}
        })
    }

    fun attachMedia(postId:String,url:String,mediaType:String,mimeType:String?,size:Long,order:Int,callback:ResultCallback<Boolean>){val uid=currentUserId()?:return fail(callback,"Sign in required");if(size !in 0L..(95L*1024L*1024L)||order !in 0..3)return fail(callback,"Invalid media");if(!url.startsWith(BuildConfig.SOCIAL_MEDIA_URL.trimEnd('/')+"/media/"))return fail(callback,"Media must be hosted by Cloudflare");raw("/rest/v1/post_media","POST",json(mapOf("post_id" to postId,"owner_id" to uid,"media_url" to url,"media_type" to mediaType,"mime_type" to mimeType,"byte_size" to size,"sort_order" to order)),callback)}
    fun publicMediaUrl(kind:String,uid:String,filename:String):String=BuildConfig.SOCIAL_MEDIA_URL.trimEnd('/')+"/media/$kind/$uid/$filename"

    private fun postsPath(limit:Int)="/rest/v1/posts?select=id,body,media_url,media_type,created_at,like_count,reply_count,repost_count,author:profiles!posts_author_id_fkey(id,username,display_name,avatar_url,bio)&order=created_at.desc&limit=${limit.coerceIn(1,50)}"
    private fun toggle(table:String,key:String,value:String,userKey:String,enabled:Boolean,callback:ResultCallback<Boolean>){val uid=currentUserId()?:return fail(callback,"Sign in required");val filter="$key=eq.${enc(value)}&$userKey=eq.${enc(uid)}";if(enabled)raw("/rest/v1/$table","POST",json(mapOf(key to value,userKey to uid)),callback)else raw("/rest/v1/$table?$filter","DELETE",null,callback)}
    private fun <T> get(path:String,callback:ResultCallback<T>,parser:(String)->T){request(path,"GET",null,currentAccessToken(),null){r->if(r.error!=null)callback.onComplete(SocialResult(error=r.error))else try{callback.onComplete(SocialResult(value=parser(r.value)))}catch(t:Throwable){callback.onComplete(SocialResult(error=t))}}}
    private fun getText(path:String,callback:ResultCallback<String>){request(path,"GET",null,currentAccessToken(),null){r->callback.onComplete(if(r.error==null)SocialResult(value=r.value)else SocialResult(error=r.error))}}
    private fun <T> mutate(path:String,method:String,payload:String?,callback:ResultCallback<T>,parser:(String)->T){request(path,method,payload,currentAccessToken(),"return=representation"){r->if(r.error!=null)callback.onComplete(SocialResult(error=r.error))else try{callback.onComplete(SocialResult(value=parser(r.value)))}catch(t:Throwable){callback.onComplete(SocialResult(error=t))}}}
    private fun raw(path:String,method:String,payload:String?,callback:ResultCallback<Boolean>){request(path,method,payload,currentAccessToken(),"return=minimal"){r->callback.onComplete(if(r.error==null)SocialResult(value=true)else SocialResult(error=r.error))}}
    private fun raw(path:String,method:String,payload:String?,callback:(RawResult)->Unit){request(path,method,payload,currentAccessToken(),"return=minimal",callback)}
    private fun request(path:String,method:String,payload:String?,token:String?,prefer:String?,callback:(RawResult)->Unit){if(!isConfigured())return callback(RawResult("",IOException("Supabase is not configured")));val builder=Request.Builder().url(BuildConfig.SUPABASE_URL.trimEnd('/')+path).header("apikey",BuildConfig.SUPABASE_ANON_KEY);if(!token.isNullOrBlank())builder.header("Authorization","Bearer $token");if(!prefer.isNullOrBlank())builder.header("Prefer",prefer);val body=payload?.let{RequestBody.create(jsonType,it)};builder.method(method,body);try{http.newCall(builder.build()).enqueue(object:Callback{override fun onFailure(call:Call,e:IOException){callback(RawResult("",e))};override fun onResponse(call:Call,response:Response){response.use{val text=it.body?.string().orEmpty();callback(if(it.isSuccessful)RawResult(text,null)else RawResult(text,IOException("Supabase ${it.code}: ${friendlyAuthError(text)}")))}}})}catch(t:Throwable){callback(RawResult("",t))}}
    private fun friendlyAuthError(body:String):String=try{val o=gson.fromJson(body,JsonObject::class.java);o.get("msg")?.takeUnless{it.isJsonNull}?.asString?:o.get("message")?.takeUnless{it.isJsonNull}?.asString?:o.get("error_description")?.takeUnless{it.isJsonNull}?.asString?:body.take(300)}catch(_:Throwable){body.take(300)}
    private fun saveSession(s:SocialSession){prefs.edit().putString("access_token",s.accessToken).putString("refresh_token",s.refreshToken).putString("user_id",s.userId).apply()}
    private fun completeSession(raw:RawResult,callback:ResultCallback<SocialSession>){if(raw.error!=null)return callback.onComplete(SocialResult(error=raw.error));try{val o=gson.fromJson(raw.value,JsonObject::class.java);val s=SocialSession(o.get("access_token")?.asString.orEmpty(),o.get("refresh_token")?.asString.orEmpty(),o.getAsJsonObject("user")?.get("id")?.asString.orEmpty());if(s.accessToken.isBlank()||s.userId.isNullOrBlank())return callback.onComplete(SocialResult(error=IllegalStateException("Authentication did not return a valid session")));saveSession(s);callback.onComplete(SocialResult(value=s))}catch(t:Throwable){callback.onComplete(SocialResult(error=t))}}
    private fun json(value:Any)=gson.toJson(value)
    private fun enc(value:String)=URLEncoder.encode(value,"UTF-8")
    private data class RawResult(val value:String,val error:Throwable?)
    private fun parsePosts(s:String): List<SocialPost> = gson.fromJson(s,object:TypeToken<List<SocialPost>>(){}.type)?:emptyList()
    private fun parseProfiles(s:String): List<SocialUser> = gson.fromJson(s,object:TypeToken<List<SocialUser>>(){}.type)?:emptyList()
    private fun <T> fail(callback:ResultCallback<T>,message:String)=callback.onComplete(SocialResult(error=IllegalStateException(message)))
}