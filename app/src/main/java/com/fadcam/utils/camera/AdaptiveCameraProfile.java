package com.fadcam.utils.camera;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.PowerManager;
import android.util.Range;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.fadcam.CameraType;
import com.fadcam.Constants;
import com.fadcam.FLog;
import com.fadcam.VideoCodec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Hardware-first recording profile selector for the existing Camera2/MediaRecorder engine.
 * It never assumes that 4K/8K, 60/90fps or HEVC exist. It queries camera stream sizes,
 * AE FPS ranges and platform video encoders, then persists the highest jointly supported
 * profile. RecordingService already consumes these preferences, so this is additive.
 */
public final class AdaptiveCameraProfile {
    private static final String TAG = "AdaptiveCameraProfile";
    private static final String PREFS = "FadCamAdaptiveCamera";
    private static final String KEY_LAST_REPORT = "last_report";
    private static final int[] PREFERRED_FPS = {90, 60, 30, 24};

    private AdaptiveCameraProfile() {}

    public static final class Profile {
        public final Size size;
        public final int fps;
        public final VideoCodec codec;
        public final boolean eis;
        public final boolean ois;
        public final boolean thermalReduced;
        public final String cameraId;

        Profile(Size size, int fps, VideoCodec codec, boolean eis, boolean ois,
                boolean thermalReduced, String cameraId) {
            this.size = size;
            this.fps = fps;
            this.codec = codec;
            this.eis = eis;
            this.ois = ois;
            this.thermalReduced = thermalReduced;
            this.cameraId = cameraId;
        }

        @NonNull
        @Override
        public String toString() {
            return size.getWidth() + "x" + size.getHeight()
                    + " @" + fps + "fps " + codec.name()
                    + " EIS=" + eis + " OIS=" + ois
                    + (thermalReduced ? " thermal-safe" : "");
        }
    }

    public static final class Report {
        public final String cameraId;
        public final List<Size> mediaRecorderSizes;
        public final Set<VideoCodec> supportedCodecs;
        public final List<Integer> supportedFps;
        public final boolean eis;
        public final boolean ois;
        public final Profile selected;

        Report(String cameraId, List<Size> mediaRecorderSizes,
               Set<VideoCodec> supportedCodecs, List<Integer> supportedFps,
               boolean eis, boolean ois, Profile selected) {
            this.cameraId = cameraId;
            this.mediaRecorderSizes = mediaRecorderSizes;
            this.supportedCodecs = supportedCodecs;
            this.supportedFps = supportedFps;
            this.eis = eis;
            this.ois = ois;
            this.selected = selected;
        }

        public String summary() {
            return "camera=" + cameraId + " sizes=" + mediaRecorderSizes.size()
                    + " codecs=" + supportedCodecs + " fps=" + supportedFps
                    + " EIS=" + eis + " OIS=" + ois + " selected=" + selected;
        }
    }

    @Nullable
    public static Report applyBestAvailableProfile(@NonNull Context context,
                                                    @NonNull CameraType cameraType) {
        if (cameraType == CameraType.DUAL_PIP) return null;
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            FLog.w(TAG, "Camera permission unavailable; skipping adaptive preflight");
            return null;
        }
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) return null;
        try {
            String cameraId = findCameraId(manager, cameraType);
            if (cameraId == null) return null;
            CameraCharacteristics c = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return null;

            List<Size> sizes = filterVideoSizes(map.getOutputSizes(MediaRecorder.class));
            if (sizes.isEmpty()) sizes = filterVideoSizes(map.getOutputSizes(SurfaceTexture.class));

            Set<VideoCodec> codecs = detectEncoders(sizes);
            List<Integer> fps = detectFps(c);
            boolean eis = hasMode(c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES),
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON);
            boolean ois = hasMode(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION),
                    CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);
            boolean thermalReduced = isThermalConstrained(context);

            Profile selected = selectProfile(cameraId, sizes, codecs, fps, eis, ois, thermalReduced);
            Report report = new Report(cameraId, sizes, codecs, fps, eis, ois, selected);
            persistReport(context, report);
            if (selected != null) {
                persistProfile(context, cameraType, selected);
                FLog.i(TAG, "Applied verified camera profile: " + selected);
            } else {
                FLog.w(TAG, "No jointly supported camera/encoder profile; retaining saved settings");
            }
            return report;
        } catch (SecurityException e) {
            FLog.w(TAG, "Camera permission revoked during adaptive scan", e);
            return null;
        } catch (CameraAccessException e) {
            FLog.w(TAG, "Camera characteristics unavailable during adaptive scan", e);
            return null;
        } catch (Throwable t) {
            FLog.w(TAG, "Adaptive scan failed safely: " + t.getClass().getSimpleName(), t);
            return null;
        }
    }

    @Nullable
    private static String findCameraId(CameraManager manager, CameraType type)
            throws CameraAccessException {
        int wanted = type == CameraType.FRONT
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        for (String id : manager.getCameraIdList()) {
            Integer facing = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == wanted) return id;
        }
        return null;
    }

    private static List<Size> filterVideoSizes(@Nullable Size[] raw) {
        if (raw == null || raw.length == 0) return new ArrayList<>();
        List<Size> out = new ArrayList<>();
        for (Size s : raw) {
            if (s == null || s.getWidth() < 320 || s.getHeight() < 240) continue;
            double ratio = (double) Math.max(s.getWidth(), s.getHeight())
                    / Math.max(1, Math.min(s.getWidth(), s.getHeight()));
            if (ratio <= 2.4d) out.add(s);
        }
        Collections.sort(out, new Comparator<Size>() {
            @Override public int compare(Size a, Size b) {
                return Long.compare((long) b.getWidth() * b.getHeight(),
                        (long) a.getWidth() * a.getHeight());
            }
        });
        return out;
    }

    private static Set<VideoCodec> detectEncoders(List<Size> sizes) {
        Set<VideoCodec> result = new HashSet<>();
        MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
        for (VideoCodec codec : new VideoCodec[]{VideoCodec.HEVC, VideoCodec.AVC}) {
            for (MediaCodecInfo info : infos) {
                if (!info.isEncoder() || !supportsMime(info, codec.getMimeType())) continue;
                try {
                    MediaCodecInfo.VideoCapabilities vc = info
                            .getCapabilitiesForType(codec.getMimeType()).getVideoCapabilities();
                    if (vc == null) continue;
                    for (Size size : sizes) {
                        if (vc.isSizeSupported(size.getWidth(), size.getHeight())) {
                            result.add(codec);
                            break;
                        }
                    }
                } catch (Throwable ignored) {}
                if (result.contains(codec)) break;
            }
        }
        return result;
    }

    private static List<Integer> detectFps(CameraCharacteristics c) {
        Set<Integer> supported = new HashSet<>();
        Range<Integer>[] ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges != null) {
            for (Range<Integer> r : ranges) {
                if (r == null) continue;
                for (int candidate : PREFERRED_FPS) {
                    if (r.getLower() <= candidate && r.getUpper() >= candidate) supported.add(candidate);
                }
            }
        }
        List<Integer> out = new ArrayList<>(supported);
        Collections.sort(out, Collections.reverseOrder());
        if (out.isEmpty()) out.add(30);
        return out;
    }

    private static boolean supportsMime(MediaCodecInfo info, String mime) {
        for (String type : info.getSupportedTypes()) if (mime.equalsIgnoreCase(type)) return true;
        return false;
    }

    private static boolean hasMode(@Nullable int[] modes, int wanted) {
        if (modes == null) return false;
        for (int mode : modes) if (mode == wanted) return true;
        return false;
    }

    @Nullable
    private static Profile selectProfile(String cameraId, List<Size> sizes,
                                         Set<VideoCodec> codecs, List<Integer> fps,
                                         boolean eis, boolean ois, boolean thermalReduced) {
        if (sizes.isEmpty() || codecs.isEmpty() || fps.isEmpty()) return null;
        long maxPixels = thermalReduced ? 3840L * 2160L : Long.MAX_VALUE;
        for (Size size : sizes) {
            if ((long) size.getWidth() * size.getHeight() > maxPixels) continue;
            VideoCodec codec = codecs.contains(VideoCodec.HEVC) ? VideoCodec.HEVC
                    : (codecs.contains(VideoCodec.AVC) ? VideoCodec.AVC : null);
            if (codec == null || !encoderSupportsSize(codec, size)) continue;
            int chosenFps = chooseFps(codec, size, fps, thermalReduced);
            if (chosenFps > 0) return new Profile(size, chosenFps, codec, eis, ois, thermalReduced, cameraId);
        }
        return null;
    }

    private static int chooseFps(VideoCodec codec, Size size, List<Integer> fps,
                                 boolean thermalReduced) {
        for (int candidate : fps) {
            if (thermalReduced && candidate > 30) continue;
            if (encoderSupportsFps(codec, size, candidate)) return candidate;
        }
        return 0;
    }

    private static boolean encoderSupportsSize(VideoCodec codec, Size size) {
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if (!info.isEncoder() || !supportsMime(info, codec.getMimeType())) continue;
                MediaCodecInfo.VideoCapabilities vc = info.getCapabilitiesForType(codec.getMimeType()).getVideoCapabilities();
                if (vc != null && vc.isSizeSupported(size.getWidth(), size.getHeight())) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean encoderSupportsFps(VideoCodec codec, Size size, int fps) {
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if (!info.isEncoder() || !supportsMime(info, codec.getMimeType())) continue;
                MediaCodecInfo.VideoCapabilities vc = info.getCapabilitiesForType(codec.getMimeType()).getVideoCapabilities();
                if (vc == null || !vc.isSizeSupported(size.getWidth(), size.getHeight())) continue;
                Range<Double> rates = vc.getSupportedFrameRatesFor(size.getWidth(), size.getHeight());
                if (rates != null && rates.contains((double) fps)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean isThermalConstrained(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void persistProfile(Context context, CameraType cameraType, Profile p) {
        SharedPreferences prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        String fpsKey = cameraType == CameraType.FRONT
                ? Constants.PREF_VIDEO_FRAME_RATE_FRONT : Constants.PREF_VIDEO_FRAME_RATE_BACK;
        prefs.edit()
                .putInt(Constants.PREF_VIDEO_RESOLUTION_WIDTH, p.size.getWidth())
                .putInt(Constants.PREF_VIDEO_RESOLUTION_HEIGHT, p.size.getHeight())
                .putInt(Constants.PREF_VIDEO_FRAME_RATE, p.fps)
                .putInt(fpsKey, p.fps)
                .putString(Constants.PREF_VIDEO_CODEC, p.codec.name())
                .apply();
    }

    private static void persistReport(Context context, Report report) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_REPORT, report.summary()).apply();
    }

    @Nullable
    public static String getLastReport(@NonNull Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_REPORT, null);
    }
}
