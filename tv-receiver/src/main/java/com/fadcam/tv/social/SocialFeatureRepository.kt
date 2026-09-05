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

/** Native REST boundary for actor-scoped social interactions. */
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
        if (userId() == null) return fail(callback, "Sign in required")
        if (postId.isBlank()) return fail(callback, "Post id required")
        val request = requestBuilder("/rest/v1/rpc/record_post_view")
            .post(gson.toJson(mapOf("p_post_id" to postId)).toRequestBody(jsonType))
            .build()
        execute(request, callback)
    }

    fun toggleListFollow(listId: String, enabled: Boolean, callback: Callback<Boolean>) {
        val uid = userId() ?: return fail(callback, "Sign in required")
        if (listId.isBlank()) return fail(callback, "List id required")
        val filter = "list_id=eq.${enc(listId)}&user_id=eq.${enc(uid)}"
        if (enabled) write("/rest/v1/list_followers", mapOf("list_id" to listId, "user_id" to uid), callback)
        else delete("/rest/v1/list_followers?$filter", callback)
    }

    fun createList(name: String, description: String, privateList: Boolean, callback: Callback<String?>) {
        val uid = userId() ?: return fail(callback, "Sign in required")
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > 80) return fail(callback, "List name must be 1-80 characters")
        postReturningId("/rest/v1/lists?select=id", mapOf(
            "owner_id" to uid,
            "name" to clean,
            "description" to description.trim().take(500),
            "is_private" to privateList
        ), callback)
    }

    fun addListMember(listId: String, memberId: String, enabled: Boolean, callback: Callback<Boolean>) {
        if (userId() == null) return fail(callback, "Sign in required")
        if (listId.isBlank() || memberId.isBlank()) return fail(callback, "List and member are required")
        if (enabled) write("/rest/v1/list_members", mapOf("list_id" to listId, "user_id" to memberId), callback)
        else delete("/rest/v1/list_members?list_id=eq.${enc(listId)}&user_id=eq.${enc(memberId)}", callback)
    }

    fun createConversation(memberIds: List<String>, callback: Callback<String?>) {
        val uid = userId() ?: return fail(callback, "Sign in required")
        val members = (memberIds + uid).filter(String::isNotBlank).distinct()
        if (members.size < 2) return fail(callback, "At least two conversation members are required")
        postRaw("/rest/v1/conversations?select=id", emptyMap()) { result ->
            if (result.error != null) return@postRaw callback.onComplete(SocialResult<String?>(error = result.error))
            val id = parseId(result.value.orEmpty())
            if (id == null) return@postRaw callback.onComplete(SocialResult<String?>(error = IOException("Conversation id missing")))
            var remaining = members.size
            var firstError: Throwable? = null
            val lock = Any()
            members.forEach { member ->
                postRaw("/rest/v1/conversation_members", mapOf("conversation_id" to id, "user_id" to member)) { memberResult ->
                    synchronized(lock) {
                        if (memberResult.error != null && firstError == null) firstError = memberResult.error
                        remaining -= 1
                        if (remaining == 0) {
                            val finalResult = if (firstError == null) SocialResult<String?>(value = id) else SocialResult<String?>(error = firstError)
                            callback.onComplete(finalResult)
                        }
                    }
                }
            }
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
        val fields = mutableMapOf<String, Any?>(
            "conversation_id" to conversationId,
            "sender_id" to uid,
            "body" to clean,
            "client_message_id" to clientMessageId
        )
        if (!replyToMessageId.isNullOrBlank()) fields["reply_to_message_id"] = replyToMessageId
        if (!sharedPostId.isNullOrBlank()) fields["shared_post_id"] = sharedPostId
        postReturningId("/rest/v1/messages?select=id", fields, callback)
    }

    fun reactToMessage(messageId: String, reaction: String, enabled: Boolean, callback: Callback<Boolean>) {
        val uid = userId() ?: return fail(callback, "Sign in required")
        val clean = reaction.trim().take(32)
        if (messageId.isBlank() || clean.isEmpty()) return fail(callback, "Message reaction is invalid")
        val filter = "message_id=eq.${enc(messageId)}&user_id=eq.${enc(uid)}&reaction=eq.${enc(clean)}"
        if (enabled) write("/rest/v1/message_reactions", mapOf("message_id" to messageId, "user_id" to uid, "reaction" to clean), callback)
        else delete("/rest/v1/message_reactions?$filter", callback)
    }

    fun editMessage(messageId: String, body: String, callback: Callback<Boolean>) {
        val uid = userId() ?: return fail(callback, "Sign in required")
        val clean = body.trim()
        if (messageId.isBlank() || clean.isEmpty() || clean.length > 10000) return fail(callback, "Invalid message")
        patch("/rest/v1/messages?id=eq.${enc(messageId)}&sender_id=eq.${enc(uid)}", mapOf("body" to clean, "edited_at" to "now()"), callback)
    }

    fun deleteMessage(messageId: String, callback: Callback<Boolean>) {
        val uid = userId() ?: return fail(callback, "Sign in required")
        if (messageId.isBlank()) return fail(callback, "Message id required")
        patch("/rest/v1/messages?id=eq.${enc(messageId)}&sender_id=eq.${enc(uid)}", mapOf("deleted_at" to "now()"), callback)
    }

    fun toggleMute(targetUserId: String, enabled: Boolean, callback: Callback<Boolean>) =
        toggleRelation("mutes", "muter_id", "muted_id", targetUserId, enabled, callback)

    fun toggleBlock(targetUserId: String, enabled: Boolean, callback: Callback<Boolean>) =
        toggleRelation("user_blocks", "blocker_id", "blocked_id", targetUserId, enabled, callback)

    fun saveDraft(draftId: String?, body: String, metadata: String = "{}", callback: Callback<String?>) {
        val uid = userId() ?: return fail(callback, "Sign in required")
        val clean = body.take(25000)
        val meta = try { gson.fromJson(metadata, JsonObject::class.java) } catch (_: Throwable) { return fail(callback, "Invalid draft metadata") }
        if (draftId.isNullOrBlank()) {
            postReturningId("/rest/v1/post_drafts?select=id", mapOf("author_id" to uid, "body" to clean, "metadata" to meta), callback)
        } else {
            patchRaw("/rest/v1/post_drafts?id=eq.${enc(draftId)}&author_id=eq.${enc(uid)}", mapOf("body" to clean, "metadata" to meta, "updated_at" to "now()")) { result ->
                callback.onComplete(if (result.error == null) SocialResult<String?>(value = draftId) else SocialResult<String?>(error = result.error))
            }
        }
    }

    fun deleteDraft(draftId: String, callback: Callback<Boolean>) {
        if (userId() == null) return fail(callback, "Sign in required")
        if (draftId.isBlank()) return fail(callback, "Draft id required")
        delete("/rest/v1/post_drafts?id=eq.${enc(draftId)}", callback)
    }

    fun editPost(postId: String, body: String, callback: Callback<Boolean>) {
        val uid = userId() ?: return fail(callback, "Sign in required")
        val clean = body.trim()
        if (postId.isBlank() || clean.isEmpty() || clean.length > 25000) return fail(callback, "Invalid post")
        patch("/rest/v1/posts?id=eq.${enc(postId)}&author_id=eq.${enc(uid)}", mapOf("body" to clean, "edited_at" to "now()"), callback)
    }

    fun loadConversations(limit: Int = 50, callback: Callback<String>) {
        val uid = userId() ?: return fail(callback, "Sign in required")
        get("/rest/v1/conversation_members?user_id=eq.${enc(uid)}&select=conversation_id,joined_at,last_read_at,conversations(id,updated_at)&order=joined_at.desc&limit=${limit.coerceIn(1,100)}", callback)
    }

    fun loadListTimeline(listId: String, limit: Int = 30, callback: Callback<String>) {
        if (userId() == null) return fail(callback, "Sign in required")
        if (listId.isBlank()) return fail(callback, "List id required")
        get("/rest/v1/list_members?list_id=eq.${enc(listId)}&select=user_id&limit=${limit.coerceIn(1,100)}", callback)
    }

    private fun toggleRelation(table: String, actorColumn: String, targetColumn: String, target: String, enabled: Boolean, callback: Callback<Boolean>) {
        val uid = userId() ?: return fail(callback, "Sign in required")
        if (target.isBlank() || target == uid) return fail(callback, "Invalid target")
        val filter = "$actorColumn=eq.${enc(uid)}&$targetColumn=eq.${enc(target)}"
        if (enabled) write("/rest/v1/$table", mapOf(actorColumn to uid, targetColumn to target), callback)
        else delete("/rest/v1/$table?$filter", callback)
    }

    private fun write(path: String, fields: Map<String, Any?>, callback: Callback<Boolean>) {
        postRaw(path, fields) { result -> callback.onComplete(if (result.error == null) SocialResult<Boolean>(value = true) else SocialResult<Boolean>(error = result.error)) }
    }

    private fun postReturningId(path: String, fields: Map<String, Any?>, callback: Callback<String?>) {
        postRaw(path, fields) { result ->
            if (result.error != null) {
                callback.onComplete(SocialResult<String?>(error = result.error))
            } else {
                val id = parseId(result.value.orEmpty())
                callback.onComplete(if (id == null) SocialResult<String?>(error = IOException("API response did not contain an id")) else SocialResult<String?>(value = id))
            }
        }
    }

    private fun postRaw(path: String, fields: Map<String, Any?>, callback: (SocialResult<String>) -> Unit) {
        val request = requestBuilder(path).post(gson.toJson(fields).toRequestBody(jsonType)).build()
        executeText(request, callback)
    }

    private fun patch(path: String, fields: Map<String, Any?>, callback: Callback<Boolean>) {
        patchRaw(path, fields) { result -> callback.onComplete(if (result.error == null) SocialResult<Boolean>(value = true) else SocialResult<Boolean>(error = result.error)) }
    }

    private fun patchRaw(path: String, fields: Map<String, Any?>, callback: (SocialResult<String>) -> Unit) {
        val request = requestBuilder(path).patch(gson.toJson(fields).toRequestBody(jsonType)).build()
        executeText(request, callback)
    }

    private fun delete(path: String, callback: Callback<Boolean>) {
        execute(requestBuilder(path).delete().build(), callback)
    }

    private fun get(path: String, callback: Callback<String>) {
        executeText(requestBuilder(path).get().build()) { result ->
            callback.onComplete(if (result.error == null) SocialResult<String>(value = result.value) else SocialResult<String>(error = result.error))
        }
    }

    private fun requestBuilder(path: String): Request.Builder {
        val builder = Request.Builder()
            .url(base() + path)
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .header("Accept", "application/json")
        token()?.takeIf(String::isNotBlank)?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun execute(request: Request, callback: Callback<Boolean>) {
        executeText(request) { result ->
            callback.onComplete(if (result.error == null) SocialResult<Boolean>(value = true) else SocialResult<Boolean>(error = result.error))
        }
    }

    private fun executeText(request: Request, callback: (SocialResult<String>) -> Unit) {
        http.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) = callback(SocialResult<String>(error = e))
            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    val text = it.body?.string().orEmpty()
                    if (!it.isSuccessful) callback(SocialResult<String>(error = IOException("Social API ${it.code}: ${text.take(500)}")))
                    else callback(SocialResult<String>(value = text))
                }
            }
        })
    }

    private fun parseId(raw: String): String? = try {
        gson.fromJson(raw, Array<JsonObject>::class.java).firstOrNull()?.get("id")?.takeUnless(JsonObject::isJsonNull)?.asString?.takeIf(String::isNotBlank)
    } catch (_: Throwable) { null }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun <T> fail(callback: Callback<T>, message: String) {
        callback.onComplete(SocialResult<T>(error = IllegalStateException(message)))
    }
}
