package com.fadcam.tv.social

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tv49.com.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Focused REST surface for social interactions that must remain actor-scoped.
 * Complex writes live here instead of being assembled ad-hoc in Activities.
 */
class SocialFeatureRepository(context: Context) {
    interface Callback<T> { fun onComplete(result: SocialResult<T>) }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("tv49_social_session", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun token(): String? = prefs.getString("access_token", null)
    private fun userId(): String? = prefs.getString("user_id", null)
    private fun base(): String = BuildConfig.SUPABASE_URL.trimEnd('/')

    fun quotePost(postId: String, comment: String, callback: Callback<Boolean>) {
        val uid = userId() ?: return fail(callback, "Sign in required")
        val body = comment.trim()
        if (postId.isBlank()) return fail(callback, "Post id required")
        if (body.length > 25000) return fail(callback, "Quote is too long")
        write("/rest/v1/post_quotes", mapOf("author_id" to uid, "post_id" to postId, "comment" to body), callback)
    }

    fun viewPost(postId: String, callback: Callback<Boolean>) {
        val uid = userId() ?: return fail(callback, "Sign in required")
        if (postId.isBlank()) return fail(callback, "Post id required")
        val path = "/rest/v1/post_views?on_conflict=post_id,viewer_id"
        val body = json(mapOf("post_id" to postId, "viewer_id" to uid, "last_viewed_at" to "now()", "view_count" to 1))
        val request = requestBuilder(path)
            .header("Prefer", "resolution=merge-duplicates")
            .post(body.toRequestBody(jsonType))
            .build()
        execute(request, callback)
    }

    fun toggleListFollow(listId: String, enabled: Boolean, callback: Callback<Boolean>) {
        val uid = userId() ?: return fail(callback, "Sign in required")
        val filter = "list_id=eq.${enc(listId)}&user_id=eq.${enc(uid)}"
        if (enabled) write("/rest/v1/list_followers", mapOf("list_id" to listId, "user_id" to uid), callback)
        else delete("/rest/v1/list_followers?$filter", callback)
    }

    fun createList(name: String, description: String, privateList: Boolean, callback: Callback<String?>) {
        val uid = userId() ?: return fail(callback, "Sign in required")
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > 80) return fail(callback, "List name must be 1-80 characters")
        val path = "/rest/v1/lists?select=id"
        postReturning(path, mapOf("owner_id" to uid, "name" to clean, "description" to description.trim().take(500), "is_private" to privateList), callback) { raw ->
            parseId(raw)
        }
    }

    fun addListMember(listId: String, memberId: String, enabled: Boolean, callback: Callback<Boolean>) {
        if (userId() == null) return fail(callback, "Sign in required")
        if (listId.isBlank() || memberId.isBlank()) return fail(callback, "List and member are required")
        if (enabled) write("/rest/v1/list_members", mapOf("list_id" to listId, "user_id" to memberId), callback)
        else delete("/rest/v1/list_members?list_id=eq.${enc(listId)}&user_id=eq.${enc(memberId)}", callback)
    }

    fun createConversation(memberIds: List<String>, callback: Callback<String?>) {
        val uid = userId() ?: return fail(callback, "Sign in required")
        val members = (memberIds + uid).filter { it.isNotBlank() }.distinct()
        if (members.size < 2) return fail(callback, "At least two conversation members are required")
        postReturning("/rest/v1/conversations?select=id", emptyMap(), callback) { raw ->
            val idResult = parseId(raw)
            val id = idResult.value ?: return@postReturning idResult
            var remaining = members.size
            var firstError: Throwable? = null
            val lock = Any()
            for (member in members) {
                postRaw("/rest/v1/conversation_members", mapOf("conversation_id" to id, "user_id" to member)) { result ->
                    synchronized(lock) {
                        if (result.error != null && firstError == null) firstError = result.error
                        remaining--
                        if (remaining == 0) {
                            callback.onComplete(if (firstError == null) SocialResult(value = id) else SocialResult(error = firstError))
                        }
                    }
                }
            }
            SocialResult(value = null)
        }
    }

    fun sendMessage(
        conversationId: String,
        body: String,
        replyToMessageId: String? = null,
        sharedPostId: String? = null,
        clientMessageId: String = UUID.randomUUID().toString(),
        callback: Callback<String?>
    ) {
        val uid = userId() ?: return fail(callback, "Sign in required")
        val clean = body.trim()
        if (conversationId.isBlank()) return fail(callback, "Conversation id required")
        if (clean.isEmpty() && sharedPostId.isNullOrBlank()) return fail(callback, "Message cannot be empty")
        if (clean.length > 10000) return fail(callback, "Message is too long")
        val fields = mutableMapOf<String, Any?>("conversation_id" to conversationId, "sender_id" to uid, "body" to clean, "client_message_id" to clientMessageId)
        if (!replyToMessageId.isNullOrBlank()) fields["reply_to_message_id"] = replyToMessageId
        if (!sharedPostId.isNullOrBlank()) fields["shared_post_id"] = sharedPostId
        postReturning("/rest/v1/messages?select=id", fields, callback) { raw -> parseId(raw) }
    }

    fun reactToMessage(messageId: String, reaction: String, enabled: Boolean, callback: Callback<Boolean>) {
        val uid = userId() ?: return fail(callback, "Sign in required")
        val clean = reaction.trim().take(32)
        if (clean.isEmpty()) return fail(callback, "Reaction required")
        val filter = "message_id=eq.${enc(messageId)}&user_id=eq.${enc(uid)}&reaction=eq.${enc(clean)}"
        if (enabled) write("/rest/v1/message_reactions", mapOf("message_id" to messageId, "user_id" to uid, "reaction" to clean), callback)
        else delete("/rest/v1/message_reactions?$filter", callback)
    }

    fun editMessage(messageId: String, body: String, callback: Callback<Boolean>) {
        val clean = body.trim()
        if (messageId.isBlank() || clean.isEmpty() || clean.length > 10000) return fail(callback, "Invalid message")
        patch("/rest/v1/messages?id=eq.${enc(messageId)}", mapOf("body" to clean, "edited_at" to "now()"), callback)
    }

    fun deleteMessage(messageId: String, callback: Callback<Boolean>) =
        patch("/rest/v1/messages?id=eq.${enc(messageId)}", mapOf("deleted_at" to "now()"), callback)

    fun toggleMute(targetUserId: String, enabled: Boolean, callback: Callback<Boolean>) =
        toggleRelation("mutes", "muter_id", "muted_id", targetUserId, enabled, callback)

    fun toggleBlock(targetUserId: String, enabled: Boolean, callback: Callback<Boolean>) =
        toggleRelation("user_blocks", "blocker_id", "blocked_id", targetUserId, enabled, callback)

    fun saveDraft(draftId: String?, body: String, metadata: String = "{}", callback: Callback<String?>) {
        val uid = userId() ?: return fail(callback, "Sign in required")
        val clean = body.take(25000)
        val meta = try { gson.fromJson(metadata, JsonObject::class.java) } catch (_: Throwable) { return fail(callback, "Invalid draft metadata") }
        if (draftId.isNullOrBlank()) {
            postReturning("/rest/v1/post_drafts?select=id", mapOf("author_id" to uid, "body" to clean, "metadata" to meta), callback) { raw -> parseId(raw) }
        } else {
            patch("/rest/v1/post_drafts?id=eq.${enc(draftId)}&author_id=eq.${enc(uid)}&select=id", mapOf("body" to clean, "metadata" to meta, "updated_at" to "now()")) { result ->
                if (result.error != null) callback.onComplete(SocialResult(error = result.error))
                else callback.onComplete(SocialResult(value = draftId))
            }
        }
    }

    fun deleteDraft(draftId: String, callback: Callback<Boolean>) =
        delete("/rest/v1/post_drafts?id=eq.${enc(draftId)}", callback)

    fun editPost(postId: String, body: String, callback: Callback<Boolean>) {
        val clean = body.trim()
        if (postId.isBlank() || clean.isEmpty() || clean.length > 25000) return fail(callback, "Invalid post")
        patch("/rest/v1/posts?id=eq.${enc(postId)}", mapOf("body" to clean, "edited_at" to "now()"), callback)
    }

    fun loadConversations(limit: Int = 50, callback: Callback<String>) {
        val uid = userId() ?: return fail(callback, "Sign in required")
        get("/rest/v1/conversation_members?user_id=eq.${enc(uid)}&select=conversation_id,joined_at,last_read_at,conversations(id,updated_at)&order=joined_at.desc&limit=${limit.coerceIn(1,100)}", callback)
    }

    fun loadListTimeline(listId: String, limit: Int = 30, callback: Callback<String>) =
        get("/rest/v1/list_members?list_id=eq.${enc(listId)}&select=user_id&limit=${limit.coerceIn(1,100)}", callback)

    private fun toggleRelation(table: String, actorColumn: String, targetColumn: String, target: String, enabled: Boolean, callback: Callback<Boolean>) {
        val uid = userId() ?: return fail(callback, "Sign in required")
        if (target.isBlank() || target == uid) return fail(callback, "Invalid target")
        val filter = "$actorColumn=eq.${enc(uid)}&$targetColumn=eq.${enc(target)}"
        if (enabled) write("/rest/v1/$table", mapOf(actorColumn to uid, targetColumn to target), callback)
        else delete("/rest/v1/$table?$filter", callback)
    }

    private fun write(path: String, fields: Map<String, Any?>, callback: Callback<Boolean>) {
        postRaw(path, fields) { result -> callback.onComplete(if (result.error == null) SocialResult(value = true) else SocialResult(error = result.error)) }
    }

    private fun postReturning(path: String, fields: Map<String, Any?>, callback: Callback<String?>, parser: (String) -> SocialResult<String?>) {
        postRaw(path, fields) { result ->
            if (result.error != null) callback.onComplete(SocialResult(error = result.error))
            else callback.onComplete(parser(result.value.orEmpty()))
        }
    }

    private fun postRaw(path: String, fields: Map<String, Any?>, callback: (SocialResult<String>) -> Unit) {
        val request = requestBuilder(path).post(gson.toJson(fields).toRequestBody(jsonType)).build()
        executeText(request, callback)
    }

    private fun patch(path: String, fields: Map<String, Any?>, callback: Callback<Boolean>) {
        val request = requestBuilder(path).patch(gson.toJson(fields).toRequestBody(jsonType)).build()
        execute(request, callback)
    }

    private fun delete(path: String, callback: Callback<Boolean>) {
        val request = requestBuilder(path).delete().build()
        execute(request, callback)
    }

    private fun get(path: String, callback: Callback<String>) {
        val request = requestBuilder(path).get().build()
        executeText(request) { result -> callback.onComplete(if (result.error == null) SocialResult(value = result.value) else SocialResult(error = result.error)) }
    }

    private fun requestBuilder(path: String): Request.Builder {
        val builder = Request.Builder().url(base() + path).header("apikey", BuildConfig.SUPABASE_ANON_KEY).header("Accept", "application/json")
        token()?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun execute(request: Request, callback: Callback<Boolean>) =
        executeText(request) { result -> callback.onComplete(if (result.error == null) SocialResult(value = true) else SocialResult(error = result.error)) }

    private fun executeText(request: Request, callback: (SocialResult<String>) -> Unit) {
        http.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) = callback(SocialResult(error = e))
            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    val text = it.body?.string().orEmpty()
                    if (!it.isSuccessful) callback(SocialResult(error = IOException("Social API ${it.code}: ${text.take(500)}")))
                    else callback(SocialResult(value = text))
                }
            }
        })
    }

    private fun parseId(raw: String): SocialResult<String?> = try {
        val id = gson.fromJson(raw, Array<JsonObject>::class.java).firstOrNull()?.get("id")?.takeUnless { it.isJsonNull }?.asString
        if (id.isNullOrBlank()) SocialResult(error = IOException("API response did not contain an id")) else SocialResult(value = id)
    } catch (t: Throwable) { SocialResult(error = t) }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun <T> fail(callback: Callback<T>, message: String) = callback.onComplete(SocialResult(error = IllegalStateException(message)))

    private fun json(value: Map<String, Any?>): String = gson.toJson(value)
}
