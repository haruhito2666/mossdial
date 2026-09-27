plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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

    ndkVersion = "27.0.12077973"

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
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

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
        }
    }
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
