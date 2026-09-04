package com.fadcam.tv.social

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.tv49.com.BuildConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Native Supabase REST adapter. The APK only receives the public anon key; RLS remains authoritative. */
class SupabaseSocialRepository(context: Context) {
    interface ResultCallback<T> { fun onComplete(result: SocialResult<T>) }

    private val prefs = context.applicationContext.getSharedPreferences("tv49_social_session", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS).build()
    private val json = "application/json; charset=utf-8".toMediaType()
    private val configured get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    fun isConfigured() = configured
    fun currentAccessToken(): String? = prefs.getString("access_token", null)
    fun currentUserId(): String? = prefs.getString("user_id", null)

    fun signIn(email: String, password: String, callback: ResultCallback<SocialSession>) {
        if (!configured) return callback.onComplete(SocialResult(error = IllegalStateException("Supabase is not configured")))
        val body = gson.toJson(mapOf("email" to email.trim(), "password" to password))
        request("/auth/v1/token?grant_type=password", "POST", body, null) { result ->
            if (result.error != null) return@request callback.onComplete(SocialResult(error = result.error))
            try {
                val obj = gson.fromJson(result.value, JsonObject::class.java)
                val token = obj.get("access_token")?.asString ?: error("Missing access_token")
                val refresh = obj.get("refresh_token")?.asString
                val userId = obj.getAsJsonObject("user")?.get("id")?.asString
                val session = SocialSession(token, refresh, userId)
                saveSession(session)
                callback.onComplete(SocialResult(value = session))
            } catch (t: Throwable) { callback.onComplete(SocialResult(error = t)) }
        }
    }

    fun signUp(email: String, password: String, username: String, displayName: String, callback: ResultCallback<SocialSession>) {
        if (!configured) return callback.onComplete(SocialResult(error = IllegalStateException("Supabase is not configured")))
        val cleanUsername = username.trim()
        if (!cleanUsername.matches(Regex("[A-Za-z0-9_]{3,32}"))) return callback.onComplete(SocialResult(error = IllegalArgumentException("Username must be 3-32 letters, numbers or underscores")))
        if (password.length < 6) return callback.onComplete(SocialResult(error = IllegalArgumentException("Password must be at least 6 characters")))
        val body = gson.toJson(mapOf("email" to email.trim(), "password" to password, "data" to mapOf("username" to cleanUsername, "display_name" to displayName.trim())))
        request("/auth/v1/signup", "POST", body, null) { result ->
            if (result.error != null) return@request callback.onComplete(SocialResult(error = result.error))
            try {
                val obj = gson.fromJson(result.value, JsonObject::class.java)
                val token = obj.get("access_token")?.asString
                if (token.isNullOrBlank()) return@request callback.onComplete(SocialResult(error = IllegalStateException("Account created. Confirm the email before signing in.")))
                val session = SocialSession(token, obj.get("refresh_token")?.asString, obj.getAsJsonObject("user")?.get("id")?.asString)
                saveSession(session)
                callback.onComplete(SocialResult(value = session))
            } catch (t: Throwable) { callback.onComplete(SocialResult(error = t)) }
        }
    }

    fun signUp(email: String, password: String, callback: ResultCallback<SocialSession>) =
        signUp(email, password, "user_${UUID.randomUUID().toString().replace("-", "").take(10)}", "", callback)

    fun signOut() = prefs.edit().clear().apply()

    fun loadProfile(callback: ResultCallback<SocialUser?>) {
        if (!configured) return callback.onComplete(SocialResult(value = null))
        val id = currentUserId() ?: return callback.onComplete(SocialResult(value = null))
        request("/rest/v1/profiles?id=eq.$id&select=id,username,display_name,avatar_url,bio", "GET", null, currentAccessToken()) { result ->
            if (result.error != null) return@request callback.onComplete(SocialResult(error = result.error))
            try {
                val rows: List<RemoteProfile> = gson.fromJson(result.value, object : TypeToken<List<RemoteProfile>>() {}.type) ?: emptyList()
                callback.onComplete(SocialResult(value = rows.firstOrNull()?.toModel()))
            } catch (t: Throwable) { callback.onComplete(SocialResult(error = t)) }
        }
    }

    fun updateProfile(username: String, displayName: String, bio: String, avatarUrl: String?, callback: ResultCallback<SocialUser?>) {
        val token = currentAccessToken() ?: return callback.onComplete(SocialResult(error = IllegalStateException("Sign in required")))
        val id = currentUserId() ?: return callback.onComplete(SocialResult(error = IllegalStateException("Session user id missing; sign in again")))
        if (!username.matches(Regex("[A-Za-z0-9_]{3,32}"))) return callback.onComplete(SocialResult(error = IllegalArgumentException("Invalid username")))
        val payload = mutableMapOf<String, Any>("username" to username.trim(), "display_name" to displayName.trim(), "bio" to bio.trim())
        if (avatarUrl != null) payload["avatar_url"] = avatarUrl
        request("/rest/v1/profiles?id=eq.$id&select=id,username,display_name,avatar_url,bio", "PATCH", gson.toJson(payload), token, "return=representation") { result ->
            if (result.error != null) return@request callback.onComplete(SocialResult(error = result.error))
            try {
                val rows: List<RemoteProfile> = gson.fromJson(result.value, object : TypeToken<List<RemoteProfile>>() {}.type) ?: emptyList()
                callback.onComplete(SocialResult(value = rows.firstOrNull()?.toModel()))
            } catch (t: Throwable) { callback.onComplete(SocialResult(error = t)) }
        }
    }

    fun publicMediaUrl(kind: String, userId: String, filename: String): String =
        BuildConfig.SUPABASE_URL.trimEnd('/') + "/storage/v1/object/public/tv49-profile-media/$kind/$userId/$filename"

    fun loadFeed(limit: Int = 30, callback: ResultCallback<List<SocialPost>>) {
        if (!configured) return callback.onComplete(SocialResult(value = emptyList()))
        val token = currentAccessToken() ?: return callback.onComplete(SocialResult(error = IllegalStateException("Sign in required")))
        val path = "/rest/v1/posts?select=id,body,media_url,media_type,created_at,like_count,reply_count,repost_count,author:profiles!posts_author_id_fkey(id,username,display_name,avatar_url,bio)&order=created_at.desc&limit=${limit.coerceIn(1,50)}"
        request(path, "GET", null, token) { result ->
            if (result.error != null) return@request callback.onComplete(SocialResult(error = result.error))
            try {
                val rows: List<RemotePost> = gson.fromJson(result.value, object : TypeToken<List<RemotePost>>() {}.type) ?: emptyList()
                callback.onComplete(SocialResult(value = rows.map { it.toModel() }))
            } catch (t: Throwable) { callback.onComplete(SocialResult(error = t)) }
        }
    }

    fun createPost(bodyText: String, callback: ResultCallback<SocialPost?>) {
        val token = currentAccessToken() ?: return callback.onComplete(SocialResult(error = IllegalStateException("Sign in required")))
        val userId = currentUserId() ?: return callback.onComplete(SocialResult(error = IllegalStateException("Session user id missing; sign in again")))
        if (bodyText.trim().isEmpty()) return callback.onComplete(SocialResult(error = IllegalArgumentException("Post cannot be empty")))
        val body = gson.toJson(mapOf("author_id" to userId, "body" to bodyText.trim()))
        request("/rest/v1/posts?select=id,body,media_url,media_type,created_at,like_count,reply_count,repost_count,author:profiles!posts_author_id_fkey(id,username,display_name,avatar_url,bio)", "POST", body, token, "return=representation") { result ->
            if (result.error != null) return@request callback.onComplete(SocialResult(error = result.error))
            try {
                val rows: List<RemotePost> = gson.fromJson(result.value, object : TypeToken<List<RemotePost>>() {}.type) ?: emptyList()
                callback.onComplete(SocialResult(value = rows.firstOrNull()?.toModel()))
            } catch (t: Throwable) { callback.onComplete(SocialResult(error = t)) }
        }
    }

    private fun saveSession(session: SocialSession) {
        prefs.edit().putString("access_token", session.accessToken).putString("refresh_token", session.refreshToken).putString("user_id", session.userId).apply()
    }

    private fun request(path: String, method: String, body: String?, bearer: String?, prefer: String? = null, callback: (SocialResult<String>) -> Unit) {
        val builder = Request.Builder()
            .url(BuildConfig.SUPABASE_URL.trimEnd('/') + path)
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .header("Accept", "application/json")
        bearer?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        prefer?.let { builder.header("Prefer", it) }
        builder.method(method, body?.toRequestBody(json))
        http.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(SocialResult(error = e))
            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    val text = it.body?.string().orEmpty()
                    if (!it.isSuccessful) callback(SocialResult(error = IOException("Supabase ${it.code}: ${text.take(300)}")))
                    else callback(SocialResult(value = text))
                }
            }
        })
    }

    private data class RemoteProfile(
        val id: String = "",
        val username: String = "",
        val display_name: String = "",
        val avatar_url: String? = null,
        val bio: String? = null
    ) {
        fun toModel() = SocialUser(id, username, display_name, avatar_url, bio)
    }

    private data class RemotePost(
        val id: String = "",
        val body: String = "",
        val media_url: String? = null,
        val media_type: String? = null,
        val created_at: String = "",
        val like_count: Int = 0,
        val reply_count: Int = 0,
        val repost_count: Int = 0,
        val author: RemoteProfile? = null
    ) {
        fun toModel() = SocialPost(
            id = id,
            author = author?.toModel() ?: SocialUser("", "", ""),
            body = body,
            mediaUrl = media_url,
            mediaType = media_type,
            createdAt = created_at,
            likeCount = like_count,
            replyCount = reply_count,
            repostCount = repost_count
        )
    }
}
