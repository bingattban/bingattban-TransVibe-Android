
import java.io.File
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Local Android libraries. Keep the native Whisper bridge prebuilt; no CMake build.
// Prefer the existing Free81 build; accept the locally supplied 6.0-2 min-gpl
// artifact on devices where that is the user's available FFmpegKit binary.
// Fail with a readable error if neither one is present; never package a placeholder.
val ffmpegAarNames = listOf(
    "ffmpeg-kit-free81-8.1.9-arm64-v8a-x86_64.aar",
    "ffmpeg-kit-min-gpl-6.0-2.aar"
)
val ffmpegAar = ffmpegAarNames.map { file("libs/$it") }.firstOrNull { it.isFile }
    ?: file("libs/${ffmpegAarNames.first()}")
val ffmpegVersion = if (ffmpegAar.name.contains("min-gpl-6.0-2")) "6.0-2" else "8.1.9"
val sherpaAar = file("libs/sherpa-onnx-1.13.5.aar")
val onnxAar = file("libs/onnxruntime-android-1.27.0.aar")

fun aarContainsArm64Library(aar: File, library: String): Boolean =
    aar.isFile && ZipFile(aar).use { archive ->
        archive.getEntry("jni/arm64-v8a/$library") != null
    }

// Only include the separate ONNX Runtime when Sherpa does not already ship it.
// Its native compatibility with Sherpa must still be verified at runtime.
val sherpaBundlesOnnxRuntime = aarContainsArm64Library(sherpaAar, "libonnxruntime.so")
val useStandaloneOnnxRuntime = !sherpaBundlesOnnxRuntime

// The release signing key is injected only into the trusted GitHub Actions
// release job. Do not commit keystores or passwords to source control.
val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }


android {
    namespace = "com.transvibe.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.transvibe.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
        buildConfigField("String", "FFMPEG_KIT_VERSION", "\"$ffmpegVersion\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("transvibeRelease") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
                    ?: throw GradleException("Missing ANDROID_KEYSTORE_PASSWORD for release signing")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")?.takeIf { it.isNotBlank() }
                    ?: throw GradleException("Missing ANDROID_KEY_ALIAS for release signing")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
                    ?: throw GradleException("Missing ANDROID_KEY_PASSWORD for release signing")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Enable Code On the Go/AndroidIDE debug LogSender integration.
            // Startup is delayed until MainActivity is resumed; the injected
            // installer provider is removed in src/debug/AndroidManifest.xml.
            isDebuggable = true
        }

        release {
            // On PRs, build an unsigned release for R8 validation without secrets.
            // Only the trusted post-main-CI workflow receives this signing config.
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("transvibeRelease")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.material3)
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    // Material Components for XML themes.
    implementation("com.google.android.material:material:1.12.0")

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.common)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.mlkit.translate)

    // Resolve the user's existing AARs via the offline Maven metadata staged
    // by settings.gradle.kts. Avoid `files(.aar)` IDE extraction failures.
    implementation("local.transvibe:sherpa-onnx:1.13.5")

    // Include standalone ONNX Runtime ONLY if Sherpa lacks its native runtime.
    if (useStandaloneOnnxRuntime) {
        implementation("local.transvibe:onnxruntime-android:1.27.0")
    }

    // FFmpegKit remains a MediaCodec fallback, without changing its binaries.
    implementation("local.transvibe:ffmpeg-kit:$ffmpegVersion")

    // Prefer the user's local JAR for offline Code On The Go builds.
    val localSmartException = file("libs/smart-exception-java-0.2.1.jar")
    if (localSmartException.isFile) {
        implementation(files(localSmartException))
    } else {
        implementation("com.arthenica:smart-exception-java:0.2.1")
    }

    // FFmpegKit's local AAR does not expose its Maven transitive dependencies.
    // Java and common are separate authentic JARs. PackageLoader is in common.
    val localSmartExceptionCommon = file("libs/smart-exception-common-0.2.1.jar")
    if (localSmartExceptionCommon.isFile) {
        implementation(files(localSmartExceptionCommon))
    } else {
        implementation("com.arthenica:smart-exception-common:0.2.1")
    }

    implementation(libs.navigation.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.11.4")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.11.4")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Verify the prebuilt native JNI artifact before packaging the APK.
val verifyPrebuiltTransVibeNative = tasks.register("verifyPrebuiltTransVibeNative") {
    group = "verification"
    description = "Checks the prebuilt Whisper JNI library (ELF64 AArch64)."

    doLast {
        val nativeFile = file("src/main/jniLibs/arm64-v8a/libtransvibe_native.so")

        check(nativeFile.isFile) {
            "Missing ${nativeFile.path}. Add the real arm64-v8a .so from the external build."
        }

        check(nativeFile.length() >= 4096L) {
            "Invalid native library (too small): ${nativeFile.path}"
        }

        val header = ByteArray(20)

        nativeFile.inputStream().use { input ->
            check(input.read(header) == header.size) {
                "Cannot read ELF header: ${nativeFile.path}"
            }
        }

        check(
            header[0] == 0x7f.toByte() &&
            header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() &&
            header[3] == 'F'.code.toByte() &&
            header[4] == 2.toByte() &&
            header[5] == 1.toByte() &&
            header[16] == 3.toByte() &&
            header[17] == 0.toByte() &&
            (header[18].toInt() and 0xff) == 183 &&
            header[19] == 0.toByte()
        ) {
            "Wrong native binary: expected ELF64 little-endian AArch64 ET_DYN (.so)."
        }

        logger.lifecycle(
            "TransVibe JNI ready: ${nativeFile.name} (${nativeFile.length()} bytes)"
        )
    }
}

val verifyLocalAsrLibraries = tasks.register("verifyLocalAsrLibraries") {
    group = "verification"
    description = "Checks local FFmpegKit, Sherpa and ONNX Runtime AARs."

    doLast {
        val commonJar = file("libs/smart-exception-common-0.2.1.jar")
        check(commonJar.isFile) {
            "FFmpegKit needs app/libs/smart-exception-common-0.2.1.jar (contains PackageLoader)."
        }
        check(ZipFile(commonJar).use { it.getEntry("com/arthenica/smartexception/PackageLoader.class") != null }) {
            "Wrong Smart Exception common JAR: PackageLoader.class is missing."
        }
        check(ffmpegAar.isFile) {
            "Missing FFmpegKit AAR. Place one of ${ffmpegAarNames.joinToString()} in app/libs/"
        }

        check(sherpaAar.isFile) {
            "Missing ${sherpaAar.path}"
        }

        check(aarContainsArm64Library(ffmpegAar, "libffmpegkit.so")) {
            "FFmpegKit AAR does not contain arm64-v8a/libffmpegkit.so"
        }

        check(aarContainsArm64Library(sherpaAar, "libsherpa-onnx-jni.so")) {
            "Sherpa AAR does not contain arm64-v8a/libsherpa-onnx-jni.so"
        }

        if (useStandaloneOnnxRuntime) {
            check(onnxAar.isFile) {
                "Sherpa AAR lacks libonnxruntime.so; missing ${onnxAar.path}"
            }

            check(aarContainsArm64Library(onnxAar, "libonnxruntime.so")) {
                "Standalone ONNX Runtime AAR lacks arm64-v8a/libonnxruntime.so"
            }

            logger.lifecycle(
                "ONNX Runtime: standalone 1.27.0 AAR selected; verify Sherpa ABI compatibility"
            )
        } else {
            logger.lifecycle(
                "ONNX Runtime: bundled with Sherpa; standalone AAR intentionally not linked"
            )
        }

        logger.lifecycle("FFmpegKit: ${ffmpegAar.name}")
        logger.lifecycle("Sherpa-ONNX: ${sherpaAar.name}")
        logger.lifecycle("Smart Exception: Maven Central 0.2.1")
    }
}

tasks.named("preBuild").configure {
    dependsOn(
        verifyPrebuiltTransVibeNative,
        verifyLocalAsrLibraries
    )
}

// Temporary Code On the Go workaround: never restore cached packaged resources.
// The AndroidIDE build previously restored an empty values-night-v8.xml from cache.
// This does not alter source resources or native libraries.
tasks.matching { it.name == "packageDebugResources" }.configureEach {
    outputs.doNotCacheIf("Avoid corrupt packaged Android resource cache on device") { true }
    outputs.upToDateWhen { false }
}
