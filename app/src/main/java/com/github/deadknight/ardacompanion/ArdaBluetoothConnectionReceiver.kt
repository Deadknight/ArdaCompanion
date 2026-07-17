package com.github.deadknight.ardacompanion

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Process-free Bluetooth trigger. Android wakes this receiver when the bonded
 * AAOS device's ACL link appears, then the receiver opens the short-lived
 * bootstrap Activity. The foreground transport service starts only for an
 * active ARDA session and stops when that session ends.
 */
class ArdaBluetoothConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val device = intent.bluetoothDeviceExtra() ?: return
        val address = runCatching { device.address }.getOrNull() ?: return
        val savedAddress = ArdaBootstrapService.savedAddress(context)

        if (savedAddress.isNullOrBlank()) {
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_BT_BROADCAST=IGNORED_NO_SAVED_ASSOCIATION action=$action",
            )
            return
        }
        if (!savedAddress.equals(address, ignoreCase = true)) {
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_BT_BROADCAST=IGNORED_OTHER_DEVICE action=$action address=$address",
            )
            return
        }

        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                ArdaCompanionState.log(
                    "ARDA_RN3B_PHONE_BT_BROADCAST=ACL_CONNECTED address=$address",
                )
                ArdaCompanionPresence.ensureObserving(context, address)
                ArdaBootstrapService.startSessionFromBackground(
                    context = context,
                    address = address,
                    source = "ACL_CONNECTED",
                )
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                ArdaCompanionState.log(
                    "ARDA_RN3B_PHONE_BT_BROADCAST=ACL_DISCONNECTED address=$address",
                )
                context.stopService(
                    Intent(context, ArdaBootstrapService::class.java),
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDeviceExtra(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}
