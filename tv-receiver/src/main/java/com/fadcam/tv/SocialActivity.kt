package com.fadcam.tv

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fadcam.tv.social.SocialPost
import com.fadcam.tv.social.SupabaseSocialRepository
import com.tv49.com.BuildConfig

/**
 * First native social vertical slice. No WebView and no dependency on the reference XClone app.
 * The screen is deliberately D-pad friendly and uses ordinary Android Views so it works on
 * Android TV and handset builds without introducing a second UI runtime.
 */
class SocialActivity : AppCompatActivity() {
    private lateinit var repo: SupabaseSocialRepository
    private lateinit var feed: LinearLayout
    private lateinit var state: TextView

    private val bg = Color.rgb(10, 10, 14)
    private val card = Color.rgb(27, 24, 35)
    private val card2 = Color.rgb(39, 34, 51)
    private val accent = Color.rgb(207, 186, 253)
    private val text = Color.WHITE
    private val muted = Color.rgb(190, 184, 205)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = SupabaseSocialRepository(this)
        buildUi()
        loadFeed()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.DEFAULT.copy(if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    private fun button(value: String, listener: View.OnClickListener): Button =
        Button(this).apply {
            text = value
            textSize = 13f
            setTextColor(text)
            isAllCaps = false
            setOnClickListener(listener)
            isFocusable = true
            isClickable = true
            minHeight = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
            setBackgroundColor(card2)
        }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(18), dp(16), dp(18), dp(12))
        }

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(label("TV 49", 22f, accent, true), LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(button("LIVE TV") { finish() }, LinearLayout.LayoutParams(dp(105), dp(48)))
        root.addView(header)

        val title = label("Social", 30f, text, true)
        root.addView(title, LinearLayout.LayoutParams(-1, dp(48)))
        state = label(if (repo.isConfigured()) "Native feed • Supabase connected" else "Native feed • configure Supabase to enable cloud data", 12f, muted)
        root.addView(state, LinearLayout.LayoutParams(-1, dp(34)))

        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(button("CREATE POST") { showComposer() }, LinearLayout.LayoutParams(0, dp(50), 1f))
        actions.addView(button("SIGN IN") { showAuth() }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(8) })
        actions.addView(button("REFRESH") { loadFeed() }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(8) })
        root.addView(actions, LinearLayout.LayoutParams(-1, dp(58)))

        val scroll = ScrollView(this).apply { isFillViewport = true }
        feed = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(30))
        }
        scroll.addView(feed, ScrollView.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun loadFeed() {
        state.text = if (repo.isConfigured()) "Loading social feed…" else "Supabase is not configured — showing the native shell"
        repo.loadFeed { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                feed.removeAllViews()
                val posts = result.value.orEmpty()
                if (result.error != null) {
                    state.text = "Feed unavailable: ${result.error.message?.take(110)}"
                    addEmpty("Could not load cloud posts", "Check Supabase URL/key and RLS policies.")
                    return@runOnUiThread
                }
                state.text = if (posts.isEmpty()) "No posts yet • be the first creator" else "${posts.size} recent posts"
                posts.forEach { addPost(it) }
            }
        }
    }

    private fun addPost(post: SocialPost) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(card)
            isFocusable = true
            isClickable = true
        }
        val author = post.author.displayName.ifBlank { post.author.username.ifBlank { "TV 49 creator" } }
        c.addView(label("$author  •  @${post.author.username}", 14f, accent, true), LinearLayout.LayoutParams(-1, dp(28)))
        c.addView(label(post.body, 16f, text), LinearLayout.LayoutParams(-1, -2))
        val stats = label("♥ ${post.likeCount}    ↩ ${post.replyCount}    ⟳ ${post.repostCount}", 12f, muted)
        stats.setPadding(0, dp(12), 0, 0)
        c.addView(stats)
        val lp = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        feed.addView(c, lp)
    }

    private fun addEmpty(title: String, detail: String) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(40), dp(20), dp(40))
            setBackgroundColor(card)
        }
        val a = label(title, 18f, text, true).apply { gravity = Gravity.CENTER }
        val b = label(detail, 13f, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        c.addView(a, LinearLayout.LayoutParams(-1, -2))
        c.addView(b, LinearLayout.LayoutParams(-1, -2))
        feed.addView(c, LinearLayout.LayoutParams(-1, -2))
    }

    private fun showComposer() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        val input = EditText(this).apply {
            hint = "What is happening in TV 49 East?"
            setTextColor(text)
            setHintTextColor(muted)
            minLines = 4
            gravity = Gravity.TOP
            isFocusable = true
        }
        box.addView(input, LinearLayout.LayoutParams(-1, dp(140)))
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Create post")
            .setView(box)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("POST") { _, _ ->
                val body = input.text.toString().trim()
                if (body.isEmpty()) return@setPositiveButton
                repo.createPost(body) { result ->
                    runOnUiThread {
                        if (result.error != null) Toast.makeText(this, result.error.message ?: "Post failed", Toast.LENGTH_LONG).show()
                        else loadFeed()
                    }
                }
            }.show()
    }

    private fun showAuth() {
        if (!repo.isConfigured()) {
            Toast.makeText(this, "Add -PsupabaseUrl and -PsupabaseAnonKey when building the APK", Toast.LENGTH_LONG).show()
            return
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), 0, dp(22), 0) }
        val email = EditText(this).apply { hint = "Email"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        val password = EditText(this).apply { hint = "Password"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        box.addView(email, LinearLayout.LayoutParams(-1, dp(56)))
        box.addView(password, LinearLayout.LayoutParams(-1, dp(56)))
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("TV 49 account")
            .setView(box)
            .setNegativeButton("CANCEL", null)
            .setNeutralButton("CREATE ACCOUNT") { _, _ -> repo.signUp(email.text.toString(), password.text.toString(), authCallback()) }
            .setPositiveButton("SIGN IN") { _, _ -> repo.signIn(email.text.toString(), password.text.toString(), authCallback()) }
            .show()
    }

    private fun authCallback(): SupabaseSocialRepository.ResultCallback<com.fadcam.tv.social.SocialSession> =
        object : SupabaseSocialRepository.ResultCallback<com.fadcam.tv.social.SocialSession> {
            override fun onComplete(result: com.fadcam.tv.social.SocialResult<com.fadcam.tv.social.SocialSession>) {
                runOnUiThread {
                    if (result.error != null) Toast.makeText(this@SocialActivity, result.error.message ?: "Authentication failed", Toast.LENGTH_LONG).show()
                    else { Toast.makeText(this@SocialActivity, "Signed in", Toast.LENGTH_SHORT).show(); loadFeed() }
                }
            }
        }
}
