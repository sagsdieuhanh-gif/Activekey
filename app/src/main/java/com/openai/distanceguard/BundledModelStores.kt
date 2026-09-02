package com.openai.distanceguard

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * V13 runtime core policy: core packages are bundled into the APK at build time.
 * The app never downloads core packages while it is running.
 */
object BundledModelStores {
    private fun copyAsset(context: Context, assetName: String, destination: File): Boolean = try {
        destination.parentFile?.mkdirs()
        context.assets.open(assetName).use { input ->
            FileOutputStream(destination).use { output -> input.copyTo(output, 256 * 1024) }
        }
        true
    } catch (_: Throwable) {
        false
    }

    fun laneCore(context: Context): Result<File> {
        val out = File(File(context.filesDir, "models").apply { mkdirs() }, LANE_CORE_NAME)
        if (isValidLaneCore(out)) return Result.success(out)
        out.delete()
        if (!copyAsset(context, LANE_CORE_NAME, out) || !isValidLaneCore(out)) {
            out.delete()
            return Result.failure(IllegalStateException("APK thiếu hoặc sai mô-đun Lane Core. Hãy rebuild V13 để đóng model vào APK."))
        }
        return Result.success(out)
    }

    fun roadUsers(context: Context): Result<File> {
        val out = File(File(context.filesDir, "models").apply { mkdirs() }, ROAD_CORE_NAME)
        if (isValidRoadCore(out)) return Result.success(out)
        out.delete()
        if (!copyAsset(context, ROAD_CORE_NAME, out) || !isValidRoadCore(out)) {
            out.delete()
            return Result.failure(IllegalStateException("APK thiếu hoặc sai mô-đun Road Core. Hãy rebuild V13 để đóng model vào APK."))
        }
        return Result.success(out)
    }

    private fun isValidRoadCore(file: File): Boolean {
        if (!file.exists() || file.length() != ROAD_CORE_BYTES) return false
        return runCatching {
            sha256(file) == ROAD_CORE_SHA256
        }.getOrDefault(false)
    }

    private fun isValidLaneCore(file: File): Boolean {
        if (!file.exists() || file.length() < 20_000_000L) return false
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update("blob ${file.length()}\u0000".toByteArray(StandardCharsets.UTF_8))
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) } == LANE_CORE_GIT_BLOB_SHA1
        }.getOrDefault(false)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    const val LANE_CORE_NAME = "lane_core.dat"
    const val LANE_CORE_GIT_BLOB_SHA1 = "cb3959dc88e262beb3c23d44e3388c7c29eaa2eb"

    const val ROAD_CORE_NAME = "road_core.dat"
    const val ROAD_CORE_BYTES = 35_858_002L
    const val ROAD_CORE_SHA256 = "c5c2d13e59ae883e6af3b45daea64af4833a4951c92d116ec270d9ddbe998063"
}
