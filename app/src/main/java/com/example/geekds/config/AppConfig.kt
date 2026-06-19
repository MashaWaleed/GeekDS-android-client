package com.example.geekds.config

import android.content.Context
import android.util.Log
import com.example.geekds.GeekDsConstants
import org.json.JSONObject
import java.io.File

object AppConfig {

    fun loadExternalConfig(context: Context): JSONObject? {
        return try {
            val configFile = File(context.getExternalFilesDir(null), "config.json")
            Log.i(GeekDsConstants.TAG, "Checking config at: ${configFile.absolutePath}")
            Log.i(GeekDsConstants.TAG, "File exists: ${configFile.exists()}, canRead: ${configFile.canRead()}")

            if (configFile.exists() && configFile.canRead()) {
                val content = configFile.readText()
                Log.i(GeekDsConstants.TAG, "Loaded config from: ${configFile.absolutePath}")
                JSONObject(content)
            } else {
                Log.w(GeekDsConstants.TAG, "Config file not found at: ${configFile.absolutePath}")
                null
            }
        } catch (e: Exception) {
            Log.e(GeekDsConstants.TAG, "Error reading config: ${e.message}")
            null
        }
    }
}
