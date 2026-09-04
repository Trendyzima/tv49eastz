package com.fadcam.tv.social

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tv49.com.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Minimal native Supabase Realtime protocol client; no WebView or JS dependency. */
class SupabaseRealtimeClient {
    interface Listener {
        fun onConnected() {}
        fun onChange(table: String, event: String, payload: JsonObject) {}
        fun onError(message: String) {}
        fun onClosed() {}
    }

    private val gson = Gson()
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var socket: WebSocket? = null
    private var listener: Listener? = null
    private var topic = ""
    private var joinRef = ""
    private var heartbeat: Thread? = null
    private val seq = AtomicInteger(1)
    private val running = AtomicBoolean(false)

    fun start(userId: String, accessToken: String, conversationId: String? = null, listener: Listener) {
        stop()
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            listener.onError("Supabase is not configured")
            return
        }
        this.listener = listener
        topic = "realtime:tv49-social-${userId.replace("-", "").take(24)}"
        joinRef = nextRef()
        running.set(true)
        val base = BuildConfig.SUPABASE_URL.trimEnd('/')
        val projectUrl = base.removePrefix("https://").removePrefix("http://")
        val url = "wss://$projectUrl/realtime/v1/websocket?apikey=${enc(BuildConfig.SUPABASE_ANON_KEY)}&vsn=1.0.0"
        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, SocketListener(accessToken, conversationId))
    }

    fun stop() {
        running.set(false)
        heartbeat?.interrupt()
        heartbeat = null
        socket?.close(1000, "client_stop")
        socket = null
        listener = null
    }

    private inner class SocketListener(private val token: String, private val conversationId: String?) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val changes = mutableListOf<Map<String, String>>()
            changes += mapOf("event" to "INSERT", "schema" to "public", "table" to "notifications", "filter" to "recipient_id=eq.${userIdFromTopic()}")
            if (!conversationId.isNullOrBlank()) {
                changes += mapOf("event" to "INSERT", "schema" to "public", "table" to "messages", "filter" to "conversation_id=eq.$conversationId")
                changes += mapOf("event" to "UPDATE", "schema" to "public", "table" to "messages", "filter" to "conversation_id=eq.$conversationId")
            }
            val payload = mapOf(
                "config" to mapOf(
                    "broadcast" to mapOf("ack" to false, "self" to false),
                    "presence" to mapOf("enabled" to false),
                    "postgres_changes" to changes,
                    "private" to false
                ),
                "access_token" to token
            )
            send("phx_join", topic, payload, joinRef)
            startHeartbeat(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val o = gson.fromJson(text, JsonObject::class.java)
                val event = o.get("event")?.asString.orEmpty()
                when (event) {
                    "phx_reply" -> {
                        val status = o.getAsJsonObject("payload")?.get("status")?.asString.orEmpty()
                        if (status == "ok") listener?.onConnected() else listener?.onError("Realtime join failed: $status")
                    }
                    "postgres_changes" -> {
                        val p = o.getAsJsonObject("payload") ?: return
                        val data = p.getAsJsonObject("data") ?: p
                        val table = data.get("table")?.asString.orEmpty()
                        val change = data.get("type")?.asString ?: data.get("eventType")?.asString ?: "*"
                        listener?.onChange(table, change, data)
                    }
                    "phx_error", "phx_close" -> listener?.onError("Realtime channel closed")
                }
            } catch (t: Throwable) { listener?.onError("Realtime message error: ${t.message ?: "invalid payload"}") }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (running.get()) listener?.onError("Realtime connection failed: ${t.message ?: "unknown error"}")
            listener?.onClosed()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            heartbeat?.interrupt()
            heartbeat = null
            if (running.get()) listener?.onClosed()
        }
    }

    private fun startHeartbeat(ws: WebSocket) {
        heartbeat?.interrupt()
        heartbeat = Thread {
            while (running.get()) {
                try {
                    Thread.sleep(20_000L)
                    if (!running.get()) break
                    send("heartbeat", "phoenix", emptyMap(), nextRef())
                } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun send(event: String, channel: String, payload: Any, ref: String) {
        val message = mapOf("topic" to channel, "event" to event, "payload" to payload, "ref" to ref, "join_ref" to joinRef)
        socket?.send(gson.toJson(message))
    }

    private fun nextRef(): String = seq.getAndIncrement().toString()
    private fun userIdFromTopic(): String = topic.removePrefix("realtime:tv49-social-")
    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
