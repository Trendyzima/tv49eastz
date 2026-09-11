package com.fadcam.producer;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Size;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Discovers real camera output sizes; never advertises unsupported resolutions. */
public final class CameraResolutionCatalog {
    private CameraResolutionCatalog() {}

    public static List<CameraResolutionOption> discover(CameraManager manager, String cameraId) {
        if (manager == null || cameraId == null) return Collections.emptyList();
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return Collections.emptyList();
            Size[] sizes = map.getOutputSizes(android.graphics.SurfaceTexture.class);
            if (sizes == null) return Collections.emptyList();

            Map<String, Size> unique = new LinkedHashMap<>();
            for (Size size : sizes) {
                if (size == null || size.getWidth() < 320 || size.getHeight() < 240) continue;
                int w = Math.max(size.getWidth(), size.getHeight());
                int h = Math.min(size.getWidth(), size.getHeight());
                unique.put(w + "x" + h, new Size(w, h));
            }

            List<Size> sorted = new ArrayList<>(unique.values());
            sorted.sort(Comparator.comparingLong((Size s) -> (long) s.getWidth() * s.getHeight()).reversed());
            List<CameraResolutionOption> result = new ArrayList<>();
            for (Size size : sorted) {
                String label;
                long pixels = (long) size.getWidth() * size.getHeight();
                if (pixels >= 8_000_000L) label = "4K / Ultra HD";
                else if (pixels >= 3_000_000L) label = "1440p / QHD";
                else if (pixels >= 1_900_000L) label = "1080p / Full HD";
                else if (pixels >= 900_000L) label = "720p / HD";
                else label = "Standard";
                result.add(new CameraResolutionOption(size.getWidth(), size.getHeight(), label));
            }
            return result;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }
}
