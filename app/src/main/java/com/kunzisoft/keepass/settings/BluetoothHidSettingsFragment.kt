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
package com.kunzisoft.keepass.settings

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.activities.dialogs.UnavailableFeatureDialogFragment
import com.kunzisoft.keepass.hid.BluetoothHidManager
import com.kunzisoft.keepass.hid.BluetoothHidManager.LinkState
import net.tjado.bluetooth.BluetoothDeviceListing
import net.tjado.bluetooth.BluetoothDeviceWrapper
import net.tjado.bluetooth.BluetoothUtils
import net.tjado.bluetooth.HidDeviceProfile

/**
 * Pairing and configuration for the Bluetooth keyboard feature.
 *
 * Pairing is initiated here rather than from Android's own Bluetooth settings: the phone has to
 * connect to the computer in the HID *device* role for the pairing to register it as a keyboard
 * host, and only this screen records which paired computer to type into.
 */
class BluetoothHidSettingsFragment : NestedSettingsFragment() {

    private var deviceListing: BluetoothDeviceListing? = null
    private var bluetoothAdapter: BluetoothAdapter? = null

    private var devicesCategory: PreferenceCategory? = null
    private var candidatesCategory: PreferenceCategory? = null
    private var scanPreference: Preference? = null

    /** True while the user is picking a new computer to add. */
    private var addMode = false

    /** Devices found by the current discovery run, keyed by address to avoid duplicates. */
    private val discovered = LinkedHashMap<String, BluetoothDevice>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            refreshDeviceList()
        } else {
            scanPreference?.summary = getString(R.string.bluetooth_hid_permission_needed)
        }
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    intentDevice(intent)?.let { device ->
                        // Exclude devices that are themselves keyboards or mice; what we want
                        // is a potential HID *host*.
                        if (HidDeviceProfile.isProfileSupported(device)) {
                            discovered[device.address] = device
                            refreshDeviceList()
                        }
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothAdapter.ACTION_STATE_CHANGED -> refreshDeviceList()
                BluetoothAdapter.ACTION_DISCOVERY_STARTED,
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> updateScanRow()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun intentDevice(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    override fun onCreateScreenPreference(
        screen: Screen,
        savedInstanceState: Bundle?,
        rootKey: String?
    ) {
        setPreferencesFromResource(R.xml.preferences_bluetooth_hid, rootKey)

        devicesCategory = findPreference(getString(R.string.bluetooth_hid_devices_key))
        candidatesCategory = findPreference(getString(R.string.bluetooth_hid_candidates_key))
        scanPreference = findPreference(getString(R.string.bluetooth_hid_scan_key))

        if (!BluetoothHidManager.isApiSupported()) {
            showUnsupported(R.string.bluetooth_hid_unsupported_summary)
            return
        }

        val context = requireContext()
        bluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter

        if (bluetoothAdapter == null) {
            showUnsupported(R.string.bluetooth_hid_unsupported_summary)
            return
        }

        deviceListing = try {
            BluetoothDeviceListing(context)
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth device listing unavailable", e)
            null
        }

        scanPreference?.setOnPreferenceClickListener {
            onScanClicked()
            true
        }
    }

    private fun showUnsupported(summaryRes: Int) {
        scanPreference?.apply {
            isEnabled = false
            title = getString(R.string.bluetooth_hid_unsupported_title)
            summary = getString(summaryRes)
        }
        findPreference<Preference>(getString(R.string.bluetooth_hid_enable_key))?.isEnabled = false
        if (!BluetoothHidManager.isApiSupported()) {
            UnavailableFeatureDialogFragment
                .getInstance(Build.VERSION_CODES.P)
                .show(parentFragmentManager, "unavailableBluetoothHidDialog")
        }
    }

    override fun onResume() {
        super.onResume()

        if (!BluetoothHidManager.isApiSupported() || bluetoothAdapter == null) return

        ContextCompat.registerReceiver(
            requireContext(),
            discoveryReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        BluetoothHidManager.linkState.observe(this) { refreshDeviceList() }

        refreshDeviceList()
    }

    override fun onPause() {
        // Unregister symmetrically with onResume; leaking this across rotations would stack up
        // registrations.
        try {
            requireContext().unregisterReceiver(discoveryReceiver)
        } catch (e: IllegalArgumentException) {
            // Never registered, because the feature is unavailable on this device.
        }
        addMode = false
        stopDiscovery()
        super.onPause()
    }

    private fun missingPermissions(): Array<String> {
        val context = context ?: return emptyArray()
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    private fun onScanClicked() {
        val adapter = bluetoothAdapter ?: return

        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing)
            return
        }

        if (!adapter.isEnabled) {
            scanPreference?.summary = getString(R.string.bluetooth_hid_bluetooth_off)
            return
        }

        if (addMode) {
            // Second tap closes the picker again.
            addMode = false
            stopDiscovery()
            refreshDeviceList()
            return
        }

        // Registering as a keyboard is what makes the computer offer to pair with us.
        BluetoothHidManager.start(requireContext())
        addMode = true
        startDiscovery()
        refreshDeviceList()
    }

    @Suppress("MissingPermission")
    private fun isDiscovering(): Boolean = try {
        bluetoothAdapter?.isDiscovering == true
    } catch (e: SecurityException) {
        false
    }

    @Suppress("MissingPermission")
    private fun startDiscovery() {
        try {
            discovered.clear()
            bluetoothAdapter?.startDiscovery()
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot start discovery", e)
        }
        updateScanRow()
    }

    @Suppress("MissingPermission")
    private fun stopDiscovery() {
        try {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter?.cancelDiscovery()
            }
        } catch (e: SecurityException) {
            // Nothing to cancel without the permission.
        }
        updateScanRow()
    }

    private fun updateScanRow() {
        scanPreference?.apply {
            when {
                addMode && isDiscovering() -> {
                    title = getString(R.string.bluetooth_hid_scanning)
                    summary = getString(R.string.bluetooth_hid_pair_help)
                }
                addMode -> {
                    title = getString(R.string.bluetooth_hid_add_done)
                    summary = getString(R.string.bluetooth_hid_pair_help)
                }
                else -> {
                    title = getString(R.string.bluetooth_hid_scan_title)
                    summary = getString(R.string.bluetooth_hid_scan_summary)
                }
            }
        }
    }

    /**
     * Rebuild both lists.
     *
     * "Computers" holds only the devices the user has explicitly chosen to type into. Every
     * other paired device - headphones, watches, speakers - is deliberately kept out: they are
     * never valid targets, and a long list of them at send time is the fastest way to type a
     * password into the wrong thing. Unchosen devices appear only while adding.
     */
    private fun refreshDeviceList() {
        val category = devicesCategory ?: return
        val listing = deviceListing ?: return
        val context = context ?: return

        val scan = scanPreference
        category.removeAll()

        val chosen = try {
            listing.availableKeyboardHostDevices
        } catch (e: SecurityException) {
            emptyList<BluetoothDeviceWrapper>()
        }

        val connectedName = BluetoothHidManager.connectedDeviceName.value

        chosen.forEach { wrapper ->
            category.addPreference(Preference(context).apply {
                isPersistent = false
                title = wrapper.name ?: wrapper.address
                summary = describe(listing, wrapper, connectedName)
                setOnPreferenceClickListener {
                    showChosenDeviceActions(listing, wrapper)
                    true
                }
            })
        }

        if (chosen.isEmpty()) {
            category.addPreference(Preference(context).apply {
                isPersistent = false
                isSelectable = false
                title = getString(R.string.bluetooth_hid_no_devices)
                summary = getString(R.string.bluetooth_hid_no_devices_summary)
            })
        }

        scan?.let { category.addPreference(it) }
        updateScanRow()
        refreshCandidateList(chosen.map { it.address }.toSet())
    }

    /** The "Available devices" list, shown only while adding a computer. */
    private fun refreshCandidateList(chosenAddresses: Set<String>) {
        val category = candidatesCategory ?: return
        val listing = deviceListing ?: return
        val context = context ?: return

        category.isVisible = addMode
        category.removeAll()
        if (!addMode) return

        val bondedCandidates = try {
            listing.availableDevices.filter { it.address !in chosenAddresses }
        } catch (e: SecurityException) {
            emptyList<BluetoothDeviceWrapper>()
        }
        val bondedAddresses = bondedCandidates.map { it.address }.toSet()

        bondedCandidates.forEach { wrapper ->
            category.addPreference(Preference(context).apply {
                isPersistent = false
                title = wrapper.name ?: wrapper.address
                summary = getString(R.string.bluetooth_hid_state_paired)
                setOnPreferenceClickListener {
                    addComputer(wrapper.device)
                    true
                }
            })
        }

        discovered.values
            .filter { it.address !in chosenAddresses && it.address !in bondedAddresses }
            .forEach { device ->
                category.addPreference(Preference(context).apply {
                    isPersistent = false
                    title = safeName(device) ?: device.address
                    summary = device.address
                    setOnPreferenceClickListener {
                        addComputer(device)
                        true
                    }
                })
            }

        if (category.preferenceCount == 0) {
            category.addPreference(Preference(context).apply {
                isPersistent = false
                isSelectable = false
                summary = getString(R.string.bluetooth_hid_candidates_empty)
            })
        }
    }

    /** Mark a device as one of the user's computers, and connect so the pairing registers. */
    private fun addComputer(device: BluetoothDevice) {
        val listing = deviceListing ?: return
        stopDiscovery()
        addMode = false

        listing.cacheHidDeviceAsKeyboard(device)
        if (listing.hidDefaultDevice == null) {
            // First computer added becomes the default, so it is offered first at send time.
            listing.cacheHidDefaultDevice(device)
        }
        BluetoothHidManager.pairAsKeyboard(requireContext(), device)

        val name = safeName(device) ?: device.address
        Toast.makeText(
            requireContext(),
            getString(R.string.bluetooth_hid_added, name),
            Toast.LENGTH_SHORT
        ).show()

        refreshDeviceList()
    }

    private fun describe(
        listing: BluetoothDeviceListing,
        wrapper: BluetoothDeviceWrapper,
        connectedName: String?
    ): String {
        val state = when {
            connectedName != null && connectedName == wrapper.name ->
                getString(R.string.bluetooth_hid_state_connected)
            BluetoothHidManager.linkState.value == LinkState.CONNECTING ->
                getString(R.string.bluetooth_hid_state_connecting)
            else -> getString(R.string.bluetooth_hid_state_paired)
        }
        return if (listing.isHidDefaultDevice(wrapper)) {
            getString(R.string.bluetooth_hid_device_default, state)
        } else {
            state
        }
    }

    @Suppress("MissingPermission")
    private fun safeName(device: BluetoothDevice): String? = try {
        device.name
    } catch (e: SecurityException) {
        null
    }

    private fun showChosenDeviceActions(
        listing: BluetoothDeviceListing,
        wrapper: BluetoothDeviceWrapper
    ) {
        val actions = arrayOf(
            getString(R.string.bluetooth_hid_connect),
            getString(R.string.bluetooth_hid_set_default),
            getString(R.string.bluetooth_hid_remove),
            getString(R.string.bluetooth_hid_forget)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(wrapper.name ?: wrapper.address)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> BluetoothHidManager.pairAsKeyboard(requireContext(), wrapper.device)
                    1 -> listing.cacheHidDefaultDevice(wrapper.device)
                    2 -> {
                        // Drop it from the send list but leave the Bluetooth pairing alone;
                        // the user may still use the device for other things.
                        listing.clearHidDevice(wrapper.device)
                    }
                    3 -> {
                        listing.clearHidDevice(wrapper.device)
                        try {
                            // Reflective call into a hidden framework method; it can simply
                            // not exist, in which case the user unpairs from Android settings.
                            BluetoothUtils.removeBond(wrapper.device)
                        } catch (e: Exception) {
                            Log.w(TAG, "could not remove the bond", e)
                        }
                    }
                }
                refreshDeviceList()
            }
            .show()
    }

    companion object {
        private val TAG = BluetoothHidSettingsFragment::class.java.name
    }
}
