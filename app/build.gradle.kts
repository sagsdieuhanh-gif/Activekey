import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
}

val generatedCoreAssets = layout.projectDirectory.dir("src/main/assets")

android {
    namespace = "com.openai.distanceguard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.trungkien.distanceguard.v1tracking"
        minSdk = 24
        targetSdk = 36
        ndk {
            abiFilters += "arm64-v8a"
        }
        versionCode = 1572
        versionName = "15.7.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            pickFirsts += setOf("**/libc++_shared.so")
        }
    }
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

val prepareCorePackages = tasks.register("prepareCorePackages") {
    val outDir = generatedCoreAssets.asFile
    val visionOut = outDir.resolve("road_core.dat")
    val laneOut = outDir.resolve("lane_core.dat")
    outputs.files(visionOut, laneOut)

    doLast {
        outDir.mkdirs()
        val manualAssets = project.file("offline_models")

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    md.update(buffer, 0, n)
                }
            }
            return md.digest().joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
        }

        fun download(url: String, target: File) {
            val temp = File(target.parentFile, target.name + ".download")
            temp.delete()
            val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 180_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "TrungKien-V15.7C-PicoDet/15.7.2")
                setRequestProperty("Accept", "application/octet-stream")
            }
            try {
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw org.gradle.api.GradleException(
                        "HTTP ${connection.responseCode} khi tải ${target.name}"
                    )
                }
                connection.inputStream.use { input ->
                    temp.outputStream().use { output ->
                        input.copyTo(output, 256 * 1024)
                    }
                }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            } finally {
                connection.disconnect()
            }
        }

        fun picoDetValid(file: File): Boolean =
            file.exists() && file.length() in 1_000_000L..80_000_000L

        val manualVision = manualAssets.resolve("road_core.dat")

        // Never reuse generated road_core.dat from an older YOLOX build.
        visionOut.delete()

        if (picoDetValid(manualVision)) {
            manualVision.copyTo(visionOut, overwrite = true)
        } else {
            logger.lifecycle("TRUNGKIEN V15.7B: downloading official PicoDet-M416...")
            download(
                "https://paddledet.bj.bcebos.com/deploy/third_engine/picodet_m_416_lcnet_postprocessed.onnx",
                visionOut
            )
        }

        if (!picoDetValid(visionOut)) {
            visionOut.delete()
            throw org.gradle.api.GradleException("PicoDet-M416 Road Core invalid.")
        }

        logger.lifecycle(
            "TRUNGKIEN V15.7B: PicoDet Road Core ready (${visionOut.length()} bytes, SHA256 ${sha256(visionOut)})"
        )

        val laneExpectedSize = 178_076_232L
        val laneExpectedSha = "3b86cf67c0de36af8e8e793317c3475978ecb4c6395c4292d0bd51d3ada53491"

        fun laneValid(file: File): Boolean =
            file.exists() &&
                file.length() == laneExpectedSize &&
                sha256(file) == laneExpectedSha

        val manualLane = manualAssets.resolve("lane_core.dat")

        if (!laneValid(laneOut)) {
            laneOut.delete()
            if (laneValid(manualLane)) {
                manualLane.copyTo(laneOut, overwrite = true)
            } else {
                throw org.gradle.api.GradleException(
                    "Dedicated Lane Core missing. GitHub Actions must stage UFLD CULane."
                )
            }
        }

        if (!laneValid(laneOut)) {
            laneOut.delete()
            throw org.gradle.api.GradleException("Lane Core invalid.")
        }

        logger.lifecycle(
            "TRUNGKIEN V15.7B: Lane Core ready (${laneOut.length() / 1024 / 1024} MiB)."
        )
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareCorePackages)
}
