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
import com.fadcam.tv.social.ReelRanker;
import com.fadcam.tv.social.SocialPost;
import com.fadcam.tv.social.SupabaseSocialRepository;
import java.util.ArrayList;
import java.util.List;

/** Full-screen vertical short-video experience with incremental feed pagination. */
public class ReelsActivity extends AppCompatActivity {
    private ViewPager2 pager;
    private ReelAdapter adapter;
    private SupabaseSocialRepository repo;
    private int requested = 24;
    private boolean loading;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        repo = new SupabaseSocialRepository(this);
        pager = new ViewPager2(this);
        pager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        adapter = new ReelAdapter();
        pager.setAdapter(adapter);
        pager.setOffscreenPageLimit(1);
        setContentView(pager);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                if (position >= adapter.items.size() - 4) loadMore();
            }
        });
        loadMore();
    }

    private void loadMore() {
        if (loading) return;
        loading = true;
        repo.loadFeed(Math.min(requested, 50), result -> runOnUiThread(() -> {
            loading = false;
            if (result.getError() != null) {
                if (adapter.items.isEmpty()) Toast.makeText(this, "Unable to load Reels: " + result.getError().getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
            List<SocialPost> ranked = ReelRanker.rank(result.getValue());
            ArrayList<SocialPost> videos = new ArrayList<>();
            for (SocialPost p : ranked) if (p != null && "video".equalsIgnoreCase(p.getMediaType()) && p.getMediaUrl() != null) videos.add(p);
            adapter.replace(videos);
            requested = Math.min(50, requested + 10);
        }));
    }

    private final class ReelAdapter extends RecyclerView.Adapter<ReelAdapter.Holder> {
        final ArrayList<SocialPost> items = new ArrayList<>();
        void replace(List<SocialPost> next) { items.clear(); if (next != null) items.addAll(next); notifyDataSetChanged(); }
        @Override public Holder onCreateViewHolder(android.view.ViewGroup parent, int type) {
            FrameLayout root = new FrameLayout(ReelsActivity.this);
            root.setBackgroundColor(Color.BLACK);
            PlayerView player = new PlayerView(ReelsActivity.this);
            player.setUseController(true);
            root.addView(player, new FrameLayout.LayoutParams(-1, -1));
            TextView overlay = new TextView(ReelsActivity.this);
            overlay.setTextColor(Color.WHITE); overlay.setTextSize(15); overlay.setGravity(Gravity.BOTTOM | Gravity.LEFT);
            overlay.setPadding(dp(20), dp(20), dp(90), dp(45)); overlay.setShadowLayer(8,0,2,Color.BLACK);
            root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
            TextView actions = new TextView(ReelsActivity.this);
            actions.setText("♡   ↗   •••"); actions.setTextColor(Color.WHITE); actions.setTextSize(25); actions.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            actions.setPadding(0,0,dp(20),0); root.addView(actions, new FrameLayout.LayoutParams(dp(120),-1,Gravity.RIGHT));
            return new Holder(root, player, overlay);
        }
        @Override public void onBindViewHolder(Holder h, int position) {
            SocialPost p = items.get(position);
            h.release();
            h.overlay.setText(p.getBody() == null ? "" : p.getBody());
            h.player = new ExoPlayer.Builder(ReelsActivity.this).build();
            h.player.setMediaItem(MediaItem.fromUri(p.getMediaUrl()));
            h.player.prepare();
            h.player.setPlayWhenReady(position == pager.getCurrentItem());
            h.view.setPlayer(h.player);
        }
        @Override public int getItemCount() { return items.size(); }
        @Override public void onViewRecycled(Holder holder) { holder.release(); super.onViewRecycled(holder); }
        final class Holder extends RecyclerView.ViewHolder {
            final PlayerView view; final TextView overlay; ExoPlayer player;
            Holder(View root, PlayerView view, TextView overlay) { super(root); this.view=view; this.overlay=overlay; }
            void release(){ if(player!=null){player.release();player=null;} view.setPlayer(null); }
        }
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
