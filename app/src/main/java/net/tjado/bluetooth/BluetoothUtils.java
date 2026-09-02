/**
 * Based on initial work of BluetoothUtils.java from WearMouse, which comes with the
 * following copyright notice, licensed under the Apache License, Version 2.0
 *
 * Copyright 2018, Google LLC All Rights Reserved.
 */

package net.tjado.bluetooth;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Method;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;


/** Bluetooth Helper class that i.a. exposes some hidden methods from the Android framework. */
public class BluetoothUtils {
    private static final String TAG = "BluetoothUtils";

    private static final Method methodCancelBondProcess = lookupCancelBondProcess();
    private static final Method methodRemoveBond = lookupRemoveBond();

    @SuppressLint("MissingPermission")
    public static String getDeviceDisplayName(BluetoothDevice device) {
        return String.format("%s (%s)", device.getName(), device.getAddress());
    }

    /** Cancel an in-progress bonding request started with createBond. */
    public static boolean cancelBondProcess(BluetoothDevice device) {
        if (methodCancelBondProcess != null) {
            try {
                return (Boolean) methodCancelBondProcess.invoke(device);
            } catch (Exception e) {
                Log.e(TAG, "Error invoking cancelBondProcess", e);
            }
        }
        return false;
    }

    /**
     * Remove bond (pairing) with the remote device.
     *
     * <p>Delete the link key associated with the remote device, and immediately terminate
     * connections to that device that require authentication and encryption.
     */
    public static boolean removeBond(BluetoothDevice device) throws NoSuchMethodException {
        if (methodRemoveBond != null) {
            try {
                return (Boolean) methodRemoveBond.invoke(device);
            } catch (Exception e) {
                throw new NoSuchMethodException();
            }
        }
        return false;
    }


    /**
     * Queries whether or not the HID device profile is enabled for the local Bluetooth adapter.
     *
     * <p>The Bluetooth HID device profile is necessary to emulate a virtual HID connection
     * with a host and to implement the wireless CTAPHID protocol over Bluetooth link.
     *
     * @return {@code true} if profile is supported by the queried adapter or {@code false} if the
     * profile is not supported by the adapter or the adapter instance is null.
     */
    @Nullable
    private static Method lookupCancelBondProcess() {
        try {
            return BluetoothDevice.class.getMethod("cancelBondProcess");
        } catch (Exception e) {
            Log.e(TAG, "Error looking up cancelBondProcess", e);
        }
        return null;
    }

    @Nullable
    private static Method lookupRemoveBond() {
        try {
            return BluetoothDevice.class.getMethod("removeBond");
        } catch (Exception e) {
            Log.e(TAG, "Error looking up removeBond", e);
        }
        return null;
    }


    public static boolean androidSupportsBluetoothHid() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
    }

    /**
     * BLUETOOTH_CONNECT only exists from API 31 (S). Below that the legacy BLUETOOTH
     * permission is normal-level and granted at install time, so there is nothing to check.
     * The original unconditional check returned false on every pre-S device.
     */
    public static boolean hasRequiredPermissions(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * BLUETOOTH_SCAN only exists from API 31 (S). Below that, discovery requires a
     * location permission instead.
     */
    public static boolean hasScanPermissions(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean isBluetoothEnabled(Context context)
    {
        BluetoothManager bluetoothManager =
                (BluetoothManager) context.getApplicationContext().getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            return false;
        }
        BluetoothAdapter btAdapter = bluetoothManager.getAdapter();
        if (btAdapter != null) {
            return btAdapter.isEnabled();
        }

        return false;
    }

    public static String parseBondState(int state) {
        if (state == BluetoothDevice.BOND_BONDED) {
            return "Bonded";
        } else if (state == BluetoothDevice.BOND_BONDING) {
            return "Bonding";
        } else {
            return "Available";
        }
    }
}