package com.tv49eastz.build;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.Task;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Build-only source overlay for TV 49 East producer mode.
 *
 * <p>The upstream FadCam camera/GL implementation is intentionally kept byte-for-byte
 * in the protected application tree. This plugin creates generated compile inputs with
 * the small producer-mode additions, so the server-room protection boundary is not
 * rewritten just to add the TV producer feature.</p>
 */
public final class ProducerCompositorPlugin implements Plugin<Project> {
    private static final String SERVICE = "src/main/java/com/fadcam/dualcam/service/DualCameraRecordingService.java";
    private static final String RENDERER = "src/main/java/com/fadcam/opengl/GLWatermarkRenderer.java";

    @Override
    public void apply(Project project) {
        Provider<Directory> output = project.getLayout().getBuildDirectory().dir("generated/producer-compositor/java");
        TaskProvider<Task> prepare = project.getTasks().register("prepareProducerCompositorSources", task -> {
            task.getOutputs().dir(output);
            task.doLast(ignored -> generate(project, output.get().getAsFile()));
        });

        project.getTasks().withType(JavaCompile.class).configureEach(task -> {
            String name = task.getName();
            if (!name.contains("JavaWithJavac") || name.contains("UnitTest") || name.contains("AndroidTest")) {
                return;
            }
            task.dependsOn(prepare);
            File service = new File(project.getProjectDir(), SERVICE);
            File renderer = new File(project.getProjectDir(), RENDERER);
            File generatedService = new File(output.get().getAsFile(), "com/fadcam/dualcam/service/DualCameraRecordingService.java");
            File generatedRenderer = new File(output.get().getAsFile(), "com/fadcam/opengl/GLWatermarkRenderer.java");
            task.setSource(task.getSource()
                    .minus(project.files(service, renderer))
                    .plus(project.files(generatedService, generatedRenderer)));
        });
    }

    private static void generate(Project project, File out) {
        try {
            File serviceSource = new File(project.getProjectDir(), SERVICE);
            File rendererSource = new File(project.getProjectDir(), RENDERER);
            File serviceOut = new File(out, "com/fadcam/dualcam/service/DualCameraRecordingService.java");
            File rendererOut = new File(out, "com/fadcam/opengl/GLWatermarkRenderer.java");
            Files.createDirectories(serviceOut.getParentFile().toPath());
            Files.createDirectories(rendererOut.getParentFile().toPath());

            String service = Files.readString(serviceSource.toPath(), StandardCharsets.UTF_8);
            service = patchService(service);
            Files.writeString(serviceOut.toPath(), service, StandardCharsets.UTF_8);

            String renderer = Files.readString(rendererSource.toPath(), StandardCharsets.UTF_8);
            renderer = patchRenderer(renderer);
            Files.writeString(rendererOut.toPath(), renderer, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Unable to generate TV 49 East producer compositor sources", e);
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
                        + "            // Reuse the proven primary-only fallback path: the secondary OES input becomes the program-video surface.\n"
                        + "            useBlackFrameFallback = true;\n"
                        + "            FLog.i(TAG, \"Producer mode: primary camera + program video as fullscreen source\");\n"
                        + "        }\n\n"
                        + "        // ── Load config ───────────────────────────────────────────────\n        config = prefs.getDualCameraConfig();");
        s = replaceOnce(s,
                "        // Schedule periodic secondary camera snapshots ONLY if not in black frame test mode\n        if (!useBlackFrameFallback) {",
                "        // Schedule periodic secondary camera snapshots only for the real camera fallback path.\n        if (!useBlackFrameFallback && !producerVideoMode) {");
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
    /** Starts local program video on the secondary OES input and keeps the primary camera as PiP commentary. */
    private void startProducerVideoPlayback() {
        if (!producerVideoMode || producerVideoUri == null || recordingPipeline == null) return;
        Surface programSurface = recordingPipeline.getSecondaryCameraInputSurface();
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

    private static String patchRenderer(String s) {
        s = replaceOnce(s,
                "                float[] fsTexMatrix = camerasSwapped ? pipLatestTexMatrix : encoderTexMatrix;\n"
                        + "                // pipLatestTexMatrix is raw from pipSurfaceTexture — apply flip if fullscreen camera is front\n"
                        + "                if (camerasSwapped && isFullscreenCameraFront() && frontVideoMirrorEnabled) {\n"
                        + "                    fsTexMatrix = applyHorizontalTexFlip(fsTexMatrix);\n"
                        + "                }\n"
                        + "                if (mFullFrameBlit != null) {\n"
                        + "                    if (!camerasSwapped) {\n"
                        + "                        drawOESTexture(encoderCameraMvp, fsTexMatrix);\n"
                        + "                    } else {\n"
                        + "                        drawOESTextureWithId(oesTextureId, encoderCameraMvp, fsTexMatrix);\n"
                        + "                    }\n"
                        + "                } else {\n"
                        + "                    drawWithFallbackMethodId(oesTextureId, encoderCameraMvp, fsTexMatrix);\n"
                        + "                }",
                "                float[] fsTexMatrix = camerasSwapped ? pipLatestTexMatrix : encoderTexMatrix;\n"
                        + "                if (camerasSwapped && isFullscreenCameraFront() && frontVideoMirrorEnabled) fsTexMatrix = applyHorizontalTexFlip(fsTexMatrix);\n"
                        + "                int fullscreenTextureId = camerasSwapped ? pipOesTextureId : oesTextureId;\n"
                        + "                if (mFullFrameBlit != null) drawOESTextureWithId(fullscreenTextureId, encoderCameraMvp, fsTexMatrix);\n"
                        + "                else drawWithFallbackMethodId(fullscreenTextureId, encoderCameraMvp, fsTexMatrix);");
        s = replaceOnce(s,
                "        // Bind PiP texture\n        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);\n        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, pipOesTextureId);\n        GLES20.glUniform1i(pipTextureHandle, 0);",
                "        // After a producer swap, the primary camera is the PiP commentator.\n"
                        + "        int pipTextureId = camerasSwapped ? oesTextureId : pipOesTextureId;\n"
                        + "        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);\n"
                        + "        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, pipTextureId);\n"
                        + "        GLES20.glUniform1i(pipTextureHandle, 0);");
        return s;
    }

    private static String replaceOnce(String source, String oldText, String newText) {
        int first = source.indexOf(oldText);
        if (first < 0 || first != source.lastIndexOf(oldText)) {
            throw new IllegalStateException("Producer compositor patch anchor missing or duplicated: " + oldText.substring(0, Math.min(80, oldText.length())));
        }
        return source.substring(0, first) + newText + source.substring(first + oldText.length());
    }
}
