package com.github.deadknight.ardacompanion

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import java.util.concurrent.Executors

/**
 * Session-scoped foreground service. It is not a permanent Bluetooth
 * listener: CompanionDeviceService or the ACL receiver starts this service
 * directly when the associated AAOS device appears. The Activity is only used
 * when Android requires explicit user interaction such as first Wi-Fi save.
 */
class ArdaBootstrapService : Service() {
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var client: ArdaRfcommClient? = null
    @Volatile private var activeAddress: String? = null
    @Volatile private var sessionReadyMode: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopClient(intent.getStringExtra(EXTRA_ADDRESS))
            ACTION_CONNECT -> {
                // Required immediately after startForegroundService().
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Resolving AAOS Bluetooth device"),
                )

                val source = intent.getStringExtra(EXTRA_TRIGGER_SOURCE) ?: "UNKNOWN"
                ArdaCompanionState.log(
                    "ARDA_RN3B_PHONE_SESSION_SERVICE=STARTED source=$source",
                )
                val address = resolveAddress(intent.getStringExtra(EXTRA_ADDRESS))
                if (address.isNullOrBlank()) {
                    ArdaCompanionState.log("ARDA_RN3B_PHONE_ADDRESS_MISSING=FAIL")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                } else {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification("Connecting ARDA transport"),
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
                return it.uppercase()
            }

        savedAddress(this)
            ?.trim()
            ?.takeIf(::isBluetoothAddress)
            ?.let {
                ArdaCompanionState.log("ARDA_RN3B_PHONE_ADDRESS_SOURCE=SAVED")
                return it.uppercase()
            }

        associatedAddresses().firstOrNull()?.let {
            ArdaCompanionState.log("ARDA_RN3B_PHONE_ADDRESS_SOURCE=ASSOCIATION")
            return it.uppercase()
        }

        return resolveBondedArdaAddress()?.also {
            ArdaCompanionState.log("ARDA_RN3B_PHONE_ADDRESS_SOURCE=BONDED")
        }?.uppercase()
    }

    @Synchronized
    private fun startClient(address: String) {
        val normalizedAddress = address.uppercase()
        val currentClient = client
        if (
            currentClient != null &&
            activeAddress.equals(normalizedAddress, ignoreCase = true)
        ) {
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_SESSION_ALREADY_ACTIVE=$normalizedAddress",
            )
            sessionReadyMode?.let(::broadcastSessionReady)
            return
        }

        currentClient?.close()
        sessionReadyMode = null
        activeAddress = normalizedAddress

        val newClient = ArdaRfcommClient(
            context = applicationContext,
            onSessionReady = { mode ->
                sessionReadyMode = mode
                updateNotification(
                    if (mode == "reverse_proxy") {
                        "ARDA Wi-Fi and cellular proxy active"
                    } else {
                        "ARDA local Wi-Fi active"
                    },
                )
                broadcastSessionReady(mode)
            },
        )
        client = newClient

        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_ADDRESS, normalizedAddress)
            .apply()
        ArdaCompanionState.selectedDeviceAddress.value = normalizedAddress
        ArdaCompanionState.log("ARDA_RN3B_PHONE_SELECTED_ADDRESS=$normalizedAddress")

        executor.execute {
            try {
                newClient.connectAndRun(
                    bluetoothAddress = normalizedAddress,
                    keepOpen = true,
                )
            } finally {
                synchronized(this) {
                    if (client === newClient) {
                        client = null
                        activeAddress = null
                        sessionReadyMode = null
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun broadcastSessionReady(mode: String) {
        getSystemService(NotificationManager::class.java).apply {
            cancel(ENROLLMENT_NOTIFICATION_ID)
            cancel(CONNECTION_NOTIFICATION_ID)
        }
        sendBroadcast(
            Intent(ACTION_SESSION_READY)
                .setPackage(packageName)
                .putExtra(EXTRA_SESSION_MODE, mode),
        )
        ArdaCompanionState.log(
            "ARDA_RN3B_PHONE_BACKGROUND_SERVICE=ACTIVE mode=$mode",
        )
    }

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
        if (
            Build.VERSION.SDK_INT >= 31 &&
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

        val ranked = candidates.sortedBy(::deviceScore)
        ranked.forEach { device ->
            val name = safeDeviceName(device).ifBlank { "<unknown>" }
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_BONDED_CANDIDATE=${device.address} " +
                    "name=$name score=${deviceScore(device)}",
            )
        }

        return ranked.firstOrNull { deviceScore(it) < SCORE_OTHER }?.address
            ?: ranked.singleOrNull()?.address
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

    @Synchronized
    private fun stopClient(requestedAddress: String?) {
        if (
            !requestedAddress.isNullOrBlank() &&
            !activeAddress.isNullOrBlank() &&
            !activeAddress.equals(requestedAddress, ignoreCase = true)
        ) {
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_SESSION_STOP=IGNORED_OTHER_DEVICE address=$requestedAddress",
            )
            return
        }

        client?.close()
        client = null
        activeAddress = null
        sessionReadyMode = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        ArdaCompanionState.log("ARDA_RN3B_PHONE_SESSION_STOP=PASS")
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "ARDA active transport",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("ARDA Reverse Companion")
            .setContentText(text)
            .setContentIntent(bootstrapPendingIntent(this, enrollmentOnly = false))
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        client?.close()
        client = null
        activeAddress = null
        sessionReadyMode = null
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_CONNECT =
            "com.github.deadknight.ardacompanion.action.CONNECT_RFCOMM"
        const val ACTION_STOP =
            "com.github.deadknight.ardacompanion.action.STOP_RFCOMM"
        const val ACTION_SESSION_READY =
            "com.github.deadknight.ardacompanion.action.SESSION_READY"
        const val EXTRA_ADDRESS = "bluetooth_address"
        const val EXTRA_SESSION_MODE = "session_mode"
        const val EXTRA_TRIGGER_SOURCE = "trigger_source"

        const val PREFS = "arda_companion"
        const val KEY_ADDRESS = "selected_bluetooth_address"

        internal const val CHANNEL_ID = "arda_bt_bootstrap"
        internal const val NOTIFICATION_ID = 3203
        private const val INTERACTION_CHANNEL_ID = "arda_interaction_required"
        private const val ENROLLMENT_NOTIFICATION_ID = 3204
        private const val CONNECTION_NOTIFICATION_ID = 3205
        private const val SCORE_UUID = 0
        private const val SCORE_NAME = 1
        private const val SCORE_OTHER = 2
        private val BLUETOOTH_ADDRESS =
            Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")


        fun startSessionFromBackground(
            context: Context,
            address: String,
            source: String,
        ): Boolean {
            val normalizedAddress = address.trim().uppercase()
            if (!isBluetoothAddress(normalizedAddress)) return false
            val intent = Intent(context, ArdaBootstrapService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_ADDRESS, normalizedAddress)
                .putExtra(EXTRA_TRIGGER_SOURCE, source)
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                ArdaCompanionState.log(
                    "ARDA_RN3B_PHONE_SESSION_SERVICE=START_REQUESTED " +
                        "source=$source address=$normalizedAddress",
                )
                true
            }.getOrElse { error ->
                ArdaCompanionState.log(
                    "ARDA_RN3B_PHONE_SESSION_SERVICE=START_FAILED " +
                        "source=$source error=${error.javaClass.simpleName}:" +
                        (error.message ?: "no-message"),
                )
                notifyConnectionTapRequired(context, normalizedAddress)
                false
            }
        }

        fun notifyEnrollmentRequired(
            context: Context,
            ssid: String,
        ) {
            val manager = context.getSystemService(NotificationManager::class.java)
            ensureInteractionChannel(manager)
            val notification = Notification.Builder(context, INTERACTION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("ARDA Wi-Fi setup required")
                .setContentText("Tap to save $ssid and continue the ARDA connection")
                .setContentIntent(bootstrapPendingIntent(context, enrollmentOnly = true))
                .setOngoing(true)
                .setAutoCancel(false)
                .build()
            manager.notify(ENROLLMENT_NOTIFICATION_ID, notification)
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_SAVED_NETWORK_NOTIFICATION=POSTED ssid=$ssid",
            )
        }

        private fun notifyConnectionTapRequired(
            context: Context,
            address: String,
        ) {
            val manager = context.getSystemService(NotificationManager::class.java)
            ensureInteractionChannel(manager)
            val notification = Notification.Builder(context, INTERACTION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("ARDA connection ready")
                .setContentText("Tap once to allow the ARDA transport session")
                .setContentIntent(
                    bootstrapPendingIntent(
                        context = context,
                        enrollmentOnly = false,
                        explicitAddress = address,
                    ),
                )
                .setAutoCancel(true)
                .build()
            manager.notify(CONNECTION_NOTIFICATION_ID, notification)
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_SESSION_NOTIFICATION=POSTED address=$address",
            )
        }

        private fun ensureInteractionChannel(manager: NotificationManager) {
            manager.createNotificationChannel(
                NotificationChannel(
                    INTERACTION_CHANNEL_ID,
                    "ARDA connection actions",
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }

        private fun bootstrapPendingIntent(
            context: Context,
            enrollmentOnly: Boolean,
            explicitAddress: String? = null,
        ): PendingIntent {
            val address = explicitAddress ?: savedAddress(context)
            val intent = Intent(context, BluetoothBootstrapActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                .putExtra(BluetoothBootstrapActivity.EXTRA_ENROLLMENT_ONLY, enrollmentOnly)
                .putExtra(
                    BluetoothBootstrapActivity.EXTRA_FINISH_WHEN_SESSION_READY,
                    true,
                )
            if (!enrollmentOnly) {
                intent.putExtra(BluetoothBootstrapActivity.EXTRA_AUTO_CONNECT, true)
                intent.putExtra(
                    BluetoothBootstrapActivity.EXTRA_TRIGGER_SOURCE,
                    "NOTIFICATION_TAP",
                )
            }
            if (!address.isNullOrBlank()) {
                intent.putExtra(EXTRA_ADDRESS, address)
            }
            return PendingIntent.getActivity(
                context,
                if (enrollmentOnly) ENROLLMENT_NOTIFICATION_ID else CONNECTION_NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        fun savedAddress(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ADDRESS, null)

        fun isBluetoothAddress(address: String): Boolean =
            BLUETOOTH_ADDRESS.matches(address.trim())
    }
}
