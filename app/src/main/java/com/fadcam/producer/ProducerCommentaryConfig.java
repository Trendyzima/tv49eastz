package com.fadcam.producer;

import androidx.annotation.NonNull;

/** Immutable configuration for producer commentary recording. */
public final class ProducerCommentaryConfig {
    public enum PipPosition { TOP_RIGHT, BOTTOM_RIGHT }
    public enum PipSize { SMALL, MEDIUM, LARGE }

    private final boolean enabled;
    private final String sourceUri;
    private final PipPosition position;
    private final PipSize size;
    private final boolean muted;
    private final boolean loop;

    public ProducerCommentaryConfig(boolean enabled, String sourceUri,
                                    @NonNull PipPosition position,
                                    @NonNull PipSize size,
                                    boolean muted, boolean loop) {
        this.enabled = enabled;
        this.sourceUri = sourceUri;
        this.position = position;
        this.size = size;
        this.muted = muted;
        this.loop = loop;
    }

    public static ProducerCommentaryConfig disabled() {
        return new ProducerCommentaryConfig(false, null, PipPosition.BOTTOM_RIGHT,
                PipSize.MEDIUM, true, true);
    }

    public boolean isEnabled() { return enabled && sourceUri != null && !sourceUri.isEmpty(); }
    public String getSourceUri() { return sourceUri; }
    public PipPosition getPosition() { return position; }
    public PipSize getSize() { return size; }
    public boolean isMuted() { return muted; }
    public boolean isLoop() { return loop; }

    public ProducerCommentaryConfig withEnabled(boolean value) {
        return new ProducerCommentaryConfig(value, sourceUri, position, size, muted, loop);
    }
    public ProducerCommentaryConfig withSourceUri(String value) {
        return new ProducerCommentaryConfig(value != null, value, position, size, muted, loop);
    }
    public ProducerCommentaryConfig withPosition(PipPosition value) {
        return new ProducerCommentaryConfig(enabled, sourceUri, value, size, muted, loop);
    }
    public ProducerCommentaryConfig withSize(PipSize value) {
        return new ProducerCommentaryConfig(enabled, sourceUri, position, value, muted, loop);
    }
    public ProducerCommentaryConfig withMuted(boolean value) {
        return new ProducerCommentaryConfig(enabled, sourceUri, position, size, value, loop);
    }
    public ProducerCommentaryConfig withLoop(boolean value) {
        return new ProducerCommentaryConfig(enabled, sourceUri, position, size, muted, value);
    }
}
