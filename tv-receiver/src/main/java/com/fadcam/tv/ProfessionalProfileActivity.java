package com.fadcam.tv;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.fadcam.tv.social.SocialUser;
import com.fadcam.tv.social.SupabaseSocialRepository;

/** Professional creator profile shell: identity, verification, audience stats, links and creator tools. */
public class ProfessionalProfileActivity extends AppCompatActivity {
    private SupabaseSocialRepository repo;
    private LinearLayout root;
    @Override protected void onCreate(@Nullable Bundle state){super.onCreate(state);repo=new SupabaseSocialRepository(this);renderLoading();repo.loadProfile(r->runOnUiThread(()->render(r.getValue())));}
    private void renderLoading(){root=base();root.addView(text("Loading profile…",16,Color.DKGRAY,false));setContentView(root);}
    private void render(SocialUser p){root.removeAllViews();
        LinearLayout cover=new LinearLayout(this);cover.setBackgroundColor(Color.rgb(20,32,40));cover.setGravity(Gravity.BOTTOM);root.addView(cover,new LinearLayout.LayoutParams(-1,dp(170)));
        TextView title=text("TV 49 EAST CREATOR",22,Color.WHITE,true);title.setPadding(dp(18),0,0,dp(18));cover.addView(title);
        TextView name=text(p==null?"Creator":clean(p.getDisplayName(),p.getUsername()),24,Color.rgb(15,20,25),true);root.addView(name);
        TextView handle=text("@"+clean(p==null?null:p.getUsername(),"creator")+"  ✓",14,Color.rgb(0,140,95),true);root.addView(handle);
        TextView bio=text(clean(p==null?null:p.getBio(),"Tell your audience what you create."),15,Color.DKGRAY,false);bio.setPadding(0,dp(8),0,dp(8));root.addView(bio);
        LinearLayout stats=new LinearLayout(this);stats.setGravity(Gravity.CENTER);String[] s={"0\nPosts","0\nFollowers","0\nFollowing","0\nLikes"};for(String x:s){TextView t=text(x,14,Color.rgb(15,20,25),true);t.setGravity(Gravity.CENTER);stats.addView(t,new LinearLayout.LayoutParams(0,dp(58),1));}root.addView(stats);
        Button edit=new Button(this);edit.setText("Edit professional profile");edit.setOnClickListener(v->finish());root.addView(edit,new LinearLayout.LayoutParams(-1,dp(50)));
        Button studio=new Button(this);studio.setText("Creator Studio • Analytics & Earnings");studio.setOnClickListener(v->startActivity(new android.content.Intent(this,CreatorStudioActivity.class)));root.addView(studio,new LinearLayout.LayoutParams(-1,dp(50)));
        Button reels=new Button(this);reels.setText("Watch Reels");reels.setOnClickListener(v->startActivity(new android.content.Intent(this,ReelsActivity.class)));root.addView(reels,new LinearLayout.LayoutParams(-1,dp(50)));
    }
    private LinearLayout base(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(18),dp(18),dp(18),dp(18));l.setBackgroundColor(Color.WHITE);return l;}
    private TextView text(String s,float size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(null,1);return t;}
    private String clean(String v,String f){return v==null||v.trim().isEmpty()?f:v.trim();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
