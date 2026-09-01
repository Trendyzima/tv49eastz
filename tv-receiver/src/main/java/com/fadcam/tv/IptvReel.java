package com.fadcam.tv;

public final class IptvReel {
    public final String id;
    public final String channel;
    public final String title;
    public final String url;
    public final String quality;
    public final String referrer;
    public final String userAgent;
    public final String source;

    public IptvReel(String id, String channel, String title, String url, String quality,
                    String referrer, String userAgent, String source) {
        this.id = id;
        this.channel = channel;
        this.title = title;
        this.url = url;
        this.quality = quality;
        this.referrer = referrer;
        this.userAgent = userAgent;
        this.source = source;
    }
}
