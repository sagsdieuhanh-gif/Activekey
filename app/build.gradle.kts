import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Base64

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
        // V14 CENTER-FIRST: near-road lane marking priority, central lead focus, low-noise side cut-in watch, long-range fusion and licensed access.
        versionCode = 1400
        versionName = "14.0.0"
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

    // Shared on-device inference runtime for both internal core packages.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
    // Bundled on-device OCR used only when the user enables BIỂN BÁO AI.
    implementation("com.google.mlkit:text-recognition:16.0.1")
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
            return md.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

        fun gitBlobSha1(file: File): String {
            val md = MessageDigest.getInstance("SHA-1")
            md.update("blob ${file.length()}\u0000".toByteArray(Charsets.UTF_8))
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    md.update(buffer, 0, n)
                }
            }
            return md.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

        fun reveal(encoded: String): String = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)

        fun download(url: String, target: File) {
            val temp = File(target.parentFile, target.name + ".download")
            temp.delete()
            val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 120_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "TRUNGKIEN-V14-Core-Build/14.0.0")
                setRequestProperty("Accept", "application/octet-stream")
            }
            try {
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw org.gradle.api.GradleException("HTTP ${connection.responseCode} khi tải ${target.name}")
                }
                connection.inputStream.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
                }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            } finally {
                connection.disconnect()
            }
        }

        val visionExpectedSize = 35_858_002L
        val visionExpectedSha = "c5c2d13e59ae883e6af3b45daea64af4833a4951c92d116ec270d9ddbe998063"
        fun visionValid(file: File) = file.exists() && file.length() == visionExpectedSize && sha256(file) == visionExpectedSha

        val laneExpectedGitSha = "cb3959dc88e262beb3c23d44e3388c7c29eaa2eb"
        fun laneValid(file: File) = file.exists() && file.length() > 20_000_000L && gitBlobSha1(file) == laneExpectedGitSha

        val manualVision = manualAssets.resolve("road_core.dat")
        if (!visionValid(visionOut)) {
            visionOut.delete()
            if (visionValid(manualVision)) {
                manualVision.copyTo(visionOut, overwrite = true)
            } else {
                logger.lifecycle("TRUNGKIEN V14: preparing Road Core package...")
                download(
                    reveal("aHR0cHM6Ly9naXRodWIuY29tL01lZ3ZpaS1CYXNlRGV0ZWN0aW9uL1lPTE9YL3JlbGVhc2VzL2Rvd25sb2FkLzAuMS4xcmMwL3lvbG94X3Mub25ueA=="),
                    visionOut,
                )
            }
        }
        if (!visionValid(visionOut)) {
            visionOut.delete()
            throw org.gradle.api.GradleException(
                "Road Core package invalid. Place the verified package at app/offline_models/road_core.dat and rebuild."
            )
        }
        logger.lifecycle("TRUNGKIEN V14: Road Core ready (${visionOut.length()} bytes).")

        val manualLane = manualAssets.resolve("lane_core.dat")
        if (!laneValid(laneOut)) {
            laneOut.delete()
            if (laneValid(manualLane)) {
                manualLane.copyTo(laneOut, overwrite = true)
            } else {
                logger.lifecycle("TRUNGKIEN V14: preparing Lane Core package...")
                download(
                    reveal("aHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL01UYW1tdmVlL29wZW5waWxvdC1zdXBlcmNvbWJvLW1vZGVsLzEzNDkwOGU1NTQ5MzAyMDc1MGY4NDk2MTI3YmFmZjVhYjNhMTkyYWQvc3VwZXJjb21iby5vbm54"),
                    laneOut,
                )
            }
        }
        if (!laneValid(laneOut)) {
            laneOut.delete()
            throw org.gradle.api.GradleException(
                "Lane Core package invalid. Place the verified package at app/offline_models/lane_core.dat and rebuild."
            )
        }
        logger.lifecycle("TRUNGKIEN V14: Lane Core ready (${laneOut.length() / 1024 / 1024} MiB).")
        logger.lifecycle("TRUNGKIEN V14: core packages will be bundled into the APK; runtime download is disabled.")
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareCorePackages)
}
