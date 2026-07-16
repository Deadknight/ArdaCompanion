package com.github.deadknight.ardacompanion

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class BluetoothBootstrapActivity : ComponentActivity() {
    private lateinit var associationController: BluetoothAssociationController
    private lateinit var autoPermissionLauncher: ActivityResultLauncher<Array<String>>
    private var pendingAutoConnectAddress: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register every Activity Result launcher before this Activity reaches STARTED.
        autoPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { result ->
            val granted = result.values.all { it }
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_AUTO_PERMISSION=" +
                    if (granted) "PASS" else "FAIL",
            )
            val address = pendingAutoConnectAddress
            pendingAutoConnectAddress = null
            if (granted && !address.isNullOrBlank()) {
                startBootstrapService(this, address)
            }
        }

        // Creating this controller from a Composable is too late on resumed activities.
        associationController = BluetoothAssociationController(
            activity = this,
            onLog = ArdaCompanionState::log,
            onAddressSelected = { address ->
                ArdaCompanionState.selectedDeviceAddress.value = address
            },
        )

        val explicitAddress = intent
            .getStringExtra(ArdaBootstrapService.EXTRA_ADDRESS)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val savedAddress = associationController.savedAddress()
        val selectedAddress = explicitAddress ?: savedAddress
        ArdaCompanionState.selectedDeviceAddress.value = selectedAddress

        setContent {
            ArdaCompanionTheme {
                BluetoothBootstrapScreen(associationController)
            }
        }

        if (intent.getBooleanExtra(EXTRA_AUTO_CONNECT, false)) {
            beginAutoConnect(selectedAddress)
        }
    }

    private fun beginAutoConnect(address: String?) {
        if (address.isNullOrBlank()) {
            ArdaCompanionState.log("ARDA_RN3B_PHONE_ADDRESS_MISSING=FAIL")
            return
        }

        val missing = requiredCompanionRuntimePermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            ArdaCompanionState.log("ARDA_RN3B_PHONE_AUTO_PERMISSION=PASS")
            startBootstrapService(this, address)
        } else {
            pendingAutoConnectAddress = address
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_AUTO_PERMISSION=REQUEST count=${missing.size}",
            )
            autoPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    companion object {
        const val EXTRA_AUTO_CONNECT = "arda_auto_connect"
    }
}

private fun requiredCompanionRuntimePermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= 33) {
        add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}.toTypedArray()

private fun startBootstrapService(context: Context, address: String) {
    context.startForegroundService(
        Intent(context, ArdaBootstrapService::class.java)
            .setAction(ArdaBootstrapService.ACTION_CONNECT)
            .putExtra(ArdaBootstrapService.EXTRA_ADDRESS, address),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BluetoothBootstrapScreen(
    controller: BluetoothAssociationController,
) {
    val context = LocalContext.current
    val connected by ArdaCompanionState.connected
    val passed by ArdaCompanionState.gatePassed
    val wifiConnected by ArdaCompanionState.wifiConnected
    val cellularConnected by ArdaCompanionState.cellularConnected
    val proxyReady by ArdaCompanionState.proxyReady
    val proxyEndpoint by ArdaCompanionState.proxyEndpoint
    val selectedAddress by ArdaCompanionState.selectedDeviceAddress

    var manualAddress by rememberSaveable {
        mutableStateOf(selectedAddress ?: controller.savedAddress().orEmpty())
    }

    LaunchedEffect(selectedAddress) {
        selectedAddress
            ?.takeIf { it.isNotBlank() && it != manualAddress }
            ?.let { manualAddress = it }
    }

    var pendingManualConnectAddress by rememberSaveable {
        mutableStateOf<String?>(null)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.values.all { it }
        ArdaCompanionState.log(
            "ARDA_RN3B_PHONE_NEARBY_PERMISSION=" +
                if (granted) "PASS" else "FAIL",
        )
        val address = pendingManualConnectAddress
        pendingManualConnectAddress = null
        if (granted && !address.isNullOrBlank()) {
            startBootstrapService(context, address)
        }
    }

    fun grantPermissions() {
        val missing = requiredCompanionRuntimePermissions().filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            ArdaCompanionState.log("ARDA_RN3B_PHONE_NEARBY_PERMISSION=PASS")
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    fun connect() {
        val address = manualAddress.trim()
        if (address.isBlank()) {
            ArdaCompanionState.log("ARDA_RN3B_PHONE_ADDRESS_MISSING=FAIL")
            return
        }

        ArdaCompanionState.selectedDeviceAddress.value = address
        val missing = requiredCompanionRuntimePermissions().filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startBootstrapService(context, address)
        } else {
            pendingManualConnectAddress = address
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    fun disconnect() {
        context.startService(
            Intent(context, ArdaBootstrapService::class.java)
                .setAction(ArdaBootstrapService.ACTION_STOP),
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "ARDA Bluetooth Bootstrap",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Bluetooth bootstrap + cellular reverse proxy",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        proxyReady -> Color(0xFFD7F8E1)
                        passed -> Color(0xFFDCE9FF)
                        connected -> Color(0xFFDCE9FF)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "e43cd6de-4bb2-49fe-8d43-ecace91038cc",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            proxyReady -> "RN-3B PASS — AAOS proxy uses phone cellular (${proxyEndpoint ?: "?"})."
                            passed -> "Bluetooth gate passed; joining AAOS local Wi-Fi."
                            connected -> "RFCOMM connected; waiting for bootstrap."
                            else -> "Grant permission, associate/bond, then connect."
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Local Wi-Fi: " + if (wifiConnected) "connected" else "not connected")
                    Text("Cellular: " + if (cellularConnected) "bound" else "not bound")
                    Text("Proxy: " + if (proxyReady) (proxyEndpoint ?: "ready") else "not ready")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = ::grantPermissions, modifier = Modifier.weight(1f)) {
                    Text("Grant Nearby")
                }
                Button(
                    onClick = controller::associateRaspberryPi,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Associate Pi")
                }
            }

            OutlinedTextField(
                value = manualAddress,
                onValueChange = { manualAddress = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Pi Bluetooth address") },
                supportingText = {
                    Text("Filled after association; manual entry is allowed.")
                },
                singleLine = true,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = ::connect,
                    enabled = !connected,
                    modifier = Modifier.weight(1f),
                ) { Text("Connect + Proxy") }
                OutlinedButton(
                    onClick = ::disconnect,
                    enabled = connected,
                    modifier = Modifier.weight(1f),
                ) { Text("Disconnect") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Bluetooth marker log", fontWeight = FontWeight.SemiBold)
                OutlinedButton(
                    onClick = {
                        val text = ArdaCompanionState.logs.joinToString("\n")
                        val clipboard = context.getSystemService(
                            Context.CLIPBOARD_SERVICE,
                        ) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("ARDA RN-3B log", text),
                        )
                        Toast.makeText(
                            context,
                            "Bluetooth log copied.",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                ) { Text("Copy") }
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                items(ArdaCompanionState.logs) { line ->
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            line.endsWith("=PASS") -> Color(0xFF087A36)
                            line.contains("=FAIL") || line.contains("_ERROR=") ->
                                MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
            }
        }
    }
}
