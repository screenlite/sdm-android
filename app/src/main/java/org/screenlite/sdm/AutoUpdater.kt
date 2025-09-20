package org.screenlite.sdm

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.screenlite.sdm.receivers.ScreenliteDeviceAdminReceiver
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

data class ApkReleaseInfo(val downloadUrl: String, val versionCode: Long, val versionName: String)

class AutoUpdater(private val context: Context) {
    private val client = OkHttpClient()
    private val kioskPackage = "org.screenlite.webkiosk"
    private val TAG = "AutoUpdater"

    suspend fun updateIfNeeded() = withContext(Dispatchers.IO) {
        Log.d(TAG, "Checking if update is needed...")

        val installed = isAppInstalled(context, kioskPackage)
        Log.d(TAG, "App installed: $installed")

        val releaseInfo = fetchLatestApkRelease()
        if (releaseInfo == null) {
            Log.w(TAG, "Failed to fetch release info.")
            return@withContext
        }

        Log.d(TAG, "Fetched release: ${releaseInfo.versionName} (code: ${releaseInfo.versionCode})")

        val installedVersion = getInstalledVersionCode(kioskPackage)
        val remoteVersion = releaseInfo.versionCode

        Log.d(TAG, "Installed version: $installedVersion, Remote version: $remoteVersion")

        if (!installed || installedVersion == -1L || remoteVersion > installedVersion) {
            Log.i(TAG, if (!installed) "App not installed. Installing..." else "New version detected. Updating app...")
            updateApp(releaseInfo.downloadUrl)
        } else {
            Log.d(TAG, "No update required.")
        }

        grantPermissions()
    }

    private fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun getInstalledVersionCode(packageName: String): Long {
        return try {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode.toLong()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Package $packageName not found", e)
            -1L
        } catch (e: Exception) {
            Log.e(TAG, "Error getting version code for $packageName", e)
            -1L
        }
    }

    suspend fun fetchLatestApkRelease(): ApkReleaseInfo? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Fetching latest release info from GitHub...")
        val request = Request.Builder()
            .url("https://api.github.com/repos/screenlite/web-kiosk/releases/latest")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "GitHub API call failed: ${response.code}")
                return@withContext null
            }

            val json = response.body.string()
            val jsonObject = JSONObject(json)
            val tagName = jsonObject.getString("tag_name")
            val assets = jsonObject.getJSONArray("assets")

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk")) {
                    val url = asset.getString("browser_download_url")
                    Log.d(TAG, "Found APK asset: $name ($url)")

                    val apkInfo = downloadAndExtractApkInfo(url)
                    if (apkInfo != null) {
                        return@withContext ApkReleaseInfo(url, apkInfo.first, apkInfo.second)
                    } else {
                        Log.w(TAG, "Failed to extract version info from APK, falling back to tag name")
                        val fallbackVersion = parseVersionCode(tagName)
                        return@withContext ApkReleaseInfo(url, fallbackVersion, tagName)
                    }
                }
            }

            Log.w(TAG, "No APK found in the latest release.")
            null
        }
    }

    private suspend fun downloadAndExtractApkInfo(apkUrl: String): Pair<Long, String>? = withContext(Dispatchers.IO) {
        val tempApkFile = File(context.cacheDir, "temp_apk_check.apk")

        try {
            client.newCall(Request.Builder().url(apkUrl).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to download APK for version check: ${response.code}")
                    return@withContext null
                }

                FileOutputStream(tempApkFile).use { fos ->
                    response.body.byteStream().copyTo(fos)
                }
            }

            return@withContext extractVersionFromApk(tempApkFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting version info from APK", e)
            return@withContext null
        } finally {
            if (tempApkFile.exists()) {
                tempApkFile.delete()
            }
        }
    }

    private fun extractVersionFromApk(apkFile: File): Pair<Long, String>? {
        return try {
            ZipFile(apkFile).use { zip ->
                val entry = zip.getEntry("AndroidManifest.xml")
                if (entry != null) {
                    zip.getInputStream(entry).use { inputStream ->
                        return getApkInfoFromPackageManager(apkFile)
                    }
                } else {
                    Log.w(TAG, "AndroidManifest.xml not found in APK")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading APK file", e)
            null
        }
    }

    private fun getApkInfoFromPackageManager(apkFile: File): Pair<Long, String>? {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_META_DATA)
            }

            if (packageInfo != null) {
                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                }
                Pair(versionCode, packageInfo.versionName ?: "unknown")
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting package info from APK", e)
            null
        }
    }

    private fun parseVersionCode(tagName: String): Long {
        val cleaned = tagName.trimStart('v', 'V')
        val parts = cleaned.split(".", "-", "+")
        return try {
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
            (major * 10000 + minor * 100 + patch).toLong()
        } catch (_: Exception) {
            0L
        }
    }

    suspend fun updateApp(apkUrl: String) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Downloading APK from: $apkUrl")
        val apkFile = File(context.cacheDir, "webkiosk_update.apk")

        client.newCall(Request.Builder().url(apkUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.e(TAG, "Failed to download APK: ${resp.code}")
                return@use
            }

            FileOutputStream(apkFile).use { fos ->
                resp.body.byteStream().copyTo(fos)
            }

            Log.i(TAG, "APK downloaded successfully to ${apkFile.absolutePath}")
        }

        installApkSilently(context, apkFile)

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, ScreenliteDeviceAdminReceiver::class.java)
        dpm.setUninstallBlocked(admin, kioskPackage, true)
        Log.i(TAG, "Uninstall blocked for $kioskPackage")
    }

    @SuppressLint("RequestInstallPackagesPolicy")
    fun installApkSilently(context: Context, apkFile: File) {
        Log.i(TAG, "Installing APK silently...")
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        apkFile.inputStream().use { input ->
            session.openWrite("apk", 0, apkFile.length()).use { output ->
                input.copyTo(output)
                session.fsync(output)
            }
        }

        val intent = Intent("org.screenlite.sdm.INSTALL_COMPLETE")
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            sessionId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        session.commit(pendingIntent.intentSender)
        session.close()

        Log.i(TAG, "APK install session committed")
    }

    private fun grantPermissions() {
        Log.i(TAG, "Granting permissions to $kioskPackage...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, ScreenliteDeviceAdminReceiver::class.java)

            dpm.setPermissionGrantState(
                adminComponent,
                kioskPackage,
                Manifest.permission.SYSTEM_ALERT_WINDOW,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                dpm.setPermissionGrantState(
                    adminComponent,
                    kioskPackage,
                    Manifest.permission.POST_NOTIFICATIONS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
            }

            Log.i(TAG, "Permissions granted")
        } else {
            Log.w(TAG, "Skipping permission grant — unsupported Android version")
        }
    }
}