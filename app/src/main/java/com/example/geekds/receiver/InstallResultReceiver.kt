package com.example.geekds.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.example.geekds.GeekDsConstants

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val status = intent?.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        Log.i(GeekDsConstants.TAG, "Installation result received: status=$status, message=$message")

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(GeekDsConstants.TAG, "Installation completed successfully")
            }
            else -> {
                Log.w(GeekDsConstants.TAG, "Installation status: $status - $message")
            }
        }
    }
}
