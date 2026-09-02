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
package com.kunzisoft.keepass.services

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.hid.BluetoothHidManager
import com.kunzisoft.keepass.hid.BluetoothHidManager.LinkState
import com.kunzisoft.keepass.hid.BluetoothHidManager.SendResult
import com.kunzisoft.keepass.hid.BluetoothHidManager.SendStatus
import net.tjado.bluetooth.HidDeviceController

/**
 * Holds the Bluetooth HID keyboard link so credentials can be typed into a paired computer.
 *
 * Extends [LockNotificationService], so locking the database - by button, by timeout or by
 * screen-off - tears the link down and unregisters the HID SDP record. A locked database must
 * not leave a keyboard attached to the host.
 *
 * Transmission runs on a dedicated worker thread. The HID callbacks arrive on the main thread
 * (HidDeviceApp marshals them there), and a send blocks for roughly
 * 2 x characters x 12ms, so it can never run on the caller's thread.
 */
@RequiresApi(Build.VERSION_CODES.P)
class BluetoothHidNotificationService : LockNotificationService() {

    override val notificationId = 487

    private val hidDeviceController: HidDeviceController = HidDeviceController.getInstance()

    private var transmitThread: HandlerThread? = null
    private var transmitHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var registered = false
    private var listenerRegistered = false

    /** Set while a send is waiting for the host to attach. */
    private var pendingRequestId: Int = 0
    private var pendingDevice: BluetoothDevice? = null
    private var connectWatchdog: Runnable? = null

    override fun retrieveChannelId(): String = CHANNEL_BLUETOOTH_HID_ID

    override fun retrieveChannelName(): String = getString(R.string.bluetooth_hid_title)

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                    Log.i(TAG, "Bluetooth switched off, stopping the HID service")
                    stopService()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        transmitThread = HandlerThread("keepassdx-hid-tx").also {
            it.start()
            transmitHandler = Handler(it.looper)
        }

        // ACTION_STATE_CHANGED is a protected system broadcast, but the explicit flag is
        // required from API 34 and is harmless below it.
        ContextCompat.registerReceiver(
            this,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        startForegroundCompat(notificationId, buildServiceNotification(),
            NotificationServiceType.BLUETOOTH_HID)

        ensureHidAppRegistered()

        when (intent?.action) {
            ACTION_START -> {
                // Registration above is the whole job.
            }
            ACTION_PAIR -> {
                intentDevice(intent)?.let { pairAsKeyboard(it) }
            }
            ACTION_SEND -> {
                val device = intentDevice(intent)
                val requestId = intent.getIntExtra(EXTRA_REQUEST_ID, 0)
                if (device == null) {
                    finishRequest(requestId, SendStatus.NO_DEVICE_SELECTED)
                } else {
                    startSend(device, requestId)
                }
            }
            null -> Log.w(TAG, "null intent")
            else -> Log.w(TAG, "unknown action ${intent.action}")
        }

        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun intentDevice(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_DEVICE)
        }
    }

    private fun buildServiceNotification() = buildNewNotification().apply {
        setSmallIcon(R.drawable.notification_ic_keyboard_key_24dp)
        setContentTitle(getString(R.string.bluetooth_hid_notification_title))
        setContentText(getString(R.string.bluetooth_hid_notification_description))
        setAutoCancel(false)
        setOngoing(true)
    }

    private fun ensureHidAppRegistered() {
        if (listenerRegistered) return
        listenerRegistered = true
        hidDeviceController.registerKeyboard(this, profileListener)
    }

    private fun pairAsKeyboard(device: BluetoothDevice) {
        BluetoothHidManager.setLinkState(LinkState.CONNECTING)
        hidDeviceController.requestConnect(device)
    }

    /**
     * Connect to the chosen computer, type, and drop the link again.
     *
     * The link is deliberately not kept alive between sends: the phone should not sit there
     * advertising itself as a keyboard to a computer the user has walked away from.
     *
     * Registration is asynchronous, so a send that arrives before the HID app is registered
     * parks itself and is resumed from onAppStatusChanged().
     */
    private fun startSend(device: BluetoothDevice, requestId: Int) {
        cancelWatchdog()
        pendingRequestId = requestId
        pendingDevice = device
        BluetoothHidManager.setLinkState(LinkState.CONNECTING)

        // One watchdog covers registering, connecting and typing.
        connectWatchdog = Runnable {
            if (pendingRequestId == requestId) {
                Log.w(TAG, "host did not attach within ${CONNECT_TIMEOUT_MS}ms")
                finishRequest(requestId, SendStatus.CONNECT_TIMEOUT)
            }
        }.also { mainHandler.postDelayed(it, CONNECT_TIMEOUT_MS) }

        if (registered) {
            hidDeviceController.requestConnect(device)
        }
        // Otherwise onAppStatusChanged(true) picks it up as soon as the SDP record is live.
    }

    /** Resume a send that was waiting for the HID app to finish registering. */
    private fun resumePendingSend() {
        val device = pendingDevice ?: return
        if (pendingRequestId == 0) return
        hidDeviceController.requestConnect(device)
    }

    /**
     * Tear the link down once a send has finished, so nothing persists between uses.
     *
     * Stopping the service unregisters the SDP record through stopService(), which is what
     * actually makes the phone stop presenting itself as a keyboard.
     */
    private fun teardownAfterSend() {
        mainHandler.postDelayed({
            if (pendingRequestId != 0) {
                // A newer send arrived while this one was settling; leave it alone.
                return@postDelayed
            }
            try {
                hidDeviceController.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "disconnect failed", e)
            }
            stopService()
        }, POST_SEND_SETTLE_MS)
    }

    private fun transmitPending(requestId: Int) {
        val payload = BluetoothHidManager.takePayload(requestId)
        if (payload == null) {
            // Superseded by a newer request, or already consumed.
            return
        }

        val queued = transmitHandler?.post {
            try {
                val result = hidDeviceController.sendToKeyboardHost(payload)
                // sendReport() is not queued: a congested link silently refuses reports. Report
                // that as a failure rather than claiming the credential was typed.
                val status = when {
                    result == null -> SendStatus.PROFILE_UNAVAILABLE
                    result.dropped > 0 || result.aborted -> SendStatus.REFUSED_BY_STACK
                    else -> SendStatus.SENT
                }
                if (status != SendStatus.SENT) {
                    Log.w(TAG, "transmission incomplete: $result")
                }
                finishRequest(requestId, status)
            } finally {
                // The payload is the credential in keystroke form; do not leave it on the heap.
                payload.fill(0)
            }
        } ?: false

        if (!queued) {
            // No transmit thread, or its looper is already shutting down.
            payload.fill(0)
            finishRequest(requestId, SendStatus.PROFILE_UNAVAILABLE)
        }
    }

    private fun finishRequest(requestId: Int, status: SendStatus, unmapped: Int = 0) {
        mainHandler.post {
            var wasCurrent = false
            if (pendingRequestId == requestId) {
                wasCurrent = true
                pendingRequestId = 0
                pendingDevice = null
                cancelWatchdog()
            }
            if (status != SendStatus.SENT) {
                BluetoothHidManager.discardPayload()
            }
            BluetoothHidManager.publishResult(SendResult(requestId, status, unmapped))

            // Connect, type, disconnect - the link is never held open between sends, whether
            // the send succeeded or failed.
            if (wasCurrent) {
                teardownAfterSend()
            }
        }
    }

    private fun cancelWatchdog() {
        connectWatchdog?.let { mainHandler.removeCallbacks(it) }
        connectWatchdog = null
    }

    private val profileListener = object : HidDeviceController.ProfileListener {

        override fun onAppStatusChanged(pluggedIn: Boolean) {
            registered = pluggedIn
            BluetoothHidManager.setLinkState(
                if (pluggedIn) LinkState.REGISTERED else LinkState.STOPPED
            )
            if (pluggedIn) {
                // A send may have arrived before the SDP record was live.
                resumePendingSend()
            } else {
                BluetoothHidManager.discardPayload()
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    BluetoothHidManager.setLinkState(LinkState.CONNECTED, safeDeviceName(device))
                    val requestId = pendingRequestId
                    val target = pendingDevice
                    if (requestId != 0) {
                        // Only ever type into the host this request asked for. Another host
                        // attaching while a send is pending must not receive the credential.
                        if (target != null && device != null && target.address == device.address) {
                            cancelWatchdog()
                            transmitPending(requestId)
                        } else {
                            Log.w(TAG, "a different host attached while a send was pending")
                        }
                    }
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    BluetoothHidManager.setLinkState(LinkState.CONNECTING)
                }
                else -> {
                    BluetoothHidManager.setLinkState(
                        if (registered) LinkState.REGISTERED else LinkState.STOPPED
                    )
                }
            }
        }

        override fun onInterruptData(
            device: BluetoothDevice?,
            reportId: Int,
            data: ByteArray?,
            inputHost: BluetoothHidDevice?
        ) {
            // A keyboard-only HID device has no output reports to act on.
        }

        override fun onServiceStateChanged(proxy: BluetoothProfile?) {
            if (proxy == null) {
                registered = false
                BluetoothHidManager.setLinkState(LinkState.STOPPED)
            }
        }
    }

    @Suppress("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice?): String? {
        return try {
            device?.name
        } catch (e: SecurityException) {
            null
        }
    }

    /**
     * Called by [LockNotificationService] on LOCK_ACTION, and by the Bluetooth-off receiver.
     * Everything secret goes away here.
     */
    override fun stopService() {
        BluetoothHidManager.discardPayload()
        releaseHid()
        super.stopService()
    }

    private fun releaseHid() {
        cancelWatchdog()
        pendingRequestId = 0
        pendingDevice = null
        if (listenerRegistered) {
            listenerRegistered = false
            // Unconditional: the controller singleton refuses a second listener, so a leaked
            // one would disable the feature until the process dies.
            try {
                hidDeviceController.unregister(profileListener)
            } catch (e: Exception) {
                Log.w(TAG, "unregister failed", e)
            }
        }
        registered = false
        BluetoothHidManager.setLinkState(LinkState.STOPPED)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "receiver was not registered")
        }
        BluetoothHidManager.discardPayload()
        releaseHid()
        transmitHandler = null
        transmitThread?.quitSafely()
        transmitThread = null
        super.onDestroy()
    }

    companion object {
        private val TAG = BluetoothHidNotificationService::class.java.name

        const val ACTION_START = "com.kunzisoft.keepass.hid.ACTION_START"
        const val ACTION_PAIR = "com.kunzisoft.keepass.hid.ACTION_PAIR"
        const val ACTION_SEND = "com.kunzisoft.keepass.hid.ACTION_SEND"

        const val EXTRA_DEVICE = "com.kunzisoft.keepass.hid.EXTRA_DEVICE"
        const val EXTRA_REQUEST_ID = "com.kunzisoft.keepass.hid.EXTRA_REQUEST_ID"

        /** How long to wait for the host to attach before giving up on a send. */
        private const val CONNECT_TIMEOUT_MS = 10_000L

        /**
         * Pause between the last keystroke and dropping the link, so the final all-keys-up
         * report is on the wire before the connection goes away.
         */
        private const val POST_SEND_SETTLE_MS = 400L

        private const val CHANNEL_BLUETOOTH_HID_ID =
            "com.kunzisoft.keepass.notification.channel.bluetoothhid"
    }
}
