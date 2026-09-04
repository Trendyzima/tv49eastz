package com.fadcam.tv;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import com.fadcam.tv.social.SmartReel;
import com.fadcam.tv.social.SmartReelRepository;
import java.util.ArrayList;
import java.util.List;

/** Full-screen vertical short-video experience with server ranking, author diversity and pagination. */
public class ReelsActivity extends AppCompatActivity {
    private ViewPager2 pager;
    private ReelAdapter adapter;
    private SmartReelRepository repo;
    private boolean loading;
    private int nextOffset;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state); getWindow().setStatusBarColor(Color.BLACK); getWindow().setNavigationBarColor(Color.BLACK);
        repo=new SmartReelRepository(this); pager=new ViewPager2(this); pager.setOrientation(ViewPager2.ORIENTATION_VERTICAL); adapter=new ReelAdapter(); pager.setAdapter(adapter); pager.setOffscreenPageLimit(1); setContentView(pager);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback(){@Override public void onPageSelected(int position){if(position>=adapter.items.size()-3)loadMore();}}); loadMore();
    }
    private void loadMore(){if(loading)return;loading=true;repo.load(12,nextOffset,(items,error)->runOnUiThread(()->{loading=false;if(error!=null){if(adapter.items.isEmpty())Toast.makeText(this,"Unable to load Reels: "+error.getMessage(),Toast.LENGTH_LONG).show();return;}if(items==null||items.isEmpty())return;adapter.append(items);nextOffset+=items.size();}));}

    private final class ReelAdapter extends RecyclerView.Adapter<ReelAdapter.Holder>{
        final ArrayList<SmartReel> items=new ArrayList<>();
        void append(List<SmartReel> next){int start=items.size();if(next!=null)for(SmartReel r:next)if(r!=null&&r.video_url!=null&&!r.video_url.isBlank())items.add(r);notifyItemRangeInserted(start,items.size()-start);}
        @Override public Holder onCreateViewHolder(android.view.ViewGroup parent,int type){FrameLayout root=new FrameLayout(ReelsActivity.this);root.setBackgroundColor(Color.BLACK);PlayerView player=new PlayerView(ReelsActivity.this);player.setUseController(true);root.addView(player,new FrameLayout.LayoutParams(-1,-1));TextView overlay=new TextView(ReelsActivity.this);overlay.setTextColor(Color.WHITE);overlay.setTextSize(16);overlay.setGravity(Gravity.BOTTOM|Gravity.LEFT);overlay.setPadding(dp(20),dp(20),dp(90),dp(55));overlay.setShadowLayer(8,0,2,Color.BLACK);root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));TextView actions=new TextView(ReelsActivity.this);actions.setText("♡\n↗\n•••");actions.setTextColor(Color.WHITE);actions.setTextSize(27);actions.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);actions.setPadding(0,0,dp(18),0);root.addView(actions,new FrameLayout.LayoutParams(dp(90),-1,Gravity.RIGHT));return new Holder(root,player,overlay);}
        @Override public void onBindViewHolder(Holder h,int position){SmartReel r=items.get(position);h.release();String caption=r.caption==null?"":r.caption;String author="@"+(r.author_username==null?"creator":r.author_username);h.overlay.setText(author+"\n"+caption+"\n♥ "+r.like_count+"   ↗ "+r.share_count+"   💬 "+r.comment_count);h.player=new ExoPlayer.Builder(ReelsActivity.this).build();h.player.setMediaItem(MediaItem.fromUri(r.video_url));h.player.prepare();h.player.setPlayWhenReady(position==pager.getCurrentItem());h.view.setPlayer(h.player);}
        @Override public int getItemCount(){return items.size();}
        @Override public void onViewRecycled(Holder holder){holder.release();super.onViewRecycled(holder);}
        final class Holder extends RecyclerView.ViewHolder{final PlayerView view;final TextView overlay;ExoPlayer player;Holder(View root,PlayerView view,TextView overlay){super(root);this.view=view;this.overlay=overlay;}void release(){if(player!=null){player.release();player=null;}view.setPlayer(null);}}
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
