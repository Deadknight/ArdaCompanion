package com.github.deadknight.ardacompanion

import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.util.regex.Pattern

class BluetoothAssociationController(
    private val activity: ComponentActivity,
    private val onLog: (String) -> Unit,
    private val onAddressSelected: (String) -> Unit,
) {
    private val manager =
        activity.getSystemService(CompanionDeviceManager::class.java)

    private val chooserLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            onLog("ARDA_RN3B_PHONE_ASSOCIATION_RESULT=CANCELED")
            return@registerForActivityResult
        }

        val address = extractAddress(result.data)
        if (address != null) {
            saveAddress(address)
            onLog("ARDA_RN3B_PHONE_ASSOCIATION_ADDRESS=$address")
            onAddressSelected(address)
        } else {
            onLog("ARDA_RN3B_PHONE_ASSOCIATION_ADDRESS=PENDING_CALLBACK_OR_BOND")
        }
    }

    fun associateRaspberryPi() {
        val filter = BluetoothDeviceFilter.Builder()
            .setNamePattern(Pattern.compile("(?i).*(Raspberry|RPI|ARDA|Automotive).*"))
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(false)
            .build()

        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                launchChooser(intentSender)
            }

            @Deprecated("Legacy API")
            override fun onDeviceFound(chooserLauncher: IntentSender) {
                launchChooser(chooserLauncher)
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                val address = associationInfo.deviceMacAddress?.toString()
                onLog("ARDA_RN3B_PHONE_ASSOCIATION_CREATED=${associationInfo.id}")
                if (!address.isNullOrBlank()) {
                    saveAddress(address)
                    onAddressSelected(address)
                }
            }

            override fun onFailure(error: CharSequence?) {
                onLog(
                    "ARDA_RN3B_PHONE_ASSOCIATION_FAIL=" +
                        (error?.toString() ?: "unknown"),
                )
            }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            manager.associate(request, activity.mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            manager.associate(request, callback, null)
        }
        onLog("ARDA_RN3B_PHONE_ASSOCIATION_SCAN=STARTED")
    }

    fun savedAddress(): String? =
        activity.getSharedPreferences(
            ArdaBootstrapService.PREFS,
            Activity.MODE_PRIVATE,
        ).getString(ArdaBootstrapService.KEY_ADDRESS, null)

    private fun launchChooser(intentSender: IntentSender) {
        chooserLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
    }

    private fun saveAddress(address: String) {
        activity.getSharedPreferences(
            ArdaBootstrapService.PREFS,
            Activity.MODE_PRIVATE,
        ).edit()
            .putString(ArdaBootstrapService.KEY_ADDRESS, address)
            .apply()
        ArdaCompanionPresence.ensureObserving(
            activity.applicationContext,
            address,
        )
    }

    @Suppress("DEPRECATION")
    private fun extractAddress(intent: Intent?): String? {
        if (intent == null) return null

        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(
                CompanionDeviceManager.EXTRA_ASSOCIATION,
                AssociationInfo::class.java,
            )?.deviceMacAddress?.toString()?.let { return it }

            intent.getParcelableExtra(
                CompanionDeviceManager.EXTRA_DEVICE,
                BluetoothDevice::class.java,
            )?.address?.let { return it }
        } else {
            (intent.getParcelableExtra(
                CompanionDeviceManager.EXTRA_DEVICE,
            ) as? BluetoothDevice)?.address?.let { return it }
        }

        return null
    }
}
