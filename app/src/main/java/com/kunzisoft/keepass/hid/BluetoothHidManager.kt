/*
 * Copyright 2019 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassDX.
 *
 *  KeePassDX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.kunzisoft.keepass.hid

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.kunzisoft.keepass.services.BluetoothHidNotificationService
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-global state for the Bluetooth HID keyboard feature.
 *
 * This exists so that [BluetoothHidNotificationService] can be a plain started service driven
 * by action Intents, rather than a bound service that every Activity would have to hold a
 * reference to. Keeping the coupling here rather than on an Activity is what makes the whole
 * feature purely additive to KeePassDX.
 *
 * The scancode payload is deliberately held here as a field and never placed in an Intent
 * extra: an Intent extra crosses a Binder and is serialised into a Parcel, and this payload is
 * a credential in keystroke form.
 */
object BluetoothHidManager {

    /** Where the link currently is, for the settings UI to render. */
    enum class LinkState {
        /** No HID app registered; nothing running. */
        STOPPED,

        /** SDP record registered, advertising as a keyboard, but no host attached. */
        REGISTERED,

        /** Connecting to a host. */
        CONNECTING,

        /** A host is attached and can be typed into. */
        CONNECTED
    }

    /** Why a send finished the way it did. */
    enum class SendStatus {
        SENT,
        NO_DEVICE_SELECTED,
        NOT_CONNECTED,
        UNMAPPED_CHARACTERS,
        CONNECT_TIMEOUT,
        REFUSED_BY_STACK,
        PROFILE_UNAVAILABLE
    }

    /**
     * Outcome of one send request. Carries no credential material - only a status and, for the
     * unmapped case, how many characters could not be typed.
     */
    data class SendResult(
        val requestId: Int,
        val status: SendStatus,
        val unmappedCount: Int = 0
    ) {
        val isSuccess: Boolean get() = status == SendStatus.SENT
    }

    private val _linkState = MutableLiveData(LinkState.STOPPED)
    val linkState: LiveData<LinkState> = _linkState

    private val _connectedDeviceName = MutableLiveData<String?>(null)
    val connectedDeviceName: LiveData<String?> = _connectedDeviceName

    /**
     * Result of the most recent send. Consumers observe it and call [consumeResult] so a
     * configuration change does not replay an old toast/snackbar.
     */
    private val _lastSendResult = MutableLiveData<SendResult?>(null)
    val lastSendResult: LiveData<SendResult?> = _lastSendResult

    private val requestIds = AtomicInteger(0)

    /**
     * The pending scancode stream, handed to the service in-process. Guarded by [payloadLock]
     * because it is written on the main thread and read on the HID transmit thread.
     */
    private val payloadLock = Any()
    private var pendingPayload: ByteArray? = null
    private var pendingRequestId: Int = 0

    /** True when this build and this device can act as a Bluetooth HID keyboard at all. */
    fun isApiSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    @MainThread
    internal fun setLinkState(state: LinkState, deviceName: String? = null) {
        _linkState.value = state
        _connectedDeviceName.value = if (state == LinkState.CONNECTED) deviceName else null
    }

    @MainThread
    internal fun publishResult(result: SendResult) {
        _lastSendResult.value = result
    }

    /** Clear the last result once the UI has shown it. */
    @MainThread
    fun consumeResult() {
        _lastSendResult.value = null
    }

    /**
     * Stash a payload for the service to transmit and return its request id.
     *
     * Any payload already waiting is zeroed first: there is a single HID link, so a newer
     * request always supersedes an older one rather than queueing behind it.
     */
    fun stashPayload(payload: ByteArray): Int {
        val id = requestIds.incrementAndGet()
        synchronized(payloadLock) {
            pendingPayload?.fill(0)
            pendingPayload = payload
            pendingRequestId = id
        }
        return id
    }

    /**
     * Take the pending payload, if its request id still matches. The caller owns the array and
     * must zero it once transmitted.
     */
    internal fun takePayload(requestId: Int): ByteArray? {
        synchronized(payloadLock) {
            if (pendingRequestId != requestId) {
                return null
            }
            val payload = pendingPayload
            pendingPayload = null
            pendingRequestId = 0
            return payload
        }
    }

    /** Zero and drop any payload still waiting. Called on lock, timeout and service teardown. */
    internal fun discardPayload() {
        synchronized(payloadLock) {
            pendingPayload?.fill(0)
            pendingPayload = null
            pendingRequestId = 0
        }
    }

    /**
     * Start the service and register as a Bluetooth HID keyboard.
     *
     * Must be called from a visible Activity: from API 31 a background
     * [Context.startForegroundService] throws ForegroundServiceStartNotAllowedException.
     */
    fun start(context: Context) {
        if (!isApiSupported()) return
        sendAction(context, BluetoothHidNotificationService.ACTION_START)
    }

    /** Pair with, and connect to, a host as a keyboard. */
    fun pairAsKeyboard(context: Context, device: BluetoothDevice) {
        if (!isApiSupported()) return
        sendAction(context, BluetoothHidNotificationService.ACTION_PAIR) {
            it.putExtra(BluetoothHidNotificationService.EXTRA_DEVICE, device)
        }
    }

    /**
     * Transmit [payload] to [device].
     *
     * Only the request id travels in the Intent; the payload itself stays in this object.
     */
    fun send(context: Context, device: BluetoothDevice, payload: ByteArray) {
        if (!isApiSupported()) return
        val requestId = stashPayload(payload)
        sendAction(context, BluetoothHidNotificationService.ACTION_SEND) {
            it.putExtra(BluetoothHidNotificationService.EXTRA_DEVICE, device)
            it.putExtra(BluetoothHidNotificationService.EXTRA_REQUEST_ID, requestId)
        }
    }

    /** Unregister the HID app and stop the service. */
    fun stop(context: Context) {
        context.stopService(Intent(context, BluetoothHidNotificationService::class.java))
    }

    private fun sendAction(
        context: Context,
        action: String,
        configure: (Intent) -> Unit = {}
    ) {
        val intent = Intent(context, BluetoothHidNotificationService::class.java).apply {
            this.action = action
            configure(this)
        }
        ContextCompat.startForegroundService(context, intent)
    }
}
