package com.github.deadknight.ardacompanion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ArdaCompanionTheme {
                ArdaCompanionApp()
            }
        }
    }
}

private enum class ProbeStatus {
    Idle,
    Running,
    Passed,
    Failed,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArdaCompanionApp() {
    val context = LocalContext.current
    val runner = remember {
        DualNetworkProbeRunner(context.applicationContext)
    }
    val logs = remember {
        mutableStateListOf(
            "ARDA_RN2_APP_READY=YES",
            "ARDA_RN2_GEARHEAD_USED=NO",
            "ARDA_RN2_BIND_PROCESS_TO_NETWORK_USED=NO",
            "ARDA_RN2_UI=JETPACK_COMPOSE",
        )
    }

    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("5278") }
    var cellularUrl by rememberSaveable {
        mutableStateOf("https://example.com/")
    }
    var status by rememberSaveable { mutableStateOf(ProbeStatus.Idle) }
    var inputError by rememberSaveable { mutableStateOf<String?>(null) }

    DisposableEffect(runner) {
        onDispose {
            runner.close()
        }
    }

    fun appendLog(line: String) {
        logs += line
    }

    fun runProbe() {
        val parsedPort = port.toIntOrNull()
        when {
            parsedPort == null || parsedPort !in 1..65535 -> {
                inputError = "AAOS port must be between 1 and 65535."
                status = ProbeStatus.Failed
            }
            !cellularUrl.startsWith("https://") &&
                !cellularUrl.startsWith("http://") -> {
                inputError = "Cellular probe URL must start with http:// or https://."
                status = ProbeStatus.Failed
            }
            else -> {
                inputError = null
                status = ProbeStatus.Running
                runner.start(
                    config = ProbeConfig(
                        aaosHostOverride = host.trim(),
                        aaosPort = parsedPort,
                        cellularProbeUrl = cellularUrl.trim(),
                    ),
                    onLog = ::appendLog,
                    onFinished = { passed ->
                        status = if (passed) {
                            ProbeStatus.Passed
                        } else {
                            ProbeStatus.Failed
                        }
                    },
                )
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "ARDA Reverse Companion",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "RN-2 network proof",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    context,
                                    BluetoothBootstrapActivity::class.java,
                                ),
                            )
                        },
                    ) {
                        Text("Bluetooth")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IntroCard()

            ConfigurationCard(
                host = host,
                onHostChanged = { host = it },
                port = port,
                onPortChanged = { port = it.filter(Char::isDigit) },
                cellularUrl = cellularUrl,
                onCellularUrlChanged = { cellularUrl = it },
                running = status == ProbeStatus.Running,
                inputError = inputError,
                onRun = ::runProbe,
            )

            StatusCard(status = status)

            LogHeader(
                onClear = { logs.clear() },
                onCopy = {
                    val text = logs.joinToString("\n")
                    val clipboard = context.getSystemService(
                        Context.CLIPBOARD_SERVICE,
                    ) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("ARDA RN-2 log", text),
                    )
                    Toast.makeText(
                        context,
                        "Log copied.",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )

            LogPanel(
                logs = logs,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun IntroCard() {
    ArdaCard {
        Text(
            text = "Two independent network paths",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Wi-Fi socket → AAOS local-only hotspot\n" +
                "Cellular socket → public HTTPS",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Gearhead is not used. The process is never globally " +
                "bound to cellular; each socket is bound to its own Network.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConfigurationCard(
    host: String,
    onHostChanged: (String) -> Unit,
    port: String,
    onPortChanged: (String) -> Unit,
    cellularUrl: String,
    onCellularUrlChanged: (String) -> Unit,
    running: Boolean,
    inputError: String?,
    onRun: () -> Unit,
) {
    ArdaCard {
        Text(
            text = "Probe configuration",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = host,
            onValueChange = onHostChanged,
            modifier = Modifier.fillMaxWidth(),
            enabled = !running,
            singleLine = true,
            label = { Text("AAOS host") },
            supportingText = {
                Text("Leave blank to discover the Wi-Fi gateway.")
            },
        )

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = port,
            onValueChange = onPortChanged,
            modifier = Modifier.fillMaxWidth(),
            enabled = !running,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
            ),
            label = { Text("AAOS port") },
        )

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = cellularUrl,
            onValueChange = onCellularUrlChanged,
            modifier = Modifier.fillMaxWidth(),
            enabled = !running,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
            ),
            label = { Text("Cellular HTTPS probe URL") },
        )

        inputError?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(14.dp))

        Button(
            onClick = onRun,
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .height(18.dp),
                    strokeWidth = 2.dp,
                )
                Text("Running proof…")
            } else {
                Text("Run dual-network proof")
            }
        }
    }
}

@Composable
private fun StatusCard(status: ProbeStatus) {
    val container = when (status) {
        ProbeStatus.Idle -> MaterialTheme.colorScheme.surfaceVariant
        ProbeStatus.Running -> MaterialTheme.colorScheme.primaryContainer
        ProbeStatus.Passed -> MaterialTheme.colorScheme.tertiaryContainer
        ProbeStatus.Failed -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (status) {
        ProbeStatus.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
        ProbeStatus.Running -> MaterialTheme.colorScheme.onPrimaryContainer
        ProbeStatus.Passed -> MaterialTheme.colorScheme.onTertiaryContainer
        ProbeStatus.Failed -> MaterialTheme.colorScheme.onErrorContainer
    }
    val text = when (status) {
        ProbeStatus.Idle ->
            "Connect to ARDA-RN2, keep mobile data enabled, then run the proof."
        ProbeStatus.Running ->
            "Waiting for Wi-Fi and cellular networks, then testing both sockets."
        ProbeStatus.Passed ->
            "RN-2 passed: AAOS local Wi-Fi and cellular internet work together."
        ProbeStatus.Failed ->
            "RN-2 failed. Copy the log and inspect the first FAIL marker."
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = container,
            contentColor = content,
        ),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun LogHeader(
    onClear: () -> Unit,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Marker log",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onClear) {
                Text("Clear")
            }
            OutlinedButton(onClick = onCopy) {
                Text("Copy")
            }
        }
    }
}

@Composable
private fun LogPanel(
    logs: List<String>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No log entries.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                itemsIndexed(
                    items = logs,
                    key = { index, item -> "$index-$item" },
                ) { index, line ->
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            line.endsWith("=PASS") ->
                                MaterialTheme.colorScheme.tertiary
                            line.contains("=FAIL") ||
                                line.contains("_ERROR=") ->
                                MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                    if (index < logs.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArdaCard(
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        content = {
            Column(
                modifier = Modifier.padding(16.dp),
                content = content,
            )
        },
    )
}
