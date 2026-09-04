package com.trungkien.cleanvehicle

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object AdasAutoUpdater {
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/sagsdieuhanh-gif/Activekey/releases/latest"

    private const val PREFS =
        "trungkien_adas_auto_update"

    private const val KEY_LAST_CHECK =
        "last_check_ms"

    private const val KEY_PENDING_APK =
        "pending_apk"

    private const val KEY_PENDING_CODE =
        "pending_code"

    private const val CHECK_INTERVAL_MS =
        3L * 60L * 60L * 1000L

    private const val START_DELAY_MS =
        5_000L

    private const val INSTALL_RETRY_MS =
        30L * 60L * 1000L

    private val executor =
        Executors.newSingleThreadExecutor()

    private val checkBusy =
        AtomicBoolean(false)

    private val downloadBusy =
        AtomicBoolean(false)

    private val main =
        Handler(Looper.getMainLooper())

    @Volatile
    private var installPromptedAtMs =
        0L

    fun onActivityResumed(
        activity: Activity,
    ) {
        cleanupInstalledPending(
            activity
        )

        val pending =
            pendingApk(activity)

        if (
            pending != null &&
            pending.exists()
        ) {
            val now =
                System.currentTimeMillis()

            if (
                now -
                    installPromptedAtMs >=
                INSTALL_RETRY_MS
            ) {
                installPromptedAtMs =
                    now

                main.postDelayed(
                    {
                        requestInstall(
                            activity,
                            pending,
                        )
                    },
                    1_000L,
                )
            }
        }

        main.postDelayed(
            {
                checkIfDue(
                    activity
                )
            },
            START_DELAY_MS,
        )
    }

    fun checkNow(
        activity: Activity,
    ) {
        startCheck(
            activity,
            force = true,
        )
    }

    private fun checkIfDue(
        activity: Activity,
    ) {
        val prefs =
            activity.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE,
            )

        val now =
            System.currentTimeMillis()

        val last =
            prefs.getLong(
                KEY_LAST_CHECK,
                0L,
            )

        if (
            now -
                last <
            CHECK_INTERVAL_MS
        ) {
            return
        }

        startCheck(
            activity,
            force = false,
        )
    }

    private fun startCheck(
        activity: Activity,
        force: Boolean,
    ) {
        if (
            !checkBusy.compareAndSet(
                false,
                true,
            )
        ) {
            return
        }

        executor.execute {
            try {
                val currentCode =
                    currentVersionCode(
                        activity
                    )

                val release =
                    fetchLatestRelease()

                if (
                    release.versionCode <=
                    currentCode
                ) {
                    if (force) {
                        toast(
                            activity,
                            "TrungKien ADAS đang là bản mới nhất.",
                        )
                    }

                    return@execute
                }

                main.post {
                    if (
                        activity.isFinishing ||
                        activity.isDestroyed
                    ) {
                        return@post
                    }

                    if (
                        isMetered(
                            activity
                        )
                    ) {
                        AlertDialog.Builder(
                            activity
                        )
                            .setTitle(
                                "CÓ BẢN CẬP NHẬT ${release.versionName}"
                            )
                            .setMessage(
                                "Bạn đang dùng mạng có tính phí. APK ADAS khá lớn. Tải và cập nhật ngay?"
                            )
                            .setNegativeButton(
                                "ĐỂ SAU",
                                null,
                            )
                            .setPositiveButton(
                                "TẢI NGAY"
                            ) {
                                _,
                                _ ->
                                download(
                                    activity,
                                    release,
                                )
                            }
                            .show()
                    } else {
                        toast(
                            activity,
                            "Có ${release.versionName}. Đang tự tải bản cập nhật…",
                        )

                        download(
                            activity,
                            release,
                        )
                    }
                }
            } catch (_: Throwable) {
                if (force) {
                    toast(
                        activity,
                        "Không kiểm tra được cập nhật. Sẽ tự thử lại sau.",
                    )
                }
            } finally {
                activity
                    .getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE,
                    )
                    .edit()
                    .putLong(
                        KEY_LAST_CHECK,
                        System.currentTimeMillis(),
                    )
                    .apply()

                checkBusy.set(
                    false
                )
            }
        }
    }

    private fun download(
        activity: Activity,
        release: ReleaseInfo,
    ) {
        if (
            !downloadBusy.compareAndSet(
                false,
                true,
            )
        ) {
            return
        }

        executor.execute {
            try {
                val dir =
                    File(
                        activity.externalCacheDir
                            ?: activity.cacheDir,
                        "updates",
                    )

                dir.mkdirs()

                val apk =
                    File(
                        dir,
                        "TrungKien_ADAS_${release.versionCode}.apk",
                    )

                val tmp =
                    File(
                        dir,
                        apk.name +
                            ".part",
                    )

                tmp.delete()

                val connection =
                    (
                        URL(
                            release.apkUrl
                        )
                            .openConnection()
                            as HttpURLConnection
                        ).apply {
                            instanceFollowRedirects =
                                true

                            connectTimeout =
                                25_000

                            readTimeout =
                                60_000

                            requestMethod =
                                "GET"

                            setRequestProperty(
                                "User-Agent",
                                "TrungKien-ADAS-Updater",
                            )

                            setRequestProperty(
                                "Accept",
                                "application/octet-stream",
                            )
                        }

                try {
                    val code =
                        connection.responseCode

                    require(
                        code in
                            200..299
                    ) {
                        "HTTP $code"
                    }

                    connection.inputStream.use {
                        input ->

                        tmp.outputStream().use {
                            output ->

                            input.copyTo(
                                output,
                                512 *
                                    1024,
                            )
                        }
                    }
                } finally {
                    connection.disconnect()
                }

                require(
                    tmp.length() >
                        5_000_000L
                ) {
                    "APK quá nhỏ"
                }

                if (
                    apk.exists()
                ) {
                    apk.delete()
                }

                require(
                    tmp.renameTo(
                        apk
                    )
                ) {
                    "Không hoàn tất file APK"
                }

                verifyDownloadedApk(
                    activity,
                    apk,
                    release.versionCode,
                )

                activity
                    .getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE,
                    )
                    .edit()
                    .putString(
                        KEY_PENDING_APK,
                        apk.absolutePath,
                    )
                    .putLong(
                        KEY_PENDING_CODE,
                        release.versionCode,
                    )
                    .apply()

                installPromptedAtMs =
                    System.currentTimeMillis()

                main.post {
                    if (
                        !activity.isFinishing &&
                        !activity.isDestroyed
                    ) {
                        requestInstall(
                            activity,
                            apk,
                        )
                    }
                }
            } catch (e: Throwable) {
                toast(
                    activity,
                    "Tải cập nhật lỗi: ${e.message ?: e.javaClass.simpleName}",
                )
            } finally {
                downloadBusy.set(
                    false
                )
            }
        }
    }

    private fun fetchLatestRelease():
        ReleaseInfo {
        val connection =
            (
                URL(
                    LATEST_RELEASE_API
                )
                    .openConnection()
                    as HttpURLConnection
                ).apply {
                    connectTimeout =
                        20_000

                    readTimeout =
                        20_000

                    requestMethod =
                        "GET"

                    setRequestProperty(
                        "User-Agent",
                        "TrungKien-ADAS-Updater",
                    )

                    setRequestProperty(
                        "Accept",
                        "application/vnd.github+json",
                    )
                }

        try {
            val code =
                connection.responseCode

            require(
                code in
                    200..299
            ) {
                "GitHub HTTP $code"
            }

            val text =
                connection.inputStream
                    .bufferedReader()
                    .use {
                        it.readText()
                    }

            val json =
                JSONObject(
                    text
                )

            val tag =
                json.getString(
                    "tag_name"
                )

            val match =
                Regex(
                    """trungkien-adas-(\d+)-v(.+)"""
                )
                    .matchEntire(
                        tag
                    )
                    ?: error(
                        "Tag update không hợp lệ: $tag"
                    )

            val codeValue =
                match.groupValues[1]
                    .toLong()

            val nameValue =
                match.groupValues[2]

            val assets =
                json.getJSONArray(
                    "assets"
                )

            var apkUrl:
                String? =
                null

            for (
                i in
                0 until
                    assets.length()
            ) {
                val item =
                    assets.getJSONObject(
                        i
                    )

                val name =
                    item.optString(
                        "name"
                    )

                if (
                    name.endsWith(
                        ".apk",
                        ignoreCase = true,
                    )
                ) {
                    apkUrl =
                        item.getString(
                            "browser_download_url"
                        )

                    break
                }
            }

            require(
                !apkUrl.isNullOrBlank()
            ) {
                "Release không có APK"
            }

            return ReleaseInfo(
                versionCode =
                    codeValue,
                versionName =
                    nameValue,
                apkUrl =
                    apkUrl,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun verifyDownloadedApk(
        context: Context,
        apk: File,
        expectedCode: Long,
    ) {
        val pm =
            context.packageManager

        val flags =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.P
            ) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }

        val archive =
            pm.getPackageArchiveInfo(
                apk.absolutePath,
                flags,
            ) ?: error(
                "Android không đọc được APK"
            )

        require(
            archive.packageName ==
                context.packageName
        ) {
            "Sai package: ${archive.packageName}"
        }

        val archiveCode =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.P
            ) {
                archive.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                archive.versionCode.toLong()
            }

        require(
            archiveCode ==
                expectedCode
        ) {
            "Sai versionCode APK"
        }

        require(
            archiveCode >
                currentVersionCode(
                    context
                )
        ) {
            "APK không mới hơn"
        }

        val installedCert =
            installedCertificateSha256(
                context
            )

        val archiveCert =
            certificateSha256(
                archive
            )

        require(
            installedCert !=
                null &&
                installedCert ==
                archiveCert
        ) {
            "Chữ ký APK không khớp signing stable"
        }
    }

    private fun installedCertificateSha256(
        context: Context,
    ): String? {
        val flags =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.P
            ) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }

        val info =
            context.packageManager
                .getPackageInfo(
                    context.packageName,
                    flags,
                )

        return certificateSha256(
            info
        )
    }

    private fun certificateSha256(
        info: android.content.pm.PackageInfo,
    ): String? {
        val bytes =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.P
            ) {
                info.signingInfo
                    ?.apkContentsSigners
                    ?.firstOrNull()
                    ?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                info.signatures
                    ?.firstOrNull()
                    ?.toByteArray()
            }
                ?: return null

        return MessageDigest
            .getInstance(
                "SHA-256"
            )
            .digest(
                bytes
            )
            .joinToString(
                ""
            ) {
                "%02X".format(
                    it
                )
            }
    }

    private fun requestInstall(
        activity: Activity,
        apk: File,
    ) {
        if (
            !apk.exists()
        ) {
            clearPending(
                activity
            )

            return
        }

        if (
            Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O &&
            !activity.packageManager
                .canRequestPackageInstalls()
        ) {
            toast(
                activity,
                "Cho phép TrungKien ADAS cài bản cập nhật, rồi quay lại ứng dụng.",
            )

            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse(
                        "package:${activity.packageName}"
                    ),
                )
            )

            return
        }

        val uri =
            FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.updates",
                apk,
            )

        activity.startActivity(
            Intent(
                Intent.ACTION_VIEW
            ).apply {
                setDataAndType(
                    uri,
                    "application/vnd.android.package-archive",
                )

                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        )
    }

    private fun cleanupInstalledPending(
        context: Context,
    ) {
        val prefs =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE,
            )

        val code =
            prefs.getLong(
                KEY_PENDING_CODE,
                0L,
            )

        if (
            code >
                0L &&
            currentVersionCode(
                context
            ) >=
                code
        ) {
            pendingApk(
                context
            )
                ?.delete()

            clearPending(
                context
            )
        }
    }

    private fun pendingApk(
        context: Context,
    ): File? {
        val path =
            context
                .getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE,
                )
                .getString(
                    KEY_PENDING_APK,
                    null,
                )
                ?: return null

        return File(
            path
        )
    }

    private fun clearPending(
        context: Context,
    ) {
        context
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE,
            )
            .edit()
            .remove(
                KEY_PENDING_APK
            )
            .remove(
                KEY_PENDING_CODE
            )
            .apply()
    }

    private fun currentVersionCode(
        context: Context,
    ): Long {
        val info =
            context.packageManager
                .getPackageInfo(
                    context.packageName,
                    0,
                )

        return if (
            Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.P
        ) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun isMetered(
        context: Context,
    ): Boolean {
        val cm =
            context.getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as ConnectivityManager

        return cm.isActiveNetworkMetered
    }

    private fun toast(
        activity: Activity,
        text: String,
    ) {
        main.post {
            if (
                !activity.isFinishing &&
                !activity.isDestroyed
            ) {
                Toast.makeText(
                    activity,
                    text,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private data class ReleaseInfo(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
    )
}
