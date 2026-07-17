package com.github.deadknight.ardacompanion

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean


data class ArdaWifiOffer(
    val ssid: String,
    val passphrase: String?,
    val security: String,
    val bssid: String?,
    val apIp: String?,
    val proxyPort: Int,
) {
    companion object {
        fun fromJson(text: String): ArdaWifiOffer {
            val json = JSONObject(text)
            require(json.optString("status") == "ready") {
                json.optString("error", "AAOS Wi-Fi offer is not ready")
            }
            return ArdaWifiOffer(
                ssid = json.getString("ssid"),
                passphrase = if (json.isNull("psk")) null else json.getString("psk"),
                security = json.optString("security", "wpa2"),
                bssid = if (json.isNull("bssid")) null else json.optString("bssid")
                    .takeIf { it.isNotBlank() },
                apIp = if (json.isNull("ap_ip")) null else json.optString("ap_ip")
                    .takeIf { it.isNotBlank() },
                proxyPort = json.optInt("proxy_port", 5285),
            )
        }
    }
}

data class ArdaProxyEndpoint(
    val phoneIp: String,
    val port: Int?,
    val cellularAvailable: Boolean,
) {
    val proxyAvailable: Boolean
        get() = cellularAvailable && port != null
}

class ReverseProxyController(private val context: Context) : Closeable {
    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var wifiNetwork: Network? = null
    @Volatile private var cellularNetwork: Network? = null
    @Volatile private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var cellularCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var proxyServer: CellularHttpProxyServer? = null

    fun startBlocking(
        offer: ArdaWifiOffer,
        timeoutMs: Long = WIFI_SELECTION_TIMEOUT_MS,
    ): ArdaProxyEndpoint {
        close()

        // The local Wi-Fi transport is an independent gate. The first run saves
        // the fixed AAOS network through Android Settings; later runs only observe
        // Android's normal saved-network auto-join. Missing cellular must not close
        // RFCOMM or hide a successful local Wi-Fi connection.
        val wifi = requestLocalWifiBlocking(offer, timeoutMs)
        val phoneIp = waitForIpv4Address(wifi, IPV4_WAIT_TIMEOUT_MS)
            ?: error("Phone has no IPv4 address on ARDA local-only Wi-Fi")
        val phoneIpText = phoneIp.hostAddress ?: error("Phone Wi-Fi IPv4 missing")

        ArdaCompanionState.log("ARDA_RN3B_PHONE_LOCAL_WIFI_IPV4=$phoneIpText")
        ArdaCompanionState.log("ARDA_RN3B_PHONE_SOCKET_BIND_WIFI=PASS")
        ArdaCompanionState.log("ARDA_RN3B_PHONE_LOCAL_WIFI_GATE=PASS")

        val cellular = requestCellularOptional(CELLULAR_REQUEST_TIMEOUT_MS)
        if (cellular == null) {
            ArdaCompanionState.log("ARDA_RN3B_PHONE_CELLULAR_NETWORK=UNAVAILABLE")
            ArdaCompanionState.log("ARDA_RN3B_PHONE_LOCAL_ONLY_SESSION=PASS")
            return ArdaProxyEndpoint(
                phoneIp = phoneIpText,
                port = null,
                cellularAvailable = false,
            )
        }

        val server = CellularHttpProxyServer(
            bindAddress = phoneIp,
            requestedPort = offer.proxyPort,
            cellularNetwork = cellular,
        )
        server.start()
        proxyServer = server

        val endpoint = ArdaProxyEndpoint(
            phoneIp = phoneIpText,
            port = server.localPort,
            cellularAvailable = true,
        )
        ArdaCompanionState.proxyEndpoint.value = "${endpoint.phoneIp}:${endpoint.port}"
        ArdaCompanionState.log(
            "ARDA_RN3B_PHONE_PROXY_LISTEN=${endpoint.phoneIp}:${endpoint.port}",
        )
        ArdaCompanionState.log("ARDA_RN3B_PHONE_EGRESS_BIND_CELLULAR=PASS")
        return endpoint
    }

    private fun requestLocalWifiBlocking(
        offer: ArdaWifiOffer,
        timeoutMs: Long,
    ): Network {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "ARDA local Wi-Fi requires Android 11 or newer"
        }

        // A saved configuration is useful for normal Android auto-join, but it
        // does not provide an on-demand connection guarantee. First accept an
        // already-connected saved network. If Android does not select it, keep
        // the saved configuration and actively request the same local-only AP.
        // The active request remains registered for the complete ARDA session.
        val precheckTimeoutMs = minOf(timeoutMs, AUTOJOIN_PRECHECK_TIMEOUT_MS)
        ArdaCompanionState.log(
            "ARDA_RN3B_PHONE_SAVED_NETWORK_PRECHECK=START " +
                "ssid=${offer.ssid};timeout_ms=$precheckTimeoutMs",
        )
        val existingNetwork = waitForSavedNetworkBlocking(
            offer = offer,
            timeoutMs = precheckTimeoutMs,
            phase = "PRECHECK",
        )
        if (existingNetwork != null) {
            ArdaWifiEnrollmentCoordinator.rememberObservedSavedNetwork(context, offer)
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_WIFI_CONNECT_MODE=SAVED_NETWORK_ALREADY_CONNECTED",
            )
            return existingNetwork
        }

        val enrolled = ArdaWifiEnrollmentCoordinator.ensureSavedNetworkBlocking(
            context = context,
            offer = offer,
            timeoutMs = SAVED_NETWORK_ENROLLMENT_TIMEOUT_MS,
        )
        require(enrolled) { "ARDA Wi-Fi was not saved by the user" }

        // ACTION_WIFI_ADD_NETWORKS normally triggers a connection immediately
        // after a new save. Give that system-owned connection a short chance
        // before issuing the explicit local-only request.
        ArdaCompanionState.log(
            "ARDA_RN3B_PHONE_SAVED_NETWORK_POST_ENROLLMENT_CHECK=START " +
                "ssid=${offer.ssid};timeout_ms=$POST_ENROLLMENT_AUTOJOIN_TIMEOUT_MS",
        )
        val postEnrollmentNetwork = waitForSavedNetworkBlocking(
            offer = offer,
            timeoutMs = minOf(timeoutMs, POST_ENROLLMENT_AUTOJOIN_TIMEOUT_MS),
            phase = "POST_ENROLLMENT",
        )
        if (postEnrollmentNetwork != null) {
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_WIFI_CONNECT_MODE=SAVED_NETWORK_AUTOJOIN",
            )
            return postEnrollmentNetwork
        }

        ArdaCompanionState.log(
            "ARDA_RN3B_PHONE_WIFI_CONNECT_MODE=ACTIVE_LOCAL_ONLY_REQUEST",
        )
        return requestSpecificLocalWifiBlocking(offer, timeoutMs)
    }

    private fun requestSpecificLocalWifiBlocking(
        offer: ArdaWifiOffer,
        timeoutMs: Long,
    ): Network {
        val wifiManager = context.applicationContext
            .getSystemService(WifiManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val concurrencySupported = runCatching {
                wifiManager?.isStaConcurrencyForLocalOnlyConnectionsSupported == true
            }.getOrDefault(false)
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_STA_CONCURRENCY_LOCAL_ONLY=$concurrencySupported",
            )
        }

        val specifierBuilder = WifiNetworkSpecifier.Builder()
            .setSsid(offer.ssid)
        val security = offer.security.lowercase(Locale.US)
        val passphrase = offer.passphrase
        when {
            passphrase.isNullOrBlank() || security == "open" -> Unit
            security.contains("wpa3") || security.contains("sae") ->
                specifierBuilder.setWpa3Passphrase(passphrase)
            else -> specifierBuilder.setWpa2Passphrase(passphrase)
        }

        val exactBssid = offer.bssid
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("unreported", ignoreCase = true) }
            ?.let { value -> runCatching { MacAddress.fromString(value) }.getOrNull() }
        if (exactBssid != null) specifierBuilder.setBssid(exactBssid)

        val matchMode = if (exactBssid != null) {
            "EXACT_SSID_BSSID"
        } else {
            "EXACT_SSID_ONLY"
        }
        ArdaCompanionState.log("ARDA_RN3B_PHONE_WIFI_MATCH=$matchMode")

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()
        val latch = CountDownLatch(1)
        val selected = java.util.concurrent.atomic.AtomicReference<Network?>()
        val unavailable = AtomicBoolean(false)

        fun available(network: Network) {
            if (!selected.compareAndSet(null, network)) return
            wifiNetwork = network
            ArdaCompanionState.wifiConnected.value = true
            ArdaCompanionState.log("ARDA_RN3B_PHONE_LOCAL_WIFI=PASS")
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_WIFI_SELECTION_RESULT=CONNECTED_ACTIVE_REQUEST",
            )
            logNetwork("WIFI", network)
            latch.countDown()
        }

        fun lost(network: Network) {
            if (network == wifiNetwork) {
                wifiNetwork = null
                ArdaCompanionState.wifiConnected.value = false
                ArdaCompanionState.log("ARDA_RN3B_PHONE_LOCAL_WIFI_LOST=YES")
            }
        }

        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(
                ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO,
            ) {
                override fun onAvailable(network: Network) = available(network)
                override fun onLost(network: Network) = lost(network)
                override fun onUnavailable() {
                    unavailable.set(true)
                    latch.countDown()
                }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = available(network)
                override fun onLost(network: Network) = lost(network)
                override fun onUnavailable() {
                    unavailable.set(true)
                    latch.countDown()
                }
            }
        }
        wifiCallback = callback

        ArdaCompanionState.log(
            "ARDA_RN3B_PHONE_WIFI_SELECTION_WAIT=START " +
                "attempt=$matchMode;timeout_ms=$timeoutMs",
        )
        connectivityManager.requestNetwork(
            request,
            callback,
            mainHandler,
            timeoutMs.toInt(),
        )

        if (!latch.await(timeoutMs + CALLBACK_GRACE_MS, TimeUnit.MILLISECONDS)) {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            if (wifiCallback === callback) wifiCallback = null
            ArdaCompanionState.log("ARDA_RN3B_PHONE_WIFI_ACTIVE_REQUEST=TIMEOUT")
            error("Timed out requesting ARDA local-only Wi-Fi")
        }
        if (unavailable.get() || selected.get() == null) {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            if (wifiCallback === callback) wifiCallback = null
            ArdaCompanionState.log("ARDA_RN3B_PHONE_WIFI_ACTIVE_REQUEST=UNAVAILABLE")
            error("Android rejected or could not find ARDA local-only Wi-Fi")
        }
        return selected.get() ?: error("ARDA local-only Wi-Fi callback returned no network")
    }

    private fun waitForSavedNetworkBlocking(
        offer: ArdaWifiOffer,
        timeoutMs: Long,
        phase: String,
    ): Network? {
        val latch = CountDownLatch(1)
        val selected = java.util.concurrent.atomic.AtomicReference<Network?>()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        fun consider(
            network: Network,
            capabilities: NetworkCapabilities?,
        ) {
            if (selected.get() != null) return
            val match = savedNetworkMatch(network, capabilities, offer) ?: return
            if (!selected.compareAndSet(null, network)) return
            wifiNetwork = network
            ArdaCompanionState.wifiConnected.value = true
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_SAVED_NETWORK_MATCH=$match phase=$phase",
            )
            ArdaCompanionState.log("ARDA_RN3B_PHONE_LOCAL_WIFI=PASS")
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_WIFI_SELECTION_RESULT=AUTO_CONNECTED_SAVED_NETWORK",
            )
            logNetwork("WIFI", network)
            latch.countDown()
        }

        fun lost(network: Network) {
            if (network == wifiNetwork) {
                wifiNetwork = null
                ArdaCompanionState.wifiConnected.value = false
                ArdaCompanionState.log("ARDA_RN3B_PHONE_LOCAL_WIFI_LOST=YES")
            }
        }

        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(
                ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO,
            ) {
                override fun onAvailable(network: Network) {
                    consider(network, connectivityManager.getNetworkCapabilities(network))
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    consider(network, networkCapabilities)
                }

                override fun onLost(network: Network) = lost(network)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    consider(network, connectivityManager.getNetworkCapabilities(network))
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    consider(network, networkCapabilities)
                }

                override fun onLost(network: Network) = lost(network)
            }
        }
        wifiCallback = callback
        connectivityManager.registerNetworkCallback(request, callback, mainHandler)

        connectivityManager.allNetworks.forEach { network ->
            consider(network, connectivityManager.getNetworkCapabilities(network))
        }

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            if (wifiCallback === callback) wifiCallback = null
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_SAVED_NETWORK_WAIT=TIMEOUT phase=$phase",
            )
            return null
        }
        return selected.get()
    }

    private fun savedNetworkMatch(
        network: Network,
        capabilities: NetworkCapabilities?,
        offer: ArdaWifiOffer,
    ): String? {
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
            return null
        }

        val wifiInfo = capabilities.transportInfo as? WifiInfo
        val observedSsid = wifiInfo?.ssid
            ?.trim()
            ?.trim('"')
            ?.takeUnless { it.equals("<unknown ssid>", ignoreCase = true) }
        if (observedSsid == offer.ssid) return "SSID"

        val expectedApIp = offer.apIp
        if (!expectedApIp.isNullOrBlank()) {
            val link = connectivityManager.getLinkProperties(network)
            val gatewayMatch = link?.routes?.any { route ->
                route.gateway?.hostAddress == expectedApIp
            } == true
            if (gatewayMatch) return "AP_IP_GATEWAY"
        }
        return null
    }

    private fun requestCellularOptional(timeoutMs: Long): Network? {
        if (!hasUsableCellularSubscription()) {
            ArdaCompanionState.log("ARDA_RN3B_PHONE_CELLULAR_REQUEST=SKIPPED_NO_ACTIVE_SIM")
            return null
        }

        val latch = CountDownLatch(1)
        val unavailable = AtomicBoolean(false)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cellularNetwork = network
                ArdaCompanionState.cellularConnected.value = true
                ArdaCompanionState.log("ARDA_RN3B_PHONE_CELLULAR_NETWORK=PASS")
                logNetwork("CELLULAR", network)
                latch.countDown()
            }

            override fun onLost(network: Network) {
                if (network == cellularNetwork) {
                    cellularNetwork = null
                    ArdaCompanionState.cellularConnected.value = false
                    ArdaCompanionState.log("ARDA_RN3B_PHONE_CELLULAR_LOST=YES")
                }
            }

            override fun onUnavailable() {
                unavailable.set(true)
                latch.countDown()
            }
        }
        cellularCallback = callback

        ArdaCompanionState.log(
            "ARDA_RN3B_PHONE_CELLULAR_WAIT=START timeout_ms=$timeoutMs",
        )
        connectivityManager.requestNetwork(
            request,
            callback,
            mainHandler,
            timeoutMs.toInt(),
        )

        if (!latch.await(timeoutMs + CALLBACK_GRACE_MS, TimeUnit.MILLISECONDS)) {
            ArdaCompanionState.log("ARDA_RN3B_PHONE_CELLULAR_TIMEOUT=YES")
            unregisterCellularCallback()
            return null
        }
        if (unavailable.get()) {
            ArdaCompanionState.log("ARDA_RN3B_PHONE_CELLULAR_REQUEST=UNAVAILABLE")
            unregisterCellularCallback()
            return null
        }
        return cellularNetwork
    }

    private fun hasUsableCellularSubscription(): Boolean {
        val telephony = runCatching {
            context.getSystemService(TelephonyManager::class.java)
        }.getOrNull()
        val simState = runCatching { telephony?.simState }.getOrNull()
        val defaultDataSubId = runCatching {
            SubscriptionManager.getDefaultDataSubscriptionId()
        }.getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)

        ArdaCompanionState.log(
            "ARDA_RN3B_PHONE_CELLULAR_PREFLIGHT=" +
                "sim_state=${simState ?: "unknown"};default_data_sub_id=$defaultDataSubId",
        )
        val available =
            simState != TelephonyManager.SIM_STATE_ABSENT &&
                defaultDataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID
        ArdaCompanionState.log(
            if (available) {
                "ARDA_RN3B_PHONE_CELLULAR_PREFLIGHT=PASS"
            } else {
                "ARDA_RN3B_PHONE_CELLULAR_PREFLIGHT=NO_ACTIVE_SIM_LOCAL_ONLY"
            },
        )
        return available
    }

    private fun unregisterCellularCallback() {
        cellularCallback?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        cellularCallback = null
        cellularNetwork = null
        ArdaCompanionState.cellularConnected.value = false
    }

    private fun waitForIpv4Address(network: Network, timeoutMs: Long): Inet4Address? {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            findIpv4Address(connectivityManager.getLinkProperties(network))?.let {
                return it
            }
            Thread.sleep(200)
        }
        return findIpv4Address(connectivityManager.getLinkProperties(network))
    }

    private fun logNetwork(prefix: String, network: Network) {
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        val link = connectivityManager.getLinkProperties(network)
        ArdaCompanionState.log(
            "ARDA_RN3B_PHONE_${prefix}_INTERNET=" +
                (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true),
        )
        ArdaCompanionState.log(
            "ARDA_RN3B_PHONE_${prefix}_LINK=${describeLink(link)}",
        )
    }

    override fun close() {
        runCatching { proxyServer?.close() }
        proxyServer = null
        wifiCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        cellularCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        wifiCallback = null
        cellularCallback = null
        wifiNetwork = null
        cellularNetwork = null
        ArdaCompanionState.wifiConnected.value = false
        ArdaCompanionState.cellularConnected.value = false
        ArdaCompanionState.proxyReady.value = false
        ArdaCompanionState.proxyEndpoint.value = null
    }

    private class CellularHttpProxyServer(
        private val bindAddress: InetAddress,
        private val requestedPort: Int,
        private val cellularNetwork: Network,
    ) : Closeable {
        private val running = AtomicBoolean(false)
        private val acceptExecutor: ExecutorService = Executors.newSingleThreadExecutor()
        private val clientExecutor: ExecutorService = Executors.newCachedThreadPool()
        private var serverSocket: ServerSocket? = null

        val localPort: Int
            get() = serverSocket?.localPort ?: 0

        fun start() {
            check(running.compareAndSet(false, true)) { "Proxy is already running" }
            val server = ServerSocket()
            server.reuseAddress = true
            runCatching {
                server.bind(InetSocketAddress(bindAddress, requestedPort), 32)
            }.getOrElse {
                server.bind(InetSocketAddress(bindAddress, 0), 32)
            }
            serverSocket = server
            acceptExecutor.execute(::acceptLoop)
        }

        private fun acceptLoop() {
            while (running.get()) {
                val client = try {
                    serverSocket?.accept() ?: break
                } catch (error: Throwable) {
                    if (running.get()) {
                        ArdaCompanionState.log(
                            "ARDA_RN3B_PHONE_PROXY_ACCEPT_ERROR=${error.safeMessage()}",
                        )
                    }
                    break
                }
                clientExecutor.execute {
                    handleClient(client)
                }
            }
        }

        private fun handleClient(client: Socket) {
            client.use {
                try {
                    client.tcpNoDelay = true
                    client.soTimeout = IO_TIMEOUT_MS
                    val headerBytes = readHttpHeader(client.getInputStream())
                    val headerText = headerBytes.toString(StandardCharsets.ISO_8859_1)
                    val lines = headerText.split("\r\n")
                    val requestLine = lines.firstOrNull().orEmpty()
                    val parts = requestLine.split(' ', limit = 3)
                    require(parts.size == 3) { "Invalid HTTP proxy request line" }

                    val method = parts[0].uppercase(Locale.US)
                    if (method == "CONNECT") {
                        val (host, port) = parseAuthority(parts[1], 443)
                        connectCellular(host, port).use { remote ->
                            client.getOutputStream().write(
                                (
                                    "HTTP/1.1 200 Connection Established\r\n" +
                                        "Proxy-Agent: ARDA-RN3B\r\n\r\n"
                                ).toByteArray(StandardCharsets.US_ASCII),
                            )
                            client.getOutputStream().flush()
                            ArdaCompanionState.log(
                                "ARDA_RN3B_PHONE_PROXY_CONNECT=$host:$port",
                            )
                            client.soTimeout = 0
                            relayBidirectional(client, remote)
                        }
                    } else {
                        val uri = URI(parts[1])
                        val hostHeader = lines
                            .firstOrNull { it.startsWith("Host:", ignoreCase = true) }
                            ?.substringAfter(':')
                            ?.trim()
                        val host = uri.host ?: hostHeader?.substringBefore(':')
                            ?: error("HTTP proxy request has no host")
                        val port = if (uri.port > 0) uri.port else 80
                        val path = buildString {
                            append(uri.rawPath?.ifBlank { "/" } ?: "/")
                            uri.rawQuery?.let { append('?').append(it) }
                        }
                        val rewritten = buildString {
                            append(method).append(' ').append(path).append(' ').append(parts[2])
                            append("\r\n")
                            lines.drop(1)
                                .takeWhile { it.isNotEmpty() }
                                .filterNot {
                                    it.startsWith("Proxy-Connection:", ignoreCase = true)
                                }
                                .forEach { line -> append(line).append("\r\n") }
                            append("\r\n")
                        }.toByteArray(StandardCharsets.ISO_8859_1)

                        connectCellular(host, port).use { remote ->
                            remote.getOutputStream().write(rewritten)
                            remote.getOutputStream().flush()
                            ArdaCompanionState.log(
                                "ARDA_RN3B_PHONE_PROXY_HTTP=$method $host:$port$path",
                            )
                            client.soTimeout = 0
                            relayBidirectional(client, remote)
                        }
                    }
                } catch (error: Throwable) {
                    ArdaCompanionState.log(
                        "ARDA_RN3B_PHONE_PROXY_CLIENT_ERROR=" +
                            "${error.javaClass.simpleName}:${error.safeMessage()}",
                    )
                    runCatching {
                        client.getOutputStream().write(
                            "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n"
                                .toByteArray(StandardCharsets.US_ASCII),
                        )
                    }
                }
            }
        }

        private fun connectCellular(host: String, port: Int): Socket {
            val addresses = cellularNetwork.getAllByName(host)
            var lastError: Throwable? = null
            addresses.forEach { address ->
                val socket = Socket()
                try {
                    cellularNetwork.bindSocket(socket)
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = 0
                    return socket
                } catch (error: Throwable) {
                    lastError = error
                    runCatching { socket.close() }
                }
            }
            throw IllegalStateException(
                "Cellular connect failed for $host:$port",
                lastError,
            )
        }

        private fun relayBidirectional(left: Socket, right: Socket) {
            val leftToRight = clientExecutor.submit {
                pump(left.getInputStream(), right.getOutputStream())
                runCatching { right.shutdownOutput() }
            }
            val rightToLeft = clientExecutor.submit {
                pump(right.getInputStream(), left.getOutputStream())
                runCatching { left.shutdownOutput() }
            }
            runCatching { leftToRight.get() }
            runCatching { rightToLeft.get() }
        }

        private fun pump(input: InputStream, output: OutputStream) {
            val buffer = ByteArray(32 * 1024)
            while (running.get()) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                output.flush()
            }
        }

        override fun close() {
            running.set(false)
            runCatching { serverSocket?.close() }
            serverSocket = null
            acceptExecutor.shutdownNow()
            clientExecutor.shutdownNow()
        }

        companion object {
            private const val MAX_HEADER_BYTES = 32 * 1024
            private const val CONNECT_TIMEOUT_MS = 12_000
            private const val IO_TIMEOUT_MS = 15_000

            private fun readHttpHeader(input: InputStream): ByteArray {
                val output = ByteArrayOutputStream()
                var state = 0
                while (output.size() < MAX_HEADER_BYTES) {
                    val value = input.read()
                    if (value < 0) error("Proxy client closed before HTTP header")
                    output.write(value)
                    state = when {
                        state == 0 && value == '\r'.code -> 1
                        state == 1 && value == '\n'.code -> 2
                        state == 2 && value == '\r'.code -> 3
                        state == 3 && value == '\n'.code -> return output.toByteArray()
                        value == '\r'.code -> 1
                        else -> 0
                    }
                }
                error("HTTP proxy header exceeds $MAX_HEADER_BYTES bytes")
            }

            private fun parseAuthority(authority: String, defaultPort: Int): Pair<String, Int> {
                if (authority.startsWith("[")) {
                    val end = authority.indexOf(']')
                    require(end > 0) { "Invalid IPv6 CONNECT authority" }
                    val host = authority.substring(1, end)
                    val port = authority.substring(end + 1)
                        .removePrefix(":")
                        .toIntOrNull() ?: defaultPort
                    return host to port
                }
                val split = authority.lastIndexOf(':')
                return if (split > 0 && authority.indexOf(':') == split) {
                    authority.substring(0, split) to authority.substring(split + 1).toInt()
                } else {
                    authority to defaultPort
                }
            }

            private fun Throwable.safeMessage(): String =
                message?.replace('\n', ' ') ?: "no-message"
        }
    }

    companion object {
        private const val WIFI_SELECTION_TIMEOUT_MS = 180_000L
        private const val AUTOJOIN_PRECHECK_TIMEOUT_MS = 3_000L
        private const val POST_ENROLLMENT_AUTOJOIN_TIMEOUT_MS = 5_000L
        private const val SAVED_NETWORK_ENROLLMENT_TIMEOUT_MS = 180_000L
        private const val CELLULAR_REQUEST_TIMEOUT_MS = 45_000L
        private const val CALLBACK_GRACE_MS = 2_000L
        private const val IPV4_WAIT_TIMEOUT_MS = 10_000L

        private fun findIpv4Address(link: LinkProperties?): Inet4Address? =
            link?.linkAddresses
                ?.asSequence()
                ?.map { it.address }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull {
                    !it.isAnyLocalAddress &&
                        !it.isLoopbackAddress &&
                        !it.isLinkLocalAddress
                }

        private fun describeLink(link: LinkProperties?): String {
            if (link == null) return "NONE"
            val addresses = link.linkAddresses.joinToString(",") {
                it.address.hostAddress ?: "?"
            }
            val gateways = link.routes.mapNotNull { it.gateway?.hostAddress }
                .distinct()
                .joinToString(",")
            return "iface=${link.interfaceName};addresses=$addresses;gateways=$gateways"
        }

        private fun Throwable.safeMessage(): String =
            message?.replace('\n', ' ') ?: "no-message"
    }
}
