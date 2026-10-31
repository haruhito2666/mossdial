import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * The tunnel engine is shipped as `libcloudflared.so` rather than as a user-supplied file.
 *
 * Android will only execute a native binary from an app's native library directory; a binary in
 * external storage or downloaded into app data is blocked or unreliable. Packaging cloudflared as a
 * JNI library is what makes "paste a token and press Start" work without ADB, chmod or a file path.
 *
 * The binary is compiled from the pinned Cloudflare source instead of using the official Linux
 * release. A pure-Go build resolves DNS through `/etc/resolv.conf`, which Android does not have, so
 * Cloudflare's API cannot be reached. Building with cgo makes Go call Android's `getaddrinfo`, which
 * is the resolver every other app on the phone uses. The source tarball is checked against a pinned
 * hash before it is compiled, so a substituted download fails the build.
 */
val cloudflaredVersion = "2026.10.6"
val cloudflaredSourceSha256 = "eb4d6b02b017cd3e49676934f14fe3025cebb07f9f272cd60dd790a2d812b90b"
val cloudflaredJniDir = layout.buildDirectory.dir("generated/cloudflared/jniLibs")
val androidNdkVersion = "27.0.12077973"

val isWindows = System.getProperty("os.name").lowercase().contains("win")

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun goExecutable(): File {
    val name = if (isWindows) "go.exe" else "go"
    val fromPath = System.getenv("PATH").orEmpty()
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { File(it, name) }
    val common = listOfNotNull(
        System.getenv("GOROOT")?.let { File(it, "bin/$name") },
        File("/opt/homebrew/bin/$name"),
        File("/usr/local/go/bin/$name")
    )
    return (fromPath + common).firstOrNull { it.isFile && it.canExecute() }
        ?: throw GradleException(
            "Go 1.27 or newer is required to build the bundled cloudflared engine. " +
                "Install it from https://go.dev/dl/ and run the build again."
        )
}

fun androidSdkDirectory(): File {
    val fromExtension = runCatching {
        project.extensions.getByType(com.android.build.gradle.BaseExtension::class.java).sdkDirectory
    }.getOrNull()
    if (fromExtension != null && fromExtension.isDirectory) return fromExtension
    (System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))
        ?.takeIf { it.isNotBlank() }
        ?.let { return File(it) }
    val local = project.rootProject.file("local.properties")
    if (local.isFile) {
        val properties = Properties()
        local.inputStream().use(properties::load)
        properties.getProperty("sdk.dir")?.let { return File(it) }
    }
    throw GradleException("Android SDK not found; set ANDROID_HOME or sdk.dir in local.properties")
}

fun ndkClang(): File {
    val ndk = File(androidSdkDirectory(), "ndk/$androidNdkVersion")
    val host = when {
        System.getProperty("os.name").lowercase().contains("mac") -> {
            val arm = System.getProperty("os.arch").lowercase() in listOf("aarch64", "arm64")
            if (arm && File(ndk, "toolchains/llvm/prebuilt/darwin-arm64").isDirectory) {
                "darwin-arm64"
            } else {
                "darwin-x86_64"
            }
        }
        isWindows -> "windows-x86_64"
        else -> "linux-x86_64"
    }
    val executable = File(
        ndk,
        "toolchains/llvm/prebuilt/$host/bin/" + if (isWindows) "clang.exe" else "clang"
    )
    if (!executable.isFile) {
        throw GradleException("Android NDK $androidNdkVersion is missing $executable; install it with sdkmanager")
    }
    return executable
}

fun androidTarget(abi: String): String = when (abi) {
    "arm64-v8a" -> "aarch64-linux-android26"
    "x86_64" -> "x86_64-linux-android26"
    else -> throw GradleException("No cloudflared build target for $abi")
}

val buildCloudflared = tasks.register("buildCloudflared") {
    group = "build"
    description = "Builds the pinned cloudflared tunnel engine for Android from source."
    inputs.property("cloudflaredVersion", cloudflaredVersion)
    inputs.property("cloudflaredSourceSha256", cloudflaredSourceSha256)
    inputs.property("ndkVersion", androidNdkVersion)
    outputs.dir(cloudflaredJniDir)
    doLast {
        val root = project.layout.buildDirectory.dir("cloudflared").get().asFile
        val archive = File(root, "cloudflared-$cloudflaredVersion.tar.gz")
        val source = File(root, "src/cloudflared-$cloudflaredVersion")
        val goModule = File(source, "go.mod")
        if (!goModule.isFile) {
            root.mkdirs()
            if (!archive.isFile || sha256(archive) != cloudflaredSourceSha256) {
                val temporary = File(root, "${archive.name}.part")
                val connection = URI(
                    "https://github.com/cloudflare/cloudflared/archive/refs/tags/" +
                        "$cloudflaredVersion.tar.gz"
                ).toURL().openConnection().apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                    setRequestProperty("User-Agent", "mossdial-build")
                }
                try {
                    connection.getInputStream().use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output) }
                    }
                    val actualSha = sha256(temporary)
                    if (actualSha != cloudflaredSourceSha256) {
                        throw GradleException(
                            "cloudflared source checksum mismatch: expected " +
                                "$cloudflaredSourceSha256, got $actualSha"
                        )
                    }
                    if (!temporary.renameTo(archive)) {
                        temporary.copyTo(archive, overwrite = true)
                        temporary.delete()
                    }
                } finally {
                    temporary.delete()
                }
            }
            File(root, "src").deleteRecursively()
            project.copy {
                from(project.tarTree(project.resources.gzip(archive)))
                into(File(root, "src"))
            }
        }
        if (!goModule.isFile) {
            throw GradleException("cloudflared sources were not extracted to $source")
        }

        val go = goExecutable()
        val clang = ndkClang()
        listOf(
            "arm64-v8a" to "arm64",
            "x86_64" to "amd64"
        ).forEach { (abi, goArch) ->
            val target = androidTarget(abi)
            val output = File(cloudflaredJniDir.get().asFile, "$abi/libcloudflared.so")
            output.parentFile.mkdirs()
            output.delete()
            logger.lifecycle("Building cloudflared for $abi with $goArch")
            val process = ProcessBuilder(
                go.absolutePath,
                "build",
                "-trimpath",
                "-buildvcs=false",
                "-ldflags",
                "-s -w",
                "-o",
                output.absolutePath,
                "./cmd/cloudflared"
            ).directory(source)
                .redirectErrorStream(true)
                .apply {
                    environment().apply {
                        put("GOOS", "android")
                        put("GOARCH", goArch)
                        put("CGO_ENABLED", "1")
                        put("CC", clang.absolutePath)
                        put("CGO_CFLAGS", "--target=$target -O2 -fPIC")
                        // Android 15 devices may use 16KB pages; the default 4KB alignment
                        // would make the executable fail to load on those devices.
                        put("CGO_LDFLAGS", "--target=$target -Wl,-z,max-page-size=16384")
                        put("GOTOOLCHAIN", "auto")
                    }
                }
                .start()
            val log = process.inputStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            if (exit != 0 || !output.isFile) {
                throw GradleException("cloudflared build failed for $abi (exit $exit)\n$log")
            }
            output.setExecutable(true, false)
        }
    }
}

android {
    namespace = "com.mossdial"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mossdial"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The AI runtimes are the reason for the ABI list: llama.cpp is compiled per ABI
        // from src/main/cpp, and the ONNX Runtime AAR ships arm64-v8a, armeabi-v7a, x86
        // and x86_64. Adding an ABI here also needs a matching check in the CMake build.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared"
                )
                cppFlags += "-std=c++17"
            }
        }
    }

    ndkVersion = androidNdkVersion

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val ksPath = System.getenv("MOSSDIAL_KEYSTORE_FILE")
                ?: (project.rootProject.file("keystore/release.keystore").takeIf { it.exists() }?.absolutePath)
            val ksPass = System.getenv("MOSSDIAL_KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("MOSSDIAL_KEY_ALIAS")
            val keyPass = System.getenv("MOSSDIAL_KEY_PASSWORD")
            if (ksPath != null && ksPass != null && keyAlias != null && keyPass != null) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(ksPath)
                    storePassword = ksPass
                    this.keyAlias = keyAlias
                    this.keyPassword = keyPass
                }
            }
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // FlowRow is how the button and chip rows wrap instead of overflowing on a narrow screen.
        // It is still marked experimental, so the whole module opts in rather than every call site
        // carrying its own annotation.
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The tunnel binary is generated by buildCloudflared. It is packaged as a JNI library so
    // Android extracts it to the executable native-library directory at install time.
    sourceSets.getByName("main").jniLibs.srcDir(cloudflaredJniDir)

    testOptions {
        // TunnelManager only touches android.util.Log, so the default stubs are enough to
        // exercise the real process lifecycle from JVM tests. No AndroidX test library needed.
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // The ONNX Runtime AAR ships its .so files compressed, so they have to be
            // extracted at install time instead of being mapped straight out of the APK.
            useLegacyPackaging = true
            // cloudflared is a Go executable, not an ELF shared library; stripping it would
            // either fail or damage the binary the tunnel process runs.
            keepDebugSymbols += "**/libcloudflared.so"
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(buildCloudflared)
}

dependencies {
    // The only two third-party runtime exceptions this project is allowed to ship.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.15.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.21")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
