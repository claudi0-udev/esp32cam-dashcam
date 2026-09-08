package com.example.esp32dashcam.wifi

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.provider.Settings

class WifiManagerHelper(private val context: Context) {

    private val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Connects directly to the ESP32 Dashcam Wi-Fi AP using WifiNetworkSpecifier (NO Location permission required).
     * Supported on Android 10+ (API 29+).
     */
    fun connectDirectly(
        ssid: String = "Dashcam-WiFi",
        passphrase: String = "12345678",
        onConnected: () -> Unit,
        onFailed: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(passphrase)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            releaseNetworkCallback()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    // Bind app socket traffic exclusively to Dashcam Wi-Fi
                    connectivityManager.bindProcessToNetwork(network)
                    onConnected()
                }

                override fun onUnavailable() {
                    super.onUnavailable()
                    onFailed("No se pudo conectar a $ssid. Verifica que la dashcam esté encendida en modo Wi-Fi.")
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    connectivityManager.bindProcessToNetwork(null)
                }
            }
            networkCallback = callback
            try {
                connectivityManager.requestNetwork(request, callback)
            } catch (e: Exception) {
                onFailed("Error al solicitar conexión: ${e.message}")
            }
        } else {
            // Android 9 or lower: Launch Wi-Fi panel seamlessly
            openWifiSettings()
        }
    }

    /**
     * Opens native lightweight Android Wi-Fi panel (Floating modal on Android 10+)
     */
    fun openWifiSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val panelIntent = Intent(Settings.Panel.ACTION_WIFI).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(panelIntent)
            } else {
                val wifiIntent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(wifiIntent)
            }
        } catch (e: Exception) {
            val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
        }
    }

    fun releaseNetworkCallback() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
                connectivityManager.bindProcessToNetwork(null)
            } catch (_: Exception) {}
            networkCallback = null
        }
    }
}
