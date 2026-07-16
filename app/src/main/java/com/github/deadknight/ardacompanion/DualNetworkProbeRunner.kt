package com.github.deadknight.ardacompanion

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class ProbeConfig(
    val aaosHostOverride: String,
    val aaosPort: Int,
    val cellularProbeUrl: String,
)

class DualNetworkProbeRunner(context: Context) : Closeable {
    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var cellularCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiNetwork: Network? = null
    private var cellularNetwork: Network? = null
    private var running = false
    private val proofStarted = AtomicBoolean(false)

    fun start(
        config: ProbeConfig,
        onLog: (String) -> Unit,
        onFinished: (Boolean) -> Unit,
    ) {
        if (running) {
            onLog("ARDA_RN2_PROBE_ALREADY_RUNNING=YES")
            return
        }

        running = true
        proofStarted.set(false)
        wifiNetwork = null
        cellularNetwork = null
        unregisterCallbacks()

        onLog("")
        onLog("================ RN-2 START ================")
        onLog("ARDA_RN2_AAOS_PORT=${config.aaosPort}")
        onLog("ARDA_RN2_CELLULAR_URL=${config.cellularProbeUrl}")
        onLog("ARDA_RN2_GEARHEAD_USED=NO")
        onLog("ARDA_RN2_BIND_PROCESS_TO_NETWORK_USED=NO")

        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cellularRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        wifiCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifiNetwork = network
                onLog("ARDA_RN2_WIFI_NETWORK=PASS")
                appendNetworkDetails("WIFI", network, onLog)
                maybeRunProof(config, onLog, onFinished)
            }

            override fun onLost(network: Network) {
                if (network == wifiNetwork) {
                    wifiNetwork = null
                    onLog("ARDA_RN2_WIFI_NETWORK_LOST=YES")
                }
            }

            override fun onUnavailable() {
                onLog("ARDA_RN2_WIFI_NETWORK=FAIL")
            }
        }

        cellularCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cellularNetwork = network
                onLog("ARDA_RN2_CELLULAR_NETWORK=PASS")
                appendNetworkDetails("CELLULAR", network, onLog)
                maybeRunProof(config, onLog, onFinished)
            }

            override fun onLost(network: Network) {
                if (network == cellularNetwork) {
                    cellularNetwork = null
                    onLog("ARDA_RN2_CELLULAR_NETWORK_LOST=YES")
                }
            }

            override fun onUnavailable() {
                onLog("ARDA_RN2_CELLULAR_NETWORK=FAIL")
            }
        }

        try {
            connectivityManager.requestNetwork(
                wifiRequest,
                requireNotNull(wifiCallback),
                mainHandler,
                NETWORK_TIMEOUT_MS,
            )
            connectivityManager.requestNetwork(
                cellularRequest,
                requireNotNull(cellularCallback),
                mainHandler,
                NETWORK_TIMEOUT_MS,
            )
        } catch (error: RuntimeException) {
            onLog(
                "ARDA_RN2_NETWORK_REQUEST_ERROR=" +
                    "${error.javaClass.simpleName}:${error.safeMessage()}",
            )
            finish(false, onFinished)
            return
        }

        mainHandler.postDelayed(
            {
                if (running && !proofStarted.get()) {
                    onLog("ARDA_RN2_NETWORK_PAIR_TIMEOUT=YES")
                    if (wifiNetwork == null) onLog("ARDA_RN2_WIFI_NETWORK=FAIL")
                    if (cellularNetwork == null) onLog("ARDA_RN2_CELLULAR_NETWORK=FAIL")
                    finish(false, onFinished)
                }
            },
            NETWORK_TIMEOUT_MS + 1_000L,
        )
    }

    private fun maybeRunProof(
        config: ProbeConfig,
        onLog: (String) -> Unit,
        onFinished: (Boolean) -> Unit,
    ) {
        val wifi = wifiNetwork ?: return
        val cellular = cellularNetwork ?: return
        if (!running || !proofStarted.compareAndSet(false, true)) return

        ioExecutor.execute {
            val aaosHost = config.aaosHostOverride.ifBlank {
                discoverIpv4Gateway(connectivityManager.getLinkProperties(wifi)).orEmpty()
            }

            if (aaosHost.isBlank()) {
                logOnMain(onLog, "ARDA_RN2_AAOS_GATEWAY_DISCOVERY=FAIL")
                logOnMain(
                    onLog,
                    "ARDA_RN2_HINT=Paste the AAOS IPv4 printed by the RN-2 Mac script.",
                )
                mainHandler.post { finish(false, onFinished) }
                return@execute
            }

            logOnMain(onLog, "ARDA_RN2_AAOS_GATEWAY=$aaosHost")

            val wifiLocalOk = probeAaosOverWifi(
                wifi = wifi,
                host = aaosHost,
                port = config.aaosPort,
                onLog = onLog,
            )
            val cellularOk = probePublicOverCellular(
                cellular = cellular,
                urlText = config.cellularProbeUrl,
                onLog = onLog,
            )
            val passed = wifiLocalOk && cellularOk

            logOnMain(
                onLog,
                "ARDA_RN2_WIFI_LOCAL_SOCKET=${if (wifiLocalOk) "PASS" else "FAIL"}",
            )
            logOnMain(
                onLog,
                "ARDA_RN2_CELLULAR_HTTPS=${if (cellularOk) "PASS" else "FAIL"}",
            )
            logOnMain(
                onLog,
                "ARDA_RN2_PHONE_DUAL_NETWORK_GATE=${if (passed) "PASS" else "FAIL"}",
            )

            mainHandler.post { finish(passed, onFinished) }
        }
    }

    private fun probeAaosOverWifi(
        wifi: Network,
        host: String,
        port: Int,
        onLog: (String) -> Unit,
    ): Boolean {
        return try {
            wifi.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(host, port), IO_TIMEOUT_MS)
                socket.soTimeout = IO_TIMEOUT_MS

                val request = buildString {
                    append("GET / HTTP/1.1\r\n")
                    append("Host: $host:$port\r\n")
                    append("Connection: close\r\n")
                    append("User-Agent: ARDA-Reverse-Companion/0.2-Compose\r\n")
                    append("\r\n")
                }

                val output = socket.getOutputStream()
                output.write(request.toByteArray(StandardCharsets.US_ASCII))
                output.flush()

                val response = readLimitedText(
                    input = socket.getInputStream(),
                    maxBytes = MAX_RESPONSE_BYTES,
                )
                logOnMain(
                    onLog,
                    "ARDA_RN2_WIFI_HTTP_RESPONSE=${response.oneLine(500)}",
                )

                response.contains("200 OK") &&
                    response.contains("ARDA RN2 LOCAL OK")
            }
        } catch (error: Throwable) {
            logOnMain(
                onLog,
                "ARDA_RN2_WIFI_LOCAL_ERROR=" +
                    "${error.javaClass.simpleName}:${error.safeMessage()}",
            )
            false
        }
    }

    private fun probePublicOverCellular(
        cellular: Network,
        urlText: String,
        onLog: (String) -> Unit,
    ): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = cellular.openConnection(URL(urlText)) as HttpURLConnection
            connection.connectTimeout = IO_TIMEOUT_MS
            connection.readTimeout = IO_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty(
                "User-Agent",
                "ARDA-Reverse-Companion/0.2-Compose",
            )

            val code = connection.responseCode
            logOnMain(onLog, "ARDA_RN2_CELLULAR_HTTP_CODE=$code")
            logOnMain(onLog, "ARDA_RN2_CELLULAR_FINAL_URL=${connection.url}")

            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val bytesRead = stream?.use { input ->
                val buffer = ByteArray(64)
                input.read(buffer).coerceAtLeast(0)
            } ?: 0

            logOnMain(onLog, "ARDA_RN2_CELLULAR_RESPONSE_BYTES=$bytesRead")
            code in 200..399
        } catch (error: Throwable) {
            logOnMain(
                onLog,
                "ARDA_RN2_CELLULAR_ERROR=" +
                    "${error.javaClass.simpleName}:${error.safeMessage()}",
            )
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun appendNetworkDetails(
        prefix: String,
        network: Network,
        onLog: (String) -> Unit,
    ) {
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        val linkProperties = connectivityManager.getLinkProperties(network)
        val hasInternet = capabilities?.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_INTERNET,
        ) == true
        val validated = capabilities?.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_VALIDATED,
        ) == true

        onLog("ARDA_RN2_${prefix}_INTERNET_CAPABILITY=$hasInternet")
        onLog("ARDA_RN2_${prefix}_VALIDATED=$validated")
        onLog("ARDA_RN2_${prefix}_LINK=${describeLink(linkProperties)}")
    }

    private fun finish(
        passed: Boolean,
        onFinished: (Boolean) -> Unit,
    ) {
        if (!running) return
        running = false
        unregisterCallbacks()
        onFinished(passed)
    }

    private fun unregisterCallbacks() {
        wifiCallback?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        cellularCallback?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        wifiCallback = null
        cellularCallback = null
    }

    private fun logOnMain(
        onLog: (String) -> Unit,
        line: String,
    ) {
        mainHandler.post { onLog(line) }
    }

    override fun close() {
        running = false
        unregisterCallbacks()
        ioExecutor.shutdownNow()
    }

    companion object {
        private const val NETWORK_TIMEOUT_MS = 15_000
        private const val IO_TIMEOUT_MS = 8_000
        private const val MAX_RESPONSE_BYTES = 32 * 1024

        private fun discoverIpv4Gateway(
            linkProperties: LinkProperties?,
        ): String? {
            val routes = linkProperties?.routes.orEmpty()

            val defaultGateway = routes
                .asSequence()
                .filter { it.isDefaultRoute }
                .mapNotNull { it.gateway as? Inet4Address }
                .firstOrNull(::isUsableIpv4Gateway)

            val fallbackGateway = routes
                .asSequence()
                .mapNotNull { it.gateway as? Inet4Address }
                .firstOrNull(::isUsableIpv4Gateway)

            return (defaultGateway ?: fallbackGateway)?.hostAddress
        }

        private fun isUsableIpv4Gateway(address: Inet4Address): Boolean =
            !address.isAnyLocalAddress &&
                !address.isLoopbackAddress &&
                !address.isLinkLocalAddress &&
                !address.isMulticastAddress

        private fun describeLink(linkProperties: LinkProperties?): String {
            if (linkProperties == null) return "NONE"

            val addresses = linkProperties.linkAddresses.joinToString(",") {
                it.address.hostAddress ?: "?"
            }
            val gateways = linkProperties.routes
                .mapNotNull { it.gateway?.hostAddress }
                .distinct()
                .joinToString(",")

            return "iface=${linkProperties.interfaceName};" +
                "addresses=$addresses;gateways=$gateways"
        }

        private fun readLimitedText(
            input: InputStream,
            maxBytes: Int,
        ): String {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(2_048)
            var remaining = maxBytes

            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }

            return output.toString(StandardCharsets.UTF_8.name())
        }

        private fun Throwable.safeMessage(): String =
            message?.replace('\n', ' ') ?: "no-message"

        private fun String.oneLine(maxChars: Int): String {
            val flat = replace("\r", "\\r").replace("\n", "\\n")
            return if (flat.length <= maxChars) flat else flat.take(maxChars) + "…"
        }
    }
}
