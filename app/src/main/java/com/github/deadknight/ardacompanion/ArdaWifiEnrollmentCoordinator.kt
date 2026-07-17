package com.github.deadknight.ardacompanion

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Bridges the RFCOMM worker thread to Android's user-confirmed saved-network UI.
 * ACTION_WIFI_ADD_NETWORKS must be launched by a foreground Activity, while the
 * Bluetooth protocol worker must wait without closing RFCOMM.
 */
object ArdaWifiEnrollmentCoordinator {
    data class Request(
        val requestId: Long,
        val ssid: String,
        val passphrase: String?,
        val security: String,
    )

    private data class Pending(
        val request: Request,
        val latch: CountDownLatch = CountDownLatch(1),
        @Volatile var accepted: Boolean = false,
        @Volatile var resultDescription: String = "pending",
    )

    private const val PREFS = "arda_rn3b_saved_wifi"
    private const val KEY_IDENTITY = "approved_identity"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ids = AtomicLong(1)
    private val lock = Any()

    @Volatile
    private var listener: ((Request) -> Unit)? = null

    @Volatile
    private var pending: Pending? = null

    fun attach(callback: (Request) -> Unit) {
        listener = callback
        pending?.request?.let { request ->
            mainHandler.post { callback(request) }
        }
    }

    fun detach(callback: (Request) -> Unit) {
        if (listener === callback) listener = null
    }

    fun ensureSavedNetworkBlocking(
        context: Context,
        offer: ArdaWifiOffer,
        timeoutMs: Long,
    ): Boolean {
        val identity = identity(offer)
        val preferences = context.applicationContext.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE,
        )
        if (preferences.getString(KEY_IDENTITY, null) == identity) {
            ArdaCompanionState.log("ARDA_RN3B_PHONE_SAVED_NETWORK_ENROLLMENT=ALREADY_APPROVED")
            return true
        }

        val request = Request(
            requestId = ids.getAndIncrement(),
            ssid = offer.ssid,
            passphrase = offer.passphrase,
            security = offer.security,
        )
        val created = Pending(request)
        synchronized(lock) {
            check(pending == null) { "Another ARDA Wi-Fi enrollment is already pending" }
            pending = created
        }

        val target = listener
        ArdaCompanionState.log("ARDA_RN3B_PHONE_SAVED_NETWORK_ENROLLMENT=REQUIRED")
        if (target == null) {
            ArdaCompanionState.log(
                "ARDA_RN3B_PHONE_SAVED_NETWORK_UI=WAITING_FOR_NOTIFICATION_TAP",
            )
            ArdaBootstrapService.notifyEnrollmentRequired(context, offer.ssid)
        } else {
            ArdaCompanionState.log("ARDA_RN3B_PHONE_SAVED_NETWORK_UI=LAUNCH_REQUESTED")
            mainHandler.post { target(request) }
        }

        val completed = created.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        synchronized(lock) { if (pending === created) pending = null }
        if (!completed) {
            ArdaCompanionState.log("ARDA_RN3B_PHONE_SAVED_NETWORK_UI=TIMEOUT")
            return false
        }

        ArdaCompanionState.log(
            "ARDA_RN3B_PHONE_SAVED_NETWORK_RESULT=${created.resultDescription}",
        )
        if (!created.accepted) return false

        val persisted = preferences.edit()
            .putString(KEY_IDENTITY, identity)
            .commit()
        ArdaCompanionState.log(
            "ARDA_RN3B_PHONE_SAVED_NETWORK_ENROLLMENT=" +
                if (persisted) "PASS" else "PERSIST_FAIL",
        )
        return persisted
    }


    fun rememberObservedSavedNetwork(
        context: Context,
        offer: ArdaWifiOffer,
    ): Boolean {
        val persisted = context.applicationContext.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE,
        ).edit()
            .putString(KEY_IDENTITY, identity(offer))
            .commit()
        ArdaCompanionState.log(
            "ARDA_RN3B_PHONE_SAVED_NETWORK_ENROLLMENT=" +
                if (persisted) "OBSERVED_EXISTING_NETWORK" else "OBSERVED_PERSIST_FAIL",
        )
        return persisted
    }

    fun invalidateSavedApproval(
        context: Context,
        offer: ArdaWifiOffer,
    ) {
        val preferences = context.applicationContext.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE,
        )
        if (preferences.getString(KEY_IDENTITY, null) != identity(offer)) return
        preferences.edit().remove(KEY_IDENTITY).apply()
        ArdaCompanionState.log(
            "ARDA_RN3B_PHONE_SAVED_NETWORK_APPROVAL_CACHE=INVALIDATED",
        )
    }

    fun complete(
        requestId: Long,
        accepted: Boolean,
        resultDescription: String,
    ) {
        val active = pending ?: return
        if (active.request.requestId != requestId) return
        active.accepted = accepted
        active.resultDescription = resultDescription
        active.latch.countDown()
    }

    private fun identity(offer: ArdaWifiOffer): String = buildString {
        append(offer.ssid)
        append('\u0000')
        append(offer.security.lowercase())
        append('\u0000')
        append(offer.passphrase.orEmpty())
    }
}
