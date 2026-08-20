plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.tianrking.ffmpegsdk.sample"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "io.github.tianrking.ffmpegsdk.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // API 36 is the intentionally validated 2026 Play target; target 37 remains a future matrix.
        disable += "OldTargetApi"
    }
}

dependencies {
    implementation(project(":ffmpeg-sdk-core"))
    implementation(project(":ffmpeg-sdk-android"))
    implementation(project(":ffmpeg-sdk-engine-ffmpegkit"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity)

    // Evaluation runtime only. Production applications should pin a source-built, audited AAR.
    implementation(libs.ffmpegkit.full)
    // The evaluated community POM omits this FFmpegKit runtime dependency.
    implementation(libs.smart.exception)
}
