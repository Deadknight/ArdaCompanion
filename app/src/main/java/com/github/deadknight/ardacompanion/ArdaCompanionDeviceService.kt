package com.github.deadknight.ardacompanion

import android.companion.CompanionDeviceService
import android.content.Intent

/**
 * Companion Device Manager presence callback. This is a system-bound listener,
 * not an always-running ARDA foreground service. Presence opens the same
 * short-lived Activity used by the Bluetooth ACL broadcast receiver.
 */
class ArdaCompanionDeviceService : CompanionDeviceService() {
    @Deprecated("Legacy callback retained for Android 12-15")
    override fun onDeviceAppeared(address: String) {
        ArdaCompanionState.log("ARDA_RN3B_PHONE_DEVICE_APPEARED=$address")
        ArdaBootstrapService.startSessionFromBackground(
            context = this,
            address = address,
            source = "COMPANION_DEVICE_APPEARED",
        )
    }

    @Deprecated("Legacy callback retained for Android 12-15")
    override fun onDeviceDisappeared(address: String) {
        ArdaCompanionState.log("ARDA_RN3B_PHONE_DEVICE_DISAPPEARED=$address")
        stopService(Intent(this, ArdaBootstrapService::class.java))
    }
}
