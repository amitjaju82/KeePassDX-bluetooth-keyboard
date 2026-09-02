# Provenance of the Bluetooth HID code

This fork adds Bluetooth keyboard auto-type to KeePassDX. The transport and the keyboard layout
tables are ported from other projects and keep their original package names
(`net.tjado.bluetooth`, `net.tjado.authorizer`) and licence headers, so their provenance stays
auditable.

KeePassDX is GPL-3.0. Apache-2.0 code may be incorporated into a GPL-3.0 work; GPL-3.0 into
GPL-3.0 is unchanged. Both are satisfied here.

## Apache-2.0 — Copyright 2018 Google LLC

Derived from Google's Android **WearMouse** sample, by way of Authorizer. Each file keeps its
original Apache-2.0 header.

| File | Changes made |
|---|---|
| `net/tjado/bluetooth/HidDeviceProfile.java` | none |
| `net/tjado/bluetooth/KeyboardReport.java` | hands out defensive copies instead of one shared mutable buffer; validates report size |
| `net/tjado/bluetooth/HidDeviceApp.java` | FIDO branch removed from `registerApp`; dead `BatteryReport` field removed; `sendScancode` returns the `sendReport()` result |
| `net/tjado/bluetooth/HidDeviceController.java` | FIDO mode removed; `ArraySet` → `HashSet`; transmission delegated to `HidReportTransmitter`; scancode logging removed |
| `net/tjado/bluetooth/Constants.java` | FIDO report descriptor, SDP record and QoS removed; SDP record rebranded to KeePassDX |
| `net/tjado/bluetooth/BluetoothUtils.java` | permission checks corrected for API < 31; `isBluetoothEnabled` takes a `Context` and null-guards the adapter |

`BatteryReport.java` was not ported (dead code).

## GPL-3.0 — Copyright Tjado Mäcke

From [Authorizer](https://github.com/tejado/Authorizer).

| File | Changes made |
|---|---|
| `net/tjado/bluetooth/BluetoothDeviceListing.java` | logging swapped to `android.util.Log`, gated to debug builds; device-fingerprint logging removed; FIDO accessors removed; preferences file renamed `keepassdx_hid_bt` |
| `net/tjado/bluetooth/BluetoothDeviceWrapper.java` | local hex helper replacing an Authorizer utility, deliberately locale-independent because the value is a preferences key |
| `net/tjado/authorizer/UsbHidKbd.java` and the eight `UsbHidKbd_*.java` layout tables | **none** — ported verbatim |
| `net/tjado/authorizer/HidKeyboardOutput.java` | was `OutputBluetoothKeyboard`; reduced to a converter (its `sendText` always threw); `Class.forName` layout lookup replaced with an explicit switch; returns unmapped characters instead of silently dropping them; added a `CharArray` overload |

## GPL-3.0 — Copyright Amit Jaju

From [amitjaju82/Authorizer @ `bluetooth-hid-autotype-fixes`](https://github.com/amitjaju82/Authorizer/tree/bluetooth-hid-autotype-fixes).

| File | Changes made |
|---|---|
| `net/tjado/bluetooth/HidKeyboardReportSequence.java` | none — ported verbatim |
| `net/tjado/bluetooth/HidReportTransmitter.java` | none — ported verbatim |
| `app/src/test/java/net/tjado/bluetooth/HidKeyboardTransmissionTest.java` | none — ported verbatim (22 tests) |

## Written for this fork — GPL-3.0

`com/kunzisoft/keepass/hid/BluetoothHidManager.kt`,
`com/kunzisoft/keepass/hid/HidPayloadBuilder.kt`,
`com/kunzisoft/keepass/services/BluetoothHidNotificationService.kt`,
`com/kunzisoft/keepass/settings/BluetoothHidSettingsFragment.kt`,
and the send-button additions to `TextFieldView`, `TemplateView` and `EntryFragment`.

## Not ported

FIDO U2F / WebAuthn over Bluetooth, and Authorizer's USB HID output. Neither is present in this
fork; `net/tjado/webauthn/**` and the `OutputUsbKeyboard*` classes were not copied.
