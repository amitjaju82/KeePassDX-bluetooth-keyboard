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
package com.kunzisoft.keepass.activities.fragments

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.adapters.EntryAttachmentsItemsAdapter
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.hid.BluetoothHidManager
import com.kunzisoft.keepass.hid.HidPayloadBuilder
import com.kunzisoft.keepass.model.EntryInfo
import com.kunzisoft.keepass.model.FieldProtection
import com.kunzisoft.keepass.model.StreamDirection
import com.kunzisoft.keepass.settings.PreferencesUtil
import com.kunzisoft.keepass.utils.TimeUtil.getDateTimeString
import com.google.android.material.snackbar.Snackbar
import com.kunzisoft.keepass.view.TemplateView
import com.kunzisoft.keepass.view.asError
import com.kunzisoft.keepass.view.collapse
import com.kunzisoft.keepass.view.expand
import com.kunzisoft.keepass.view.hideByFading
import com.kunzisoft.keepass.view.showByFading
import com.kunzisoft.keepass.viewmodels.AttachmentsViewModel
import com.kunzisoft.keepass.viewmodels.EntryViewModel
import net.tjado.bluetooth.BluetoothDeviceListing
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class EntryFragment: DatabaseFragment() {

    private lateinit var rootView: View
    private lateinit var mainSection: View
    private lateinit var advancedSection: View

    private lateinit var templateView: TemplateView

    private lateinit var creationDateView: TextView
    private lateinit var modificationDateView: TextView

    private lateinit var attachmentsContainerView: View
    private lateinit var attachmentsListView: RecyclerView
    private var attachmentsAdapter: EntryAttachmentsItemsAdapter? = null

    private lateinit var customDataView: TextView

    private lateinit var uuidContainerView: View
    private lateinit var uuidReferenceView: TextView

    private val mEntryViewModel: EntryViewModel by activityViewModels()
    private val mAttachmentsViewModel: AttachmentsViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater,
                              container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        return inflater.inflate(R.layout.fragment_entry, container, false)
    }
    
    override fun onViewCreated(view: View,
                               savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rootView = view
        // Hide only the first time
        if (savedInstanceState == null) {
            view.isVisible = false
        }

        mainSection = view.findViewById(R.id.entry_section_main)
        advancedSection = view.findViewById(R.id.entry_section_advanced)

        templateView = view.findViewById(R.id.entry_template)

        templateView.apply {
            // Set copy buttons
            onChangeFieldProtectionClickListener = mEntryViewModel::requestChangeFieldProtection
            onAskCopySafeClickListener = ::showClipboardDialog
            onCopyActionClickListener = mEntryViewModel::requestCopyField
            // Type a field into the computer paired over Bluetooth
            onSendActionClickListener = ::sendFieldToPairedComputer
            // OTP timer updated
            onOtpUpdatedListener = mEntryViewModel::onOtpElementUpdated
        }

        observeBluetoothHidResults()

        attachmentsContainerView = view.findViewById(R.id.entry_attachments_container)
        attachmentsListView = view.findViewById(R.id.entry_attachments_list)
        attachmentsListView.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            (itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false
        }

        creationDateView = view.findViewById(R.id.entry_created)
        modificationDateView = view.findViewById(R.id.entry_modified)

        // TODO Custom data
        // customDataView = view.findViewById(R.id.entry_custom_data)

        uuidContainerView = view.findViewById(R.id.entry_UUID_container)
        uuidContainerView.apply {
            visibility = if (PreferencesUtil.showUUID(context)) View.VISIBLE else View.GONE
        }
        uuidReferenceView = view.findViewById(R.id.entry_UUID_reference)

        context?.let { context ->
            attachmentsAdapter = EntryAttachmentsItemsAdapter(context)
            attachmentsAdapter?.onItemClickListener = { item ->
                mEntryViewModel.onAttachmentSelected(item.attachment)
            }
            attachmentsAdapter?.onListSizeChangedListener = { previousSize, newSize ->
                if (previousSize > 0 && newSize == 0) {
                    attachmentsContainerView.collapse(true)
                } else if (previousSize == 0 && newSize == 1) {
                    attachmentsContainerView.expand(true)
                } else {
                    attachmentsContainerView.isVisible = newSize != 0
                }
            }
            attachmentsListView.adapter = attachmentsAdapter
        }

        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    mEntryViewModel.entryUIState
                        .map { it.entryInfo }
                        .distinctUntilChanged()
                        .collect { entryInfo ->
                            entryInfo?.let {
                                assignEntryInfo(it)
                                // Smooth appearing
                                rootView.showByFading()
                            }
                        }
                }
                launch {
                    mAttachmentsViewModel.attachmentsUIState.collect { state ->
                        attachmentsAdapter?.assignItems(state.attachments)
                    }
                }
                launch {
                    mEntryViewModel.entryEvents.collect { event ->
                        when (event) {
                            is EntryViewModel.EntryEvent.EntryLoaded -> {
                                resetAppTimeoutWhenViewFocusedOrChanged(rootView)
                            }
                            is EntryViewModel.EntryEvent.SectionSelected -> {
                                when (event.section) {
                                    EntryViewModel.EntrySection.MAIN -> {
                                        mainSection.showByFading()
                                        advancedSection.hideByFading()
                                    }
                                    EntryViewModel.EntrySection.ADVANCED -> {
                                        mainSection.hideByFading()
                                        advancedSection.showByFading()
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                }
                launch {
                    mEntryViewModel.onFieldProtectionUpdated.collect { fieldProtection ->
                        updateField(fieldProtection)
                    }
                }
            }
        }
    }

    override fun onDatabaseRetrieved(database: ContextualDatabase) {
        attachmentsAdapter?.binaryCache = database.binaryCache
    }

    override fun onDestroyView() {
        super.onDestroyView()
        templateView.apply {
            onChangeFieldProtectionClickListener = null
            onAskCopySafeClickListener = null
            onCopyActionClickListener = null
            onSendActionClickListener = null
            onOtpUpdatedListener = null
        }
        attachmentsAdapter?.apply {
            onItemClickListener = null
            onListSizeChangedListener = null
        }
        attachmentsListView.adapter = null
    }

    private fun assignEntryInfo(entryInfo: EntryInfo) {
        // Set template
        templateView.setTemplate(entryInfo.template)

        // Populate entry views
        templateView.setEntryInfo(entryInfo)

        // Assign attachments
        val attachments = entryInfo.attachments
        attachmentsContainerView.isVisible = attachments.isNotEmpty()
        mAttachmentsViewModel.setAttachments(
            attachments = attachments,
            direction = StreamDirection.DOWNLOAD
        )

        // Assign dates
        creationDateView.text = entryInfo.creationTime.getDateTimeString(resources)
        modificationDateView.text = entryInfo.lastModificationTime.getDateTimeString(resources)

        // Assign special data
        uuidReferenceView.text = entryInfo.nodeId.toString()
    }

    fun updateField(field: FieldProtection) {
        templateView.setFieldProtection(field)
    }

    /**
     * Type a field into the computer paired over Bluetooth, as if from a keyboard.
     *
     * Runs alongside the clipboard and Magikeyboard paths without altering either.
     */
    private fun sendFieldToPairedComputer(fieldProtection: FieldProtection) {
        val context = context ?: return

        val listing = try {
            BluetoothDeviceListing(context)
        } catch (e: Exception) {
            showBluetoothMessage(getString(R.string.bluetooth_hid_error_unavailable), isError = true)
            return
        }

        val candidates = try {
            listing.availableDevices
        } catch (e: SecurityException) {
            emptyList()
        }

        if (candidates.isEmpty()) {
            showBluetoothMessage(getString(R.string.bluetooth_hid_error_no_device), isError = true)
            return
        }

        // Build the keystrokes before asking, so an unmappable value fails before the user
        // has picked a computer.
        val payload = HidPayloadBuilder.build(
            context,
            fieldProtection.field.protectedValue.charArrayValue
        )
        if (payload is HidPayloadBuilder.Result.Unmappable) {
            showBluetoothMessage(
                resources.getQuantityString(
                    R.plurals.bluetooth_hid_error_unmapped,
                    payload.count,
                    payload.count
                ),
                isError = true
            )
            return
        }
        val scancodes = (payload as HidPayloadBuilder.Result.Ready).scancodes

        // Always ask which computer to type into: the phone may be paired with several, and
        // typing a credential into the wrong one is not recoverable.
        val defaultDevice = try {
            listing.hidDefaultDevice
        } catch (e: Exception) {
            null
        }
        val ordered = candidates.sortedByDescending { it.address == defaultDevice?.address }
        val labels = ordered.map { device ->
            val name = device.name ?: device.address
            if (device.address == defaultDevice?.address) {
                getString(R.string.bluetooth_hid_device_default, name)
            } else {
                name
            }
        }.toTypedArray<CharSequence>()

        AlertDialog.Builder(context)
            .setTitle(R.string.bluetooth_hid_choose_computer)
            .setItems(labels) { _, which ->
                templateView.setSendInProgress(true)
                BluetoothHidManager.send(context, ordered[which].device, scancodes)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                scancodes.fill(0)
            }
            .setOnCancelListener { scancodes.fill(0) }
            .show()
    }

    private fun observeBluetoothHidResults() {
        BluetoothHidManager.lastSendResult.observe(viewLifecycleOwner) { result ->
            if (result == null) return@observe
            templateView.setSendInProgress(false)

            val message = when (result.status) {
                BluetoothHidManager.SendStatus.SENT -> null
                BluetoothHidManager.SendStatus.NO_DEVICE_SELECTED ->
                    getString(R.string.bluetooth_hid_error_no_device)
                BluetoothHidManager.SendStatus.NOT_CONNECTED ->
                    getString(R.string.bluetooth_hid_error_not_connected)
                BluetoothHidManager.SendStatus.CONNECT_TIMEOUT ->
                    getString(R.string.bluetooth_hid_error_connect_timeout)
                BluetoothHidManager.SendStatus.REFUSED_BY_STACK ->
                    getString(R.string.bluetooth_hid_error_refused)
                BluetoothHidManager.SendStatus.PROFILE_UNAVAILABLE ->
                    getString(R.string.bluetooth_hid_error_unavailable)
                BluetoothHidManager.SendStatus.UNMAPPED_CHARACTERS ->
                    resources.getQuantityString(
                        R.plurals.bluetooth_hid_error_unmapped,
                        result.unmappedCount,
                        result.unmappedCount
                    )
            }
            message?.let { showBluetoothMessage(it, isError = true) }
            BluetoothHidManager.consumeResult()
        }
    }

    private fun showBluetoothMessage(message: String, isError: Boolean) {
        val anchor = view ?: return
        Snackbar.make(anchor, message, Snackbar.LENGTH_LONG).apply {
            if (isError) asError()
        }.show()
    }

    private fun showClipboardDialog() {
        context?.let {
            AlertDialog.Builder(it)
                .setMessage(
                    getString(R.string.allow_copy_password_warning) +
                            "\n\n" +
                            getString(R.string.clipboard_warning)
                )
                .create().apply {
                    setButton(AlertDialog.BUTTON_POSITIVE, getText(R.string.enable)) { dialog, _ ->
                        PreferencesUtil.setAllowCopyPasswordAndProtectedFields(context, true)
                        finishDialog(dialog)
                    }
                    setButton(AlertDialog.BUTTON_NEGATIVE, getText(R.string.disable)) { dialog, _ ->
                        PreferencesUtil.setAllowCopyPasswordAndProtectedFields(context, false)
                        finishDialog(dialog)
                    }
                    show()
                }
        }
    }

    private fun finishDialog(dialog: DialogInterface) {
        dialog.dismiss()
        templateView.reload()
    }

    /* -------------
     * Education
     * -------------
     */

    fun firstEntryFieldCopyView(): View? {
        return try {
            templateView.getActionImageView()
        } catch (_: Exception) {
            null
        }
    }

    companion object {

        fun getInstance(): EntryFragment {
            return EntryFragment().apply {
                arguments = Bundle()
            }
        }
    }
}
