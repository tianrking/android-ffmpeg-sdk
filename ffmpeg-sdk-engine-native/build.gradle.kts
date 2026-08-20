import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "io.github.tianrking.ffmpegsdk.engine.nativeffmpeg"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets {
        getByName("main").apply {
            jniLibs.directories.add(
                rootProject.file("native-runtime/prebuilt/jniLibs").absolutePath,
            )
            assets.directories.add(
                project.layout.buildDirectory
                    .dir("generated/officialFfmpegRuntimeAssets")
                    .get()
                    .asFile
                    .absolutePath,
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

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

val officialRuntimeDirectory = rootProject.layout.projectDirectory.dir("native-runtime/prebuilt")
val officialRuntimeManifest = officialRuntimeDirectory.file("manifest.json")
val officialRuntimeAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
val officialRuntimeLibraries = listOf(
    "libavcodec.so",
    "libavdevice.so",
    "libavfilter.so",
    "libavformat.so",
    "libavutil.so",
    "libswresample.so",
    "libswscale.so",
    "libffmpeg_sdk_cli.so",
    "libffmpeg_sdk_bridge.so",
)

val validateOfficialFfmpegRuntime by tasks.registering {
    val manifest = officialRuntimeManifest
    val lock = rootProject.layout.projectDirectory.file("native-runtime/ffmpeg.lock.json")
    val libraries = officialRuntimeAbis.flatMap { abi ->
        officialRuntimeLibraries.map { library ->
            officialRuntimeDirectory.file("jniLibs/$abi/$library")
        }
    }
    inputs.file(manifest)
    inputs.file(lock)
    inputs.files(libraries)
    doLast {
        check(manifest.asFile.isFile) {
            "Official FFmpeg runtime manifest is missing. Run scripts/build-official-ffmpeg.ps1."
        }
        val missing = libraries.map { it.asFile }.filterNot { it.isFile }
        check(missing.isEmpty()) {
            "Official FFmpeg runtime is incomplete; missing: " +
                missing.joinToString { it.invariantSeparatorsPath }
        }

        fun requireMap(value: Any?, name: String): Map<*, *> =
            value as? Map<*, *> ?: error("$name must be a JSON object")
        fun requireList(value: Any?, name: String): List<*> =
            value as? List<*> ?: error("$name must be a JSON array")
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        val manifestData = requireMap(JsonSlurper().parse(manifest.asFile), "manifest")
        val lockData = requireMap(JsonSlurper().parse(lock.asFile), "lock")
        val lockedUpstream = requireMap(lockData["upstream"], "lock.upstream")
        val lockedSource = requireMap(lockedUpstream["source"], "lock.upstream.source")
        val lockedKey = requireMap(lockedUpstream["releaseKey"], "lock.upstream.releaseKey")
        val lockedToolchain = requireMap(lockData["toolchain"], "lock.toolchain")
        val lockedNdk = requireMap(lockedToolchain["androidNdk"], "lock.toolchain.androidNdk")
        val lockedProfile = requireMap(lockData["profile"], "lock.profile")
        val manifestFfmpeg = requireMap(manifestData["ffmpeg"], "manifest.ffmpeg")
        val manifestToolchain = requireMap(manifestData["toolchain"], "manifest.toolchain")

        check(manifestData["provenance"] == "official-ffmpeg-signed-release") {
            "Runtime provenance is not the signed official FFmpeg release"
        }
        check(manifestData["profile"] == lockedProfile["name"])
        check(manifestData["license"] == lockedProfile["license"])
        check(manifestData["externalLibraries"] == lockedProfile["externalLibraries"])
        check(manifestData["systemLibraries"] == lockedProfile["systemLibraries"])
        check(manifestData["patches"] == lockedProfile["patches"])
        check(manifestData["bridgeSources"] == lockedProfile["bridgeSources"])
        check(requireList(manifestData["abis"], "manifest.abis") == officialRuntimeAbis)
        check(
            requireList(manifestData["libraries"], "manifest.libraries") ==
                officialRuntimeLibraries,
        )
        check(requireList(lockedProfile["abis"], "lock.profile.abis") == officialRuntimeAbis)
        check(
            requireList(lockedProfile["libraries"], "lock.profile.libraries") +
                requireList(lockedProfile["sdkLibraries"], "lock.profile.sdkLibraries") ==
                officialRuntimeLibraries,
        )
        check(manifestFfmpeg["tag"] == lockedUpstream["tag"])
        check(manifestFfmpeg["commit"] == lockedUpstream["commit"])
        check(manifestFfmpeg["sourceUrl"] == lockedSource["url"])
        check(manifestFfmpeg["sourceSha256"] == lockedSource["sha256"])
        check(manifestFfmpeg["sourceDateEpoch"] == lockedSource["sourceDateEpoch"])
        check(manifestFfmpeg["releaseKeyFingerprint"] == lockedKey["fingerprint"])
        check(manifestFfmpeg["signatureVerified"] == true)
        check(manifestToolchain["ndkRevision"] == lockedNdk["revision"])
        check(manifestToolchain["ndkArchiveSha256"] == lockedNdk["sha256"])
        check(manifestToolchain["androidApi"] == lockedToolchain["minSdk"])

        val expectedPaths = officialRuntimeAbis.flatMap { abi ->
            officialRuntimeLibraries.map { library -> "jniLibs/$abi/$library" }
        }.toSet()
        val artifacts = requireList(manifestData["artifacts"], "manifest.artifacts")
            .mapIndexed { index, value -> requireMap(value, "manifest.artifacts[$index]") }
        val artifactPaths = artifacts.map { it["path"] as? String ?: error("Artifact path missing") }
        check(artifactPaths.size == artifactPaths.toSet().size) {
            "Runtime manifest contains duplicate artifact paths"
        }
        check(artifactPaths.toSet() == expectedPaths) {
            "Runtime manifest artifact closure does not match the four ABI runtime"
        }
        artifacts.forEach { artifact ->
            val path = artifact["path"] as String
            val abi = artifact["abi"] as? String ?: error("Artifact ABI missing for $path")
            check(path.startsWith("jniLibs/$abi/")) { "Artifact ABI/path mismatch for $path" }
            val file = officialRuntimeDirectory.file(path).asFile
            val expectedSize = (artifact["sizeBytes"] as? Number)?.toLong()
                ?: error("Artifact size missing for $path")
            val expectedSha = artifact["sha256"] as? String
                ?: error("Artifact SHA-256 missing for $path")
            check(file.length() == expectedSize) { "Artifact size mismatch for $path" }
            check(sha256(file) == expectedSha) { "Artifact SHA-256 mismatch for $path" }
        }
    }
}

val generateOfficialFfmpegRuntimeAssets by tasks.registering(Sync::class) {
    dependsOn(validateOfficialFfmpegRuntime)
    from(officialRuntimeManifest)
    into(layout.buildDirectory.dir("generated/officialFfmpegRuntimeAssets/ffmpeg-sdk"))
}

tasks.named("preBuild").configure {
    dependsOn(generateOfficialFfmpegRuntimeAssets)
}

dependencies {
    api(project(":ffmpeg-sdk-core"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(kotlin("test-junit"))
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "ffmpeg-sdk-engine-native"
                pom {
                    name.set("FFmpeg Android Official Native Engine")
                    description.set("JNI engine built from the signed FFmpeg/FFmpeg official source release.")
                    url.set("https://github.com/tianrking/android-ffmpeg-sdk")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                        license {
                            name.set("GNU Lesser General Public License, version 2.1 or later")
                            url.set("https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html")
                            distribution.set("repo")
                            comments.set("Applies to the bundled dynamically linked FFmpeg runtime.")
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
