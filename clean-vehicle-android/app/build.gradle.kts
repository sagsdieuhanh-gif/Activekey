plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.trungkien.cleanvehicle"
    compileSdk = 36

    signingConfigs {
        create("release") {
            storeFile =
                file(
                    System.getenv("TK_ADAS_KEYSTORE_FILE")
                        ?: "trungkien-release.jks"
                )

            storePassword =
                System.getenv("TK_ADAS_KEYSTORE_PASSWORD")
                    ?: ""

            keyAlias =
                System.getenv("TK_ADAS_KEY_ALIAS")
                    ?: "trungkienadas"

            keyPassword =
                System.getenv("TK_ADAS_KEY_PASSWORD")
                    ?: ""
        }
    }

    defaultConfig {
        applicationId = "com.trungkien.adas"
        minSdk = 24
        targetSdk = 36
        versionCode = 2400
        versionName = "2.4.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }

        release {
            isMinifyEnabled = false
            signingConfig =
                signingConfigs.getByName(
                    "release"
                )
        }
    }

    compileOptions {
        sourceCompatibility =
            JavaVersion.VERSION_17

        targetCompatibility =
            JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            pickFirsts +=
                setOf(
                    "**/libc++_shared.so"
                )
        }
    }
}

kotlin {
    jvmToolchain(
        17
    )
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-ktx:1.13.0")

    val cameraX = "1.5.3"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
}
