package com.fadcam.services;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

import androidx.annotation.NonNull;

import java.util.Arrays;

/**
 * Device-safe capture policy shared by the recorder UI and RecordingService.
 * It deliberately prefers the highest stable profile instead of blindly
 * selecting the largest advertised camera size.
 */
public final class CaptureQualityController {
    public enum Preset { MAXIMUM, CREATOR, BALANCED, LOW_LIGHT, VOICE_PRIORITY }

    public static final class Decision {
        public final int width;
        public final int height;
        public final int fps;
        public final int videoBitrate;
        public final int audioSampleRate;
        public final int audioChannels;
        public final int audioBitrate;
        public final boolean voicePriority;
        public final String audioRoute;
        public final String[] warnings;

        private Decision(int width, int height, int fps, int videoBitrate,
                         int audioSampleRate, int audioChannels, int audioBitrate,
                         boolean voicePriority, String audioRoute, String[] warnings) {
            this.width = width; this.height = height; this.fps = fps;
            this.videoBitrate = videoBitrate; this.audioSampleRate = audioSampleRate;
            this.audioChannels = audioChannels; this.audioBitrate = audioBitrate;
            this.voicePriority = voicePriority; this.audioRoute = audioRoute;
            this.warnings = warnings;
        }
    }

    private CaptureQualityController() {}

    @NonNull
    public static Decision decide(@NonNull Preset preset, int maxWidth, int maxHeight,
                                  int maxFps, boolean hevcSupported, boolean lowStorage) {
        int width = maxWidth > 0 ? maxWidth : 1920;
        int height = maxHeight > 0 ? maxHeight : 1080;
        int fps = maxFps > 0 ? maxFps : 30;
        if (preset != Preset.MAXIMUM) fps = Math.min(fps, 30);
        if (preset == Preset.LOW_LIGHT) fps = Math.min(fps, 30);
        if (preset == Preset.BALANCED) {
            width = Math.min(width, 1920); height = Math.min(height, 1080);
        }
        if (preset == Preset.VOICE_PRIORITY) {
            width = Math.min(width, 1920); height = Math.min(height, 1080); fps = Math.min(fps, 30);
        }
        int bitrate = bitrateFor(width, height, fps, lowStorage);
        int channels = (preset == Preset.CREATOR || preset == Preset.VOICE_PRIORITY) ? 1 : 2;
        String route = "auto";
        String[] warnings = (width >= 3840 && fps >= 60)
                ? new String[]{"4K60 selected: monitor heat, storage throughput, and dropped frames"}
                : new String[0];
        return new Decision(width, height, fps, bitrate, 48000, channels, 192000,
                preset == Preset.CREATOR || preset == Preset.VOICE_PRIORITY, route, warnings);
    }

    private static int bitrateFor(int width, int height, int fps, boolean lowStorage) {
        long pixels = (long) width * height;
        int base = pixels >= 8000000 ? 50000000 : pixels >= 2000000 ? 16000000 : 8000000;
        if (fps >= 50) base = (int) Math.min(80000000L, base * 1.5);
        if (lowStorage) base = Math.max(4000000, base * 2 / 3);
        return base;
    }

    @NonNull
    public static String resolveAudioRoute(@NonNull Context context) {
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return "default";
        if (Build.VERSION.SDK_INT >= 23) {
            AudioDeviceInfo[] devices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS);
            for (AudioDeviceInfo device : devices) {
                int type = device.getType();
                if (type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET)
                    return "usb_external";
            }
            for (AudioDeviceInfo device : devices) {
                int type = device.getType();
                if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
                    return "wired";
            }
            for (AudioDeviceInfo device : devices) {
                if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return "bluetooth";
            }
        }
        return "builtin";
    }
}
