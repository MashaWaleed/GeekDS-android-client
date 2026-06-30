package com.example.geekds
import android.app.AlertDialog
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import android.util.Log
import com.example.geekds.data.LocalStorage
import com.example.geekds.util.DeviceIdentity
import com.example.geekds.util.NetworkUtils

internal fun MainActivity.checkRegistrationByUuid(uuid: String, pollingRunnable: Runnable) {
        registrationCheckAttempts++

        val uuidReq = Request.Builder()
            .url("$cmsUrl/api/devices/check-registration/by-uuid/$uuid")
            .get()
            .build()

        client.newCall(uuidReq).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Network error - retry with exponential backoff
                if (state == AppState.REGISTERING) {
                    val delay = calculateRegistrationDelay()
                    Log.d(GeekDsConstants.TAG, "[REGISTERING] UUID check failed (network error), retrying in ${delay}ms: ${e.message}")
                    handler.postDelayed(pollingRunnable, delay)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    try {
                        val json = JSONObject(body)
                        val registered = json.getBoolean("registered")
                        if (registered) {
                            val deviceJson = json.getJSONObject("device")
                            val newId = deviceJson.getInt("id")
                            val newName = deviceJson.getString("name")
                            runOnUiThread {
                                stopRegistrationPolling()
                                currentRegistrationDialog?.dismiss()
                                LocalStorage.saveDeviceId(this@checkRegistrationByUuid, newId)
                                LocalStorage.saveDeviceName(this@checkRegistrationByUuid, newName)
                                deviceId = newId
                                deviceName = newName
                                setState(AppState.IDLE, "Device registered (UUID: ${uuid.take(8)}...)")
                                startBackgroundTasks()
                            }
                            return
                        } else {
                            // Not registered yet - keep polling
                            if (state == AppState.REGISTERING) {
                                val delay = calculateRegistrationDelay()
                                Log.d(GeekDsConstants.TAG, "[REGISTERING] Not registered yet (UUID: ${uuid.take(8)}...), retry in ${delay}ms")
                                handler.postDelayed(pollingRunnable, delay)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(GeekDsConstants.TAG, "[REGISTERING] Error parsing UUID check response: ${e.message}")
                        if (state == AppState.REGISTERING) {
                            val delay = calculateRegistrationDelay()
                            handler.postDelayed(pollingRunnable, delay)
                        }
                    }
                } else {
                    // Server error or bad response
                    if (state == AppState.REGISTERING) {
                        val delay = calculateRegistrationDelay()
                        Log.d(GeekDsConstants.TAG, "[REGISTERING] Server error (HTTP ${response.code}), retry in ${delay}ms")
                        handler.postDelayed(pollingRunnable, delay)
                    }
                }
            }
        })
    }

internal fun MainActivity.showRegistrationScreen() {
        runOnUiThread {
            // Stop all activities first
            stopAllActivities()
            setState(AppState.REGISTERING, "Device needs registration...")

            // Show registration dialog immediately while requesting code
            showWaitingDialog()

            // Request a registration code from the server
            val ip = NetworkUtils.getLocalIpAddress() ?: "unknown"
            // Start polling immediately by IP/UUID as a safety net
            startRegistrationPolling(ip)
            requestRegistrationCode()
        }
    }

internal fun MainActivity.showWaitingDialog() {
        currentRegistrationDialog?.dismiss()
        currentRegistrationDialog = AlertDialog.Builder(this)
            .setTitle("Device Registration Required")
            .setMessage("Requesting registration code from server...\n\nPlease wait...")
            .setCancelable(false)
            .show()
        applyDialogRotation(deviceOrientation)
    }

internal fun MainActivity.requestRegistrationCode() {
        val currentIp = NetworkUtils.getLocalIpAddress() ?: "unknown"
        val currentUuid = deviceUuid ?: "unknown"

        val json = JSONObject().apply {
            put("ip", currentIp)
            put("uuid", currentUuid)  // Send UUID for server-side tracking
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$cmsUrl/api/devices/register-request")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    setState(AppState.ERROR, "Failed to request registration code: ${e.message}")
                    showErrorDialog("Network Error", "Could not connect to server to get registration code.\n\nRetrying in 5 seconds...")
                    handler.postDelayed({ requestRegistrationCode() }, 5000)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    try {
                        val jsonResponse = JSONObject(responseBody)
                        val code = jsonResponse.getString("code")

                        runOnUiThread {
                            showRegistrationDialog(code)
                            startRegistrationPolling(currentIp)
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            setState(AppState.ERROR, "Failed to parse registration response")
                            showErrorDialog("Server Error", "Invalid response from server.\n\nRetrying in 5 seconds...")
                            handler.postDelayed({ requestRegistrationCode() }, 5000)
                        }
                    }
                } else {
                    runOnUiThread {
                        setState(AppState.ERROR, "Failed to get registration code: HTTP ${response.code}")
                        showErrorDialog("Server Error", "Server returned error: ${response.code}\n\nRetrying in 5 seconds...")
                        handler.postDelayed({ requestRegistrationCode() }, 5000)
                    }
                }
            }
        })
    }

internal fun MainActivity.showErrorDialog(title: String, message: String) {
        currentRegistrationDialog?.dismiss()
        currentRegistrationDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Retry Now") { _, _ ->
                requestRegistrationCode()
            }
            .setCancelable(false)
            .show()
        applyDialogRotation(deviceOrientation)
    }

internal fun MainActivity.startRegistrationPolling(ip: String) {
        // Stop any existing polling
        registrationPollingRunnable?.let { handler.removeCallbacks(it) }
        registrationCheckAttempts = 0 // Reset attempt counter

        registrationPollingRunnable = object : Runnable {
            override fun run() {
                val attempt = registrationCheckAttempts + 1
                Log.d(GeekDsConstants.TAG, "[REGISTERING] Poll attempt=$attempt")
                val uuid = deviceUuid
                if (uuid != null) {
                    Log.d(GeekDsConstants.TAG, "[REGISTERING] Checking registration by UUID ONLY: $uuid")
                    checkRegistrationByUuid(uuid, this)
                } else {
                    Log.e(GeekDsConstants.TAG, "[REGISTERING] ERROR: No UUID available! Cannot check registration.")
                    // Retry generating UUID
                    deviceUuid = DeviceIdentity.generateHardwareBasedUuid(contentResolver)
                    LocalStorage.saveDeviceUuid(this@startRegistrationPolling, deviceUuid!!)
                    handler.postDelayed(this, 5000)
                }
            }
        }

        // Start polling immediately
        Log.d(GeekDsConstants.TAG, "[REGISTERING] Starting polling loop now")
        handler.post(registrationPollingRunnable!!)
    }

internal fun MainActivity.calculateRegistrationDelay(): Long {
        val baseDelay = 1000L
        val maxDelay = 15000L
        val attempt = if (registrationCheckAttempts < 1) 1 else registrationCheckAttempts
        val delay = baseDelay * (1L shl minOf(attempt - 1, 4))
        return minOf(delay, maxDelay)
    }

internal fun MainActivity.stopRegistrationPolling() {
        registrationPollingRunnable?.let { handler.removeCallbacks(it) }
        Log.d(GeekDsConstants.TAG, "[REGISTERING] Stopped polling loop")
        registrationPollingRunnable = null
    }

internal fun MainActivity.showRegistrationDialog(code: String) {
        currentRegistrationDialog?.dismiss()
        currentRegistrationDialog = AlertDialog.Builder(this)
            .setTitle("Device Registration")
            .setMessage("Please register this device in the CMS dashboard:\n\n" +
                    "Registration Code: $code\n\n" +
                    "Steps:\n" +
                    "1. Open CMS Dashboard\n" +
                    "2. Click 'Add Device'\n" +
                    "3. Enter code: $code\n" +
                    "4. Enter device name\n" +
                    "5. Click 'Register'\n\n" +
                    "Waiting for registration with smart retry...")
            .setPositiveButton("Check Now") { _, _ ->
                // Reset attempts for immediate check
                registrationCheckAttempts = 0
                registrationPollingRunnable?.let {
                    handler.removeCallbacks(it)
                    handler.post(it) // Check immediately
                }
                // Redisplay the dialog
                showRegistrationDialog(code)
            }
            .setNegativeButton("Get New Code") { _, _ ->
                stopRegistrationPolling()
                requestRegistrationCode()
            }
            .setNeutralButton("Check Network") { _, _ ->
                showNetworkInfo()
            }
            .setCancelable(false)
            .show()
        applyDialogRotation(deviceOrientation)
    }

internal fun MainActivity.showNetworkInfo() {
        val ip = NetworkUtils.getLocalIpAddress() ?: "No IP"
        val networkConnected = isNetworkConnected()

        currentRegistrationDialog?.dismiss()
        currentRegistrationDialog = AlertDialog.Builder(this)
            .setTitle("Network Information")
            .setMessage("Device IP: $ip\n" +
                    "Network Connected: ${if(networkConnected) "Yes" else "No"}\n" +
                    "Server URL: $cmsUrl\n\n" +
                    "Make sure:\n" +
                    "• Device has internet connection\n" +
                    "• CMS server is running\n" +
                    "• Server URL is correct")
            .setPositiveButton("Continue Registration") { _, _ ->
                requestRegistrationCode()
            }
            .setNegativeButton("Retry Connection") { _, _ ->
                requestRegistrationCode()
            }
            .setCancelable(false)
            .show()
        applyDialogRotation(deviceOrientation)
    }