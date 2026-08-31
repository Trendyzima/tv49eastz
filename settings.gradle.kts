pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "FadCam"
include(":app")
include(":tv-receiver")

// Include patched Media3 as composite build for live streaming support.
val media3PatchedPath = if (file("local.properties").exists()) {
    val props = java.util.Properties()
    file("local.properties").inputStream().use { props.load(it) }
    props.getProperty("media3.patched.path", "/tmp/media3-patched")
} else {
    "/tmp/media3-patched"
}

if (file(media3PatchedPath).exists()) {
    includeBuild(media3PatchedPath) {
        dependencySubstitution {
            substitute(module("androidx.media3:media3-muxer")).using(project(":lib-muxer"))
            substitute(module("androidx.media3:media3-common")).using(project(":lib-common"))
            substitute(module("androidx.media3:media3-container")).using(project(":lib-container"))
        }
    }
} else {
    logger.warn("Patched Media3 not found at: $media3PatchedPath")
}
