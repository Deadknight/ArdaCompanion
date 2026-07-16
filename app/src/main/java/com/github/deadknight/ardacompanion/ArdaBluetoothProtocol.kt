package com.github.deadknight.ardacompanion

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID

object ArdaBluetoothProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("e43cd6de-4bb2-49fe-8d43-ecace91038cc")
    const val VERSION = 1
    const val MAX_FRAME_BYTES = 64 * 1024

    const val OP_HELLO = 1
    const val OP_HELLO_ACK = 2
    const val OP_PING = 3
    const val OP_PONG = 4
    const val OP_SESSION_STOP = 5
    const val OP_WIFI_REQUEST = 10
    const val OP_WIFI_OFFER = 11
    const val OP_PROXY_READY = 12
    const val OP_PROXY_RESULT = 13
    const val OP_PROXY_STATUS = 14
    const val OP_PROXY_STOP = 15
    const val OP_PLATFORM_WIFI_INFO = 20
    const val OP_PLATFORM_WIFI_ACK = 21
    const val OP_ERROR = 255

    data class Frame(
        val version: Int,
        val operation: Int,
        val requestId: Long,
        val payloadJson: String,
    )

    fun writeFrame(
        output: OutputStream,
        operation: Int,
        requestId: Long,
        payloadJson: String,
    ) {
        val payload = payloadJson.toByteArray(StandardCharsets.UTF_8)
        val bodyLength = 1 + 1 + 8 + payload.size
        require(bodyLength in 10..MAX_FRAME_BYTES)

        val bytes = ByteBuffer
            .allocate(4 + bodyLength)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(bodyLength)
            .put(VERSION.toByte())
            .put(operation.toByte())
            .putLong(requestId)
            .put(payload)
            .array()

        DataOutputStream(output).apply {
            write(bytes)
            flush()
        }
    }

    fun readFrame(input: InputStream): Frame {
        val data = DataInputStream(input)
        val lengthBytes = ByteArray(4)
        try {
            data.readFully(lengthBytes)
        } catch (error: EOFException) {
            throw EOFException("ARDA Bluetooth peer closed before frame header")
        }

        val bodyLength = ByteBuffer.wrap(lengthBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
        require(bodyLength in 10..MAX_FRAME_BYTES)

        val body = ByteArray(bodyLength)
        data.readFully(body)
        val buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val version = buffer.get().toInt() and 0xff
        val operation = buffer.get().toInt() and 0xff
        val requestId = buffer.long
        val payload = ByteArray(buffer.remaining())
        buffer.get(payload)

        return Frame(
            version = version,
            operation = operation,
            requestId = requestId,
            payloadJson = payload.toString(StandardCharsets.UTF_8),
        )
    }

    fun operationName(operation: Int): String = when (operation) {
        OP_HELLO -> "HELLO"
        OP_HELLO_ACK -> "HELLO_ACK"
        OP_PING -> "PING"
        OP_PONG -> "PONG"
        OP_SESSION_STOP -> "SESSION_STOP"
        OP_WIFI_REQUEST -> "WIFI_REQUEST"
        OP_WIFI_OFFER -> "WIFI_OFFER"
        OP_PROXY_READY -> "PROXY_READY"
        OP_PROXY_RESULT -> "PROXY_RESULT"
        OP_PROXY_STATUS -> "PROXY_STATUS"
        OP_PROXY_STOP -> "PROXY_STOP"
        OP_PLATFORM_WIFI_INFO -> "PLATFORM_WIFI_INFO"
        OP_PLATFORM_WIFI_ACK -> "PLATFORM_WIFI_ACK"
        OP_ERROR -> "ERROR"
        else -> "UNKNOWN_$operation"
    }
}
