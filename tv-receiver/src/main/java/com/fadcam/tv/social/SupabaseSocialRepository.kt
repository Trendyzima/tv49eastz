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
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Small, explicit Supabase REST adapter. Secrets never leave the server. */
class SupabaseSocialRepository(context: Context) {
    interface ResultCallback<T> { fun onComplete(result: SocialResult<T>) }
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("tv49_social_session", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun isConfigured() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()
    fun currentAccessToken(): String? = prefs.getString("access_token", null)
    fun currentRefreshToken(): String? = prefs.getString("refresh_token", null)
    fun currentUserId(): String? = prefs.getString("user_id", null)
    fun isSignedIn() = !currentAccessToken().isNullOrBlank()
    fun signOut() { prefs.edit().clear().apply() }

    fun signIn(email: String, password: String, callback: ResultCallback<SocialSession>) {
        if (!isConfigured()) return fail(callback, "Supabase is not configured")
        if (email.trim().isEmpty() || password.isEmpty()) return fail(callback, "Email and password are required")
        request("/auth/v1/token?grant_type=password", "POST", body(mapOf("email" to email.trim(), "password" to password)), null, null) { completeSession(it, callback) }
    }
    fun signUp(email: String, password: String, username: String, displayName: String, callback: ResultCallback<SocialSession>) {
        if (!isConfigured()) return fail(callback, "Supabase is not configured")
        val u = username.trim()
        if (!u.matches(Regex("[A-Za-z0-9_]{3,32}"))) return fail(callback, "Username must be 3-32 letters, numbers or underscores")
        if (password.length < 6) return fail(callback, "Password must be at least 6 characters")
        request("/auth/v1/signup", "POST", body(mapOf("email" to email.trim(), "password" to password, "data" to mapOf("username" to u, "display_name" to displayName.trim()))), null, null) { r ->
            if (r.error != null) return@request callback.onComplete(SocialResult(error = r.error))
            try {
                val o = gson.fromJson(r.value, JsonObject::class.java)
                if (o.get("access_token") == null || o.get("access_token").isJsonNull || o.get("access_token").asString.isBlank()) callback.onComplete(SocialResult(error = IllegalStateException("Account created. Confirm the email before signing in."))) else completeSession(r, callback)
            } catch (t: Throwable) { callback.onComplete(SocialResult(error = t)) }
        }
    }
    fun signUp(email: String, password: String, callback: ResultCallback<SocialSession>) = signUp(email, password, "user_${UUID.randomUUID().toString().replace("-", "").take(10)}", "", callback)
    fun refreshSession(callback: ResultCallback<SocialSession>) { val t = currentRefreshToken() ?: return fail(callback, "No refresh token"); request("/auth/v1/token?grant_type=refresh_token", "POST", body(mapOf("refresh_token" to t)), null, null) { completeSession(it, callback) } }

    fun loadProfile(callback: ResultCallback<SocialUser?>) { val id = currentUserId() ?: return callback.onComplete(SocialResult(value = null)); get("/rest/v1/profiles?id=eq.${enc(id)}&select=id,username,display_name,avatar_url,bio", callback) { parseProfiles(it).firstOrNull() } }
    fun updateProfile(username: String, displayName: String, bio: String, avatarUrl: String?, callback: ResultCallback<SocialUser?>) {
        val id = currentUserId() ?: return fail(callback, "Sign in required")
        val u = username.trim(); if (!u.matches(Regex("[A-Za-z0-9_]{3,32}"))) return fail(callback, "Invalid username")
        val f = mutableMapOf<String, Any>("username" to u, "display_name" to displayName.trim(), "bio" to bio.trim()); if (avatarUrl != null) f["avatar_url"] = avatarUrl
        mutate("/rest/v1/profiles?id=eq.${enc(id)}&select=id,username,display_name,avatar_url,bio", "PATCH", body(f), callback) { parseProfiles(it).firstOrNull() }
    }
    fun loadFeed(limit: Int = 30, callback: ResultCallback<List<SocialPost>>) = get(postsPath(limit), callback) { parsePosts(it) }
    fun loadTrending(limit: Int = 20, callback: ResultCallback<List<SocialPost>>) = get("/rest/v1/trending_posts?select=id,body,media_url,media_type,created_at,like_count,reply_count,repost_count&limit=${limit.coerceIn(1,50)}", callback) { parsePosts(it) }
    fun searchPosts(query: String, limit: Int = 30, callback: ResultCallback<List<SocialPost>>) { val q = URLEncoder.encode(query.trim(), "UTF-8"); get("${postsPath(limit)}&body=ilike.*$q*", callback) { parsePosts(it) } }
    fun createPost(bodyText: String, callback: ResultCallback<SocialPost?>) {
        val uid = currentUserId() ?: return fail(callback, "Sign in required"); val text = bodyText.trim(); if (text.isEmpty()) return fail(callback, "Post cannot be empty")
        mutate("/rest/v1/posts?select=id,body,media_url,media_type,created_at,like_count,reply_count,repost_count,author:profiles!posts_author_id_fkey(id,username,display_name,avatar_url,bio)", "POST", body(mapOf("author_id" to uid, "body" to text)), callback) { parsePosts(it).firstOrNull() }
    }
    fun likePost(postId: String, enabled: Boolean, callback: ResultCallback<Boolean>) = toggle("post_likes", "post_id", postId, "user_id", enabled, callback)
    fun repostPost(postId: String, enabled: Boolean, callback: ResultCallback<Boolean>) = toggle("post_reposts", "post_id", postId, "user_id", enabled, callback)
    fun bookmarkPost(postId: String, enabled: Boolean, callback: ResultCallback<Boolean>) = toggle("bookmarks", "post_id", postId, "user_id", enabled, callback)
    fun followUser(userId: String, enabled: Boolean, callback: ResultCallback<Boolean>) = toggle("follows", "following_id", userId, "follower_id", enabled, callback)
    fun replyToPost(postId: String, bodyText: String, callback: ResultCallback<Boolean>) { val uid = currentUserId() ?: return fail(callback, "Sign in required"); val text = bodyText.trim(); if (text.isEmpty()) return fail(callback, "Reply cannot be empty"); raw("/rest/v1/post_replies", "POST", body(mapOf("post_id" to postId, "author_id" to uid, "body" to text)), callback) }
    fun loadNotifications(limit: Int = 50, callback: ResultCallback<String>) = getText("/rest/v1/notifications?select=id,kind,post_id,read_at,created_at,data&order=created_at.desc&limit=${limit.coerceIn(1,100)}", callback)
    fun markNotificationRead(id: String, callback: ResultCallback<Boolean>) = raw("/rest/v1/notifications?id=eq.${enc(id)}", "PATCH", body(mapOf("read_at" to "now()")), callback)
    fun sendMessage(conversationId: String, bodyText: String, callback: ResultCallback<Boolean>) { val uid = currentUserId() ?: return fail(callback, "Sign in required"); val text = bodyText.trim(); if (text.isEmpty()) return fail(callback, "Message cannot be empty"); raw("/rest/v1/messages", "POST", body(mapOf("conversation_id" to conversationId, "sender_id" to uid, "body" to text)), callback) }
    fun loadMessages(conversationId: String, limit: Int = 50, callback: ResultCallback<String>) = getText("/rest/v1/messages?conversation_id=eq.${enc(conversationId)}&select=id,sender_id,body,media_url,media_type,created_at,edited_at&order=created_at.desc&limit=${limit.coerceIn(1,100)}", callback)

    fun uploadMedia(uri: Uri, kind: String, callback: ResultCallback<String>) {
        val token = currentAccessToken() ?: return fail(callback, "Sign in required"); val uid = currentUserId() ?: return fail(callback, "Session user id missing")
        val size = try { app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L } catch (_: Throwable) { -1L }
        if (size < 0L) return fail(callback, "Unable to determine media size"); if (size > 20L * 1024L * 1024L) return fail(callback, "Media exceeds 20 MB")
        val mime = app.contentResolver.getType(uri) ?: "application/octet-stream"; val ext = mime.substringAfter('/', "bin").substringBefore(';').replace(Regex("[^A-Za-z0-9]"), ""); val file = "${UUID.randomUUID()}.$ext"; val path = "$kind/$uid/$file"
        val rb = object : RequestBody() { override fun contentType() = mime.toMediaType(); override fun contentLength() = size; override fun writeTo(sink: BufferedSink) { app.contentResolver.openInputStream(uri)?.use { it.copyTo(sink.outputStream()) } ?: throw IOException("Cannot open media") } }
        val req = Request.Builder().url(BuildConfig.SUPABASE_URL.trimEnd('/') + "/storage/v1/object/tv49-profile-media/$path").header("apikey", BuildConfig.SUPABASE_ANON_KEY).header("Authorization", "Bearer $token").header("x-upsert", "false").put(rb).build()
        http.newCall(req).enqueue(object : Callback { override fun onFailure(call: Call, e: IOException) { callback.onComplete(SocialResult(error = e)) }; override fun onResponse(call: Call, response: Response) { response.use { if (!it.isSuccessful) callback.onComplete(SocialResult(error = IOException("Storage ${it.code}: ${it.body?.string()?.take(300)}"))) else callback.onComplete(SocialResult(value = publicMediaUrl(kind, uid, file))) } } })
    }
    fun attachMedia(postId: String, url: String, mediaType: String, mimeType: String?, size: Long, order: Int, callback: ResultCallback<Boolean>) { val uid = currentUserId() ?: return fail(callback, "Sign in required"); if (size !in 0L..(20L * 1024L * 1024L) || order !in 0..3) return fail(callback, "Invalid media"); raw("/rest/v1/post_media", "POST", body(mapOf("post_id" to postId, "owner_id" to uid, "media_url" to url, "media_type" to mediaType, "mime_type" to mimeType, "byte_size" to size, "sort_order" to order)), callback) }
    fun publicMediaUrl(kind: String, uid: String, filename: String): String = BuildConfig.SUPABASE_URL.trimEnd('/') + "/storage/v1/object/public/tv49-profile-media/$kind/$uid/$filename"

    private fun postsPath(limit: Int) = "/rest/v1/posts?select=id,body,media_url,media_type,created_at,like_count,reply_count,repost_count,author:profiles!posts_author_id_fkey(id,username,display_name,avatar_url,bio)&order=created_at.desc&limit=${limit.coerceIn(1,50)}"
    private fun toggle(table: String, key: String, value: String, userKey: String, enabled: Boolean, callback: ResultCallback<Boolean>) { val uid = currentUserId() ?: return fail(callback, "Sign in required"); val filter = "$key=eq.${enc(value)}&$userKey=eq.${enc(uid)}"; if (enabled) raw("/rest/v1/$table", "POST", body(mapOf(key to value, userKey to uid)), callback) else raw("/rest/v1/$table?$filter", "DELETE", null, callback) }
    private fun <T> get(path: String, callback: ResultCallback<T>, parser: (String) -> T) = request(path, "GET", null, currentAccessToken(), null) { r -> if (r.error != null) callback.onComplete(SocialResult(error = r.error)) else try { callback.onComplete(SocialResult(value = parser(r.value))) } catch (t: Throwable) { callback.onComplete(SocialResult(error = t)) } }
    private fun getText(path: String, callback: ResultCallback<String>) = request(path, "GET", null, currentAccessToken(), null) { r -> callback.onComplete(if (r.error == null) SocialResult(value = r.value) else SocialResult(error = r.error)) }
    private fun <T> mutate(path: String, method: String, payload: String?, callback: ResultCallback<T>, parser: (String) -> T) = request(path, method, payload, currentAccessToken(), "return=representation") { r -> if (r.error != null) callback.onComplete(SocialResult(error = r.error)) else try { callback.onComplete(SocialResult(value = parser(r.value))) } catch (t: Throwable) { callback.onComplete(SocialResult(error = t)) } }
    private fun raw(path: String, method: String, payload: String?, callback: ResultCallback<Boolean>) = request(path, method, payload, currentAccessToken(), "return=minimal") { r -> callback.onComplete(if (r.error == null) SocialResult(value = true) else SocialResult(error = r.error)) }
    private fun request(path: String, method: String, payload: String?, token: String?, prefer: String?, callback: (RawResult) -> Unit) { val builder = Request.Builder().url(BuildConfig.SUPABASE_URL.trimEnd('/') + path).header("apikey", BuildConfig.SUPABASE_ANON_KEY); if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token"); if (!prefer.isNullOrBlank()) builder.header("Prefer", prefer); builder.method(method, payload?.toRequestBody(jsonType)); http.newCall(builder.build()).enqueue(object : Callback { override fun onFailure(call: Call, e: IOException) { callback(RawResult("", e)) }; override fun onResponse(call: Call, response: Response) { response.use { val text = it.body?.string().orEmpty(); callback(if (it.isSuccessful) RawResult(text, null) else RawResult(text, IOException("Supabase ${it.code}: ${text.take(300)}"))) } } }) }
    private fun completeSession(r: RawResult, callback: ResultCallback<SocialSession>) { if (r.error != null) return callback.onComplete(SocialResult(error = r.error)); try { callback.onComplete(SocialResult(value = parseAndSaveSession(r.value))) } catch (t: Throwable) { callback.onComplete(SocialResult(error = t)) } }
    private fun parseAndSaveSession(text: String): SocialSession { val o = gson.fromJson(text, JsonObject::class.java); val access = o.get("access_token")?.asString ?: error("Missing access_token"); val refresh = o.get("refresh_token")?.takeUnless { it.isJsonNull }?.asString; val uid = o.getAsJsonObject("user")?.get("id")?.asString; val s = SocialSession(access, refresh, uid); prefs.edit().putString("access_token", access).apply { if (refresh != null) putString("refresh_token", refresh); if (uid != null) putString("user_id", uid) }.apply(); return s }
    private fun parseProfiles(text: String): List<SocialUser> { val type = object : TypeToken<List<RemoteProfile>>() {}.type; return (gson.fromJson<List<RemoteProfile>>(text, type) ?: emptyList()).map { SocialUser(it.id, it.username.orEmpty(), it.displayName.orEmpty(), it.avatarUrl, it.bio) } }
    private fun parsePosts(text: String): List<SocialPost> { val type = object : TypeToken<List<RemotePost>>() {}.type; return (gson.fromJson<List<RemotePost>>(text, type) ?: emptyList()).map { p -> SocialPost(p.id, p.author?.let { SocialUser(it.id, it.username.orEmpty(), it.displayName.orEmpty(), it.avatarUrl, it.bio) } ?: SocialUser("", "", "TV 49 East user"), p.body.orEmpty(), p.mediaUrl, p.mediaType, p.createdAt.orEmpty(), p.likeCount, p.replyCount, p.repostCount) } }
    private fun body(value: Any) = gson.toJson(value)
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun <T> fail(callback: ResultCallback<T>, message: String) { callback.onComplete(SocialResult(error = IllegalStateException(message))) }
    private data class RawResult(val value: String, val error: Throwable?)
    private data class RemoteProfile(val id: String, val username: String?, val display_name: String?, val avatar_url: String?, val bio: String?) { val displayName get() = display_name; val avatarUrl get() = avatar_url }
    private data class RemotePost(val id: String, val body: String?, val media_url: String?, val media_type: String?, val created_at: String?, val like_count: Int?, val reply_count: Int?, val repost_count: Int?, val author: RemoteProfile?) { val mediaUrl get() = media_url; val mediaType get() = media_type; val createdAt get() = created_at; val likeCount get() = like_count ?: 0; val replyCount get() = reply_count ?: 0; val repostCount get() = repost_count ?: 0 }
}
