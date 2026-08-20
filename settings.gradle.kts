pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ffmpeg-android"

include(
    ":ffmpeg-sdk-core",
    ":ffmpeg-sdk-android",
    ":ffmpeg-sdk-engine-ffmpegkit",
    ":sample-app",
)
