package com.fadcam.tv

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fadcam.tv.social.SocialFeatureRepository

/** Native Testagram parity hub for TV 49 East. */
class SocialParityActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var features: SocialFeatureRepository
    private val purple = Color.rgb(112, 82, 226)
    private val darkPurple = Color.rgb(65, 43, 120)
    private val bg = Color.rgb(248, 245, 251)
    private val text = Color.rgb(38, 29, 48)
    private val muted = Color.rgb(117, 104, 126)

    override fun onCreate(state: Bundle?) { super.onCreate(state); features = SocialFeatureRepository(this); renderHome() }

    private fun renderHome() {
        root = column(bg)
        val header = row(); header.gravity = Gravity.CENTER_VERTICAL; header.setPadding(dp(14), dp(10), dp(14), dp(8))
        header.addView(label("49", 20f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setBackgroundColor(purple) }, lp(48, 44))
        header.addView(label("Social", 22f, text, true).apply { setPadding(dp(12), 0, 0, 0) }, lp(0, 48, 1f))
        header.addView(button("TV", Color.WHITE, purple) { open(MainActivity::class.java, true) }, lp(62, 42)); root.addView(header)
        root.addView(label("Testagram-style social, built into TV 49 East", 14f, muted, false).apply { setPadding(dp(16), 0, dp(16), dp(8)) })
        val scroll = ScrollView(this); content = column(bg); content.setPadding(dp(14), 0, dp(14), dp(92)); scroll.addView(content); root.addView(scroll, lp(0, 0, 1f)); setContentView(root); showOverview()
    }

    private fun showOverview() {
        content.removeAllViews(); card("YOUR SOCIAL HOME", "Everything Testagram-style is grouped here: feed, discovery, messages, communities, creator tools, saved content and account controls.")
        content.addView(sectionTitle("Main"))
        actionCard("For You", "Personal feed, posts, photos, video, likes, reposts, replies and bookmarks.") { open(ModernSocialActivity::class.java) }
        actionCard("Explore & Search", "Trending topics, hashtags, people and content discovery.") { open(ModernSocialActivity::class.java) }
        actionCard("Notifications", "Mentions, likes, follows, replies and account activity.") { showNotifications() }
        actionCard("Messages", "Direct conversations with delivery/read state, reactions and shared posts.") { showMessages() }
        actionCard("Communities", "Community discovery, membership, discussion and moderation surfaces.") { showComing("Communities", "Community read/write endpoints will be connected against the verified shared schema in the next backend pass.") }
        content.addView(sectionTitle("Your library"))
        actionCard("Bookmarks", "Saved posts available across devices.") { showComing("Bookmarks", "Bookmark writes are already wired; the native read surface is next.") }
        actionCard("Lists", "Curated people lists with private/public membership and timelines.") { showLists() }
        actionCard("History", "Recently viewed social content and activity.") { showComing("History", "Post-view recording is wired; the native history read surface is next.") }
        content.addView(sectionTitle("Creator & community"))
        actionCard("Creator Studio", "Profile, analytics, publishing and monetization tools.") { open(CreatorStudioActivity::class.java) }
        actionCard("Profile", "Posts, media, likes, reposts, bio, avatar and cover identity.") { open(ProfessionalProfileActivity::class.java) }
        actionCard("Reels / Clips", "Vertical short-form video and TV-linked clips.") { open(ReelsActivity::class.java) }
        actionCard("Live / Spaces", "Live TV, audio/social live experiences and community events.") { open(MainActivity::class.java) }
        content.addView(sectionTitle("Safety & account"))
        actionCard("Privacy & Safety", "Block, mute, follow-request controls and responsible reporting.") { showSafety() }
        actionCard("Settings & Privacy", "Account preferences, device/media permissions and policy entry points.") { open(SocialSettingsActivity::class.java) }
        actionCard("Help / Policies", "Terms, privacy, community guidelines and support.") { open(SocialSettingsActivity::class.java) }
    }

    private fun showMessages() {
        content.removeAllViews(); content.addView(back()); card("MESSAGES", "Direct messages use the same authenticated Supabase session as the social feed.")
        if (!hasSession()) { actionCard("Sign in required", "Open Social to sign in, then return here to load conversations.") { open(ModernSocialActivity::class.java) }; return }
        val loading = label("Loading conversations…", 14f, muted, false); content.addView(loading, lp(-1, 54))
        features.loadConversations(50, object : SocialFeatureRepository.Callback<String> {
            override fun onComplete(result: com.fadcam.tv.social.SocialResult<String>) { runOnUiThread { content.removeView(loading); if (!result.isSuccess) { actionCard("Inbox unavailable", result.error?.message ?: "Try again later.") { showMessages() }; return@runOnUiThread }; textBlock(result.value.orEmpty().ifBlank { "No conversations yet." }.take(9000)); actionCard("New conversation", "Create a direct conversation from a profile or people picker.") { showComing("New conversation", "The atomic create_conversation RPC is already wired in the repository.") } } }
        })
    }

    private fun showNotifications() { content.removeAllViews(); content.addView(back()); card("NOTIFICATIONS", "Authenticated social activity remains available from the native social feed."); if (!hasSession()) { actionCard("Sign in required", "Open Social and sign in first.") { open(ModernSocialActivity::class.java) }; return }; textBlock("Notification read rendering will be connected to the verified notifications schema rather than fabricating local data."); actionCard("Open Social", "Return to the authenticated feed.") { open(ModernSocialActivity::class.java) } }
    private fun showLists() { content.removeAllViews(); content.addView(back()); card("LISTS", "Private or public curated timelines are part of the parity model."); actionCard("Create a list", "Create a named list with optional privacy and add members.") { showComing("Create list", "createList and addListMember are implemented in the native repository.") }; actionCard("List timeline", "Read a list-specific timeline with pagination.") { showComing("List timeline", "get_list_timeline is wired and ready for the native picker surface.") } }
    private fun showSafety() { content.removeAllViews(); content.addView(back()); card("PRIVACY & SAFETY", "Account safety controls are actor-scoped and authenticated."); actionCard("Mute a user", "Hide a user's content without blocking them.") { showComing("Mute", "toggleMute is wired in SocialFeatureRepository.") }; actionCard("Block a user", "Prevent unwanted interactions.") { showComing("Block", "toggleBlock is wired in SocialFeatureRepository.") }; actionCard("Follow requests", "Request, cancel or respond to follows for private accounts.") { showComing("Follow requests", "requestFollow, cancelFollowRequest and respondToFollowRequest are wired.") }; actionCard("Post views", "Record content views for ranking and history.") { showComing("Post views", "record_post_view is wired in SocialFeatureRepository.") } }
    private fun back(): Button = button("‹  Social home", Color.WHITE, purple) { showOverview() }
    private fun card(title: String, body: String) { val c = column(Color.WHITE); c.setPadding(dp(18), dp(16), dp(18), dp(16)); c.addView(label(title, 11f, purple, true)); c.addView(label(body, 15f, text, false).apply { setPadding(0, dp(5), 0, 0) }); val p = lp(-1, -2); p.topMargin = dp(7); p.bottomMargin = dp(7); content.addView(c, p) }
    private fun actionCard(title: String, body: String, action: () -> Unit) { val b = button("$title\n$body", text, Color.WHITE, action); b.gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL; b.setTextSize(14f); b.setTypeface(Typeface.DEFAULT, Typeface.NORMAL); val p = lp(-1, 76); p.topMargin = dp(5); p.bottomMargin = dp(5); content.addView(b, p) }
    private fun sectionTitle(s: String): TextView = label(s, 18f, darkPurple, true).apply { setPadding(dp(5), dp(16), 0, dp(5)) }
    private fun textBlock(s: String) { content.addView(label(s, 12f, muted, false).apply { setPadding(dp(10), dp(8), dp(10), dp(8)) }, lp(-1, -2)) }
    private fun showComing(title: String, detail: String) = Toast.makeText(this, "$title: $detail", Toast.LENGTH_LONG).show()
    private fun hasSession(): Boolean = getSharedPreferences("tv49_social_session", MODE_PRIVATE).getString("access_token", null).orEmpty().isNotBlank()
    private fun open(clazz: Class<*>, finish: Boolean = false) { startActivity(Intent(this, clazz)); overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right); if (finish) finish() }
    private fun <T : Any> open(clazz: Class<T>) = open(clazz, false)
    private fun label(s: String, size: Float, fg: Int, bold: Boolean) = TextView(this).apply { text = s; textSize = size; setTextColor(fg); if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD) }
    private fun button(s: String, fg: Int, bg: Int, action: () -> Unit) = Button(this).apply { text = s; setTextColor(fg); textSize = 13f; isAllCaps = false; minHeight = 0; minWidth = 0; setBackgroundColor(bg); setOnClickListener { action() } }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun column(color: Int) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(color) }
    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(if (w == 0) 0 else if (w < 0) LinearLayout.LayoutParams.MATCH_PARENT else dp(w), if (h == 0) 0 else if (h < 0) LinearLayout.LayoutParams.WRAP_CONTENT else dp(h), weight)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
