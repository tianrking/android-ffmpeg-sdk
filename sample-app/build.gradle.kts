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
        versionCode = 2
        versionName = "0.2.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
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
    implementation(project(":ffmpeg-sdk-engine-native"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity)

}
