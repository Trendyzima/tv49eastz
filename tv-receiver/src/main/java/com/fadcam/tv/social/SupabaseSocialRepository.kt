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
import java.util.concurrent.TimeUnit

/**
 * Small dependency-free Supabase adapter for the Android client.
 *
 * It intentionally uses the public anon key only. Authorization is performed by Supabase RLS;
 * no service_role credential is ever accepted by this class.
 */
class SupabaseSocialRepository(context: Context) {
    interface ResultCallback<T> { fun onComplete(result: SocialResult<T>) }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("tv49_social_session", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    private val configured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    fun isConfigured(): Boolean = configured

    fun currentAccessToken(): String? = prefs.getString("access_token", null)

    fun signIn(email: String, password: String, callback: ResultCallback<SocialSession>) {
        if (!configured) return callback.onComplete(SocialResult(error = IllegalStateException("Supabase is not configured")))
        val body = gson.toJson(mapOf("email" to email, "password" to password))
        request("/auth/v1/token?grant_type=password", "POST", body, null) { result ->
            if (result.error != null) return@request callback.onComplete(SocialResult(error = result.error))
            try {
                val obj = gson.fromJson(result.value, JsonObject::class.java)
                val session = SocialSession(
                    accessToken = obj.get("access_token")?.asString ?: error("Missing access_token"),
                    refreshToken = obj.get("refresh_token")?.asString,
                    userId = obj.getAsJsonObject("user")?.get("id")?.asString
                )
                saveSession(session)
                callback.onComplete(SocialResult(value = session))
            } catch (t: Throwable) { callback.onComplete(SocialResult(error = t)) }
        }
    }

    fun signUp(email: String, password: String, callback: ResultCallback<SocialSession?>) {
        if (!configured) return callback.onComplete(SocialResult(error = IllegalStateException("Supabase is not configured")))
        val body = gson.toJson(mapOf("email" to email, "password" to password))
        request("/auth/v1/signup", "POST", body, null) { result ->
            if (result.error != null) return@request callback.onComplete(SocialResult(error = result.error))
            try {
                val obj = gson.fromJson(result.value, JsonObject::class.java)
                val token = obj.get("access_token")?.asString
                val session = token?.let {
                    SocialSession(it, obj.get("refresh_token")?.asString, obj.getAsJsonObject("user")?.get("id")?.asString)
                }
                if (session != null) saveSession(session)
                callback.onComplete(SocialResult(value = session))
            } catch (t: Throwable) { callback.onComplete(SocialResult(error = t)) }
        }
    }

    fun signOut() {
        prefs.edit().clear().apply()
    }

    fun loadFeed(limit: Int = 30, callback: ResultCallback<List<SocialPost>>) {
        if (!configured) return callback.onComplete(SocialResult(value = emptyList()))
        val token = currentAccessToken()
        val path = "/rest/v1/posts?select=id,body,media_url,media_type,created_at,like_count,reply_count,repost_count,author:profiles!posts_author_id_fkey(id,username,display_name,avatar_url,bio)&order=created_at.desc&limit=${limit.coerceIn(1, 50)}"
        request(path, "GET", null, token) { result ->
            if (result.error != null) return@request callback.onComplete(SocialResult(error = result.error))
            try {
                val type = object : TypeToken<List<RemotePost>>() {}.type
                val remote: List<RemotePost> = gson.fromJson(result.value, type) ?: emptyList()
                callback.onComplete(SocialResult(value = remote.map { it.toModel() }))
            } catch (t: Throwable) { callback.onComplete(SocialResult(error = t)) }
        }
    }

    fun createPost(bodyText: String, callback: ResultCallback<SocialPost?>) {
        if (!configured) return callback.onComplete(SocialResult(error = IllegalStateException("Supabase is not configured")))
        val token = currentAccessToken()
            ?: return callback.onComplete(SocialResult(error = IllegalStateException("Sign in required")))
        val body = gson.toJson(mapOf("body" to bodyText.trim()))
        request("/rest/v1/posts?select=id,body,media_url,media_type,created_at,like_count,reply_count,repost_count,author:profiles!posts_author_id_fkey(id,username,display_name,avatar_url,bio)", "POST", body, token, prefer = "return=representation") { result ->
            if (result.error != null) return@request callback.onComplete(SocialResult(error = result.error))
            try {
                val type = object : TypeToken<List<RemotePost>>() {}.type
                val rows: List<RemotePost> = gson.fromJson(result.value, type) ?: emptyList()
                callback.onComplete(SocialResult(value = rows.firstOrNull()?.toModel()))
            } catch (t: Throwable) { callback.onComplete(SocialResult(error = t)) }
        }
    }

    private fun saveSession(session: SocialSession) {
        prefs.edit()
            .putString("access_token", session.accessToken)
            .putString("refresh_token", session.refreshToken)
            .putString("user_id", session.userId)
            .apply()
    }

    private fun request(path: String, method: String, body: String?, bearer: String?, prefer: String? = null, callback: (SocialResult<String>) -> Unit) {
        val base = BuildConfig.SUPABASE_URL.trimEnd('/')
        val builder = Request.Builder()
            .url(base + path)
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .header("Accept", "application/json")
        bearer?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        prefer?.let { builder.header("Prefer", it) }
        if (body != null) builder.method(method, body.toRequestBody(json)) else builder.method(method, null)
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
        val id: String = "", val username: String = "", val display_name: String = "",
        val avatar_url: String? = null, val bio: String? = null
    )
    private data class RemotePost(
        val id: String = "", val body: String = "", val media_url: String? = null,
        val media_type: String? = null, val created_at: String = "",
        val like_count: Int = 0, val reply_count: Int = 0, val repost_count: Int = 0,
        val author: RemoteProfile? = null
    ) {
        fun toModel() = SocialPost(
            id, SocialUser(author?.id.orEmpty(), author?.username.orEmpty(), author?.display_name.orEmpty(), author?.avatar_url, author?.bio),
            body, media_url, media_type, created_at, like_count, reply_count, repost_count
        )
    }
}
