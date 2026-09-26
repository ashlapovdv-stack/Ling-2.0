import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val prepareLauncherIcon = tasks.register("prepareLauncherIcon") {
    val encodedIcon = layout.projectDirectory.file("src/main/launcher_icon.b64")
    val outputIcon = layout.projectDirectory.file("src/main/res/mipmap-nodpi/ling_launcher_v3.webp")

    inputs.file(encodedIcon)
    outputs.file(outputIcon)

    doLast {
        val outputFile = outputIcon.asFile
        outputFile.parentFile.mkdirs()
        val encoded = encodedIcon.asFile.readText().filterNot { it.isWhitespace() }
        outputFile.writeBytes(Base64.getDecoder().decode(encoded))
    }
}

android {
    namespace = "com.ling20.translator"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.ling20.translator"
        minSdk = 28
        targetSdk = 36
        versionCode = 18
        versionName = "0.1.15.2"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DBUILD_SHARED_LIBS=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_CURL=OFF",
                    "-DLLAMA_OPENSSL=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DGGML_BACKEND_DL=OFF"
                )
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    packaging {
        resources {
            excludes += "DebugProbesKt.bin"
        }
    }

    val stableDebugKeystore = file("ling-debug.keystore")
    signingConfigs {
        getByName("debug") {
            if (stableDebugKeystore.exists()) {
                storeFile = stableDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareLauncherIcon)
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Resources") }.configureEach {
    dependsOn(prepareLauncherIcon)
}

dependencies {
    // Compose 1.9 is kept deliberately for stable compileSdk 36 support.
    // Compose 1.12+ requires compileSdk 37.
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    val cameraX = "1.6.2"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    // Tesseract 5.5.1 wrapper. OCR models are bundled as assets by CI, so
    // recognition remains fully offline at runtime.
    implementation("com.github.adaptech-cz.Tesseract4Android:tesseract4android:4.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
