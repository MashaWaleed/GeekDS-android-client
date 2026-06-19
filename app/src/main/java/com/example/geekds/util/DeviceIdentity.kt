package com.example.geekds.util

import android.content.ContentResolver
import android.provider.Settings
import android.util.Log
import com.example.geekds.GeekDsConstants
import java.util.UUID

object DeviceIdentity {

    fun generateHardwareBasedUuid(contentResolver: ContentResolver): String {
        val androidId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID,
        ) ?: "fallback-${System.currentTimeMillis()}"

        val namespace = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
        val bytes = (namespace.toString() + androidId).toByteArray()
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest(bytes)

        hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()

        val uuid = UUID.nameUUIDFromBytes(hash)
        Log.i(GeekDsConstants.TAG, "Hardware-based UUID generated from Android ID: ${androidId.take(8)}...")
        return uuid.toString()
    }
}
