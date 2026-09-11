package com.fadcam.capture;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Device-side capture policy. It deliberately chooses the highest stable profile
 * instead of blindly requesting the largest advertised camera mode.
 */
public final class CaptureQualityController {
    public enum Preset { MAXIMUM, CREATOR, BALANCED, LOW_LIGHT, VOICE_PRIORITY }

    public static final class VideoProfile {
        public final int width;
        public final int height;
        public final int fps;
        public final int bitrate;
        public final String codec;

        public VideoProfile(int width, int height, int fps, int bitrate, String codec) {
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.bitrate = bitrate;
            this.codec = codec;
        }
    }

    public static final class AudioProfile {
        public final int sampleRate;
        public final int channels;
        public final int bitrate;
        public final String route;

        public AudioProfile(int sampleRate, int channels, int bitrate, String route) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitrate = bitrate;
            this.route = route;
        }
    }

    private CaptureQualityController() {}

    public static VideoProfile selectVideo(
            @NonNull List<int[]> supportedSizes,
            int requestedFps,
            int requestedBitrate,
            @NonNull Preset preset) {
        List<int[]> candidates = new ArrayList<>();
        for (int[] size : supportedSizes) {
            if (size != null && size.length >= 2 && size[0] > 0 && size[1] > 0) {
                candidates.add(size);
            }
        }
        Collections.sort(candidates, new Comparator<int[]>() {
            @Override public int compare(int[] a, int[] b) {
                return Integer.compare(b[0] * b[1], a[0] * a[1]);
            }
        });
        int fps = requestedFps > 0 ? requestedFps : 30;
        if (preset != Preset.MAXIMUM) fps = Math.min(fps, 30);
        if (preset == Preset.LOW_LIGHT) fps = Math.min(fps, 24);
        int[] selected = candidates.isEmpty() ? new int[]{1280, 720} : candidates.get(0);
        int bitrate = requestedBitrate > 0 ? requestedBitrate : defaultBitrate(selected[0], selected[1], fps);
        return new VideoProfile(selected[0], selected[1], fps, bitrate, "avc");
    }

    public static AudioProfile selectAudio(@NonNull AudioManager audioManager, @NonNull Preset preset) {
        int sampleRate = 48000;
        int channels = (preset == Preset.CREATOR || preset == Preset.VOICE_PRIORITY) ? 1 : 2;
        String route = "builtin";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
            for (AudioDeviceInfo device : devices) {
                int type = device.getType();
                if (type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                    route = "usb";
                    break;
                }
                if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) {
                    route = "wired";
                } else if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO_HEADSET && "builtin".equals(route)) {
                    route = "bluetooth";
                }
            }
        }
        return new AudioProfile(sampleRate, channels, 192000, route);
    }

    private static int defaultBitrate(int width, int height, int fps) {
        long pixels = (long) width * height;
        int bitrate = pixels >= 3840L * 2160L ? 50000000 : pixels >= 1920L * 1080L ? 16000000 : 8000000;
        return fps >= 50 ? (int) Math.min(80000000L, bitrate * 1.5) : bitrate;
    }
}
