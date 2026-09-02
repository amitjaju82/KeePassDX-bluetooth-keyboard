package net.tjado.bluetooth;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import androidx.annotation.Nullable;
import java.util.Objects;

import android.util.Log;

@SuppressLint({"MissingPermission", "RestrictedApi"})
public class BluetoothDeviceListing {

    private static final String TAG = "BluetoothDeviceListing";
    private static final String HID_PREFERENCES = "keepassdx_hid_bt";
    public static final String HID_PREFERRED_HOST = "DEFAULT_HID_HOST";
    public static final String HID_FIDO_HOST = "FIDO_HID_HOST";
    public static final String HID_KEYBOARD_HOST = "KEYBOARD_HID_HOST";
    public static final String HID_UNKNOWN_HOST = "UNKNOWN_HID_HOST";

    final private BluetoothAdapter bluetoothAdapter;
    final private SharedPreferences hidPreferences;

    public BluetoothDeviceListing(Context context) {
        hidPreferences = Objects.requireNonNull(context).
            getSharedPreferences(HID_PREFERENCES, Context.MODE_PRIVATE);

        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = Objects.requireNonNull(bluetoothManager.getAdapter());
    }

    @SuppressLint("RestrictedApi")
    public List<BluetoothDeviceWrapper> getAvailableDevices() {
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        List<BluetoothDeviceWrapper> availableDevices = new ArrayList<>();

        for (BluetoothDevice device : pairedDevices) {
            if (HidDeviceProfile.isProfileSupported(device)) {
                BluetoothClass devClass = device.getBluetoothClass();
                BluetoothDeviceWrapper deviceWrapped = new BluetoothDeviceWrapper(device);
                deviceWrapped.setType(hidPreferences.getString(deviceWrapped.getHash(), HID_UNKNOWN_HOST));
                deviceWrapped.setDefault(isHidDefaultDevice(deviceWrapped));

                logd("Bluetooth device: " + device + " is of class: " + devClass.getMajorDeviceClass());
                logd("Device Type:" + deviceWrapped.getType());

                availableDevices.add(deviceWrapped);
            }
        }

        return availableDevices;
    }

    public List<BluetoothDeviceWrapper> getHidAvailableDevices() {
        List<BluetoothDeviceWrapper> availableDevices = getAvailableDevices();
        List<BluetoothDeviceWrapper> removeDevices = new ArrayList<>();

        for (BluetoothDeviceWrapper device: availableDevices) {
            if (!hidPreferences.contains(device.getHash())) {
                logd(device + " is not paired as an HID host");
                removeDevices.add(device);
            }
        }

        availableDevices.removeAll(removeDevices);

        return availableDevices;
    }

    public List<BluetoothDeviceWrapper> getAvailableKeyboardHostDevices() {
        List<BluetoothDeviceWrapper> availableDevices = getAvailableDevices();
        List<BluetoothDeviceWrapper> removeDevices = new ArrayList<>();

        for (BluetoothDeviceWrapper device: availableDevices) {
            if (!device.getType().equals(HID_KEYBOARD_HOST)) {
                logd("Removing: " + device.getType());
                removeDevices.add(device);
            }
        }

        availableDevices.removeAll(removeDevices);
        return availableDevices;
    }

    public boolean cacheHidDefaultDevice(BluetoothDevice device) {
        if (device != null && HidDeviceProfile.isProfileSupported(device)
                && device.getBondState() == BluetoothDevice.BOND_BONDED) {
            return saveHidPreference(device, HID_PREFERRED_HOST);
        }
        return false;
    }

    public boolean cacheHidDeviceAsKeyboard(BluetoothDevice device) {
        return cacheHidDevice(device, HID_KEYBOARD_HOST);
    }

    public boolean cacheHidDevice(BluetoothDevice device, String type) {
        if (device != null && HidDeviceProfile.isProfileSupported(device)) {
            return saveHidPreference(device, type);
        }
        return false;
    }

    public String getDeviceType(BluetoothDevice device) {
        if (device != null) {
            BluetoothDeviceWrapper deviceModel = new BluetoothDeviceWrapper(device);
            String deviceHash = deviceModel.getHash();
            return hidPreferences.getString(deviceHash, HID_UNKNOWN_HOST);
        }

        return null;
    }

    public boolean isKeyboardHost(BluetoothDevice device) {
        return HID_KEYBOARD_HOST.equals(getDeviceType(device));
    }

    public boolean isKeyboardHost(BluetoothDeviceWrapper device) {
        return HID_KEYBOARD_HOST.equals(device.getType());
    }


    public boolean isHidDevice(BluetoothDevice device) {
        boolean retcode = false;
        if (device != null) {
            BluetoothDeviceWrapper deviceModel = new BluetoothDeviceWrapper(device);
            String deviceHash = deviceModel.getHash();
            retcode = hidPreferences.contains(deviceHash);
        }
        return retcode;
    }

    public boolean isHidDefaultDevice(BluetoothDevice device) {
        if (device != null) {
            BluetoothDeviceWrapper deviceWrapped= new BluetoothDeviceWrapper(device);
            return isHidDefaultDevice(deviceWrapped);
        }

        return false;
    }

    public boolean isHidDefaultDevice(BluetoothDeviceWrapper device) {
        String hidDefaultHost = hidPreferences.getString(HID_PREFERRED_HOST, null);
        if (device != null) {
            String deviceHash = device.getHash();
            return (deviceHash.equals(hidDefaultHost));
        }
        return false;
    }

    @Nullable
    public BluetoothDeviceWrapper getHidDefaultDevice() {
        List<BluetoothDeviceWrapper> pairedDevices = getAvailableDevices();
        if (pairedDevices.size() > 0) {
            String defaultDeviceHash = hidPreferences.getString(HID_PREFERRED_HOST, null);
            if (defaultDeviceHash == null) {
                return null;
            }

            // At this point we had some previous preference so try to see if it is still paired
            for (BluetoothDeviceWrapper device: pairedDevices) {
                if (device.getHash().equals(defaultDeviceHash)) {
                    logd("Found bounded preferred device: " + device);
                    return device;
                }
            }

            // At this point we end up with no currently bonded devices matching the preferred
            // one, so simply clear the preference to reflect the reality
            if (clearHidPreference(null, HID_PREFERRED_HOST)) {
                logd("Preference cleared");
            } else {
                logd("Preference kept");
            }
        }

        return null;
    }

    public boolean clearHidDevice(BluetoothDevice device) {
        boolean retcode = true;
        if (device != null) {
            if (isHidDefaultDevice(device)) {
                retcode = clearHidPreference(device, HID_PREFERRED_HOST);
            }
            if (isHidDevice(device)) {
                retcode &= clearHidPreference(device, HID_FIDO_HOST);
                retcode &= clearHidPreference(device, HID_KEYBOARD_HOST);
            }
        }
        return retcode;
    }

    private boolean saveHidPreference(BluetoothDevice device, String type) {
        BluetoothDeviceWrapper deviceModel = new BluetoothDeviceWrapper(device);

        if (type.equals(HID_PREFERRED_HOST)) {
            return hidPreferences.edit()
                    .putString(HID_PREFERRED_HOST, deviceModel.getHash())
                    .commit();
        } else if (type.equals(HID_FIDO_HOST) || type.equals(HID_KEYBOARD_HOST)) {
            return hidPreferences.edit()
                    .putString(deviceModel.getHash(), type)
                    .commit();
        }
        // Unknown type of preference
        return false;
    }

    private boolean clearHidPreference(BluetoothDevice device, String type) {
        if (type.equals(HID_PREFERRED_HOST)) {
            return hidPreferences.edit()
                    .remove(HID_PREFERRED_HOST)
                    .commit();
        }
        if (type.equals(HID_FIDO_HOST) || type.equals(HID_KEYBOARD_HOST)) {
            BluetoothDeviceWrapper deviceModel = new BluetoothDeviceWrapper(device);
            String deviceHash = deviceModel.getHash();
            if (hidPreferences.contains(deviceHash)) {
                return hidPreferences.edit()
                        .remove(deviceHash)
                        .commit();
            }
        }
        return false;
    }

    /**
     * Debug-only logging, replacing Authorizer's PasswdSafeUtil.dbginfo which was gated on
     * BuildConfig.DEBUG. Log.d alone would also emit in release builds, and these messages
     * carry Bluetooth device names and addresses. Gated on Log.isLoggable rather than on
     * BuildConfig so this package keeps no dependency on app-generated code; enable with
     * "adb shell setprop log.tag.BluetoothDeviceListing DEBUG".
     */
    private static void logd(String message) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, message);
        }
    }
}
