package com.github.deadknight.ardacompanion

import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Opens the short-lived bootstrap Activity when the associated AAOS device
 * appears over Bluetooth. Multiple Android Bluetooth/CDM callbacks can arrive
 * for the same physical connection, so launches are deduplicated here.
 */
object ArdaBootstrapLaunchCoordinator {
    private const val DUPLICATE_WINDOW_MS = 2_500L

    private var lastAddress: String? = null
    private var lastLaunchElapsedMs: Long = 0L

    @Synchronized
    fun launch(
        context: Context,
        address: String,
        source: String,
    ) {
        val normalizedAddress = address.trim().uppercase()
        if (!ArdaBootstrapService.isBluetoothAddress(normalizedAddress)) {
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_BOOTSTRAP_ACTIVITY=IGNORED_INVALID_ADDRESS source=$source",
            )
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (
            lastAddress.equals(normalizedAddress, ignoreCase = true) &&
            now - lastLaunchElapsedMs < DUPLICATE_WINDOW_MS
        ) {
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_BOOTSTRAP_ACTIVITY=DEDUPLICATED source=$source",
            )
            return
        }
        lastAddress = normalizedAddress
        lastLaunchElapsedMs = now

        val intent = Intent(context, BluetoothBootstrapActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            .putExtra(BluetoothBootstrapActivity.EXTRA_AUTO_CONNECT, true)
            .putExtra(ArdaBootstrapService.EXTRA_ADDRESS, normalizedAddress)
            .putExtra(BluetoothBootstrapActivity.EXTRA_TRIGGER_SOURCE, source)

        runCatching { context.startActivity(intent) }
            .onSuccess {
                ArdaCompanionState.log(
                    "ARDA_RN3B_PHONE_BOOTSTRAP_ACTIVITY=OPEN source=$source address=$normalizedAddress",
                )
            }
            .onFailure { error ->
                ArdaCompanionState.log(
                    "ARDA_RN3B_PHONE_BOOTSTRAP_ACTIVITY=FAILED source=$source " +
                        "error=${error.javaClass.simpleName}:${error.message ?: "no-message"}",
                )
            }
    }
}
