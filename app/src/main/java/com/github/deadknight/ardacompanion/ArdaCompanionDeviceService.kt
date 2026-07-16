package com.github.deadknight.ardacompanion

import android.companion.CompanionDeviceService
import android.content.Intent
import android.os.Build

class ArdaCompanionDeviceService : CompanionDeviceService() {
    @Deprecated("Legacy callback retained for Android 12-15")
    override fun onDeviceAppeared(address: String) {
        ArdaCompanionState.log("ARDA_RN3B_PHONE_DEVICE_APPEARED=$address")
        startBootstrap(address)
    }

    @Deprecated("Legacy callback retained for Android 12-15")
    override fun onDeviceDisappeared(address: String) {
        ArdaCompanionState.log("ARDA_RN3B_PHONE_DEVICE_DISAPPEARED=$address")
    }

    private fun startBootstrap(address: String) {
        val intent = Intent(this, ArdaBootstrapService::class.java)
            .setAction(ArdaBootstrapService.ACTION_CONNECT)
            .putExtra(ArdaBootstrapService.EXTRA_ADDRESS, address)

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
