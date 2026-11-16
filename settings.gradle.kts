pluginManagement {
    repositories {
        // Android Gradle Plugin and Android/Google build plugins.
        maven {
            name = "GoogleDirectPlugins"
            url = uri("https://dl.google.com/dl/android/maven2/")
            content {
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("androidx(\\..*)?")
                includeGroupByRegex("com\\.google\\.android(\\..*)?")
            }
        }
        // Kotlin and other JVM/Gradle plugins.
        maven {
            name = "MavenCentralDirectPlugins"
            url = uri("https://repo.maven.apache.org/maven2/")
        }
        gradlePluginPortal()

        // Code On the Go / AndroidIDE cache is a LAST fallback only.
        // Keeping it last prevents incomplete cached modules from shadowing
        // complete artifacts available from the Internet repositories above.
        val androidIdeLocalRepo = file("/data/data/com.itsaky.androidide/files/home/maven/localMvnRepository")
        if (androidIdeLocalRepo.exists()) {
            maven {
                name = "AndroidIdeLocalFallbackPlugins"
                url = uri(androidIdeLocalRepo)
            }
        }
    }
}

// Code On The Go: expose user-provided AARs as a local Maven repository instead
// of Gradle's `files(...)` dependency variant. This avoids a fragile IDE model
// extraction path ("variant=local file") without downloading any AI models.
// The original AAR files remain in app/libs; only derived copies go to .gradle/.
data class TransVibeLocalAar(val artifact: String, val version: String, val fileName: String)

val transVibeLocalAarRepository = file(".gradle/transvibe-local-aars")
val transVibeLocalAars = listOf(
    TransVibeLocalAar("sherpa-onnx", "1.13.5", "sherpa-onnx-1.13.5.aar"),
    TransVibeLocalAar("onnxruntime-android", "1.27.0", "onnxruntime-android-1.27.0.aar"),
    TransVibeLocalAar("ffmpeg-kit", "8.1.9", "ffmpeg-kit-free81-8.1.9-arm64-v8a-x86_64.aar"),
    TransVibeLocalAar("ffmpeg-kit", "6.0-2", "ffmpeg-kit-min-gpl-6.0-2.aar")
)

transVibeLocalAars.forEach { entry ->
    val source = file("app/libs/${entry.fileName}")
    if (source.isFile) {
        try {
            java.util.zip.ZipFile(source).use { archive ->
                check(archive.getEntry("AndroidManifest.xml") != null) {
                    "Missing AndroidManifest.xml inside ${source.name}"
                }
            }
        } catch (cause: Exception) {
            throw GradleException(
                "Invalid local AAR: ${source.path}. Restore a complete Android AAR, not a renamed ZIP or interrupted download.",
                cause
            )
        }

        val artifactDir = transVibeLocalAarRepository.resolve(
            "local/transvibe/${entry.artifact}/${entry.version}"
        )
        artifactDir.mkdirs()
        val target = artifactDir.resolve("${entry.artifact}-${entry.version}.aar")
        if (!target.isFile || target.length() != source.length() ||
            target.lastModified() != source.lastModified()) {
            source.copyTo(target, overwrite = true)
            target.setLastModified(source.lastModified())
        }
        val pom = artifactDir.resolve("${entry.artifact}-${entry.version}.pom")
        val metadata = """<project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <groupId>local.transvibe</groupId>
            <artifactId>${entry.artifact}</artifactId>
            <version>${entry.version}</version>
            <packaging>aar</packaging>
        </project>""".trimIndent()
        if (!pom.isFile || pom.readText() != metadata) pom.writeText(metadata)
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Resolve generated offline coordinates for app/libs/*.aar FIRST.
        maven {
            name = "TransVibeLocalAars"
            url = uri(transVibeLocalAarRepository)
            content { includeGroup("local.transvibe") }
            metadataSources { mavenPom(); artifact() }
        }

        // Artifacts maintained in Google's Android Maven repository.
        // IMPORTANT: do NOT route all com.google.* here: Guava and many JVM
        // libraries live in Maven Central, not Google Maven.
        exclusiveContent {
            forRepository {
                maven {
                    name = "GoogleDirect"
                    url = uri("https://dl.google.com/dl/android/maven2/")
                }
            }
            filter {
                includeGroupByRegex("androidx(\\..*)?")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.firebase(\\..*)?")
                includeGroupByRegex("com\\.google\\.mlkit(\\..*)?")
            }
        }

        // Guava (com.google.guava), Kotlin/Coroutines, JUnit and normal JVM
        // dependencies are resolved here.
        maven {
            name = "MavenCentralDirect"
            url = uri("https://repo.maven.apache.org/maven2/")
            mavenContent { releasesOnly() }
        }

        // sherpa-onnx Android/JNI artifacts are published through JitPack.
        maven {
            name = "JitPackSherpaOnnx"
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.k2-fsa.sherpa-onnx") }
        }

        // Use Code On the Go's preloaded Maven cache only when an artifact is
        // unavailable online. It is intentionally LAST to avoid the incomplete
        // AAR/JAR entries that caused processDebugNavigationResources failures.
        val androidIdeLocalRepo = file("/data/data/com.itsaky.androidide/files/home/maven/localMvnRepository")
        if (androidIdeLocalRepo.exists()) {
            maven {
                name = "AndroidIdeLocalFallback"
                url = uri(androidIdeLocalRepo)
            }
        }
    }
}

rootProject.name = "TransVibe"
include(":app")
