/*
 * Copyright 2018 Google LLC All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.tjado.bluetooth;

/**
 * Helper class to store the keyboard state and retrieve the binary report.
 *
 * <p>Every accessor hands out a fresh array. The stored state is read from a Binder thread
 * (a GET_REPORT from the host) while the Auto-Type worker thread is writing the next report,
 * so sharing one buffer between the two would let the host observe a half-written report.
 */
public class KeyboardReport {

    /** Size of a boot-protocol keyboard report, without the report ID. */
    static final int REPORT_SIZE = HidKeyboardReportSequence.REPORT_SIZE;

    /** Last report handed to the stack, replayed on a GET_REPORT from the host. */
    private final byte[] keyboardData = new byte[REPORT_SIZE];

    KeyboardReport() {}

    synchronized byte[] setValue(int modifier, int key1, int key2, int key3, int key4, int key5,
                                int key6) {
        keyboardData[0] = (byte) modifier;
        keyboardData[1] = 0;
        keyboardData[2] = (byte) key1;
        keyboardData[3] = (byte) key2;
        keyboardData[4] = (byte) key3;
        keyboardData[5] = (byte) key4;
        keyboardData[6] = (byte) key5;
        keyboardData[7] = (byte) key6;
        return keyboardData.clone();
    }

    /**
     * Store {@code scancode} as the current keyboard state.
     *
     * @return a private copy to hand to the stack, so that the caller mutating or reusing its
     *         own buffer can never change a report that is already in flight.
     * @throws IllegalArgumentException if the scancode is not exactly one report long.
     */
    synchronized byte[] setValue(byte[] scancode) {
        if (scancode == null || scancode.length != REPORT_SIZE) {
            throw new IllegalArgumentException(
                    "keyboard report must be " + REPORT_SIZE + " bytes, got " +
                    (scancode == null ? "null" : String.valueOf(scancode.length)));
        }
        System.arraycopy(scancode, 0, keyboardData, 0, REPORT_SIZE);
        return keyboardData.clone();
    }

    synchronized byte[] getReport() {
        return keyboardData.clone();
    }

    /** Interface to send the Keyboard data with. */
    public interface KeyboardDataSender {
        /**
         * Send Keyboard data to the connected HID Host device. Up to six buttons pressed
         * simultaneously are supported (not including modifier keys).
         *
         * @param modifier Modifier keys bit mask (Ctrl/Shift/Alt/GUI).
         * @param key1 Scan code of the 1st button that is currently pressed (or 0 if none).
         * @param key2 Scan code of the 2nd button that is currently pressed (or 0 if none).
         * @param key3 Scan code of the 3rd button that is currently pressed (or 0 if none).
         * @param key4 Scan code of the 4th button that is currently pressed (or 0 if none).
         * @param key5 Scan code of the 5th button that is currently pressed (or 0 if none).
         * @param key6 Scan code of the 6th button that is currently pressed (or 0 if none).
         */
        void sendKeyboard(int modifier, int key1, int key2, int key3, int key4, int key5, int key6);

        /**
         * Send one raw 8-byte keyboard report.
         *
         * @return true if the Bluetooth stack accepted the report for transmission.
         */
        boolean sendScancode(byte[] scancode);
    }
}
