package com.trungkien.cleanvehicle

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Locale

data class AdasLicenseResult(
    val valid: Boolean,
    val message: String,
    val expiryEpochDay: Long = 0L,
    val serial: String = "",
)

class AdasLicenseManager(
    private val context: Context,
) {
    private val prefs =
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE,
        )

    val deviceCode: String by lazy {
        buildDeviceCode()
    }

    private var trialRunning =
        false

    private var trialTickMs =
        0L

    fun hasAccess(): Boolean =
        storedLicenseResult().valid ||
            remainingTrialMs() > 0L

    fun isLicensed(): Boolean =
        storedLicenseResult().valid

    fun remainingTrialMs(): Long {
        consumeTrialNow()

        val used =
            prefs.getLong(
                KEY_TRIAL_USED_MS,
                0L,
            )

        return (
            TRIAL_TOTAL_MS -
                used
            )
            .coerceAtLeast(
                0L
            )
    }

    fun startTrialClock() {
        if (
            isLicensed() ||
            remainingTrialMs() <= 0L ||
            trialRunning
        ) {
            return
        }

        trialRunning =
            true

        trialTickMs =
            SystemClock.elapsedRealtime()
    }

    fun stopTrialClock() {
        consumeTrialNow()

        trialRunning =
            false

        trialTickMs =
            0L
    }

    fun consumeTrialNow() {
        if (
            !trialRunning ||
            isLicensed()
        ) {
            return
        }

        val now =
            SystemClock.elapsedRealtime()

        if (
            trialTickMs <= 0L
        ) {
            trialTickMs =
                now
            return
        }

        val delta =
            (
                now -
                    trialTickMs
                )
                .coerceIn(
                    0L,
                    10_000L,
                )

        trialTickMs =
            now

        if (
            delta <= 0L
        ) {
            return
        }

        val used =
            prefs.getLong(
                KEY_TRIAL_USED_MS,
                0L,
            )

        prefs.edit()
            .putLong(
                KEY_TRIAL_USED_MS,
                (
                    used +
                        delta
                    )
                    .coerceAtMost(
                        TRIAL_TOTAL_MS
                    ),
            )
            .apply()
    }

    fun storedLicenseResult(): AdasLicenseResult {
        val license =
            prefs.getString(
                KEY_LICENSE,
                null,
            )
                ?.trim()
                .orEmpty()

        if (
            license.isBlank()
        ) {
            return AdasLicenseResult(
                false,
                "CHƯA CÓ KEY",
            )
        }

        return verifyLicense(
            license
        )
    }

    fun activate(
        rawLicense: String,
    ): AdasLicenseResult {
        val license =
            rawLicense.trim()

        val result =
            verifyLicense(
                license
            )

        if (
            result.valid
        ) {
            prefs.edit()
                .putString(
                    KEY_LICENSE,
                    license,
                )
                .putLong(
                    KEY_TRIAL_USED_MS,
                    TRIAL_TOTAL_MS,
                )
                .apply()

            trialRunning =
                false

            trialTickMs =
                0L
        }

        return result
    }

    fun licenseSummary(): String {
        val result =
            storedLicenseResult()

        if (
            !result.valid
        ) {
            return "TRIAL"
        }

        if (
            result.expiryEpochDay ==
            0L
        ) {
            return "KEY VĨNH VIỄN"
        }

        val remaining =
            (
                result.expiryEpochDay -
                    effectiveTodayEpochDay() +
                    1L
                )
                .coerceAtLeast(
                    0L
                )

        return "KEY ${remaining}N"
    }

    private fun verifyLicense(
        license: String,
    ): AdasLicenseResult {
        return try {
            val parts =
                license.split(
                    "."
                )

            if (
                parts.size != 2
            ) {
                return AdasLicenseResult(
                    false,
                    "KEY KHÔNG ĐÚNG ĐỊNH DẠNG",
                )
            }

            val payload =
                decodeUrlBase64(
                    parts[0]
                )

            val signature =
                decodeUrlBase64(
                    parts[1]
                )

            val publicDer =
                Base64.decode(
                    PUBLIC_KEY_DER_B64,
                    Base64.DEFAULT,
                )

            val publicKey =
                KeyFactory.getInstance(
                    "EC"
                )
                    .generatePublic(
                        X509EncodedKeySpec(
                            publicDer
                        )
                    )

            val verifier =
                Signature.getInstance(
                    "SHA256withECDSA"
                )

            verifier.initVerify(
                publicKey
            )

            verifier.update(
                payload
            )

            if (
                !verifier.verify(
                    signature
                )
            ) {
                return AdasLicenseResult(
                    false,
                    "CHỮ KÝ KEY KHÔNG HỢP LỆ",
                )
            }

            val fields =
                String(
                    payload,
                    StandardCharsets.UTF_8,
                )
                    .split(
                        "|"
                    )

            if (
                fields.size != 4 ||
                fields[0] != LICENSE_PREFIX
            ) {
                return AdasLicenseResult(
                    false,
                    "KEY KHÔNG ĐÚNG PHIÊN BẢN",
                )
            }

            val licensedDevice =
                fields[1]
                    .uppercase(
                        Locale.US
                    )

            if (
                licensedDevice != deviceCode
            ) {
                return AdasLicenseResult(
                    false,
                    "KEY KHÔNG ĐÚNG MÃ THIẾT BỊ",
                )
            }

            val expiry =
                fields[2]
                    .toLongOrNull()
                    ?: return AdasLicenseResult(
                        false,
                        "KEY SAI NGÀY HẾT HẠN",
                    )

            val serial =
                fields[3]

            if (
                !serial.matches(
                    Regex(
                        "[0-9A-Fa-f]{4,32}"
                    )
                )
            ) {
                return AdasLicenseResult(
                    false,
                    "KEY SAI SERIAL",
                )
            }

            if (
                expiry > 0L &&
                effectiveTodayEpochDay() > expiry
            ) {
                return AdasLicenseResult(
                    false,
                    "KEY ĐÃ HẾT HẠN",
                    expiry,
                    serial,
                )
            }

            AdasLicenseResult(
                true,
                if (
                    expiry == 0L
                ) {
                    "KÍCH HOẠT THÀNH CÔNG • VĨNH VIỄN"
                } else {
                    "KÍCH HOẠT THÀNH CÔNG"
                },
                expiry,
                serial,
            )
        } catch (
            error: Throwable
        ) {
            AdasLicenseResult(
                false,
                "KEY KHÔNG HỢP LỆ: ${error.javaClass.simpleName}",
            )
        }
    }

    private fun effectiveTodayEpochDay(): Long {
        val current =
            System.currentTimeMillis() /
                DAY_MS

        val last =
            prefs.getLong(
                KEY_LAST_EPOCH_DAY,
                0L,
            )

        val effective =
            maxOf(
                current,
                last,
            )

        if (
            current > last
        ) {
            prefs.edit()
                .putLong(
                    KEY_LAST_EPOCH_DAY,
                    current,
                )
                .apply()
        }

        return effective
    }

    private fun buildDeviceCode(): String {
        val androidId =
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID,
            )
                ?.trim()
                .orEmpty()

        val digest =
            MessageDigest.getInstance(
                "SHA-256"
            )
                .digest(
                    (
                        DEVICE_CODE_SALT +
                            "|" +
                            androidId
                        )
                        .toByteArray(
                            StandardCharsets.UTF_8
                        )
                )

        val hex =
            buildString {
                for (
                    index in 0 until 8
                ) {
                    append(
                        "%02X".format(
                            Locale.US,
                            digest[index]
                                .toInt() and
                                0xFF,
                        )
                    )
                }
            }

        return hex.chunked(
            4
        )
            .joinToString(
                "-"
            )
    }

    private fun decodeUrlBase64(
        text: String,
    ): ByteArray {
        val padding =
            (
                4 -
                    text.length %
                    4
                ) %
                4

        return Base64.decode(
            text +
                "=".repeat(
                    padding
                ),
            Base64.URL_SAFE or
                Base64.NO_WRAP,
        )
    }

    companion object {
        private const val PREFS =
            "trungkien_adas_license_v22"

        private const val KEY_TRIAL_USED_MS =
            "trial_used_ms"

        private const val KEY_LICENSE =
            "license"

        private const val KEY_LAST_EPOCH_DAY =
            "last_epoch_day"

        private const val TRIAL_TOTAL_MS =
            5L *
                60L *
                1000L

        private const val DAY_MS =
            86_400_000L

        private const val LICENSE_PREFIX =
            "DG12"

        private const val DEVICE_CODE_SALT =
            "TRUNGKIEN-ADAS-DG12-V22"

        private const val PUBLIC_KEY_DER_B64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEHbkF3spSsePMGGCV1ccOxIE7lhYe5LfUK0wnTarf48icE9SR9L4KsKRMmSw3/KQ5Pgt0JhQBPCYyKAE0oGGuXQ=="
    }
}
