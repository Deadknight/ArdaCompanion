package com.github.deadknight.ardacompanion

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ArdaRfcommClient(
    private val context: Context,
    private val onSessionReady: (String) -> Unit = {},
) : Closeable {
    private val running = AtomicBoolean(false)
    private val requestIds = AtomicLong(1)
    private val proxyController = ReverseProxyController(context)

    @Volatile
    private var socket: BluetoothSocket? = null

    fun connectAndRun(
        bluetoothAddress: String,
        keepOpen: Boolean = true,
    ) {
        if (!running.compareAndSet(false, true)) {
            ArdaCompanionState.log("ARDA_RN3B_PHONE_CLIENT_ALREADY_RUNNING=YES")
            return
        }

        try {
            require(hasConnectPermission()) {
                "BLUETOOTH_CONNECT permission is not granted"
            }

            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: error("Bluetooth adapter unavailable")
            require(adapter.isEnabled) { "Bluetooth adapter disabled" }

            adapter.cancelDiscovery()
            val device = adapter.getRemoteDevice(bluetoothAddress)
            val connectedSocket = connectRfcommWithRetry(device)
            socket = connectedSocket
            ArdaCompanionState.connected.value = true
            ArdaCompanionState.log("ARDA_RN3B_PHONE_RFCOMM_CONNECT=PASS")

            val input = connectedSocket.inputStream
            val output = connectedSocket.outputStream

            val helloId = requestIds.getAndIncrement()
            ArdaBluetoothProtocol.writeFrame(
                output,
                ArdaBluetoothProtocol.OP_HELLO,
                helloId,
                JSONObject()
                    .put("role", "phone_companion")
                    .put("package", "com.github.deadknight.ardacompanion")
                    .put("keep_profiles_connected", true)
                    .put("keep_bootstrap_rfcomm_open", keepOpen)
                    .toString(),
            )
            ArdaCompanionState.log("ARDA_RN3B_PHONE_HELLO_TX=PASS")

            val helloAck = requireResponse(
                input = input,
                expectedOperation = ArdaBluetoothProtocol.OP_HELLO_ACK,
                expectedRequestId = helloId,
            )
            val helloJson = JSONObject(helloAck.payloadJson)
            require(helloJson.optBoolean("wifi_bootstrap_supported", false)) {
                "AAOS LocalOnlyHotspot is not available"
            }
            ArdaCompanionState.log("ARDA_RN3B_PHONE_HELLO_ACK_RX=PASS")

            sendPingAndRequirePong(input, output)
            ArdaCompanionState.gatePassed.value = true
            ArdaCompanionState.log("ARDA_RN3B_PHONE_HELLO_PING_PONG_GATE=PASS")

            val wifiRequestId = requestIds.getAndIncrement()
            ArdaBluetoothProtocol.writeFrame(
                output,
                ArdaBluetoothProtocol.OP_WIFI_REQUEST,
                wifiRequestId,
                JSONObject()
                    .put("mode", "aaos_local_only_hotspot")
                    .put("need_cellular_proxy", true)
                    .toString(),
            )
            ArdaCompanionState.log("ARDA_RN3B_PHONE_WIFI_REQUEST_TX=PASS")

            val wifiOfferFrame = requireResponse(
                input = input,
                expectedOperation = ArdaBluetoothProtocol.OP_WIFI_OFFER,
                expectedRequestId = wifiRequestId,
            )
            val wifiOffer = ArdaWifiOffer.fromJson(wifiOfferFrame.payloadJson)
            ArdaCompanionState.log("ARDA_RN3B_PHONE_WIFI_OFFER_RX=PASS")
            ArdaCompanionState.log("ARDA_RN3B_PHONE_WIFI_SSID=${wifiOffer.ssid}")

            val endpoint = proxyController.startBlocking(wifiOffer)

            if (endpoint.proxyAvailable) {
                val proxyPort = requireNotNull(endpoint.port)
                val proxyRequestId = requestIds.getAndIncrement()
                ArdaBluetoothProtocol.writeFrame(
                    output,
                    ArdaBluetoothProtocol.OP_PROXY_READY,
                    proxyRequestId,
                    JSONObject()
                        .put("phone_ip", endpoint.phoneIp)
                        .put("proxy_port", proxyPort)
                        .put("proxy_type", "http_connect")
                        .put("egress_network", "cellular")
                        .put("test_url", DEFAULT_PROXY_TEST_URL)
                        .toString(),
                )
                ArdaCompanionState.log("ARDA_RN3B_PHONE_PROXY_READY_TX=PASS")

                val proxyResult = requireResponse(
                    input = input,
                    expectedOperation = ArdaBluetoothProtocol.OP_PROXY_RESULT,
                    expectedRequestId = proxyRequestId,
                )
                val proxyJson = JSONObject(proxyResult.payloadJson)
                require(proxyJson.optString("status") == "ready") {
                    proxyJson.optString("error", "senderd rejected phone proxy")
                }
                ArdaCompanionState.proxyReady.value = true
                ArdaCompanionState.log("ARDA_RN3B_PHONE_REVERSE_PROXY_GATE=PASS")
                ArdaCompanionState.log(
                    "ARDA_RN3B_PHONE_GLOBAL_PROXY=${proxyJson.optString("proxy")}",
                )
                ArdaCompanionState.log(
                    "ARDA_RN3B_PHONE_PROXY_TEST_STATUS=${proxyJson.optString("test_status")}",
                )
                ArdaCompanionState.log(
                    "ARDA_RN3B_PHONE_PROXY_CONNECT_TEST_STATUS=" +
                        proxyJson.optString("connect_test_status"),
                )
                notifySessionReady(endpoint)
            } else {
                // LocalOnlyHotspot is useful independently from cellular egress.
                // Keep RFCOMM alive so the local Wi-Fi gate can be inspected and
                // cellular can be added later without repeating Bluetooth setup.
                ArdaCompanionState.log(
                    "ARDA_RN3B_PHONE_LOCAL_ONLY_ACTIVE=${endpoint.phoneIp}",
                )
                notifySessionReady(endpoint)
            }

            if (!keepOpen) {
                ArdaBluetoothProtocol.writeFrame(
                    output,
                    ArdaBluetoothProtocol.OP_SESSION_STOP,
                    requestIds.getAndIncrement(),
                    """{"reason":"rn3b_network_gate_complete"}""",
                )
                return
            }

            while (running.get()) {
                SystemClock.sleep(15_000)
                if (!running.get()) break
                sendPingAndRequirePong(input, output)
                ArdaCompanionState.log("ARDA_RN3B_PHONE_KEEPALIVE_PONG=PASS")
            }
        } catch (error: Throwable) {
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_CLIENT_ERROR=" +
                    "${error.javaClass.simpleName}:${error.safeMessage()}",
            )
        } finally {
            proxyController.close()
            runCatching { socket?.close() }
            socket = null
            running.set(false)
            ArdaCompanionState.resetTransportState()
            ArdaCompanionState.log("ARDA_RN3B_PHONE_RFCOMM_CLOSED=YES")
        }
    }

    private fun connectRfcommWithRetry(
        device: android.bluetooth.BluetoothDevice,
    ): BluetoothSocket {
        var lastError: Throwable? = null
        repeat(RFCOMM_CONNECT_ATTEMPTS) { index ->
            if (!running.get()) error("RFCOMM connection cancelled")
            val attempt = index + 1
            val candidate = device.createRfcommSocketToServiceRecord(
                ArdaBluetoothProtocol.SERVICE_UUID,
            )
            socket = candidate
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_CONNECTING=${device.address} attempt=$attempt",
            )
            try {
                candidate.connect()
                return candidate
            } catch (error: Throwable) {
                lastError = error
                runCatching { candidate.close() }
                socket = null
                ArdaCompanionState.log(
                    "ARDA_RN3B_PHONE_RFCOMM_RETRY=$attempt error=" +
                        "${error.javaClass.simpleName}:${error.safeMessage()}",
                )
                if (attempt < RFCOMM_CONNECT_ATTEMPTS) {
                    SystemClock.sleep(RFCOMM_RETRY_DELAY_MS)
                }
            }
        }
        throw IllegalStateException(
            "RFCOMM connection failed after $RFCOMM_CONNECT_ATTEMPTS attempts",
            lastError,
        )
    }

    private fun requireResponse(
        input: java.io.InputStream,
        expectedOperation: Int,
        expectedRequestId: Long,
    ): ArdaBluetoothProtocol.Frame {
        val frame = ArdaBluetoothProtocol.readFrame(input)
        require(frame.version == ArdaBluetoothProtocol.VERSION) {
            "Unsupported protocol version ${frame.version}"
        }
        if (frame.operation == ArdaBluetoothProtocol.OP_ERROR) {
            throw IllegalStateException("senderd error: ${frame.payloadJson}")
        }
        require(
            frame.operation == expectedOperation &&
                frame.requestId == expectedRequestId,
        ) {
            "Expected ${ArdaBluetoothProtocol.operationName(expectedOperation)}/" +
                "$expectedRequestId, got " +
                "${ArdaBluetoothProtocol.operationName(frame.operation)}/${frame.requestId}"
        }
        return frame
    }

    private fun sendPingAndRequirePong(
        input: java.io.InputStream,
        output: java.io.OutputStream,
    ) {
        val pingId = requestIds.getAndIncrement()
        ArdaBluetoothProtocol.writeFrame(
            output,
            ArdaBluetoothProtocol.OP_PING,
            pingId,
            JSONObject()
                .put("monotonic_ms", SystemClock.elapsedRealtime())
                .toString(),
        )
        ArdaCompanionState.log("ARDA_RN3B_PHONE_PING_TX=PASS")

        requireResponse(
            input = input,
            expectedOperation = ArdaBluetoothProtocol.OP_PONG,
            expectedRequestId = pingId,
        )
        ArdaCompanionState.log("ARDA_RN3B_PHONE_PONG_RX=PASS")
    }

    private fun notifySessionReady(endpoint: ArdaProxyEndpoint) {
        val mode = if (endpoint.proxyAvailable) "reverse_proxy" else "local_only"
        onSessionReady(mode)
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    override fun close() {
        running.set(false)
        proxyController.close()
        runCatching { socket?.close() }
    }

    private fun Throwable.safeMessage(): String =
        message?.replace('\n', ' ') ?: "no-message"

    companion object {
        private const val DEFAULT_PROXY_TEST_URL =
            "http://connectivitycheck.gstatic.com/generate_204"
        private const val RFCOMM_CONNECT_ATTEMPTS = 12
        private const val RFCOMM_RETRY_DELAY_MS = 2_500L
    }
}
