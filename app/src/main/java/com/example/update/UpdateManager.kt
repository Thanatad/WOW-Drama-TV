package com.example.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Update information parsed from the remote manifest file.
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String
)

/**
 * In-app update checker for sideloaded TV deployment.
 *
 * How to publish a new version:
 * 1. Upload the new APK to the server (e.g. https://wow-drama.com/app/WOW-Drama-TV.apk)
 * 2. Upload/update the manifest JSON (UPDATE_MANIFEST_URL below):
 *    {
 *      "versionCode": 2,
 *      "versionName": "1.1",
 *      "apkUrl": "https://wow-drama.com/app/WOW-Drama-TV.apk",
 *      "notes": "เพิ่มระบบเช็คอัปเดตในแอป"
 *    }
 * 3. Bump versionCode / versionName in app/build.gradle.kts for the new build.
 *
 * NOTE: The APK must be signed with the SAME keystore as the installed app,
 * otherwise Android will refuse to install the update.
 */
object UpdateManager {

    private const val TAG = "UpdateManager"

    /** Remote location of the update manifest JSON (repo file, always latest from main branch). */
    const val UPDATE_MANIFEST_URL =
        "https://raw.githubusercontent.com/Thanatad/WOW-Drama-TV/main/app-update.json"

    private const val APK_FILE_NAME = "wow-drama-tv-update.apk"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Fetches and parses the remote update manifest.
     * Returns null when unreachable, malformed, or incomplete.
     */
    suspend fun fetchLatestUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(UPDATE_MANIFEST_URL)
                .header("Cache-Control", "no-cache")
                .build()

            val response = httpClient.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val info = UpdateInfo(
                    versionCode = json.optInt("versionCode", 0),
                    versionName = json.optString("versionName", ""),
                    apkUrl = json.optString("apkUrl", ""),
                    notes = json.optString("notes", "")
                )
                if (info.versionCode <= 0 || info.apkUrl.isBlank()) null else info
            }
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            null
        }
    }

    /** True when the remote manifest targets a newer versionCode than this build. */
    fun isUpdateAvailable(info: UpdateInfo): Boolean =
        info.versionCode > BuildConfig.VERSION_CODE

    /** Returns the previously downloaded update APK, if any. */
    fun findDownloadedApk(context: Context): File? {
        val file = getApkFile(context)
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * Streams the new APK to app-private storage while reporting progress (0-100).
     * Returns the downloaded file, or null on failure.
     */
    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: suspend (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            // Download to a .part file and only rename on full success, so a killed app or
            // dropped connection can never leave a truncated APK that fails to install
            val finalFile = getApkFile(context)
            val partFile = File(finalFile.parentFile, finalFile.name + ".part")
            partFile.parentFile?.mkdirs()
            if (partFile.exists()) partFile.delete()
            if (finalFile.exists()) finalFile.delete()

            val request = Request.Builder().url(info.apkUrl).build()
            val response = httpClient.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                val total = body.contentLength()

                var downloaded = 0L
                var lastPercent = -1

                partFile.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val percent = (downloaded * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    withContext(Dispatchers.Main) { onProgress(percent) }
                                }
                            }
                        }
                    }
                }

                if (total > 0 && downloaded < total) {
                    partFile.delete()
                    null
                } else if (!partFile.renameTo(finalFile)) {
                    partFile.delete()
                    null
                } else {
                    finalFile
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "APK download failed", e)
            null
        }
    }

    /** True when this app is allowed to install unknown packages (Android 8+). */
    fun hasInstallPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Opens the system settings page to grant "install unknown apps" for this app. */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                fallback.data = Uri.parse("package:${context.packageName}")
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallback)
            } catch (ignored: Exception) {
                Log.w(TAG, "Cannot open install permission settings", ignored)
            }
        }
    }

    /**
     * Launches the system installer for the downloaded APK.
     * Requires hasInstallPermission() == true beforehand.
     */
    fun installApk(context: Context, file: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Install launch failed", e)
            false
        }
    }

    private fun getApkFile(context: Context): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.cacheDir
        return File(File(baseDir, "updates"), APK_FILE_NAME)
    }
}
