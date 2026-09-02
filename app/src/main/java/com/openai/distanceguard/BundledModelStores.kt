package com.openai.distanceguard

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * V15.2.1: both AI models are bundled inside the APK.
 *
 * Lane Core is pinned by exact byte size + SHA-256.
 * This replaces the stale Git-blob hash check that caused V15.2
 * to reject the correct bundled UFLD model at runtime.
 */
object BundledModelStores {
    private fun copyAsset(context: Context, assetName: String, destination: File): Boolean = try {
        destination.parentFile?.mkdirs()
        context.assets.open(assetName).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output, 256 * 1024)
                output.fd.sync()
            }
        }
        true
    } catch (_: Throwable) {
        false
    }

    fun laneCore(context: Context): Result<File> {
        val out = File(File(context.filesDir, "models").apply { mkdirs() }, LANE_CORE_NAME)
        if (isValidLaneCore(out)) return Result.success(out)

        repeat(2) {
            out.delete()
            if (copyAsset(context, LANE_CORE_NAME, out) && isValidLaneCore(out)) {
                return Result.success(out)
            }
        }

        out.delete()
        return Result.failure(
            IllegalStateException(
                "LANE CORE không thể xác thực sau khi copy từ APK. " +
                    "Cần kiểm tra bộ nhớ máy hoặc file APK."
            )
        )
    }

    fun roadUsers(context: Context): Result<File> {
        val out = File(File(context.filesDir, "models").apply { mkdirs() }, ROAD_CORE_NAME)
        if (isValidRoadCore(out)) return Result.success(out)

        repeat(2) {
            out.delete()
            if (copyAsset(context, ROAD_CORE_NAME, out) && isValidRoadCore(out)) {
                return Result.success(out)
            }
        }

        out.delete()
        return Result.failure(
            IllegalStateException("ROAD CORE không thể xác thực sau khi copy từ APK.")
        )
    }

    private fun isValidRoadCore(file: File): Boolean {
        if (!file.exists() || file.length() != ROAD_CORE_BYTES) return false
        return runCatching { sha256(file) == ROAD_CORE_SHA256 }.getOrDefault(false)
    }

    private fun isValidLaneCore(file: File): Boolean {
        if (!file.exists() || file.length() != LANE_CORE_BYTES) return false
        return runCatching { sha256(file) == LANE_CORE_SHA256 }.getOrDefault(false)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    const val LANE_CORE_NAME = "lane_core.dat"
    const val LANE_CORE_BYTES = 178_076_232L
    const val LANE_CORE_SHA256 =
        "3b86cf67c0de36af8e8e793317c3475978ecb4c6395c4292d0bd51d3ada53491"

    const val ROAD_CORE_NAME = "road_core.dat"
    const val ROAD_CORE_BYTES = 35_858_002L
    const val ROAD_CORE_SHA256 =
        "c5c2d13e59ae883e6af3b45daea64af4833a4951c92d116ec270d9ddbe998063"
}
