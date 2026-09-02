package com.openai.distanceguard

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Offline V13 access gate. The DG12 payload/device-code salt is intentionally retained for V12/V13 key compatibility.
 *
 * - New installs receive five minutes of foreground trial time.
 * - After trial expiration, protected camera/vision functions require an administrator-issued key.
 * - Licenses are signed with an administrator-held ECDSA private key; the APK contains only the
 *   matching public key, so extracting the APK does not reveal a signing/master secret.
 * - Keys are bound to the Android device code shown by the app.
 *
 * An offline app cannot prevent a user from resetting app data/uninstalling to obtain a fresh trial.
 * That requires an online entitlement service. The signed license itself remains non-forgeable
 * without the administrator private key.
 */
class LicenseGate(private val context: Context) {
    enum class AccessState { LICENSED, TRIAL, EXPIRED }

    data class Status(
        val state: AccessState,
        val remainingTrialMs: Long,
        val deviceCode: String,
        val licenseExpiryEpochDay: Long? = null,
    ) {
        val allowed: Boolean get() = state != AccessState.EXPIRED
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var usageSessionStartedElapsedMs: Long = 0L
    private var usageSessionPersistedElapsedMs: Long = 0L
    @Volatile private var cachedLicense: VerifiedLicense? = null
    @Volatile private var cachedLicenseRaw: String? = null

    val deviceCode: String by lazy { buildDeviceCode() }

    fun startUsageSession() {
        if (usageSessionStartedElapsedMs == 0L) {
            val now = SystemClock.elapsedRealtime()
            usageSessionStartedElapsedMs = now
            usageSessionPersistedElapsedMs = now
        }
    }

    fun pauseUsageSession() {
        persistForegroundUsage()
        usageSessionStartedElapsedMs = 0L
        usageSessionPersistedElapsedMs = 0L
    }

    /** Persist trial use periodically so process death cannot restore most of the five-minute trial. */
    fun tickForegroundUsage(): Status {
        if (verifiedLicense() == null) persistForegroundUsage()
        return status()
    }

    fun status(): Status {
        val verified = verifiedLicense()
        if (verified != null) {
            return Status(
                state = AccessState.LICENSED,
                remainingTrialMs = 0L,
                deviceCode = deviceCode,
                licenseExpiryEpochDay = verified.expiryEpochDay.takeIf { it > 0L },
            )
        }
        val used = prefs.getLong(KEY_TRIAL_USED_MS, 0L).coerceAtLeast(0L)
        val consumed = prefs.getBoolean(KEY_TRIAL_CONSUMED, false) || used >= TRIAL_MS
        if (consumed) {
            if (!prefs.getBoolean(KEY_TRIAL_CONSUMED, false)) {
                prefs.edit().putBoolean(KEY_TRIAL_CONSUMED, true).apply()
            }
            return Status(AccessState.EXPIRED, 0L, deviceCode)
        }
        return Status(AccessState.TRIAL, (TRIAL_MS - used).coerceAtLeast(0L), deviceCode)
    }

    fun installKey(raw: String): Result<Unit> {
        val normalized = raw.trim().replace("\n", "").replace("\r", "")
        val verified = verify(normalized) ?: return Result.failure(IllegalArgumentException("Key không hợp lệ hoặc không đúng thiết bị."))
        prefs.edit().putString(KEY_LICENSE, normalized).apply()
        cachedLicenseRaw = normalized
        cachedLicense = verified
        return Result.success(Unit)
    }

    fun clearInstalledKey() {
        cachedLicenseRaw = null
        cachedLicense = null
        prefs.edit().remove(KEY_LICENSE).apply()
    }

    fun installedKeyPresent(): Boolean = !prefs.getString(KEY_LICENSE, null).isNullOrBlank()

    private fun persistForegroundUsage() {
        if (usageSessionStartedElapsedMs == 0L) return
        val now = SystemClock.elapsedRealtime()
        val since = usageSessionPersistedElapsedMs.takeIf { it > 0L } ?: now
        val delta = (now - since).coerceIn(0L, 15_000L)
        usageSessionPersistedElapsedMs = now
        if (delta <= 0L) return
        val old = prefs.getLong(KEY_TRIAL_USED_MS, 0L).coerceAtLeast(0L)
        val updated = (old + delta).coerceAtMost(TRIAL_MS)
        prefs.edit()
            .putLong(KEY_TRIAL_USED_MS, updated)
            .putBoolean(KEY_TRIAL_CONSUMED, updated >= TRIAL_MS)
            .apply()
    }

    private data class VerifiedLicense(val expiryEpochDay: Long)

    private fun verifiedLicense(): VerifiedLicense? {
        val key = prefs.getString(KEY_LICENSE, null) ?: run {
            cachedLicenseRaw = null
            cachedLicense = null
            return null
        }
        val cached = cachedLicense
        if (cachedLicenseRaw == key && cached != null) {
            if (cached.expiryEpochDay == 0L || currentEpochDay() <= cached.expiryEpochDay) return cached
            clearInstalledKey()
            return null
        }
        val result = verify(key)
        if (result == null) {
            clearInstalledKey()
            return null
        }
        cachedLicenseRaw = key
        cachedLicense = result
        return result
    }

    private fun verify(key: String): VerifiedLicense? = runCatching {
        val parts = key.split('.')
        if (parts.size != 2) return@runCatching null
        val payload = Base64.decode(parts[0], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val signatureBytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val payloadText = String(payload, StandardCharsets.UTF_8)
        val fields = payloadText.split('|')
        if (fields.size != 4 || fields[0] != LICENSE_PREFIX) return@runCatching null
        if (fields[1] != deviceCode) return@runCatching null
        val expiryEpochDay = fields[2].toLongOrNull() ?: return@runCatching null
        if (expiryEpochDay > 0L && currentEpochDay() > expiryEpochDay) return@runCatching null

        val publicDer = Base64.decode(PUBLIC_KEY_DER_B64, Base64.DEFAULT)
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicDer))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(payload)
        if (!verifier.verify(signatureBytes)) return@runCatching null
        VerifiedLicense(expiryEpochDay)
    }.getOrNull()

    private fun currentEpochDay(): Long = System.currentTimeMillis() / 86_400_000L

    private fun buildDeviceCode(): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val raw = "$androidId|${context.packageName}|TRUNGKIEN-V12-LICENSE"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8))
        val hex = digest.take(8).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        return hex.chunked(4).joinToString("-")
    }

    companion object {
        const val TRIAL_MS = 5 * 60 * 1000L
        private const val PREFS = "access_v12"
        private const val KEY_TRIAL_USED_MS = "trial_used_ms"
        private const val KEY_TRIAL_CONSUMED = "trial_consumed"
        private const val KEY_LICENSE = "license_key"
        private const val LICENSE_PREFIX = "DG12"
        private const val PUBLIC_KEY_DER_B64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEHbkF3spSsePMGGCV1ccOxIE7lhYe5LfUK0wnTarf48icE9SR9L4KsKRMmSw3/KQ5Pgt0JhQBPCYyKAE0oGGuXQ=="
    }
}
