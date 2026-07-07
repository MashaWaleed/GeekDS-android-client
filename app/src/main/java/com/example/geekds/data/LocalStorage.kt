package com.example.geekds.data

import android.content.Context
import android.util.Log
import com.example.geekds.GeekDsConstants
import com.example.geekds.model.AdsConfig
import com.example.geekds.model.Playlist
import com.example.geekds.model.Schedule
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object LocalStorage {

    fun saveAllSchedules(context: Context, schedules: List<Schedule>) {
        prefs(context).edit().putString("all_schedules", Gson().toJson(schedules)).apply()
        Log.i(GeekDsConstants.TAG, "Saved ${schedules.size} schedules for offline use")
    }

    fun loadAllSchedules(context: Context): List<Schedule>? {
        val json = prefs(context).getString("all_schedules", null) ?: return null
        return try {
            val type = object : TypeToken<List<Schedule>>() {}.type
            Gson().fromJson<List<Schedule>>(json, type)
        } catch (e: Exception) {
            Log.e(GeekDsConstants.TAG, "Failed to load all schedules", e)
            null
        }
    }

    fun savePlaylist(context: Context, playlist: Playlist) {
        prefs(context).edit().putString("playlist", Gson().toJson(playlist)).apply()
    }

    fun loadPlaylist(context: Context): Playlist? {
        val json = prefs(context).getString("playlist", null) ?: return null
        return Gson().fromJson(json, Playlist::class.java)
    }

    fun savePlaylistById(context: Context, playlistId: Int, playlist: Playlist) {
        prefs(context).edit().putString("playlist_$playlistId", Gson().toJson(playlist)).apply()
        Log.i(GeekDsConstants.TAG, "Saved playlist $playlistId with ${playlist.mediaFiles.size} files")
    }

    fun loadPlaylistById(context: Context, playlistId: Int): Playlist? {
        val json = prefs(context).getString("playlist_$playlistId", null) ?: return null
        return try {
            Gson().fromJson(json, Playlist::class.java)
        } catch (e: Exception) {
            Log.e(GeekDsConstants.TAG, "Failed to load playlist $playlistId", e)
            null
        }
    }

    fun getCachedPlaylistUpdatedAt(context: Context, playlistId: Int): String? {
        return prefs(context).getString("playlist_${playlistId}_updated_at", null)
    }

    fun saveCachedPlaylistUpdatedAt(context: Context, playlistId: Int, updatedAt: String) {
        prefs(context).edit().putString("playlist_${playlistId}_updated_at", updatedAt).apply()
    }

    fun saveDeviceId(context: Context, id: Int) {
        prefs(context).edit().putInt("device_id", id).apply()
    }

    fun loadDeviceId(context: Context): Int? {
        val id = prefs(context).getInt("device_id", -1)
        return if (id != -1) id else null
    }

    fun saveDeviceName(context: Context, name: String) {
        prefs(context).edit().putString("device_name", name).apply()
        Log.i(GeekDsConstants.TAG, "Device name saved: '$name'")
    }

    fun loadDeviceName(context: Context): String? {
        return prefs(context).getString("device_name", null)
    }

    fun saveDeviceUuid(context: Context, uuid: String) {
        prefs(context).edit().putString("device_uuid", uuid).apply()
        Log.i(GeekDsConstants.TAG, "Device UUID saved: '$uuid'")
    }

    fun loadDeviceUuid(context: Context): String? {
        return prefs(context).getString("device_uuid", null)
    }

    fun saveOrientation(context: Context, degrees: Int) {
        prefs(context).edit().putInt("device_orientation", degrees).apply()
        Log.i(GeekDsConstants.TAG, "Device orientation saved: ${degrees}°")
    }

    fun loadOrientation(context: Context): Int {
        return prefs(context).getInt("device_orientation", 0)
    }

    fun saveAdsVersion(context: Context, version: Long) {
        prefs(context).edit().putLong("ads_version", version).apply()
    }

    fun loadAdsVersion(context: Context): Long {
        return prefs(context).getLong("ads_version", 0L)
    }

    fun saveAdsConfig(context: Context, config: AdsConfig) {
        prefs(context).edit().putString("ads_config", Gson().toJson(config)).apply()
        Log.i(GeekDsConstants.TAG, "Saved ads config version=${config.version}")
    }

    fun loadAdsConfig(context: Context): AdsConfig? {
        val json = prefs(context).getString("ads_config", null) ?: return null
        return try {
            Gson().fromJson(json, AdsConfig::class.java)
        } catch (e: Exception) {
            Log.e(GeekDsConstants.TAG, "Failed to load ads config", e)
            null
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(GeekDsConstants.PREFS_NAME, Context.MODE_PRIVATE)
}
