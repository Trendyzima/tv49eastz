package com.fadcam.tv;

import android.content.Intent;
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

/** Native creator profile backed by Supabase, with authenticated editing and post management. */
public class ProfessionalProfileActivity extends AppCompatActivity {
    private SupabaseSocialRepository repo;
    private LinearLayout root;
    @Override protected void onCreate(@Nullable Bundle state){super.onCreate(state);repo=new SupabaseSocialRepository(this);renderLoading();if(!repo.isSignedIn()){finish();return;}repo.loadProfile(r->runOnUiThread(()->{if(r.getError()!=null)renderError(r.getError().getMessage());else render(r.getValue());}));}
    private void renderLoading(){root=base();root.addView(text("Loading profile…",16,Color.DKGRAY,false));setContentView(root);}
    private void renderError(String error){root.removeAllViews();root.addView(text("Profile unavailable",22,Color.rgb(38,29,48),true));root.addView(text(error==null?"Please try again.":error,14,Color.DKGRAY,false));Button retry=new Button(this);retry.setText("Retry");retry.setOnClickListener(v->{repo.loadProfile(r->runOnUiThread(()->{if(r.getError()!=null)renderError(r.getError().getMessage());else render(r.getValue());}));});root.addView(retry,new LinearLayout.LayoutParams(-1,dp(50)));}
    private void render(SocialUser p){root.removeAllViews();
        LinearLayout cover=new LinearLayout(this);cover.setBackgroundColor(Color.rgb(20,32,40));cover.setGravity(Gravity.BOTTOM);root.addView(cover,new LinearLayout.LayoutParams(-1,dp(170)));
        TextView title=text("TV 49 EAST CREATOR",22,Color.WHITE,true);title.setPadding(dp(18),0,0,dp(18));cover.addView(title);
        TextView name=text(p==null?"Creator":clean(p.getDisplayName(),p.getUsername()),24,Color.rgb(15,20,25),true);root.addView(name);
        TextView handle=text("@"+clean(p==null?null:p.getUsername(),"creator"),14,Color.rgb(0,140,95),true);root.addView(handle);
        TextView bio=text(clean(p==null?null:p.getBio(),"Tell your audience what you create."),15,Color.DKGRAY,false);bio.setPadding(0,dp(8),0,dp(8));root.addView(bio);
        LinearLayout stats=new LinearLayout(this);stats.setGravity(Gravity.CENTER);String[] s={"Posts","Followers","Following","Likes"};for(String x:s){TextView t=text("—\n"+x,14,Color.rgb(15,20,25),true);t.setGravity(Gravity.CENTER);stats.addView(t,new LinearLayout.LayoutParams(0,dp(58),1));}root.addView(stats);
        Button edit=new Button(this);edit.setText("Edit profile");edit.setOnClickListener(v->{startActivityForResult(new Intent(this,ProfileEditorActivity.class),1001);});root.addView(edit,new LinearLayout.LayoutParams(-1,dp(50)));
        Button posts=new Button(this);posts.setText("Manage my posts");posts.setOnClickListener(v->startActivity(new Intent(this,PostManagerActivity.class)));root.addView(posts,new LinearLayout.LayoutParams(-1,dp(50)));
        Button create=new Button(this);create.setText("Create a post");create.setOnClickListener(v->startActivity(new Intent(this,ModernSocialActivity.class)));root.addView(create,new LinearLayout.LayoutParams(-1,dp(50)));
        Button studio=new Button(this);studio.setText("Creator Studio • Analytics & Earnings");studio.setOnClickListener(v->startActivity(new Intent(this,CreatorStudioActivity.class)));root.addView(studio,new LinearLayout.LayoutParams(-1,dp(50)));
        Button reels=new Button(this);reels.setText("Watch Reels");reels.setOnClickListener(v->startActivity(new Intent(this,ReelsActivity.class)));root.addView(reels,new LinearLayout.LayoutParams(-1,dp(50)));
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,@Nullable Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode==1001&&resultCode==RESULT_OK)repo.loadProfile(r->runOnUiThread(()->{if(r.getError()==null)render(r.getValue());}));}
    private LinearLayout base(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(18),dp(18),dp(18),dp(18));l.setBackgroundColor(Color.WHITE);return l;}
    private TextView text(String s,float size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(null,1);return t;}
    private String clean(String v,String f){return v==null||v.trim().isEmpty()?f:v.trim();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
