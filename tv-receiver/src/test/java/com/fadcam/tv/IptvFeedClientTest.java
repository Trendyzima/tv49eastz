package com.fadcam.tv;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IptvFeedClientTest {
    @Test
    public void directHlsUrlsAreAccepted() {
        assertTrue(IptvFeedClient.isDirectHls("https://example.com/live/index.m3u8"));
        assertTrue(IptvFeedClient.isDirectHls("http://example.com/live.m3u8?token=abc"));
    }

    @Test
    public void webPagesAndNonHlsAreRejected() {
        assertFalse(IptvFeedClient.isDirectHls("https://www.youtube.com/watch?v=abc"));
        assertFalse(IptvFeedClient.isDirectHls("https://www.twitch.tv/channel"));
        assertFalse(IptvFeedClient.isDirectHls("https://example.com/video.mp4"));
        assertFalse(IptvFeedClient.isDirectHls("https://example.com/live/playlist.mpd"));
        assertFalse(IptvFeedClient.isDirectHls("file:///sdcard/live.m3u8"));
    }
}
