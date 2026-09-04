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
include(":tv49-observability")
include(":tv49-handoff")

// Include the pinned patched Media3 composite build. The streaming path relies
// on the patched muxer/common/container artifacts, so silently falling back to
// upstream Media3 would produce a binary that is buildable but functionally
// different from the certified system. Fail closed when the required checkout
// is absent.
val media3PatchedPath = if (file("local.properties").exists()) {
    val props = java.util.Properties()
    file("local.properties").inputStream().use { props.load(it) }
    props.getProperty("media3.patched.path", "/tmp/media3-patched")
} else {
    "/tmp/media3-patched"
}

if (!file(media3PatchedPath).isDirectory) {
    throw GradleException(
        "Pinned patched Media3 checkout is required at '$media3PatchedPath'. " +
            "Fetch the certified Media3 revision before building."
    )
}

includeBuild(media3PatchedPath) {
    dependencySubstitution {
        substitute(module("androidx.media3:media3-muxer")).using(project(":lib-muxer"))
        substitute(module("androidx.media3:media3-common")).using(project(":lib-common"))
        substitute(module("androidx.media3:media3-container")).using(project(":lib-container"))
    }
}
