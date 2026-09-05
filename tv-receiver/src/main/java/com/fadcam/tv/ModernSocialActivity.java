package com.fadcam.tv;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Modern native TV 49 East social surface. */
public final class ModernSocialActivity extends AppCompatActivity {
    private static final int BG=Color.rgb(39,9,70), BG2=Color.rgb(79,18,116), CARD=Color.rgb(55,15,84);
    private static final int TEXT=Color.WHITE, MUTED=Color.rgb(211,194,224), PINK=Color.rgb(255,76,155), ORANGE=Color.rgb(255,148,73), LILAC=Color.rgb(218,183,255);
    private final ExecutorService images=Executors.newFixedThreadPool(3);
    private SupabaseSocialRepository repo; private LinearLayout feed; private TextView title; private float downX,downY;

    @Override protected void onCreate(@Nullable Bundle state){super.onCreate(state);try{getWindow().setStatusBarColor(Color.rgb(27,5,48));getWindow().setNavigationBarColor(Color.rgb(17,3,30));repo=new SupabaseSocialRepository(this);build();loadFeed();}catch(Throwable t){fatal(t);}}
    @Override public boolean dispatchTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_DOWN){downX=e.getRawX();downY=e.getRawY();}if(e.getActionMasked()==MotionEvent.ACTION_UP){float dx=e.getRawX()-downX,dy=e.getRawY()-downY;if(Math.abs(dx)>Math.abs(dy)*1.25f&&dx>dp(80)){openTv();return true;}}return super.dispatchTouchEvent(e);}

    private void build(){FrameLayout root=new FrameLayout(this);root.setBackgroundColor(BG);LinearLayout main=col();main.setPadding(dp(12),dp(4),dp(12),0);
        LinearLayout head=row();head.setGravity(Gravity.CENTER_VERTICAL);TextView logo=text("49",27,TEXT,true);logo.setGravity(Gravity.CENTER);logo.setBackground(gradient(PINK,ORANGE,22));head.addView(logo,new LinearLayout.LayoutParams(dp(52),dp(42)));title=text("MeetUp",21,TEXT,true);title.setPadding(dp(10),0,0,0);head.addView(title,new LinearLayout.LayoutParams(0,dp(42),1));head.addView(icon("♧",v->alerts()),new LinearLayout.LayoutParams(dp(42),dp(42)));Button post=button("＋ Post",TEXT,Color.TRANSPARENT);post.setOnClickListener(v->compose());head.addView(post,new LinearLayout.LayoutParams(dp(104),dp(42)));Button account=button("T",TEXT,PINK);account.setOnClickListener(v->profile());head.addView(account,new LinearLayout.LayoutParams(dp(44),dp(42)));main.addView(head);
        EditText search=new EditText(this);search.setSingleLine(true);search.setTextColor(TEXT);search.setHintTextColor(MUTED);search.setHint("⌕  Search creators, posts, football…");search.setTextSize(14);search.setPadding(dp(18),0,dp(18),0);search.setBackground(round(Color.argb(55,255,255,255),26));search.setOnEditorActionListener((v,a,e)->{search(v.getText().toString());return true;});main.addView(search,new LinearLayout.LayoutParams(-1,dp(44)));
        main.addView(chips());ScrollView body=new ScrollView(this);body.setFillViewport(true);feed=col();feed.setPadding(0,dp(6),0,dp(86));body.addView(feed,new ScrollView.LayoutParams(-1,-2));main.addView(body,new LinearLayout.LayoutParams(-1,0,1));main.addView(bottom());root.addView(main,new FrameLayout.LayoutParams(-1,-1));Button fab=button("＋",TEXT,PINK);fab.setTextSize(24);fab.setOnClickListener(v->compose());FrameLayout.LayoutParams fp=new FrameLayout.LayoutParams(dp(58),dp(58),Gravity.RIGHT|Gravity.BOTTOM);fp.setMargins(0,0,dp(18),dp(76));root.addView(fab,fp);setContentView(root);}

    private View chips(){HorizontalScrollView hs=new HorizontalScrollView(this);hs.setHorizontalScrollBarEnabled(false);LinearLayout r=row();String[] names={"For You","Following","On TV","Clips","People","Football","Music","Live"};for(String n:names){TextView c=text(n,14,"For You".equals(n)?TEXT:MUTED,"For You".equals(n));c.setGravity(Gravity.CENTER);c.setPadding(dp(18),0,dp(18),0);c.setBackground("For You".equals(n)?gradient(PINK,ORANGE,24):round(Color.argb(35,255,255,255),24));final String x=n;c.setOnClickListener(v->{title.setText(x);if("On TV".equals(x)||"Live".equals(x))openTv();else if("Clips".equals(x))reels();else loadFeed();});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(42));p.setMargins(0,dp(5),dp(7),dp(5));r.addView(c,p);}hs.addView(r,new HorizontalScrollView.LayoutParams(-2,dp(52)));return hs;}

    private View bottom(){LinearLayout n=row();n.setGravity(Gravity.CENTER);n.setPadding(dp(4),dp(5),dp(4),dp(5));n.setBackgroundColor(Color.rgb(31,6,48));String[][] a={{"⌂","For You"},{"♧","Meet Up"},{"▣","Live TV"},{"◯","Chats"},{"♙","Profile"}};for(String[]x:a){LinearLayout cell=col();cell.setGravity(Gravity.CENTER);TextView i=text(x[0],22,TEXT,false);i.setGravity(Gravity.CENTER);TextView l=text(x[1],10,MUTED,"For You".equals(x[1]));l.setGravity(Gravity.CENTER);cell.addView(i,new LinearLayout.LayoutParams(-1,dp(29)));cell.addView(l,new LinearLayout.LayoutParams(-1,dp(18)));final String d=x[1];cell.setOnClickListener(v->navigate(d));n.addView(cell,new LinearLayout.LayoutParams(0,dp(55),1));}return n;}

    private void loadFeed(){if(feed==null)return;feed.removeAllViews();hero();repo.loadFeed(30,r->runOnUiThread(()->{if(r.getError()!=null){empty("Feed unavailable right now. Sign in or try again shortly.");return;}List<SocialPost> p=r.getValue();if(p==null||p.isEmpty()){empty("No posts yet. Be the first to start the conversation.");return;}for(SocialPost x:p)addPost(x);}));}
    private void hero(){LinearLayout h=col();h.setPadding(dp(18),dp(15),dp(18),dp(15));h.setBackground(gradient(BG2,Color.rgb(104,26,142),24));h.addView(text("TV 49 EAST SOCIAL",11,LILAC,true));h.addView(text("Your people. Your TV. Your moment.",23,TEXT,true));h.addView(text("Creators • football • live TV • community",13,MUTED,false));LinearLayout a=row();a.setPadding(0,dp(11),0,0);Button live=button("● Live TV",TEXT,PINK);live.setOnClickListener(v->openTv());a.addView(live,new LinearLayout.LayoutParams(dp(112),dp(42)));Button clip=button("▶ Clips",TEXT,Color.argb(50,255,255,255));clip.setOnClickListener(v->reels());LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(100),dp(42));cp.leftMargin=dp(8);a.addView(clip,cp);h.addView(a);addBlock(h);}

    private void addPost(SocialPost p){LinearLayout card=col();card.setPadding(dp(15),dp(13),dp(15),dp(10));card.setBackground(round(CARD,24));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,dp(6),0,dp(6));feed.addView(card,cp);SocialUser u=p.getAuthor();LinearLayout h=row();h.setGravity(Gravity.CENTER_VERTICAL);TextView av=text(initial(u),15,TEXT,true);av.setGravity(Gravity.CENTER);av.setBackground(gradient(PINK,ORANGE,50));h.addView(av,new LinearLayout.LayoutParams(dp(46),dp(46)));LinearLayout who=col();who.setPadding(dp(10),0,0,0);who.addView(text(clean(u==null?null:u.getDisplayName(),"TV 49 East creator"),15,TEXT,true));who.addView(text("@"+clean(u==null?null:u.getUsername(),"creator")+"  ·  "+relative(p.getCreatedAt()),12,MUTED,false));h.addView(who,new LinearLayout.LayoutParams(0,dp(50),1));h.addView(text("⋮",22,MUTED,true),new LinearLayout.LayoutParams(dp(28),dp(42)));card.addView(h);card.addView(text(clean(p.getBody(),""),16,TEXT,false));if(p.getMediaUrl()!=null&&!p.getMediaUrl().trim().isEmpty()){ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setBackground(round(Color.rgb(29,8,39),20));LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,dp(330));ip.setMargins(0,dp(8),0,dp(8));card.addView(image,ip);loadImage(image,p.getMediaUrl);}LinearLayout actions=row();actions.addView(action("♡  "+p.getLikeCount(),v->repo.likePost(p.getId(),true,feedback("Liked"))),weight());actions.addView(action("↗  "+p.getRepostCount(),v->repo.repostPost(p.getId(),true,feedback("Reposted"))),weight());actions.addView(action("○  "+p.getReplyCount(),v->reply(p)),weight());actions.addView(action("⌁",v->share(p)),weight());card.addView(actions);}

    private void compose(){if(!repo.isSignedIn()){auth();return;}EditText in=new EditText(this);in.setHint("What is happening?");in.setTextColor(Color.BLACK);in.setMinLines(4);new AlertDialog.Builder(this).setTitle("Create a post").setView(in).setNegativeButton("Cancel",null).setPositiveButton("Post",(d,w)->repo.createPost(in.getText().toString(),r->{if(r.getError()!=null)toast(r.getError().getMessage());else runOnUiThread(this::loadFeed);})).show();}
    private void reply(SocialPost p){if(!repo.isSignedIn()){auth();return;}EditText in=new EditText(this);in.setHint("Write a reply…");in.setTextColor(Color.BLACK);new AlertDialog.Builder(this).setTitle("Reply").setView(in).setNegativeButton("Cancel",null).setPositiveButton("Reply",(d,w)->repo.replyToPost(p.getId(),in.getText().toString(),feedback("Reply sent"))).show();}
    private void auth(){LinearLayout b=col();EditText email=new EditText(this);email.setHint("Email");EditText pass=new EditText(this);pass.setHint("Password");pass.setInputType(129);b.addView(email);b.addView(pass);new AlertDialog.Builder(this).setTitle("Sign in to TV 49 East").setView(b).setNegativeButton("Cancel",null).setPositiveButton("Sign in",(d,w)->repo.signIn(email.getText().toString(),pass.getText().toString(),r->{if(r.getError()!=null)toast(r.getError().getMessage());else runOnUiThread(this::loadFeed);})).show();}
    private void alerts(){if(!repo.isSignedIn()){auth();return;}repo.loadNotifications(30,r->runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Alerts").setMessage(r.getError()!=null?"Notifications unavailable.":clean(r.getValue(),"No notifications yet.")).setPositiveButton("OK",null).show()));}
    private void search(String q){if(q==null||q.trim().isEmpty())return;repo.searchPosts(q.trim(),30,r->runOnUiThread(()->{feed.removeAllViews();title.setText("Search");if(r.getValue()!=null)for(SocialPost p:r.getValue())addPost(p);else empty("No matching posts.");}));}
    private void navigate(String d){if("Live TV".equals(d)){openTv();return;}if("Profile".equals(d)){profile();return;}if("Meet Up".equals(d)){title.setText("Meet Up");repo.loadTrending(20,r->runOnUiThread(()->{feed.removeAllViews();if(r.getValue()!=null)for(SocialPost p:r.getValue())addPost(p);else empty("No recommendations yet.");}));return;}if("Chats".equals(d)){if(!repo.isSignedIn())auth();else new AlertDialog.Builder(this).setTitle("Chats").setMessage("Messaging is wired to the Supabase conversation RPC layer. Open Messages from the next navigation build to view the full conversation list.").setPositiveButton("OK",null).show();return;}title.setText("MeetUp");loadFeed();}
    private void openTv(){startActivity(new Intent(this,MainActivity.class));overridePendingTransition(android.R.anim.slide_in_left,android.R.anim.slide_out_right);finish();}
    private void reels(){startActivity(new Intent(this,ReelsActivity.class));}
    private void profile(){startActivity(new Intent(this,ProfessionalProfileActivity.class));}
    private void share(SocialPost p){Intent s=new Intent(Intent.ACTION_SEND);s.setType("text/plain");s.putExtra(Intent.EXTRA_TEXT,clean(p.getBody(),"TV 49 East post"));startActivity(Intent.createChooser(s,"Share post"));}
    private Button action(String s,View.OnClickListener l){Button b=button(s,TEXT,Color.TRANSPARENT);b.setTextSize(12);b.setOnClickListener(l);return b;}
    private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,dp(40),1);}
    private TextView icon(String s,View.OnClickListener l){TextView t=text(s,22,TEXT,true);t.setGravity(Gravity.CENTER);t.setOnClickListener(l);return t;}
    private Button button(String s,int fg,int bg){Button b=new Button(this);b.setText(s);b.setTextColor(fg);b.setTextSize(13);b.setAllCaps(false);b.setMinHeight(0);b.setMinWidth(0);b.setPadding(dp(10),0,dp(10),0);b.setBackground(round(bg,22));return b;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String s,float size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL);return t;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}private GradientDrawable gradient(int a,int b,int radius){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{a,b});d.setCornerRadius(dp(radius));return d;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}private String clean(String s,String f){return s==null||s.trim().isEmpty()?f:s.trim();}private String initial(SocialUser u){String s=clean(u==null?null:u.getDisplayName(),"T");return s.substring(0,1).toUpperCase();}
    private String relative(String s){if(s==null||s.isEmpty())return "now";try{long t=java.time.Instant.parse(s).toEpochMilli();long m=Math.max(0,(System.currentTimeMillis()-t)/60000);if(m<1)return "now";if(m<60)return m+"m";if(m<1440)return m/60+"h";return m/1440+"d";}catch(Throwable ignored){return "now";}}
    private void addBlock(View v){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(6),0,dp(8));feed.addView(v,p);}private void empty(String s){TextView t=text(s,14,MUTED,false);t.setGravity(Gravity.CENTER);t.setPadding(dp(24),dp(35),dp(24),dp(35));feed.addView(t);}
    private void toast(String s){runOnUiThread(()->Toast.makeText(this,clean(s,"Done"),Toast.LENGTH_SHORT).show());}private SupabaseSocialRepository.ResultCallback<Boolean> feedback(String ok){return r->{if(r.getError()!=null)toast(r.getError().getMessage());else toast(ok);};}
    private void fatal(Throwable t){TextView v=text("TV 49 East Social could not start safely.\n\n"+clean(t.getMessage(),t.getClass().getSimpleName()),15,TEXT,false);v.setGravity(Gravity.CENTER);v.setPadding(dp(24),dp(24),dp(24),dp(24));v.setBackgroundColor(BG);setContentView(v);}
    private void loadImage(ImageView v,String url){images.execute(()->{try{HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(8000);c.setReadTimeout(12000);c.connect();InputStream in=c.getInputStream();android.graphics.Bitmap b=android.graphics.BitmapFactory.decodeStream(in);in.close();c.disconnect();if(b!=null)runOnUiThread(()->v.setImageBitmap(b));}catch(Throwable ignored){}});}
}
