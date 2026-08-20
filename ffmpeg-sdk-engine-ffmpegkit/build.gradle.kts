plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "io.github.tianrking.ffmpegsdk.engine.ffmpegkit"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

}

dependencies {
    api(project(":ffmpeg-sdk-core"))
    implementation(libs.kotlinx.coroutines.android)

    // Deliberately compile-only. Applications must choose and audit their own native runtime.
    compileOnly(libs.ffmpegkit.full)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "ffmpeg-sdk-engine-ffmpegkit"
                pom {
                    name.set("FFmpeg Android FFmpegKit Engine")
                    description.set("Replaceable FFmpegKit-compatible engine adapter; native runtime not bundled.")
                    url.set("https://github.com/tianrking/android-ffmpeg-sdk")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("tianrking")
                            name.set("tianrking")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/tianrking/android-ffmpeg-sdk.git")
                        developerConnection.set("scm:git:ssh://github.com/tianrking/android-ffmpeg-sdk.git")
                        url.set("https://github.com/tianrking/android-ffmpeg-sdk")
                    }
                }
            }
        }
    }
}
