plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
    `maven-publish`
}

kotlin {
    jvmToolchain(17)
}

java {
    withSourcesJar()
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test-junit"))
}

tasks.test {
    testLogging {
        events("passed", "skipped", "failed")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "ffmpeg-sdk-core"
            pom {
                name.set("FFmpeg Android Core")
                description.set("Typed, engine-neutral media job planning and execution for FFmpeg Android.")
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
