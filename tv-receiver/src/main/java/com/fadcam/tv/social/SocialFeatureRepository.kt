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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Focused actor-scoped REST/RPC surface for advanced social interactions. */
class SocialFeatureRepository(context: Context) {
    interface Callback<T> { fun onComplete(result: SocialResult<T>) }
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("tv49_social_session", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private fun token(): String? = prefs.getString("access_token", null)
    private fun userId(): String? = prefs.getString("user_id", null)
    private fun base(): String = BuildConfig.SUPABASE_URL.trimEnd('/')

    fun quotePost(postId: String, comment: String, callback: Callback<Boolean>) { val uid=userId()?:return fail(callback,"Sign in required"); val body=comment.trim(); if(postId.isBlank())return fail(callback,"Post id required"); if(body.length>25000)return fail(callback,"Quote is too long"); upsert("/rest/v1/post_quotes?on_conflict=author_id,post_id",mapOf("author_id" to uid,"post_id" to postId,"comment" to body),callback) }
    fun viewPost(postId: String, callback: Callback<Boolean>) { if(userId()==null)return fail(callback,"Sign in required"); if(postId.isBlank())return fail(callback,"Post id required"); rpc("record_post_view",mapOf("p_post_id" to postId)){r->callback.onComplete(if(r.error==null)SocialResult(value=true)else SocialResult(error=r.error))} }
    fun toggleListFollow(listId: String, enabled: Boolean, callback: Callback<Boolean>) { val uid=userId()?:return fail(callback,"Sign in required"); val filter="list_id=eq.${enc(listId)}&user_id=eq.${enc(uid)}"; if(enabled)upsert("/rest/v1/list_followers?on_conflict=list_id,user_id",mapOf("list_id" to listId,"user_id" to uid),callback)else delete("/rest/v1/list_followers?$filter",callback) }
    fun createList(name: String, description: String, privateList: Boolean, callback: Callback<String?>) { val uid=userId()?:return fail(callback,"Sign in required"); val clean=name.trim(); if(clean.isEmpty()||clean.length>80)return fail(callback,"List name must be 1-80 characters"); postReturning("/rest/v1/lists?select=id",mapOf("owner_id" to uid,"name" to clean,"description" to description.trim().take(500),"is_private" to privateList),callback){raw->SocialResult(value=parseId(raw))} }
    fun addListMember(listId: String, memberId: String, enabled: Boolean, callback: Callback<Boolean>) { if(userId()==null)return fail(callback,"Sign in required"); if(enabled)upsert("/rest/v1/list_members?on_conflict=list_id,user_id",mapOf("list_id" to listId,"user_id" to memberId),callback)else delete("/rest/v1/list_members?list_id=eq.${enc(listId)}&user_id=eq.${enc(memberId)}",callback) }
    fun createConversation(memberIds: List<String>, callback: Callback<String?>) { val uid=userId()?:return fail(callback,"Sign in required"); val members=(memberIds+uid).filter{it.isNotBlank()}.distinct(); if(members.size<2)return fail(callback,"At least two conversation members are required"); rpc("create_conversation_atomic",mapOf("p_member_ids" to members)){r->if(r.error!=null)callback.onComplete(SocialResult(error=r.error))else callback.onComplete(SocialResult(value=parseScalarId(r.value.orEmpty())))} }
    fun sendMessage(conversationId: String, body: String, replyToMessageId: String?=null, sharedPostId: String?=null, clientMessageId: String=UUID.randomUUID().toString(), callback: Callback<String?>) { if(userId()==null)return fail(callback,"Sign in required"); val clean=body.trim(); if(clean.isEmpty()&&sharedPostId.isNullOrBlank())return fail(callback,"Message cannot be empty"); if(clean.length>10000)return fail(callback,"Message is too long"); rpc("send_message_idempotent",mapOf("p_conversation_id" to conversationId,"p_body" to clean,"p_reply_to_message_id" to replyToMessageId,"p_shared_post_id" to sharedPostId,"p_client_message_id" to clientMessageId)){r->if(r.error!=null)callback.onComplete(SocialResult(error=r.error))else callback.onComplete(SocialResult(value=parseScalarId(r.value.orEmpty())))} }
    fun reactToMessage(messageId: String, reaction: String, enabled: Boolean, callback: Callback<Boolean>) { val uid=userId()?:return fail(callback,"Sign in required"); val clean=reaction.trim().take(32); if(clean.isEmpty())return fail(callback,"Reaction required"); val filter="message_id=eq.${enc(messageId)}&user_id=eq.${enc(uid)}&reaction=eq.${enc(clean)}"; if(enabled)upsert("/rest/v1/message_reactions?on_conflict=message_id,user_id,reaction",mapOf("message_id" to messageId,"user_id" to uid,"reaction" to clean),callback)else delete("/rest/v1/message_reactions?$filter",callback) }
    fun editMessage(messageId: String, body: String, callback: Callback<Boolean>) { val clean=body.trim(); if(clean.isEmpty()||clean.length>10000)return fail(callback,"Invalid message"); patch("/rest/v1/messages?id=eq.${enc(messageId)}",mapOf("body" to clean,"edited_at" to nowIso()),callback) }
    fun deleteMessage(messageId: String, callback: Callback<Boolean>)=patch("/rest/v1/messages?id=eq.${enc(messageId)}",mapOf("deleted_at" to nowIso()),callback)
    fun toggleMute(targetUserId: String, enabled: Boolean, callback: Callback<Boolean>)=toggleRelation("mutes","muter_id","muted_id",targetUserId,enabled,callback)
    fun toggleBlock(targetUserId: String, enabled: Boolean, callback: Callback<Boolean>)=toggleRelation("user_blocks","blocker_id","blocked_id",targetUserId,enabled,callback)
    fun saveDraft(draftId: String?, body: String, metadata: String="{}", callback: Callback<String?>) { val uid=userId()?:return fail(callback,"Sign in required"); val clean=body.take(25000); val parsedMetadata=try{gson.fromJson(metadata,JsonObject::class.java)}catch(_:Throwable){return fail(callback,"Invalid draft metadata")}; if(draftId.isNullOrBlank())postReturning("/rest/v1/post_drafts?select=id",mapOf("author_id" to uid,"body" to clean,"metadata" to parsedMetadata),callback){raw->SocialResult(value=parseId(raw))}else patchRaw("/rest/v1/post_drafts?id=eq.${enc(draftId)}",mapOf("body" to clean,"metadata" to parsedMetadata,"updated_at" to nowIso())){r->callback.onComplete(if(r.error==null)SocialResult<String?>(value=draftId)else SocialResult(error=r.error))} }
    fun deleteDraft(draftId: String, callback: Callback<Boolean>)=delete("/rest/v1/post_drafts?id=eq.${enc(draftId)}",callback)
    fun editPost(postId: String, body: String, callback: Callback<Boolean>) { val clean=body.trim(); if(clean.isEmpty()||clean.length>25000)return fail(callback,"Invalid post"); patch("/rest/v1/posts?id=eq.${enc(postId)}",mapOf("body" to clean,"edited_at" to nowIso()),callback) }
    fun loadConversations(limit: Int=50, callback: Callback<String>) { if(userId()==null)return fail(callback,"Sign in required"); rpc("get_conversations_with_latest",mapOf("p_limit" to limit.coerceIn(1,100))){r->callback.onComplete(if(r.error==null)SocialResult(value=r.value)else SocialResult(error=r.error))} }
    fun loadMessages(conversationId: String, limit: Int=50, callback: Callback<String>)=get("/rest/v1/messages?conversation_id=eq.${enc(conversationId)}&select=id,conversation_id,sender_id,body,media_url,media_type,created_at,edited_at,deleted_at,reply_to_message_id,shared_post_id,client_message_id,delivered_at,read_at&order=created_at.desc&limit=${limit.coerceIn(1,100)}",callback)
    fun loadListTimeline(listId: String, limit: Int=30, offset: Int=0, callback: Callback<String>) { if(userId()==null)return fail(callback,"Sign in required"); rpc("get_list_timeline",mapOf("p_list_id" to listId,"p_limit" to limit.coerceIn(1,100),"p_offset" to offset.coerceAtLeast(0))){r->callback.onComplete(if(r.error==null)SocialResult(value=r.value)else SocialResult(error=r.error))} }
    fun requestFollow(targetUserId: String, callback: Callback<Boolean>) { val uid=userId()?:return fail(callback,"Sign in required"); if(uid==targetUserId)return fail(callback,"You cannot follow yourself"); upsert("/rest/v1/follow_requests?on_conflict=requester_id,target_id",mapOf("requester_id" to uid,"target_id" to targetUserId),callback) }
    fun cancelFollowRequest(targetUserId: String, callback: Callback<Boolean>) { val uid=userId()?:return fail(callback,"Sign in required"); delete("/rest/v1/follow_requests?requester_id=eq.${enc(uid)}&target_id=eq.${enc(targetUserId)}",callback) }
    fun respondToFollowRequest(requesterId: String, accept: Boolean, callback: Callback<Boolean>) { if(userId()==null)return fail(callback,"Sign in required"); rpc("respond_follow_request_atomic",mapOf("p_requester_id" to requesterId,"p_accept" to accept)){r->callback.onComplete(if(r.error==null)SocialResult(value=true)else SocialResult(error=r.error))} }
    fun markMessageRead(messageId: String, callback: Callback<Boolean>)=rpc("mark_message_status",mapOf("p_message_id" to messageId,"p_status" to "read")){r->callback.onComplete(if(r.error==null)SocialResult(value=true)else SocialResult(error=r.error))}
    fun markMessageDelivered(messageId: String, callback: Callback<Boolean>)=rpc("mark_message_status",mapOf("p_message_id" to messageId,"p_status" to "delivered")){r->callback.onComplete(if(r.error==null)SocialResult(value=true)else SocialResult(error=r.error))}
    private fun toggleRelation(table: String, actorColumn: String, targetColumn: String, target: String, enabled: Boolean, callback: Callback<Boolean>) { val uid=userId()?:return fail(callback,"Sign in required"); val filter="$actorColumn=eq.${enc(uid)}&$targetColumn=eq.${enc(target)}"; if(enabled)upsert("/rest/v1/$table?on_conflict=$actorColumn,$targetColumn",mapOf(actorColumn to uid,targetColumn to target),callback)else delete("/rest/v1/$table?$filter",callback) }
    private fun upsert(path: String, fields: Map<String,Any?>, callback: Callback<Boolean>)=postRaw(path,fields,"resolution=ignore-duplicates,return=minimal"){r->callback.onComplete(if(r.error==null)SocialResult(value=true)else SocialResult(error=r.error))}
    private fun postReturning(path: String, fields: Map<String,Any?>, callback: Callback<String?>, parser:(String)->SocialResult<String?>)=postRaw(path,fields,"return=representation"){r->if(r.error!=null)callback.onComplete(SocialResult(error=r.error))else callback.onComplete(parser(r.value.orEmpty()))}
    private fun rpc(function:String,fields:Map<String,Any?>,callback:(SocialResult<String>)->Unit)=postRaw("/rest/v1/rpc/$function",fields,"return=representation",callback)
    private fun postRaw(path:String,fields:Map<String,Any?>,prefer:String?,callback:(SocialResult<String>)->Unit)=executeText(requestBuilder(path,prefer).post(gson.toJson(fields).toRequestBody(jsonType)).build(),callback)
    private fun patch(path:String,fields:Map<String,Any?>,callback:Callback<Boolean>)=patchRaw(path,fields){r->callback.onComplete(if(r.error==null)SocialResult(value=true)else SocialResult(error=r.error))}
    private fun patchRaw(path:String,fields:Map<String,Any?>,callback:(SocialResult<String>)->Unit)=executeText(requestBuilder(path,"return=minimal").patch(gson.toJson(fields).toRequestBody(jsonType)).build(),callback)
    private fun delete(path:String,callback:Callback<Boolean>)=executeText(requestBuilder(path,"return=minimal").delete().build()){r->callback.onComplete(if(r.error==null)SocialResult(value=true)else SocialResult(error=r.error))}
    private fun get(path:String,callback:Callback<String>)=executeText(requestBuilder(path,null).get().build()){r->callback.onComplete(if(r.error==null)SocialResult(value=r.value)else SocialResult(error=r.error))}
    private fun requestBuilder(path:String,prefer:String?):Request.Builder { val b=Request.Builder().url(base()+path).header("apikey",BuildConfig.SUPABASE_ANON_KEY).header("Accept","application/json"); if(!prefer.isNullOrBlank())b.header("Prefer",prefer); token()?.takeIf{it.isNotBlank()}?.let{b.header("Authorization","Bearer $it")}; return b }
    private fun executeText(request:Request,callback:(SocialResult<String>)->Unit){http.newCall(request).enqueue(object:okhttp3.Callback{override fun onFailure(call:okhttp3.Call,e:IOException)=callback(SocialResult(error=e));override fun onResponse(call:okhttp3.Call,response:Response){response.use{val text=it.body?.string().orEmpty();if(!it.isSuccessful)callback(SocialResult(error=IOException("Social API ${it.code}: ${text.take(500)}")))else callback(SocialResult(value=text))}}})}
    private fun parseId(raw:String):String?=try{gson.fromJson(raw,Array<JsonObject>::class.java).firstOrNull()?.get("id")?.takeUnless{it.isJsonNull}?.asString?.takeIf{it.isNotBlank()}}catch(_:Throwable){null}
    private fun parseScalarId(raw:String):String?=try{val el=gson.fromJson(raw,com.google.gson.JsonElement::class.java);when{el.isJsonPrimitive->el.asString.takeIf{it.isNotBlank()};el.isJsonArray->el.asJsonArray.firstOrNull()?.takeUnless{it.isJsonNull}?.asString;else->null}}catch(_:Throwable){null}
    private fun nowIso():String=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US).format(Date())
    private fun enc(value:String):String=URLEncoder.encode(value,"UTF-8")
    private fun <T> fail(callback:Callback<T>,message:String)=callback.onComplete(SocialResult<T>(error=IllegalStateException(message)))
}
