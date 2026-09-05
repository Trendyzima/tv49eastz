package com.fadcam.tv;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.fadcam.tv.social.SocialPost;
import com.fadcam.tv.social.SocialUser;
import com.fadcam.tv.social.SupabaseSocialRepository;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Modern native TV 49 East social surface inspired by contemporary social-feed patterns. */
public final class ModernSocialActivity extends AppCompatActivity {
    private static final int BG = Color.rgb(39, 9, 70);
    private static final int BG_2 = Color.rgb(76, 18, 112);
    private static final int CARD = Color.rgb(54, 15, 83);
    private static final int TEXT = Color.WHITE;
    private static final int MUTED = Color.rgb(211, 194, 224);
    private static final int PINK = Color.rgb(255, 76, 155);
    private static final int ORANGE = Color.rgb(255, 148, 73);
    private static final int LILAC = Color.rgb(218, 183, 255);
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(3);
    private SupabaseSocialRepository repo;
    private LinearLayout feed;
    private TextView pageTitle;
    private float downX;
    private float downY;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        try {
            getWindow().setStatusBarColor(Color.rgb(27, 5, 48));
            getWindow().setNavigationBarColor(Color.rgb(17, 3, 30));
            repo = new SupabaseSocialRepository(this);
            build();
            loadFeed();
        } catch (Throwable t) {
            showFatal(t);
        }
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { downX=e.getRawX(); downY=e.getRawY(); }
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            float dx=e.getRawX()-downX, dy=e.getRawY()-downY;
            if (Math.abs(dx)>Math.abs(dy)*1.25f && dx>dp(80)) { openTv(); return true; }
        }
        return super.dispatchTouchEvent(e);
    }

    private void build() {
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(BG);
        LinearLayout main = column();
        main.setPadding(dp(12), dp(4), dp(12), dp(0));

        LinearLayout header = row(); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(4),dp(4),dp(2),dp(4));
        LinearLayout brand = row(); brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = text("49", 27, TEXT, true); logo.setGravity(Gravity.CENTER); logo.setBackground(gradient(PINK,ORANGE,22));
        brand.addView(logo,new LinearLayout.LayoutParams(dp(52),dp(42)));
        pageTitle=text("MeetUp",21,TEXT,true); pageTitle.setPadding(dp(10),0,0,0); brand.addView(pageTitle,new LinearLayout.LayoutParams(dp(115),dp(42)));
        header.addView(brand,new LinearLayout.LayoutParams(0,dp(48),1));
        header.addView(icon("♧",v->showNotifications()),new LinearLayout.LayoutParams(dp(46),dp(46)));
        Button post=roundButton("＋ Post",TEXT,Color.TRANSPARENT); post.setOnClickListener(v->compose()); header.addView(post,new LinearLayout.LayoutParams(dp(104),dp(44)));
        Button profile=roundButton("T",TEXT,PINK); profile.setOnClickListener(v->openProfile()); header.addView(profile,new LinearLayout.LayoutParams(dp(46),dp(44)));
        main.addView(header);

        EditText search=new EditText(this); search.setSingleLine(true); search.setTextSize(14); search.setTextColor(TEXT); search.setHintTextColor(MUTED); search.setHint("⌕  Search creators, posts, football…"); search.setPadding(dp(18),0,dp(18),0); search.setBackground(round(Color.argb(55,255,255,255),28));
        search.setOnEditorActionListener((v,a,e)->{search(v.getText().toString());return true;}); main.addView(search,new LinearLayout.LayoutParams(-1,dp(44)));

        main.addView(chips());
        ScrollView body=new ScrollView(this); body.setFillViewport(true); feed=column(); feed.setPadding(0,dp(8),0,dp(90)); body.addView(feed,new ScrollView.LayoutParams(-1,-2)); main.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        main.addView(bottomNav());
        root.addView(main,new FrameLayout.LayoutParams(-1,-1));
        Button fab=roundButton("＋",TEXT,PINK); fab.setTextSize(24); fab.setElevation(dp(12)); fab.setOnClickListener(v->compose()); FrameLayout.LayoutParams fp=new FrameLayout.LayoutParams(dp(58),dp(58),Gravity.RIGHT|Gravity.BOTTOM); fp.setMargins(0,0,dp(18),dp(78)); root.addView(fab,fp);
        setContentView(root);
    }

    private View chips(){
        HorizontalScrollView scroll=new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false); LinearLayout chips=row(); chips.setPadding(0,dp(5),0,dp(5));
        String[] names={"For You","Following","On TV","Clips","People","Football","Music","Live"};
        for(String name:names){TextView c=text(name,14,"For You".equals(name)?Color.WHITE:MUTED,"For You".equals(name)); c.setGravity(Gravity.CENTER); c.setPadding(dp(18),0,dp(18),0); c.setBackground("For You".equals(name)?gradient(PINK,ORANGE,24):round(Color.argb(35,255,255,255),24)); final String selected=name; c.setOnClickListener(v->{pageTitle.setText(selected); if("On TV".equals(selected)||"Live".equals(selected))openTv(); else if("Clips".equals(selected))openReels(); else loadFeed();}); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(42));p.setMargins(0,0,dp(7),0);chips.addView(c,p);}
        scroll.addView(chips,new HorizontalScrollView.LayoutParams(-2,dp(52))); return scroll;
    }

    private View bottomNav(){
        LinearLayout nav=row(); nav.setGravity(Gravity.CENTER); nav.setPadding(dp(4),dp(5),dp(4),dp(5)); nav.setBackground(Color.rgb(31,6,48));
        String[][] items={{"⌂","For You"},{"♧","Meet Up"},{"▣","Live TV"},{"◯","Chats"},{"♙","Profile"}};
        for(String[] item:items){LinearLayout cell=column();cell.setGravity(Gravity.CENTER);TextView i=text(item[0],22,TEXT,false);i.setGravity(Gravity.CENTER);TextView l=text(item[1],10,MUTED,"For You".equals(item[1]));l.setGravity(Gravity.CENTER);cell.addView(i,new LinearLayout.LayoutParams(-1,dp(29)));cell.addView(l,new LinearLayout.LayoutParams(-1,dp(18)));final String dest=item[1];cell.setOnClickListener(v->navigate(dest));nav.addView(cell,new LinearLayout.LayoutParams(0,dp(55),1));}
        return nav;
    }

    private void loadFeed(){
        if(feed==null||repo==null)return; feed.removeAllViews();
        addWelcome();
        repo.loadFeed(30,r->runOnUiThread(()->{if(r.getError()!=null){addEmpty("Feed is temporarily unavailable. You can still browse the social shell and sign in.");return;} List<SocialPost> posts=r.getValue(); if(posts==null||posts.isEmpty()){addEmpty("No posts yet. Start the conversation with the + Post button.");return;} for(SocialPost p:posts)addPost(p);}));
    }

    private void addWelcome(){
        LinearLayout hero=column(); hero.setPadding(dp(18),dp(16),dp(18),dp(16)); hero.setBackground(gradient(BG_2,Color.rgb(104,26,142),24));
        TextView small=text("TV 49 EAST SOCIAL",11,LILAC,true); hero.addView(small); TextView title=text("Your people. Your TV. Your moment.",24,TEXT,true); title.setPadding(0,dp(4),0,dp(2)); hero.addView(title); TextView sub=text("Connect with creators, football fans and the community around TV 49 East.",13,MUTED,false); hero.addView(sub); LinearLayout actions=row(); actions.setPadding(0,dp(12),0,0); Button live=roundButton("● Live TV",TEXT,PINK);live.setOnClickListener(v->openTv());actions.addView(live,new LinearLayout.LayoutParams(dp(112),dp(42)));Button reel=roundButton("▶ Clips",TEXT,Color.argb(50,255,255,255));reel.setOnClickListener(v->openReels());LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(dp(100),dp(42));rp.leftMargin=dp(8);actions.addView(reel,rp);hero.addView(actions);addBlock(hero);
    }

    private void addPost(SocialPost p){
        LinearLayout card=column(); card.setPadding(dp(15),dp(13),dp(15),dp(10)); card.setBackground(round(CARD,24)); LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,dp(6),0,dp(6));feed.addView(card,cp);
        LinearLayout head=row();head.setGravity(Gravity.CENTER_VERTICAL); SocialUser a=p.getAuthor(); TextView avatar=text(initial(a),15,TEXT,true);avatar.setGravity(Gravity.CENTER);avatar.setBackground(gradient(PINK,ORANGE,50));head.addView(avatar,new LinearLayout.LayoutParams(dp(46),dp(46)));LinearLayout who=column();who.setPadding(dp(10),0,0,0);who.addView(text(clean(a==null?null:a.getDisplayName(),"TV 49 East creator"),15,TEXT,true));who.addView(text("@"+clean(a==null?null:a.getUsername(),"creator")+"  ·  " + relative(p.getCreatedAt()),12,MUTED,false));head.addView(who,new LinearLayout.LayoutParams(0,dp(50),1));TextView more=text("⋮",23,MUTED,true);more.setGravity(Gravity.CENTER);head.addView(more,new LinearLayout.LayoutParams(dp(28),dp(42)));card.addView(head);
        TextView body=text(clean(p.getBody(),""),16,TEXT,false);body.setPadding(dp(4),dp(10),dp(4),dp(8));card.addView(body);
        if(p.getMediaUrl()!=null&&!p.getMediaUrl().trim().isEmpty()){ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setBackground(round(Color.rgb(29,8,39),20));image.setContentDescription("Post media");LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,dp(330));ip.setMargins(0,dp(4),0,dp(8));card.addView(image,ip);loadImage(image,p.getMediaUrl());}
        LinearLayout actions=row();actions.setGravity(Gravity.CENTER_VERTICAL);actions.addView(action("♡  "+p.getLikeCount(),v->repo.likePost(p.getId(),true,feedback("Liked"))),weight());actions.addView(action("↗  "+p.getRepostCount(),v->repo.repostPost(p.getId(),true,feedback("Reposted"))),weight());actions.addView(action("○  "+p.getReplyCount(),v->reply(p)),weight());actions.addView(action("⌁",v->share(p)),weight());card.addView(actions);
    }

    private void reply(SocialPost p){ if(!repo.isSignedIn()){auth();return;} final EditText input=new EditText(this);input.setHint("Write a reply…");input.setTextColor(Color.BLACK);new AlertDialog.Builder(this).setTitle("Reply").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Reply",(d,w)->repo.replyToPost(p.getId(),input.getText().toString(),feedback("Reply sent"))).show(); }
    private void compose(){ if(!repo.isSignedIn()){auth();return;} final EditText input=new EditText(this);input.setHint("What is happening?");input.setTextColor(Color.BLACK);input.setMinLines(4);new AlertDialog.Builder(this).setTitle("Create a post").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Post",(d,w)->repo.createPost(input.getText().toString(),r->{if(r.getError()!=null)toast(r.getError().getMessage());else runOnUiThread(this::loadFeed);})).show(); }

    private void auth(){
        LinearLayout box=column();box.setPadding(dp(4),0,dp(4),0);EditText email=new EditText(this);email.setHint("Email");EditText password=new EditText(this);password.setHint("Password");password.setInputType(129);box.addView(email);box.addView(password);new AlertDialog.Builder(this).setTitle("Sign in to TV 49 East").setMessage("Use your TV 49 East account to post, follow and chat.").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Sign in",(d,w)->repo.signIn(email.getText().toString(),password.getText().toString(),r->{if(r.getError()!=null)toast(r.getError().getMessage());else runOnUiThread(this::loadFeed);})).show();
    }

    private void showNotifications(){ if(!repo.isSignedIn()){auth();return;}repo.loadNotifications(30,r->runOnUiThread(()->{String s=r.getError()!=null?"Notifications unavailable.":clean(r.getValue(),"No notifications yet.");new AlertDialog.Builder(this).setTitle("Alerts").setMessage(s).setPositiveButton("OK",null).show();})); }
    private void showFatal(Throwable t){setContentView(errorView("TV 49 East Social could not start safely.\n\n"+clean(t.getMessage(),t.getClass().getSimpleName())));}
    private void addEmpty(String s){TextView t=text(s,14,MUTED,false);t.setGravity(Gravity.CENTER);t.setPadding(dp(24),dp(35),dp(24),dp(35));feed.addView(t);}
    private void addBlock(View v){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(6),0,dp(8));feed.addView(v,p);}

    private void navigate(String destination){if("Live TV".equals(destination)){openTv();return;}if("Profile".equals(destination)){openProfile();return;}if("Meet Up".equals(destination)){pageTitle.setText("Meet Up");loadTrending();return;}if("Chats".equals(destination)){showChats();return;}if("For You".equals(destination)){pageTitle.setText("MeetUp");loadFeed();return;} }
    private void loadTrending(){feed.removeAllViews();TextView h=text("People to meet",23,TEXT,true);h.setPadding(dp(10),dp(12),dp(10),dp(8));feed.addView(h);repo.loadTrending(20,r->runOnUiThread(()->{if(r.getValue()!=null)for(SocialPost p:r.getValue())addPost(p);else addEmpty("No recommendations yet.");}));}
    private void showChats(){if(!repo.isSignedIn()){auth();return;}com.fadcam.tv.social.SocialFeatureRepository features=new com.fadcam.tv.social.SocialFeatureRepository(this);features.loadConversations(30,r->runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Chats").setMessage(r.getError()!=null?"Chats unavailable: "+r.getError().getMessage():clean(r.getValue(),"No conversations yet.")).setPositiveButton("OK",null).show()));}
    private void search(String q){if(q==null||q.trim().isEmpty())return;repo.searchPosts(q.trim(),30,r->runOnUiThread(()->{feed.removeAllViews();pageTitle.setText("Search");if(r.getValue()!=null)for(SocialPost p:r.getValue())addPost(p);else addEmpty("No matching posts.");}));}
    private void openTv(){Intent i=new Intent(this,MainActivity.class);startActivity(i);overridePendingTransition(android.R.anim.slide_in_left,android.R.anim.slide_out_right);finish();}
    private void openReels(){startActivity(new Intent(this,ReelsActivity.class));}
    private void openProfile(){startActivity(new Intent(this,ProfessionalProfileActivity.class));}
    private void share(SocialPost p){Intent s=new Intent(Intent.ACTION_SEND);s.setType("text/plain");s.putExtra(Intent.EXTRA_TEXT,clean(p.getBody(),"TV 49 East post"));startActivity(Intent.createChooser(s,"Share post"));}

    private Button action(String s,View.OnClickListener l){Button b=roundButton(s,TEXT,Color.TRANSPARENT);b.setTextSize(12);b.setAllCaps(false);b.setOnClickListener(l);return b;}
    private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,dp(40),1);}
    private TextView icon(String s,View.OnClickListener l){TextView t=text(s,23,TEXT,true);t.setGravity(Gravity.CENTER);t.setOnClickListener(l);return t;}
    private Button roundButton(String s,int fg,int bg){Button b=new Button(this);b.setText(s);b.setTextColor(fg);b.setTextSize(13);b.setAllCaps(false);b.setMinHeight(0);b.setMinWidth(0);b.setPadding(dp(10),0,dp(10),0);b.setBackground(round(bg,22));return b;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String s,float size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL);return t;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private GradientDrawable gradient(int a,int b,int radius){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{a,b});d.setCornerRadius(dp(radius));return d;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private String clean(String s,String fallback){return s==null||s.trim().isEmpty()?fallback:s.trim();}
    private String initial(SocialUser u){String s=clean(u==null?null:u.getDisplayName(),"T");return s.substring(0,1).toUpperCase();}
    private String relative(String s){if(s==null||s.isEmpty())return "now";try{long t=java.time.Instant.parse(s).toEpochMilli();long m=Math.max(0,(System.currentTimeMillis()-t)/60000);if(m<1)return "now";if(m<60)return m+"m";if(m<1440)return (m/60)+"h";return (m/1440)+"d";}catch(Throwable ignored){return "now";}}
    private View errorView(String message){TextView t=text(message,15,TEXT,false);t.setGravity(Gravity.CENTER);t.setPadding(dp(24),dp(24),dp(24),dp(24));t.setBackgroundColor(BG);return t;}
    private void toast(String s){runOnUiThread(()->Toast.makeText(this,clean(s,"Done"),Toast.LENGTH_SHORT).show());}
    private SupabaseSocialRepository.ResultCallback<Boolean> feedback(String ok){return r->{if(r.getError()!=null)toast(r.getError().getMessage());else if(ok!=null&&!ok.isEmpty())toast(ok);};}
    private void loadImage(ImageView view,String url){imageExecutor.execute(()->{try{HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(8000);c.setReadTimeout(12000);c.connect();InputStream in=c.getInputStream();android.graphics.Bitmap b=android.graphics.BitmapFactory.decodeStream(in);in.close();c.disconnect();if(b!=null)runOnUiThread(()->view.setImageBitmap(b));}catch(Throwable ignored){}});}
}
