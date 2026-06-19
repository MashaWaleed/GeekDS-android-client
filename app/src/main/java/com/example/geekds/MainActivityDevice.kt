package com.example.geekds
import android.content.Context
import java.io.File
import android.util.Log
import kotlinx.coroutines.cancelChildren

internal fun MainActivity.clearDeviceRegistration() {
        Log.w(GeekDsConstants.TAG, "Clearing device registration - invalidating all cached data except UUID")

        deviceId = null
        isPlaylistActive = false
        currentPlaylistId = null
        lastKnownScheduleVersion = 0
        lastKnownPlaylistVersion = 0
        lastAllSchedulesVersion = 0

        val sharedPrefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        with(sharedPrefs.edit()) {
            putInt("device_id", -1) // Use -1 as invalid device ID
            apply()
        }

        // Clear ALL cached data from main prefs EXCEPT device_uuid
        val mainPrefs = getSharedPreferences("geekds_prefs", Context.MODE_PRIVATE)
        val savedUuid = mainPrefs.getString("device_uuid", null) // Preserve UUID

        // Get all keys and filter out only UUID
        val allKeys = mainPrefs.all.keys
        val editor = mainPrefs.edit()

        allKeys.forEach { key ->
            if (key != "device_uuid") {
                editor.remove(key)
                Log.d(GeekDsConstants.TAG, "Cleared cached key: $key")
            }
        }
        editor.apply()

        Log.i(GeekDsConstants.TAG, "Cleared all cached schedules, playlists, and preferences (preserved UUID: ${savedUuid?.take(8)}...)")

        deviceName = GeekDsConstants.DEFAULT_DEVICE_NAME

        // Delete all downloaded media files to free space
        try {
            val mediaDir = getExternalFilesDir(null)
            if (mediaDir != null && mediaDir.exists()) {
                val files = mediaDir.listFiles()
                var deletedCount = 0
                files?.forEach { file ->
                    if (file.isFile && file.name != "config.json") { // Keep config.json
                        if (file.delete()) {
                            deletedCount++
                            Log.d(GeekDsConstants.TAG, "Deleted cached media: ${file.name}")
                        }
                    }
                }
                Log.i(GeekDsConstants.TAG, "Deleted $deletedCount cached media files")
            }
        } catch (e: Exception) {
            Log.e(GeekDsConstants.TAG, "Error deleting cached media files", e)
        }

        // Stop all background activities
        stopAllActivities()
    }

internal fun MainActivity.stopAllActivities() {
        try {
            // Cancel all background jobs gracefully
            scheduleEnforcerJob?.cancel()

            // Use a new scope for stopping activities to avoid cancellation issues
            runOnUiThread {
                try {
                    // Stop player safely
                    player?.stop()
                    player?.release()
                    player = null
                    playerView = null
                    videoTextureView = null                    // Show standby screen
                    showStandby()

                    Log.i(GeekDsConstants.TAG, "All activities stopped for re-registration")
                } catch (e: Exception) {
                    Log.e(GeekDsConstants.TAG, "Error stopping activities", e)
                }
            }

            // Cancel background jobs after UI cleanup
            scope.coroutineContext.cancelChildren()

        } catch (e: Exception) {
            Log.e(GeekDsConstants.TAG, "Error in stopAllActivities", e)
        }
    }
