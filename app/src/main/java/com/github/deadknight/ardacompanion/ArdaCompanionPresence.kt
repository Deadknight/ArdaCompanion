package com.github.deadknight.ardacompanion

import android.companion.CompanionDeviceManager
import android.content.Context
import android.os.Build

/**
 * Registers the already-associated AAOS Bluetooth device for system-managed
 * presence callbacks. On Android 12+, CompanionDeviceService can then be bound
 * when the device appears even if the app process is not running.
 */
object ArdaCompanionPresence {
    fun ensureObserving(
        context: Context,
        address: String,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val normalizedAddress = address.trim().uppercase()
        if (!ArdaBootstrapService.isBluetoothAddress(normalizedAddress)) return false

        val manager = context.getSystemService(CompanionDeviceManager::class.java)
            ?: return false
        val associated = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                manager.myAssociations.any {
                    it.deviceMacAddress?.toString()
                        ?.equals(normalizedAddress, ignoreCase = true) == true
                }
            } else {
                @Suppress("DEPRECATION")
                manager.associations.any {
                    it.equals(normalizedAddress, ignoreCase = true)
                }
            }
        }.getOrDefault(false)

        if (!associated) {
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_PRESENCE_OBSERVE=SKIPPED_NOT_ASSOCIATED address=$normalizedAddress",
            )
            return false
        }

        return runCatching {
            @Suppress("DEPRECATION")
            manager.startObservingDevicePresence(normalizedAddress)
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_PRESENCE_OBSERVE=PASS address=$normalizedAddress",
            )
            true
        }.getOrElse { error ->
            // Vendor implementations commonly report an already-observing
            // registration as IllegalStateException. It is still usable.
            val alreadyObserving = error is IllegalStateException &&
                error.message.orEmpty().contains("observ", ignoreCase = true)
            ArdaCompanionState.log(
                if (alreadyObserving) {
                    "ARDA_RN3B_PHONE_PRESENCE_OBSERVE=ALREADY_ACTIVE address=$normalizedAddress"
                } else {
                    "ARDA_RN3B_PHONE_PRESENCE_OBSERVE=FAIL " +
                        "error=${error.javaClass.simpleName}:${error.message ?: "no-message"}"
                },
            )
            alreadyObserving
        }
    }
}
