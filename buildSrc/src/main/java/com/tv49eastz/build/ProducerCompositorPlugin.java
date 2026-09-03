package com.tv49eastz.build;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.Directory;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Build-only source overlay for TV 49 East producer mode. */
public final class ProducerCompositorPlugin implements Plugin<Project> {
    private static final String SERVICE = "src/main/java/com/fadcam/dualcam/service/DualCameraRecordingService.java";

    @Override
    public void apply(Project project) {
        Provider<Directory> output = project.getLayout().getBuildDirectory().dir("generated/producer-compositor/java");
        File source = new File(project.getProjectDir(), SERVICE);
        File outputDir = output.get().getAsFile();
        File generatedService = new File(outputDir, "com/fadcam/dualcam/service/DualCameraRecordingService.java");

        TaskProvider<Task> prepare = project.getTasks().register("prepareProducerCompositorSources", task -> {
            task.getOutputs().dir(outputDir);
            task.doLast(ignored -> generate(source, outputDir));
        });

        project.getTasks().withType(JavaCompile.class).configureEach(task -> {
            String name = task.getName();
            if (!name.contains("JavaWithJavac") || name.contains("UnitTest") || name.contains("AndroidTest")) return;
            task.dependsOn(prepare);
            task.doFirst((Action<Task>) current -> current.setSource(
                    current.getSource().minus(source).plus(generatedService)));
        });
    }

    private static void generate(File source, File out) {
        try {
            File generated = new File(out, "com/fadcam/dualcam/service/DualCameraRecordingService.java");
            Files.createDirectories(generated.getParentFile().toPath());
            String patched = patchService(Files.readString(source.toPath(), StandardCharsets.UTF_8));
            Files.writeString(generated.toPath(), patched, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Unable to generate TV 49 East producer service", e);
        }
    }

    private static String patchService(String s) {
        s = replaceOnce(s,
                "import android.content.pm.ServiceInfo;\nimport android.hardware.camera2.CameraAccessException;",
                "import android.content.pm.ServiceInfo;\nimport android.media.MediaPlayer;\nimport android.hardware.camera2.CameraAccessException;");
        s = replaceOnce(s,
                "    private static final String TAG = \"DualCamService\";\n",
                "    private static final String TAG = \"DualCamService\";\n\n"
                        + "    /** Intent extra containing a local producer program-video content URI. */\n"
                        + "    public static final String EXTRA_PRODUCER_VIDEO_URI = \"producer_video_uri\";\n");
        s = replaceOnce(s,
                "    private volatile boolean useBlackFrameFallback = false;\n\n"
                        + "    /** The resolved secondary camera ID — stored for use in fallback periodic snapshots. */",
                "    private volatile boolean useBlackFrameFallback = false;\n\n"
                        + "    /** True when the PiP input is occupied by a producer program video instead of a second camera. */\n"
                        + "    private volatile boolean producerVideoMode = false;\n"
                        + "    @Nullable\n"
                        + "    private Uri producerVideoUri;\n"
                        + "    @Nullable\n"
                        + "    private MediaPlayer producerVideoPlayer;\n\n"
                        + "    /** The resolved secondary camera ID — stored for use in fallback periodic snapshots. */");
        s = replaceOnce(s,
                "        capability = new DualCameraCapability(this);\n        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);",
                "        capability = new DualCameraCapability(this);\n        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);\n\n"
                        + "        String savedProducerUri = prefs.sharedPreferences.getString(\"fadcam_producer_video_uri\", \"\");\n"
                        + "        boolean interviewActive = prefs.sharedPreferences.getBoolean(\"fadcam_live_interview_active\", false);\n"
                        + "        if (interviewActive && savedProducerUri != null && !savedProducerUri.isEmpty()) {\n"
                        + "            try { producerVideoUri = Uri.parse(savedProducerUri); producerVideoMode = true; }\n"
                        + "            catch (Exception ignored) { producerVideoUri = null; producerVideoMode = false; }\n"
                        + "        }");
        s = replaceOnce(s,
                "            case Constants.INTENT_ACTION_START_DUAL_RECORDING:\n                handleStartDualRecording();\n                break;",
                "            case Constants.INTENT_ACTION_START_DUAL_RECORDING:\n"
                        + "                String producerUri = intent.getStringExtra(EXTRA_PRODUCER_VIDEO_URI);\n"
                        + "                if (producerUri != null && !producerUri.isEmpty()) {\n"
                        + "                    try { producerVideoUri = Uri.parse(producerUri); producerVideoMode = true; }\n"
                        + "                    catch (Exception e) { producerVideoUri = null; producerVideoMode = false; FLog.e(TAG, \"Invalid producer video URI\", e); }\n"
                        + "                } else if (!prefs.sharedPreferences.getBoolean(\"fadcam_live_interview_active\", false)) {\n"
                        + "                    producerVideoUri = null; producerVideoMode = false;\n"
                        + "                }\n"
                        + "                handleStartDualRecording();\n"
                        + "                break;");
        s = replaceOnce(s,
                "        // ── Load config ───────────────────────────────────────────────\n        config = prefs.getDualCameraConfig();",
                "        if (producerVideoMode) {\n"
                        + "            if (producerVideoUri == null) { broadcastError(\"No producer video selected\"); stopSelf(); return; }\n"
                        + "            useBlackFrameFallback = true;\n"
                        + "            FLog.i(TAG, \"Producer mode: primary camera as live PiP commentator + program video fullscreen\");\n"
                        + "        }\n\n"
                        + "        // ── Load config ───────────────────────────────────────────────\n        config = prefs.getDualCameraConfig();");
        s = replaceOnce(s,
                "            createCaptureSession(\n                    primaryCameraDevice,\n                    recordingPipeline.getPrimaryCameraInputSurface(),\n                    true /* isPrimary */);",
                "            Surface primaryTargetSurface = producerVideoMode\n"
                        + "                    ? recordingPipeline.getSecondaryCameraInputSurface()\n"
                        + "                    : recordingPipeline.getPrimaryCameraInputSurface();\n"
                        + "            createCaptureSession(\n"
                        + "                    primaryCameraDevice,\n"
                        + "                    primaryTargetSurface,\n"
                        + "                    true /* isPrimary */);");
        s = replaceOnce(s,
                "        // Schedule periodic secondary camera snapshots ONLY if not in black frame test mode\n        if (!useBlackFrameFallback) {",
                "        // Producer mode supplies the PiP input continuously from the primary camera.\n        if (!useBlackFrameFallback && !producerVideoMode) {");
        s = replaceOnce(s,
                "            recordingPipeline.startRecording();\n            state = DualCameraState.RECORDING;",
                "            recordingPipeline.startRecording();\n            if (producerVideoMode) startProducerVideoPlayback();\n            state = DualCameraState.RECORDING;");
        s = replaceOnce(s,
                "        isCapturingSnapshot = false;\n\n        // Stop pipeline first (drains encoders, finalises muxer)",
                "        isCapturingSnapshot = false;\n        releaseProducerVideoPlayer();\n\n        // Stop pipeline first (drains encoders, finalises muxer)");
        s = replaceOnce(s,
                "        // Stop fallback snapshot loop\n        fallbackMode = false;\n        isCapturingSnapshot = false;",
                "        // Stop fallback snapshot loop\n        fallbackMode = false;\n        isCapturingSnapshot = false;\n        releaseProducerVideoPlayer();");

        String marker = "    // ════════════════════════════════════════════════════════════════════\n    // HELPERS\n    // ════════════════════════════════════════════════════════════════════\n";
        String methods = """
    /** Starts local program video on the renderer's primary OES input. */
    private void startProducerVideoPlayback() {
        if (!producerVideoMode || producerVideoUri == null || recordingPipeline == null) return;
        Surface programSurface = recordingPipeline.getPrimaryCameraInputSurface();
        if (programSurface == null || !programSurface.isValid()) { transitionToError("Producer video surface unavailable"); return; }
        releaseProducerVideoPlayer();
        try {
            MediaPlayer player = new MediaPlayer();
            producerVideoPlayer = player;
            player.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build());
            player.setDataSource(getApplicationContext(), producerVideoUri);
            player.setSurface(programSurface);
            player.setVolume(0f, 0f);
            try { player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING); } catch (IllegalArgumentException ignored) {}
            player.setOnPreparedListener(mp -> {
                if (isStopping || recordingPipeline == null || producerVideoPlayer != mp) { try { mp.release(); } catch (Exception ignored) {} return; }
                try { mp.start(); FLog.i(TAG, "Producer program video playback started: " + producerVideoUri); }
                catch (IllegalStateException e) { FLog.e(TAG, "Failed to start producer program playback", e); transitionToError("Producer video playback failed"); }
            });
            player.setOnCompletionListener(mp -> FLog.i(TAG, "Producer program video ended; holding final frame while commentary continues"));
            player.setOnErrorListener((mp, what, extra) -> { FLog.e(TAG, "Producer video error: what=" + what + " extra=" + extra); transitionToError("Producer video playback failed"); return true; });
            player.prepareAsync();
        } catch (Exception e) { FLog.e(TAG, "Unable to prepare producer program video", e); releaseProducerVideoPlayer(); transitionToError("Unable to load producer video"); }
    }

    private void releaseProducerVideoPlayer() {
        MediaPlayer player = producerVideoPlayer;
        producerVideoPlayer = null;
        if (player != null) { try { player.setSurface(null); } catch (Exception ignored) {} try { player.stop(); } catch (Exception ignored) {} try { player.release(); } catch (Exception ignored) {} }
    }

""";
        s = replaceOnce(s, marker, methods + marker);
        return s;
    }

    private static String replaceOnce(String source, String oldText, String newText) {
        int first = source.indexOf(oldText);
        if (first < 0 || first != source.lastIndexOf(oldText)) {
            throw new IllegalStateException("Producer compositor patch anchor missing or duplicated: " + oldText.substring(0, Math.min(100, oldText.length())));
        }
        return source.substring(0, first) + newText + source.substring(first + oldText.length());
    }
}
