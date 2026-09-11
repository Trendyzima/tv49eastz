package com.fadcam.producer;

/** A camera resolution exposed only when supported by the device encoder/camera. */
public final class CameraResolutionOption {
    private final int width;
    private final int height;
    private final String label;

    public CameraResolutionOption(int width, int height, String label) {
        this.width = width;
        this.height = height;
        this.label = label;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public String getLabel() { return label; }

    @Override public String toString() { return label + " (" + width + "x" + height + ")"; }
}
