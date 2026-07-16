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
    private val wifiManager =
        context.applicationContext.getSystemService(WifiManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wifiApprovalPreferences = context.getSharedPreferences(
        WIFI_APPROVAL_PREFS,
        Context.MODE_PRIVATE,
    )

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

        // The local-only Wi-Fi transport is an independent gate. A missing SIM or
        // cellular Network must not cancel the Android Wi-Fi selection request,
        // close RFCOMM, or hide a successful AAOS LocalOnlyHotspot connection.
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
        val platformBssid = normalizeBssid(offer.bssid)
        val savedBssid = loadSavedBssid(offer)
        val discoveredAccessPoint =
            if (platformBssid == null && savedBssid == null) {
                waitForVisibleAccessPoint(offer.ssid, WIFI_DISCOVERY_TIMEOUT_MS)
            } else {
                null
            }
        val preferredBssid =
            platformBssid ?: savedBssid ?: discoveredAccessPoint?.bssid
        val preferredSource = when {
            platformBssid != null -> "PLATFORM"
            savedBssid != null -> "SAVED_APPROVAL"
            discoveredAccessPoint != null -> "SCAN_DISCOVERY"
            else -> null
        }

        if (preferredBssid != null) {
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_WIFI_BSSID_SOURCE=$preferredSource",
            )
            ArdaCompanionState.log(
                if (savedBssid != null) {
                    "ARDA_RN3B_PHONE_WIFI_RECONNECT_ATTEMPT=EXACT_SSID_BSSID"
                } else {
                    "ARDA_RN3B_PHONE_WIFI_FIRST_APPROVAL_ATTEMPT=EXACT_SSID_BSSID"
                },
            )
            requestLocalWifiAttempt(
                offer = offer,
                requestedBssid = preferredBssid,
                timeoutMs = if (savedBssid != null) {
                    minOf(timeoutMs, EXACT_BSSID_RECONNECT_TIMEOUT_MS)
                } else {
                    timeoutMs
                },
                attemptName = "EXACT_SSID_BSSID",
            )?.let { return it }

            if (platformBssid == null && savedBssid != null) {
                clearSavedBssid()
                ArdaCompanionState.log(
                    "ARDA_RN3B_PHONE_WIFI_SAVED_BSSID=STALE_CLEARED",
                )
                ArdaCompanionState.log(
                    "ARDA_RN3B_PHONE_WIFI_RECONNECT_FALLBACK=EXACT_SSID_ONLY",
                )
            } else {
                error("Android rejected or could not connect to the visible ARDA access point")
            }
        } else {
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_WIFI_BSSID_SOURCE=UNAVAILABLE_AFTER_SCAN",
            )
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_WIFI_DISCOVERY_FALLBACK=EXACT_SSID_ONLY",
            )
        }

        return requestLocalWifiAttempt(
            offer = offer,
            requestedBssid = null,
            timeoutMs = timeoutMs,
            attemptName = "EXACT_SSID_ONLY",
        ) ?: error("Android rejected or could not find ARDA local Wi-Fi")
    }

    private data class VisibleAccessPoint(
        val bssid: String,
        val frequencyMhz: Int,
        val rssiDbm: Int,
    )

    @Suppress("DEPRECATION")
    private fun waitForVisibleAccessPoint(
        ssid: String,
        timeoutMs: Long,
    ): VisibleAccessPoint? {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        var lastScanRequestAt = 0L
        var scanReadable = true
        var lastStartScanResult: Boolean? = null

        ArdaCompanionState.log(
            "ARDA_RN3B_PHONE_WIFI_DISCOVERY_WAIT=START " +
                "ssid=$ssid;timeout_ms=$timeoutMs",
        )
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val results = runCatching { wifiManager.scanResults }
                .onFailure {
                    scanReadable = false
                    ArdaCompanionState.log(
                        "ARDA_RN3B_PHONE_WIFI_SCAN_RESULTS=UNAVAILABLE:" +
                            "${it.javaClass.simpleName}:${it.safeMessage()}",
                    )
                }
                .getOrDefault(emptyList())

            val match = results
                .asSequence()
                .filter { it.SSID == ssid }
                .maxByOrNull { it.level }
            val bssid = normalizeBssid(match?.BSSID)
            if (match != null && bssid != null) {
                val accessPoint = VisibleAccessPoint(
                    bssid = bssid,
                    frequencyMhz = match.frequency,
                    rssiDbm = match.level,
                )
                ArdaCompanionState.log(
                    "ARDA_RN3B_PHONE_WIFI_DISCOVERY=PASS " +
                        "bssid=${accessPoint.bssid};" +
                        "frequency_mhz=${accessPoint.frequencyMhz};" +
                        "rssi_dbm=${accessPoint.rssiDbm}",
                )
                return accessPoint
            }

            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastScanRequestAt >= WIFI_SCAN_RETRY_INTERVAL_MS) {
                lastScanRequestAt = now
                lastStartScanResult = runCatching { wifiManager.startScan() }
                    .onFailure {
                        ArdaCompanionState.log(
                            "ARDA_RN3B_PHONE_WIFI_SCAN_REQUEST=ERROR:" +
                                "${it.javaClass.simpleName}:${it.safeMessage()}",
                        )
                    }
                    .getOrNull()
                ArdaCompanionState.log(
                    "ARDA_RN3B_PHONE_WIFI_SCAN_REQUEST=" +
                        when (lastStartScanResult) {
                            true -> "STARTED"
                            false -> "THROTTLED_OR_REJECTED"
                            null -> "UNAVAILABLE"
                        },
                )
            }
            Thread.sleep(WIFI_SCAN_POLL_INTERVAL_MS)
        }

        ArdaCompanionState.log(
            "ARDA_RN3B_PHONE_WIFI_DISCOVERY=NOT_SEEN " +
                "ssid=$ssid;scan_readable=$scanReadable;" +
                "last_start_scan=${lastStartScanResult ?: "unknown"}",
        )
        return null
    }

    private fun requestLocalWifiAttempt(
        offer: ArdaWifiOffer,
        requestedBssid: String?,
        timeoutMs: Long,
        attemptName: String,
    ): Network? {
        val latch = CountDownLatch(1)
        val unavailable = AtomicBoolean(false)
        val availableNetwork = java.util.concurrent.atomic.AtomicReference<Network?>()
        val specifier = buildWifiSpecifier(offer, requestedBssid)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                availableNetwork.set(network)
                wifiNetwork = network
                ArdaCompanionState.wifiConnected.value = true
                ArdaCompanionState.log("ARDA_RN3B_PHONE_LOCAL_WIFI=PASS")
                ArdaCompanionState.log(
                    "ARDA_RN3B_PHONE_WIFI_SELECTION_RESULT=CONNECTED attempt=$attemptName",
                )
                persistObservedBssid(offer, network)
                logNetwork("WIFI", network)
                latch.countDown()
            }

            override fun onLost(network: Network) {
                if (network == wifiNetwork) {
                    wifiNetwork = null
                    ArdaCompanionState.wifiConnected.value = false
                    ArdaCompanionState.log("ARDA_RN3B_PHONE_LOCAL_WIFI_LOST=YES")
                }
            }

            override fun onUnavailable() {
                unavailable.set(true)
                ArdaCompanionState.log(
                    "ARDA_RN3B_PHONE_WIFI_ATTEMPT_UNAVAILABLE=$attemptName",
                )
                latch.countDown()
            }
        }
        wifiCallback = callback

        ArdaCompanionState.log(
            "ARDA_RN3B_PHONE_WIFI_SELECTION_WAIT=START " +
                "attempt=$attemptName;timeout_ms=$timeoutMs",
        )
        connectivityManager.requestNetwork(request, callback, mainHandler)

        val signalled = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        val network = availableNetwork.get()
        if (network != null) {
            return network
        }

        if (!signalled) {
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_WIFI_ATTEMPT_TIMEOUT=$attemptName",
            )
        } else if (unavailable.get()) {
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_WIFI_ATTEMPT_RESULT=$attemptName:UNAVAILABLE",
            )
        }

        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        if (wifiCallback === callback) {
            wifiCallback = null
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

    private fun buildWifiSpecifier(
        offer: ArdaWifiOffer,
        requestedBssid: String?,
    ): WifiNetworkSpecifier {
        val builder = WifiNetworkSpecifier.Builder().setSsid(offer.ssid)
        requestedBssid?.let {
            builder.setBssid(MacAddress.fromString(it))
            ArdaCompanionState.log("ARDA_RN3B_PHONE_WIFI_MATCH=EXACT_SSID_BSSID")
            ArdaCompanionState.log("ARDA_RN3B_PHONE_WIFI_BSSID=$it")
        } ?: ArdaCompanionState.log("ARDA_RN3B_PHONE_WIFI_MATCH=EXACT_SSID_ONLY")
        when (offer.security.lowercase(Locale.US)) {
            "open" -> Unit
            "wpa2" -> builder.setWpa2Passphrase(
                requireNotNull(offer.passphrase) { "WPA2 offer has no passphrase" },
            )
            "wpa3" -> builder.setWpa3Passphrase(
                requireNotNull(offer.passphrase) { "WPA3 offer has no passphrase" },
            )
            else -> error("Unsupported ARDA local Wi-Fi security: ${offer.security}")
        }
        return builder.build()
    }

    private fun persistObservedBssid(offer: ArdaWifiOffer, network: Network) {
        val wifiInfo = connectivityManager
            .getNetworkCapabilities(network)
            ?.transportInfo as? WifiInfo
        val observedBssid = normalizeBssid(wifiInfo?.bssid)
        if (observedBssid == null) {
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_WIFI_OBSERVED_BSSID=UNAVAILABLE",
            )
            return
        }

        val committed = wifiApprovalPreferences.edit()
            .putString(KEY_APPROVED_SSID, offer.ssid)
            .putString(KEY_APPROVED_BSSID, observedBssid)
            .putInt(KEY_APPROVED_CREDENTIAL, credentialFingerprint(offer))
            .commit()
        ArdaCompanionState.log("ARDA_RN3B_PHONE_WIFI_OBSERVED_BSSID=$observedBssid")
        ArdaCompanionState.log(
            "ARDA_RN3B_PHONE_WIFI_APPROVAL_IDENTITY_SAVED=" +
                if (committed) "PASS" else "FAIL",
        )
    }

    private fun loadSavedBssid(offer: ArdaWifiOffer): String? {
        val savedSsid = wifiApprovalPreferences.getString(KEY_APPROVED_SSID, null)
        val savedCredential = wifiApprovalPreferences.getInt(
            KEY_APPROVED_CREDENTIAL,
            Int.MIN_VALUE,
        )
        if (savedSsid != offer.ssid || savedCredential != credentialFingerprint(offer)) {
            return null
        }
        return normalizeBssid(
            wifiApprovalPreferences.getString(KEY_APPROVED_BSSID, null),
        )
    }

    private fun clearSavedBssid() {
        wifiApprovalPreferences.edit()
            .remove(KEY_APPROVED_SSID)
            .remove(KEY_APPROVED_BSSID)
            .remove(KEY_APPROVED_CREDENTIAL)
            .apply()
    }

    private fun credentialFingerprint(offer: ArdaWifiOffer): Int =
        "${offer.security.lowercase(Locale.US)}|${offer.passphrase.orEmpty()}".hashCode()

    private fun normalizeBssid(value: String?): String? {
        val normalized = value
            ?.trim()
            ?.uppercase(Locale.US)
            ?.takeIf { it.matches(BSSID_PATTERN) }
            ?: return null
        return normalized.takeUnless {
            it == "00:00:00:00:00:00" ||
                it == "02:00:00:00:00:00" ||
                it == "FF:FF:FF:FF:FF:FF"
        }
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
        private const val WIFI_SELECTION_TIMEOUT_MS = 120_000L
        private const val WIFI_DISCOVERY_TIMEOUT_MS = 45_000L
        private const val WIFI_SCAN_RETRY_INTERVAL_MS = 5_000L
        private const val WIFI_SCAN_POLL_INTERVAL_MS = 500L
        private const val EXACT_BSSID_RECONNECT_TIMEOUT_MS = 20_000L
        private const val CELLULAR_REQUEST_TIMEOUT_MS = 45_000L
        private const val WIFI_APPROVAL_PREFS = "arda_rn3b_wifi_approval"
        private const val KEY_APPROVED_SSID = "approved_ssid"
        private const val KEY_APPROVED_BSSID = "approved_bssid"
        private const val KEY_APPROVED_CREDENTIAL = "approved_credential"
        private val BSSID_PATTERN = Regex(
            "^[0-9A-F]{2}(:[0-9A-F]{2}){5}$",
        )
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
