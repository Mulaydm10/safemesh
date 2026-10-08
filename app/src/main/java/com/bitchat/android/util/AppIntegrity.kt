package com.bitchat.android.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.bitchat.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Checks whether this app, or an APK file, is signed with the official SafeMesh key. */
object AppIntegrity {
    enum class Verdict { OFFICIAL, NOT_OFFICIAL, NOT_SAFEMESH, UNREADABLE }

    data class ApkCheck(
        val verdict: Verdict,
        val versionName: String? = null,
        val fileSha256: String? = null
    )

    val officialCertSha256: String? = BuildConfig.GITHUB_RELEASE_CERT_SHA256
        .replace(":", "")
        .trim()
        .lowercase()
        .takeIf { it.matches(Regex("[a-f0-9]{64}")) }

    fun ownCertSha256(context: Context): String? = try {
        certDigests(context.packageManager.getPackageInfo(context.packageName, signingFlags()))
            .firstOrNull()
    } catch (e: Exception) {
        null
    }

    fun isOfficialInstall(context: Context): Boolean {
        val official = officialCertSha256 ?: return false
        return ownCertSha256(context) == official
    }

    suspend fun checkApk(context: Context, uri: Uri): ApkCheck = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "apk_check").apply { mkdirs() }
        val tmp = File(dir, "check.apk")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext ApkCheck(Verdict.UNREADABLE)
            input.use { src -> tmp.outputStream().use { src.copyTo(it) } }
            checkApkFile(context, tmp)
        } catch (e: Exception) {
            ApkCheck(Verdict.UNREADABLE)
        } finally {
            tmp.delete()
        }
    }

    fun checkApkFile(context: Context, file: File): ApkCheck {
        val info = context.packageManager.getPackageArchiveInfo(file.path, signingFlags())
            ?: return ApkCheck(Verdict.UNREADABLE)
        val sha = fileSha256(file)
        val official = officialCertSha256
        val verdict = when {
            info.packageName != context.packageName -> Verdict.NOT_SAFEMESH
            official != null && official in certDigests(info) -> Verdict.OFFICIAL
            else -> Verdict.NOT_OFFICIAL
        }
        return ApkCheck(verdict, info.versionName, sha)
    }

    fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    /** Short, human-comparable form of a fingerprint, e.g. "9D8F 870B 01FE 50E2". */
    fun shortCode(hex: String): String = hex.take(16).uppercase().chunked(4).joinToString(" ")

    private fun certDigests(info: PackageInfo): List<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return signatures.orEmpty().map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex()
        }
    }

    private fun signingFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
