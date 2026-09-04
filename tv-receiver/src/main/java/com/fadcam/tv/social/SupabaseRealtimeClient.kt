package com.fadcam.tv.social

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tv49.com.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Native Supabase Realtime protocol client for notifications and DMs. */
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
    private var userId = ""
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
        this.userId = userId
        topic = "realtime:tv49-social-${userId.replace("-", "").take(24)}"
        joinRef = nextRef()
        running.set(true)
        val projectHost = BuildConfig.SUPABASE_URL.trimEnd('/').removePrefix("https://").removePrefix("http://")
        val url = "wss://$projectHost/realtime/v1/websocket?apikey=${enc(BuildConfig.SUPABASE_ANON_KEY)}&vsn=1.0.0"
        socket = http.newWebSocket(Request.Builder().url(url).build(), SocketListener(accessToken, conversationId))
    }

    fun updateAccessToken(accessToken: String) {
        if (!running.get()) return
        send("access_token", topic, mapOf("access_token" to accessToken), nextRef())
    }

    fun stop() {
        running.set(false)
        heartbeat?.interrupt()
        heartbeat = null
        socket?.close(1000, "client_stop")
        socket = null
        listener = null
        userId = ""
        topic = ""
    }

    private inner class SocketListener(private val token: String, private val conversationId: String?) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val changes = mutableListOf<Map<String, String>>()
            changes += mapOf("event" to "INSERT", "schema" to "public", "table" to "notifications", "filter" to "recipient_id=eq.$userId")
            if (!conversationId.isNullOrBlank()) {
                changes += mapOf("event" to "INSERT", "schema" to "public", "table" to "messages", "filter" to "conversation_id=eq.$conversationId")
                changes += mapOf("event" to "UPDATE", "schema" to "public", "table" to "messages", "filter" to "conversation_id=eq.$conversationId")
            }
            send("phx_join", topic, mapOf(
                "config" to mapOf(
                    "broadcast" to mapOf("ack" to false, "self" to false),
                    "presence" to mapOf("enabled" to false),
                    "postgres_changes" to changes,
                    "private" to false
                ),
                "access_token" to token
            ), joinRef)
            startHeartbeat()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val o = gson.fromJson(text, JsonObject::class.java)
                when (o.get("event")?.asString.orEmpty()) {
                    "phx_reply" -> {
                        val p = o.getAsJsonObject("payload")
                        val status = p?.get("status")?.asString.orEmpty()
                        if (status == "ok") listener?.onConnected() else listener?.onError("Realtime join failed: ${p?.get("response")?.toString() ?: status}")
                    }
                    "postgres_changes" -> {
                        val payload = o.getAsJsonObject("payload") ?: return
                        val data = payload.getAsJsonObject("data") ?: return
                        val table = data.get("table")?.asString.orEmpty()
                        val change = data.get("type")?.asString ?: data.get("eventType")?.asString ?: "*"
                        listener?.onChange(table, change, data)
                    }
                    "phx_error" -> listener?.onError("Realtime channel error")
                    "phx_close" -> listener?.onClosed()
                }
            } catch (t: Throwable) {
                listener?.onError("Realtime message error: ${t.message ?: "invalid payload"}")
            }
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

    private fun startHeartbeat() {
        heartbeat?.interrupt()
        heartbeat = Thread {
            while (running.get()) {
                try {
                    Thread.sleep(20_000L)
                    if (running.get()) send("heartbeat", "phoenix", emptyMap<String, Any>(), nextRef())
                } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun send(event: String, channel: String, payload: Any, ref: String) {
        socket?.send(gson.toJson(mapOf("topic" to channel, "event" to event, "payload" to payload, "ref" to ref, "join_ref" to joinRef)))
    }

    private fun nextRef(): String = seq.getAndIncrement().toString()
    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
