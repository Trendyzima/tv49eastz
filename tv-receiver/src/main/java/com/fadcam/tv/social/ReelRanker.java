package com.fadcam.tv.social;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Lightweight on-device ranking fallback. It never consumes Spotify listening/content signals. */
public final class ReelRanker {
    private ReelRanker() {}

    public static List<SocialPost> rank(List<SocialPost> input) {
        List<SocialPost> out = new ArrayList<>(input == null ? new ArrayList<>() : input);
        out.sort(Comparator.comparingDouble(ReelRanker::score).reversed());
        return out;
    }

    private static double score(SocialPost p) {
        if (p == null) return -1000;
        double engagement = Math.log1p(Math.max(0, p.getLikeCount())) * 1.2
                + Math.log1p(Math.max(0, p.getReplyCount())) * 1.4
                + Math.log1p(Math.max(0, p.getRepostCount())) * 1.8;
        double media = "video".equalsIgnoreCase(p.getMediaType()) ? 5.0 : 0.0;
        double affinity = p.isLikedByViewer() ? 2.0 : 0.0;
        double freshness = 1.0;
        return engagement + media + affinity + freshness;
    }
}
