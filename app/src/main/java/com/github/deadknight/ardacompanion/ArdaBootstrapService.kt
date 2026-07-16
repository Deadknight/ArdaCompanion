package com.github.deadknight.ardacompanion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import java.util.concurrent.Executors

class ArdaBootstrapService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var client: ArdaRfcommClient? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopClient()
            ACTION_CONNECT -> {
                // startForegroundService() requires this call even when validation later fails.
                // Doing address lookup first caused ForegroundServiceDidNotStartInTimeException.
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Resolving AAOS Bluetooth device"),
                )

                val address = resolveAddress(
                    intent.getStringExtra(EXTRA_ADDRESS),
                )

                if (address.isNullOrBlank()) {
                    ArdaCompanionState.log("ARDA_RN3B_PHONE_ADDRESS_MISSING=FAIL")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                } else {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification("Connecting Bluetooth and reverse proxy"),
                    )
                    startClient(address)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun resolveAddress(explicitAddress: String?): String? {
        explicitAddress
            ?.trim()
            ?.takeIf(::isBluetoothAddress)
            ?.let {
                ArdaCompanionState.log("ARDA_RN3B_PHONE_ADDRESS_SOURCE=INTENT")
                return it
            }

        savedAddress()
            ?.trim()
            ?.takeIf(::isBluetoothAddress)
            ?.let {
                ArdaCompanionState.log("ARDA_RN3B_PHONE_ADDRESS_SOURCE=SAVED")
                return it
            }

        associatedAddresses().firstOrNull()?.let {
            ArdaCompanionState.log("ARDA_RN3B_PHONE_ADDRESS_SOURCE=ASSOCIATION")
            return it
        }

        return resolveBondedArdaAddress()?.also {
            ArdaCompanionState.log("ARDA_RN3B_PHONE_ADDRESS_SOURCE=BONDED")
        }
    }

    private fun startClient(address: String) {
        client?.close()
        val newClient = ArdaRfcommClient(applicationContext)
        client = newClient

        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_ADDRESS, address)
            .apply()
        ArdaCompanionState.selectedDeviceAddress.value = address
        ArdaCompanionState.log("ARDA_RN3B_PHONE_SELECTED_ADDRESS=$address")

        executor.execute {
            try {
                newClient.connectAndRun(
                    bluetoothAddress = address,
                    keepOpen = true,
                )
            } finally {
                if (client === newClient) {
                    client = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun savedAddress(): String? =
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_ADDRESS, null)

    private fun associatedAddresses(): List<String> {
        val manager = getSystemService(CompanionDeviceManager::class.java)
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                manager.myAssociations
                    .mapNotNull { it.deviceMacAddress?.toString() }
                    .filter(::isBluetoothAddress)
            } else {
                @Suppress("DEPRECATION")
                manager.associations.filter(::isBluetoothAddress)
            }
        }.getOrElse { error ->
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_ASSOCIATION_LOOKUP_ERROR=" +
                    "${error.javaClass.simpleName}:${error.message ?: "no-message"}",
            )
            emptyList()
        }
    }

    private fun resolveBondedArdaAddress(): String? {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_BLUETOOTH_CONNECT_PERMISSION=FAIL",
            )
            return null
        }

        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            ?: return null
        val candidates = adapter.bondedDevices.orEmpty().toList()
        ArdaCompanionState.log("ARDA_RN3B_PHONE_BONDED_COUNT=${candidates.size}")

        val ranked = candidates.sortedBy { device -> deviceScore(device) }
        ranked.forEach { device ->
            val name = safeDeviceName(device).ifBlank { "<unknown>" }
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_BONDED_CANDIDATE=${device.address} " +
                    "name=$name score=${deviceScore(device)}",
            )
        }

        val likely = ranked.firstOrNull { deviceScore(it) < SCORE_OTHER }
        if (likely != null) return likely.address
        return ranked.singleOrNull()?.address
    }

    private fun deviceScore(device: BluetoothDevice): Int {
        val hasArdaUuid = runCatching {
            device.uuids?.any { it.uuid == ArdaBluetoothProtocol.SERVICE_UUID } == true
        }.getOrDefault(false)
        if (hasArdaUuid) return SCORE_UUID

        val name = safeDeviceName(device)
        if (
            name.contains("Raspberry", ignoreCase = true) ||
            name.contains("RPI", ignoreCase = true) ||
            name.contains("ARDA", ignoreCase = true) ||
            name.contains("Automotive", ignoreCase = true)
        ) {
            return SCORE_NAME
        }
        return SCORE_OTHER
    }

    private fun safeDeviceName(device: BluetoothDevice): String =
        runCatching { device.name.orEmpty() }.getOrDefault("")

    private fun isBluetoothAddress(address: String): Boolean =
        BLUETOOTH_ADDRESS.matches(address.trim())

    private fun stopClient() {
        client?.close()
        client = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "ARDA Bluetooth and reverse proxy",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("ARDA Reverse Companion")
            .setContentText(text)
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        client?.close()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_CONNECT =
            "com.github.deadknight.ardacompanion.action.CONNECT_RFCOMM"
        const val ACTION_STOP =
            "com.github.deadknight.ardacompanion.action.STOP_RFCOMM"
        const val EXTRA_ADDRESS = "bluetooth_address"

        const val PREFS = "arda_companion"
        const val KEY_ADDRESS = "selected_bluetooth_address"

        private const val CHANNEL_ID = "arda_bt_bootstrap"
        private const val NOTIFICATION_ID = 3203
        private const val SCORE_UUID = 0
        private const val SCORE_NAME = 1
        private const val SCORE_OTHER = 2
        private val BLUETOOTH_ADDRESS =
            Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")
    }
}
