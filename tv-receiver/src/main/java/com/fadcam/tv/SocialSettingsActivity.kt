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
import androidx.appcompat.app.AppCompatActivity
import com.fadcam.tv.social.SocialFeatureRepository

/** Native account/settings surface shared by TV 49 East social clients. */
class SocialSettingsActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var repo: SocialFeatureRepository
    private val bg = Color.rgb(248, 245, 251)
    private val text = Color.rgb(38, 29, 48)
    private val muted = Color.rgb(117, 104, 126)
    private val purple = Color.rgb(112, 82, 226)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        repo = SocialFeatureRepository(this)
        render()
    }

    private fun render() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)) }
        val title = TextView(this).apply { text = "Settings & Privacy"; textSize = 22f; setTextColor(text); setTypeface(null, Typeface.BOLD) }
        header.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(button("Social") { startActivity(Intent(this, SocialParityActivity::class.java)) }, LinearLayout.LayoutParams(dp(90), dp(44)))
        root.addView(header)
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(14), dp(30)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        addCard("ACCOUNT", "Your native social account uses the authenticated Supabase session already shared by TV 49 East.")
        addAction("Session", if (hasSession()) "Signed in on this device" else "Not signed in") { openSocial() }
        addAction("Privacy", "Manage account visibility, interaction and audience controls") { privacyInfo() }
        addAction("Safety", "Mute, block and follow-request controls") { startActivity(Intent(this, SocialParityActivity::class.java)) }
        addAction("Media permissions", "Camera, microphone and local media access are requested only when needed") { privacyInfo() }
        addAction("Notifications", "Notification preferences and device registration") { privacyInfo() }
        addAction("Data & history", "Saved content, viewed posts and account activity") { privacyInfo() }
        addCard("POLICIES", "Terms, privacy policy, community guidelines and support belong to the same account ecosystem.")
        addAction("Terms & Community Guidelines", "Read the rules for using the platform") { policy("Terms & Community Guidelines") }
        addAction("Privacy Policy", "How account, media and device data are handled") { policy("Privacy Policy") }
        addAction("Help & Support", "Troubleshooting and account assistance") { policy("Help & Support") }
    }

    private fun addCard(title: String, body: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); setPadding(dp(18), dp(15), dp(18), dp(15)) }
        box.addView(TextView(this).apply { text = title; textSize = 11f; setTextColor(purple); setTypeface(null, Typeface.BOLD) })
        box.addView(TextView(this).apply { text = body; textSize = 14f; setTextColor(text); setPadding(0, dp(5), 0, 0) })
        val p = LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(6), 0, dp(6)); content.addView(box, p)
    }

    private fun addAction(title: String, body: String, action: () -> Unit) {
        val b = button("$title\n$body", action).apply { gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL; textSize = 14f; isAllCaps = false }
        val p = LinearLayout.LayoutParams(-1, dp(70)); p.setMargins(0, dp(4), 0, dp(4)); content.addView(b, p)
    }

    private fun privacyInfo() = android.widget.Toast.makeText(this, "This control is actor-scoped and will use the authenticated social backend.", android.widget.Toast.LENGTH_LONG).show()
    private fun policy(title: String) = android.widget.Toast.makeText(this, "$title — native policy route ready for the published policy content.", android.widget.Toast.LENGTH_LONG).show()
    private fun openSocial() = startActivity(Intent(this, ModernSocialActivity::class.java))
    private fun hasSession() = getSharedPreferences("tv49_social_session", MODE_PRIVATE).getString("access_token", null).orEmpty().isNotBlank()
    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setTextColor(purple); setOnClickListener { action() }; minHeight = 0; minWidth = 0 }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
