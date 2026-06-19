package com.example.geekds
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.ViewGroup
import android.widget.*
import kotlinx.coroutines.*
import kotlinx.coroutines.cancelChildren
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.*
import android.util.Log
import com.example.geekds.data.LocalStorage
import com.example.geekds.model.*
import com.example.geekds.util.NetworkUtils

internal fun MainActivity.initiateAppUpdate() {
        Log.i(GeekDsConstants.TAG, "=== INITIATING APP UPDATE ===")

        // Stop all activities immediately
        isPlaylistActive = false
        player?.stop()
        player?.release()
        player = null

        // Cancel all background tasks
        scope.coroutineContext.cancelChildren()
        scheduleEnforcerJob?.cancel()
        healthProbeJob?.cancel()

        // Show update UI
        showUpdateScreen()

        // Start download with hardcoded path
        val apkUrl = "$cmsUrl/api/devices/apk/latest"
        downloadAndInstallApk(apkUrl)
    }

internal fun MainActivity.showUpdateScreen() {
        runOnUiThread {
            rootContainer?.removeAllViews()

            val updateView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setBackgroundColor(Color.parseColor("#1a1a1a"))
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val titleText = TextView(this).apply {
                text = "Updating GeekDS..."
                setTextColor(Color.WHITE)
                textSize = 32f
                gravity = android.view.Gravity.CENTER
                setPadding(40, 40, 40, 20)
            }

            val statusText = TextView(this).apply {
                text = "Downloading update from server...\nDo not power off the device."
                setTextColor(Color.parseColor("#aaaaaa"))
                textSize = 18f
                gravity = android.view.Gravity.CENTER
                setPadding(40, 20, 40, 40)
            }

            updateView.addView(titleText)
            updateView.addView(statusText)

            rootContainer?.addView(updateView)

            setState(AppState.SYNCING, "Downloading app update...")
        }
    }

internal fun MainActivity.downloadAndInstallApk(url: String) {
        scope.launch(Dispatchers.IO) {
            try {
                Log.i(GeekDsConstants.TAG, "Downloading APK from: $url")

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw IOException("Failed to download APK: HTTP ${response.code}")
                }

                // Save to external files directory
                val apkFile = File(getExternalFilesDir(null), "GeekDS-update.apk")

                response.body?.byteStream()?.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytes = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead

                            // Log progress every 1MB
                            if (totalBytes % (1024 * 1024) < 8192) {
                                Log.d(GeekDsConstants.TAG, "Downloaded: ${totalBytes / 1024 / 1024}MB")
                            }
                        }
                    }
                }

                Log.i(GeekDsConstants.TAG, "APK downloaded successfully: ${apkFile.absolutePath}")
                Log.i(GeekDsConstants.TAG, "File size: ${apkFile.length()} bytes")

                // Install APK
                withContext(Dispatchers.Main) {
                    installApk(apkFile)
                }

            } catch (e: Exception) {
                Log.e(GeekDsConstants.TAG, "Failed to download/install APK", e)

                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@downloadAndInstallApk)
                        .setTitle("Update Failed")
                        .setMessage("Failed to download update: ${e.message}\n\nThe app will continue running with the current version.")
                        .setPositiveButton("OK") { _, _ ->
                            // Resume normal operation
                            startBackgroundTasks()
                        }
                        .setCancelable(false)
                        .show()
                }
            }
        }
    }

internal fun MainActivity.installApk(apkFile: File) {
        try {
            // Try silent installation using PackageInstaller API (works for system apps)
            if (trysilentInstall(apkFile)) {
                Log.i(GeekDsConstants.TAG, "Silent installation initiated successfully")
                return
            }

            // Fallback to regular installation if silent install fails
            Log.w(GeekDsConstants.TAG, "Silent install not available, falling back to regular install")
            installApkRegular(apkFile)

        } catch (e: Exception) {
            Log.e(GeekDsConstants.TAG, "Failed to install APK", e)
            AlertDialog.Builder(this)
                .setTitle("Installation Failed")
                .setMessage("Could not launch installer: ${e.message}")
                .setPositiveButton("OK", null)
                .show()
        }
    }

internal fun MainActivity.installApkRegular(apkFile: File) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                // Android 7.0+ requires FileProvider
                val apkUri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "${applicationContext.packageName}.fileprovider",
                    apkFile
                )

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                Log.i(GeekDsConstants.TAG, "Launching APK installer (FileProvider)")
                startActivity(intent)

            } else {
                // Android 6.0 and below
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(android.net.Uri.fromFile(apkFile), "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                Log.i(GeekDsConstants.TAG, "Launching APK installer (legacy)")
                startActivity(intent)
            }

        } catch (e: Exception) {
            Log.e(GeekDsConstants.TAG, "Failed to install APK", e)
        }
    }

internal fun MainActivity.trysilentInstall(apkFile: File): Boolean {
        return try {
            Log.i(GeekDsConstants.TAG, "Attempting silent installation...")

            // Check if we have the INSTALL_PACKAGES permission (system apps have this)
            val hasInstallPermission = packageManager.checkPermission(
                "android.permission.INSTALL_PACKAGES",
                packageName
            ) == PackageManager.PERMISSION_GRANTED

            Log.i(GeekDsConstants.TAG, "INSTALL_PACKAGES permission: ${if (hasInstallPermission) "✅ GRANTED (system app)" else "❌ DENIED"}")

            val packageInstaller = packageManager.packageInstaller
            val params = android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            params.setAppPackageName(packageName)

            // For system apps, we can set additional flags
            if (hasInstallPermission) {
                // This flag allows replacing an existing package
                params.setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_DEVICE_SETUP)
            }

            // Create install session
            val sessionId = packageInstaller.createSession(params)
            Log.i(GeekDsConstants.TAG, "Silent installation session created (ID: $sessionId)")

            val session = packageInstaller.openSession(sessionId)

            // Write APK to session
            Log.i(GeekDsConstants.TAG, "Writing APK to installation session (${apkFile.length() / 1024 / 1024}MB)...")
            FileInputStream(apkFile).use { input ->
                session.openWrite("package", 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }

            Log.i(GeekDsConstants.TAG, "APK written to session, committing installation...")

            // Create broadcast receiver for install result
            val intent = Intent(this, com.example.geekds.receiver.InstallResultReceiver::class.java).apply {
                action = "com.example.geekds.INSTALL_COMPLETE"
            }

            val pendingIntent = android.app.PendingIntent.getBroadcast(
                this,
                0,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )

            // Commit the session (this triggers the install)
            session.commit(pendingIntent.intentSender)
            session.close()

            Log.i(GeekDsConstants.TAG, "✅ Silent installation committed successfully")
            Log.i(GeekDsConstants.TAG, "System is installing the update, app will restart automatically...")

            // Clear the update flag immediately
            clearUpdateFlag()

            // Kill the app - Android will restart it automatically after install
            handler.postDelayed({
                Log.i(GeekDsConstants.TAG, "Exiting to allow system to complete installation...")
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 2000)

            true
        } catch (e: SecurityException) {
            Log.w(GeekDsConstants.TAG, "Silent install failed - missing permissions: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(GeekDsConstants.TAG, "Silent install failed with error: ${e.message}", e)
            false
        }
    }

internal fun MainActivity.clearUpdateFlag() {
        scope.launch(Dispatchers.IO) {
            try {
                val deviceId = this@clearUpdateFlag.deviceId ?: return@launch

                Log.i(GeekDsConstants.TAG, "Clearing update_requested flag for device $deviceId")

                val url = "$cmsUrl/api/devices/$deviceId/clear-update-flag"

                val request = Request.Builder()
                    .url(url)
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i(GeekDsConstants.TAG, "✅ Update flag cleared successfully")
                    } else {
                        Log.w(GeekDsConstants.TAG, "Failed to clear update flag: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(GeekDsConstants.TAG, "Error clearing update flag: ${e.message}", e)
            }
        }
    }
