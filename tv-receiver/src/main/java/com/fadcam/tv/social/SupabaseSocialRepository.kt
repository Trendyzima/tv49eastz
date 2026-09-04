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
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun isConfigured(): Boolean = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()
    fun currentAccessToken(): String? = prefs.getString("access_token", null)
    fun currentRefreshToken(): String? = prefs.getString("refresh_token", null)
    fun currentUserId(): String? = prefs.getString("user_id", null)
    fun isSignedIn(): Boolean = !currentAccessToken().isNullOrBlank()
    fun signOut() { prefs.edit().clear().apply() }

    fun signIn(email: String, password: String, callback: ResultCallback<SocialSession>) {
        if (!isConfigured()) return fail(callback, "Supabase is not configured")
        if (email.trim().isEmpty() || password.isEmpty()) return fail(callback, "Email and password are required")
        request("/auth/v1/token?grant_type=password", "POST", json(mapOf("email" to email.trim(), "password" to password)), null, null) { completeSession(it, callback) }
    }

    fun signUp(email: String, password: String, username: String, displayName: String, callback: ResultCallback<SocialSession>) {
        if (!isConfigured()) return fail(callback, "Supabase is not configured")
        val u = username.trim()
        if (!u.matches(Regex("[A-Za-z0-9_]{3,32}"))) return fail(callback, "Username must be 3-32 letters, numbers or underscores")
        if (password.length < 6) return fail(callback, "Password must be at least 6 characters")
        val payload = mapOf("email" to email.trim(), "password" to password, "data" to mapOf("username" to u, "display_name" to displayName.trim()))
        request("/auth/v1/signup", "POST", json(payload), null, null) { raw ->
            if (raw.error != null) return@request callback.onComplete(SocialResult(error = raw.error))
            try {
                val o = gson.fromJson(raw.value, JsonObject::class.java)
                val token = o.get("access_token")
                if (token == null || token.isJsonNull || token.asString.isBlank()) {
                    callback.onComplete(SocialResult(error = IllegalStateException("Account created. Confirm the email before signing in.")))
                } else completeSession(raw, callback)
            } catch (t: Throwable) { callback.onComplete(SocialResult(error = t)) }
        }
    }

    fun signUp(email: String, password: String, callback: ResultCallback<SocialSession>) =
        signUp(email, password, "user_${UUID.randomUUID().toString().replace("-", "").take(10)}", "", callback)

    fun refreshSession(callback: ResultCallback<SocialSession>) {
        val refresh = currentRefreshToken() ?: return fail(callback, "No refresh token")
        request("/auth/v1/token?grant_type=refresh_token", "POST", json(mapOf("refresh_token" to refresh)), null, null) { completeSession(it, callback) }
    }

    fun loadProfile(callback: ResultCallback<SocialUser?>) {
        val id = currentUserId() ?: return callback.onComplete(SocialResult(value = null))
        get("/rest/v1/profiles?id=eq.${enc(id)}&select=id,username,display_name,avatar_url,bio", callback) { parseProfiles(it).firstOrNull() }
    }

    fun updateProfile(username: String, displayName: String, bio: String, avatarUrl: String?, callback: ResultCallback<SocialUser?>) {
        val id = currentUserId() ?: return fail(callback, "Sign in required")
        val u = username.trim()
        if (!u.matches(Regex("[A-Za-z0-9_]{3,32}"))) return fail(callback, "Invalid username")
        val fields = mutableMapOf<String, Any>("username" to u, "display_name" to displayName.trim(), "bio" to bio.trim())
        if (avatarUrl != null) fields["avatar_url"] = avatarUrl
        mutate("/rest/v1/profiles?id=eq.${enc(id)}&select=id,username,display_name,avatar_url,bio", "PATCH", json(fields), callback) { parseProfiles(it).firstOrNull() }
    }

    fun loadFeed(limit: Int = 30, callback: ResultCallback<List<SocialPost>>) = get(postsPath(limit), callback) { parsePosts(it) }
    fun loadTrending(limit: Int = 20, callback: ResultCallback<List<SocialPost>>) = get(
        "/rest/v1/trending_posts?select=id,body,media_url,media_type,created_at,like_count,reply_count,repost_count&limit=${limit.coerceIn(1, 50)}",
        callback
    ) { parsePosts(it) }

    fun searchPosts(query: String, limit: Int = 30, callback: ResultCallback<List<SocialPost>>) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        get("${postsPath(limit)}&body=ilike.*$q*", callback) { parsePosts(it) }
    }

    fun createPost(bodyText: String, callback: ResultCallback<SocialPost?>) {
        val uid = currentUserId() ?: return fail(callback, "Sign in required")
        val text = bodyText.trim()
        if (text.isEmpty()) return fail(callback, "Post cannot be empty")
        val path = "/rest/v1/posts?select=id,body,media_url,media_type,created_at,like_count,reply_count,repost_count,author:profiles!posts_author_id_fkey(id,username,display_name,avatar_url,bio)"
        mutate(path, "POST", json(mapOf("author_id" to uid, "body" to text)), callback) { parsePosts(it).firstOrNull() }
    }

    fun likePost(postId: String, enabled: Boolean, callback: ResultCallback<Boolean>) = toggle("post_likes", "post_id", postId, "user_id", enabled, callback)
    fun repostPost(postId: String, enabled: Boolean, callback: ResultCallback<Boolean>) = toggle("post_reposts", "post_id", postId, "user_id", enabled, callback)
    fun bookmarkPost(postId: String, enabled: Boolean, callback: ResultCallback<Boolean>) = toggle("bookmarks", "post_id", postId, "user_id", enabled, callback)
    fun followUser(userId: String, enabled: Boolean, callback: ResultCallback<Boolean>) = toggle("follows", "following_id", userId, "follower_id", enabled, callback)

    fun replyToPost(postId: String, bodyText: String, callback: ResultCallback<Boolean>) {
        val uid = currentUserId() ?: return fail(callback, "Sign in required")
        val text = bodyText.trim()
        if (text.isEmpty()) return fail(callback, "Reply cannot be empty")
        raw("/rest/v1/post_replies", "POST", json(mapOf("post_id" to postId, "author_id" to uid, "body" to text)), callback)
    }

    fun loadNotifications(limit: Int = 50, callback: ResultCallback<String>) = getText(
        "/rest/v1/notifications?select=id,kind,post_id,read_at,created_at,data&order=created_at.desc&limit=${limit.coerceIn(1, 100)}", callback)

    fun markNotificationRead(id: String, callback: ResultCallback<Boolean>) = raw(
        "/rest/v1/notifications?id=eq.${enc(id)}", "PATCH", json(mapOf("read_at" to "now()")), callback)

    fun sendMessage(conversationId: String, bodyText: String, callback: ResultCallback<Boolean>) {
        val uid = currentUserId() ?: return fail(callback, "Sign in required")
        val text = bodyText.trim()
        if (text.isEmpty()) return fail(callback, "Message cannot be empty")
        raw("/rest/v1/messages", "POST", json(mapOf("conversation_id" to conversationId, "sender_id" to uid, "body" to text)), callback)
    }

    fun loadMessages(conversationId: String, limit: Int = 50, callback: ResultCallback<String>) = getText(
        "/rest/v1/messages?conversation_id=eq.${enc(conversationId)}&select=id,sender_id,body,media_url,media_type,created_at,edited_at&order=created_at.desc&limit=${limit.coerceIn(1, 100)}", callback)

    /** Uploads through the Cloudflare Worker when SOCIAL_MEDIA_URL is configured. */
    fun uploadMedia(uri: Uri, kind: String, callback: ResultCallback<String>) {
        val token = currentAccessToken() ?: return fail(callback, "Sign in required")
        val uid = currentUserId() ?: return fail(callback, "Session user id missing")
        val size = try { app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L } catch (_: Throwable) { -1L }
        if (size < 0L) return fail(callback, "Unable to determine media size")
        if (size > 20L * 1024L * 1024L) return fail(callback, "Media exceeds 20 MB")
        val mime = app.contentResolver.getType(uri) ?: return fail(callback, "Unsupported media type")
        if (!mime.startsWith("image/") && !mime.startsWith("video/")) return fail(callback, "Unsupported media type")

        val base = BuildConfig.SOCIAL_MEDIA_URL.trimEnd('/')
        if (base.isNotBlank()) {
            val requestBody = object : RequestBody() {
                override fun contentType() = mime.toMediaType()
                override fun contentLength() = size
                override fun writeTo(sink: BufferedSink) {
                    app.contentResolver.openInputStream(uri)?.use { input -> input.copyTo(sink.outputStream()) }
                        ?: throw IOException("Cannot open media")
                }
            }
            val req = Request.Builder()
                .url("$base/v1/media?kind=${enc(kind)}")
                .header("Authorization", "Bearer $token")
                .header("Content-Type", mime)
                .header("Content-Length", size.toString())
                .post(requestBody)
                .build()
            http.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { callback.onComplete(SocialResult(error = e)) }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val text = it.body?.string().orEmpty()
                        if (!it.isSuccessful) callback.onComplete(SocialResult(error = IOException("Media Worker ${it.code}: ${text.take(300)}")))
                        else {
                            try {
                                val o = gson.fromJson(text, JsonObject::class.java)
                                val url = o.get("url")?.takeUnless { x -> x.isJsonNull }?.asString
                                callback.onComplete(if (!url.isNullOrBlank()) SocialResult(value = url) else SocialResult(error = IOException("Media Worker returned no URL")))
                            } catch (t: Throwable) { callback.onComplete(SocialResult(error = t)) }
                        }
                    }
                }
            })
            return
        }

        val ext = mime.substringAfter('/', "bin").substringBefore(';').replace(Regex("[^A-Za-z0-9]"), "")
        val file = "${UUID.randomUUID()}.$ext"
        val path = "$kind/$uid/$file"
        val rb = object : RequestBody() {
            override fun contentType() = mime.toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                app.contentResolver.openInputStream(uri)?.use { it.copyTo(sink.outputStream()) } ?: throw IOException("Cannot open media")
            }
        }
        val req = Request.Builder()
            .url(BuildConfig.SUPABASE_URL.trimEnd('/') + "/storage/v1/object/tv49-profile-media/$path")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer $token")
            .header("x-upsert", "false")
            .put(rb)
            .build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { callback.onComplete(SocialResult(error = e)) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) callback.onComplete(SocialResult(error = IOException("Storage ${it.code}: ${it.body?.string()?.take(300)}")))
                    else callback.onComplete(SocialResult(value = publicMediaUrl(kind, uid, file)))
                }
            }
        })
    }

    fun attachMedia(postId: String, url: String, mediaType: String, mimeType: String?, size: Long, order: Int, callback: ResultCallback<Boolean>) {
        val uid = currentUserId() ?: return fail(callback, "Sign in required")
        if (size !in 0L..(20L * 1024L * 1024L) || order !in 0..3) return fail(callback, "Invalid media")
        raw("/rest/v1/post_media", "POST", json(mapOf("post_id" to postId, "owner_id" to uid, "media_url" to url, "media_type" to mediaType, "mime_type" to mimeType, "byte_size" to size, "sort_order" to order)), callback)
    }

    fun publicMediaUrl(kind: String, uid: String, filename: String): String =
        BuildConfig.SUPABASE_URL.trimEnd('/') + "/storage/v1/object/public/tv49-profile-media/$kind/$uid/$filename"

    private fun postsPath(limit: Int): String =
        "/rest/v1/posts?select=id,body,media_url,media_type,created_at,like_count,reply_count,repost_count,author:profiles!posts_author_id_fkey(id,username,display_name,avatar_url,bio)&order=created_at.desc&limit=${limit.coerceIn(1, 50)}"

    private fun toggle(table: String, key: String, value: String, userKey: String, enabled: Boolean, callback: ResultCallback<Boolean>) {
        val uid = currentUserId() ?: return fail(callback, "Sign in required")
        val filter = "$key=eq.${enc(value)}&$userKey=eq.${enc(uid)}"
        if (enabled) raw("/rest/v1/$table", "POST", json(mapOf(key to value, userKey to uid)), callback)
        else raw("/rest/v1/$table?$filter", "DELETE", null, callback)
    }

    private fun <T> get(path: String, callback: ResultCallback<T>, parser: (String) -> T) {
        request(path, "GET", null, currentAccessToken(), null) { r ->
            if (r.error != null) callback.onComplete(SocialResult(error = r.error))
            else try { callback.onComplete(SocialResult(value = parser(r.value))) }
            catch (t: Throwable) { callback.onComplete(SocialResult(error = t)) }
        }
    }

    private fun getText(path: String, callback: ResultCallback<String>) {
        request(path, "GET", null, currentAccessToken(), null) { r ->
            callback.onComplete(if (r.error == null) SocialResult(value = r.value) else SocialResult(error = r.error))
        }
    }

    private fun <T> mutate(path: String, method: String, payload: String?, callback: ResultCallback<T>, parser: (String) -> T) {
        request(path, method, payload, currentAccessToken(), "return=representation") { r ->
            if (r.error != null) callback.onComplete(SocialResult(error = r.error))
            else try { callback.onComplete(SocialResult(value = parser(r.value))) }
            catch (t: Throwable) { callback.onComplete(SocialResult(error = t)) }
        }
    }

    private fun raw(path: String, method: String, payload: String?, callback: ResultCallback<Boolean>) {
        request(path, method, payload, currentAccessToken(), "return=minimal") { r ->
            callback.onComplete(if (r.error == null) SocialResult(value = true) else SocialResult(error = r.error))
        }
    }

    private fun request(path: String, method: String, payload: String?, token: String?, prefer: String?, callback: (RawResult) -> Unit) {
        val builder = Request.Builder()
            .url(BuildConfig.SUPABASE_URL.trimEnd('/') + path)
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        if (!prefer.isNullOrBlank()) builder.header("Prefer", prefer)
        val requestBody = payload?.let { RequestBody.create(jsonType, it) }
        builder.method(method, requestBody)
        http.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { callback(RawResult("", e)) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = it.body?.string().orEmpty()
                    callback(if (it.isSuccessful) RawResult(text, null) else RawResult(text, IOException("Supabase ${it.code}: ${text.take(300)}")))
                }
            }
        })
    }

    private fun completeSession(raw: RawResult, callback: ResultCallback<SocialSession>) {
        if (raw.error != null) return callback.onComplete(SocialResult(error = raw.error))
        try {
            val o = gson.fromJson(raw.value, JsonObject::class.java)
            val session = SocialSession(
                o.get("access_token")?.asString.orEmpty(),
                o.get("refresh_token")?.asString.orEmpty(),
                o.getAsJsonObject("user")?.get("id")?.asString.orEmpty()
            )
            prefs.edit().putString("access_token", session.accessToken).putString("refresh_token", session.refreshToken).putString("user_id", session.userId).apply()
            callback.onComplete(SocialResult(value = session))
        } catch (t: Throwable) { callback.onComplete(SocialResult(error = t)) }
    }

    private fun json(value: Any): String = gson.toJson(value)
    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    private data class RawResult(val value: String, val error: Throwable?)

    private fun parsePosts(s: String): List<SocialPost> = gson.fromJson(s, object : TypeToken<List<SocialPost>>() {}.type) ?: emptyList()
    private fun parseProfiles(s: String): List<SocialUser> = gson.fromJson(s, object : TypeToken<List<SocialUser>>() {}.type) ?: emptyList()

    private fun <T> fail(callback: ResultCallback<T>, message: String) = callback.onComplete(SocialResult(error = IllegalStateException(message)))
}
