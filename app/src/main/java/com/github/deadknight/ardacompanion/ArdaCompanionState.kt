package com.github.deadknight.ardacompanion

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

object ArdaCompanionState {
    private const val TAG = "ARDA_RN3B_PHONE"
    private val mainHandler = Handler(Looper.getMainLooper())

    val logs = mutableStateListOf<String>()
    val connected = mutableStateOf(false)
    val gatePassed = mutableStateOf(false)
    val wifiConnected = mutableStateOf(false)
    val cellularConnected = mutableStateOf(false)
    val proxyReady = mutableStateOf(false)
    val proxyEndpoint = mutableStateOf<String?>(null)
    val selectedDeviceAddress = mutableStateOf<String?>(null)

    fun log(line: String) {
        Log.i(TAG, line)
        mainHandler.post {
            logs += line
            while (logs.size > 500) logs.removeAt(0)
        }
    }

    fun resetTransportState() {
        mainHandler.post {
            connected.value = false
            gatePassed.value = false
            wifiConnected.value = false
            cellularConnected.value = false
            proxyReady.value = false
            proxyEndpoint.value = null
        }
    }
}
