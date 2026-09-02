/**
 * Authorizer
 *
 *  Copyright 2016 by Tjado Mäcke <tjado@maecke.de>
 *  Licensed under GNU General Public License 3.0.
 *
 * @license GPL-3.0 <https://opensource.org/licenses/GPL-3.0>
 *
 * Adapted for KeePassDX: reduced to a pure text -> HID scancode converter.
 * The original OutputBluetoothKeyboard implemented OutputInterface but threw
 * NotImplementedError for every send method; only the conversion half was ever
 * used. Layout lookup by Class.forName has been replaced with an explicit
 * switch so the mapping is compile-time checked and survives minification.
 */

package net.tjado.authorizer;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Converts text into a stream of 8-byte USB HID keyboard reports
 * ({modifier, 0x00, key1..key6}) according to a host keyboard layout.
 */
public class HidKeyboardOutput {

    private static final String TAG = "HidKeyboardOutput";

    /** Host keyboard layouts for which a scancode mapping exists. */
    public enum Language {
        en_US, en_GB, de_DE, AppleMac_de_DE, de_CH, fr_CH, fr_FR, neo
    }

    public static final Language DEFAULT_LANGUAGE = Language.en_US;

    private UsbHidKbd kbdKeyInterpreter;

    public HidKeyboardOutput(Language lang) {
        setLanguage(lang);
    }

    /**
     * Select the host keyboard layout.
     *
     * @return true if the requested layout was applied, false if it fell back to en_US.
     */
    public boolean setLanguage(Language lang) {
        if (lang == null) {
            kbdKeyInterpreter = new UsbHidKbd_en_US();
            return false;
        }

        switch (lang) {
            case en_US:           kbdKeyInterpreter = new UsbHidKbd_en_US(); break;
            case en_GB:           kbdKeyInterpreter = new UsbHidKbd_en_GB(); break;
            case de_DE:           kbdKeyInterpreter = new UsbHidKbd_de_DE(); break;
            case AppleMac_de_DE:  kbdKeyInterpreter = new UsbHidKbd_AppleMac_de_DE(); break;
            case de_CH:           kbdKeyInterpreter = new UsbHidKbd_de_CH(); break;
            case fr_CH:           kbdKeyInterpreter = new UsbHidKbd_fr_CH(); break;
            case fr_FR:           kbdKeyInterpreter = new UsbHidKbd_fr_FR(); break;
            case neo:             kbdKeyInterpreter = new UsbHidKbd_neo(); break;
            default:
                Log.w(TAG, "Unknown layout " + lang + ", falling back to en_US");
                kbdKeyInterpreter = new UsbHidKbd_en_US();
                return false;
        }
        return true;
    }

    /**
     * Outcome of a conversion. Callers must check {@link #isComplete()} and refuse to
     * transmit when it is false: typing a silently truncated password into a host is
     * worse than typing nothing, because the user cannot tell it happened.
     */
    public static final class Conversion {
        private final byte[] scancodes;
        private final List<Character> unmappedCharacters;

        Conversion(byte[] scancodes, List<Character> unmappedCharacters) {
            this.scancodes = scancodes;
            this.unmappedCharacters = Collections.unmodifiableList(unmappedCharacters);
        }

        /** Concatenated 8-byte HID reports. Password material - zero it after use. */
        public byte[] getScancodes() {
            return scancodes;
        }

        /** Characters with no mapping in the active layout, in order of appearance. */
        public List<Character> getUnmappedCharacters() {
            return unmappedCharacters;
        }

        public boolean isComplete() {
            return unmappedCharacters.isEmpty();
        }

        /** Zero the scancode buffer once it has been transmitted. */
        public void wipe() {
            Arrays.fill(scancodes, (byte) 0);
        }
    }

    /**
     * Convert text into concatenated 8-byte HID reports.
     *
     * Characters with no mapping in the active layout are reported rather than dropped,
     * so the caller can refuse to send an incomplete password.
     */
    public Conversion convert(String text) {
        return convert(text == null ? null : text.toCharArray());
    }

    /**
     * Convert text held as a char array.
     *
     * Preferred over the String overload for credential material: the caller keeps ownership
     * of the array and can zero it, which is impossible with an immutable String.
     */
    public Conversion convert(char[] text) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        List<Character> unmapped = new ArrayList<>();

        if (text == null) {
            return new Conversion(outputStream.toByteArray(), unmapped);
        }

        for (int i = 0; i < text.length; i++) {
            char c = text[i];
            try {
                outputStream.write(kbdKeyInterpreter.getScancode(String.valueOf(c)));
            } catch (NoSuchElementException e) {
                unmapped.add(c);
            } catch (IOException e) {
                Log.e(TAG, "convert failed", e);
            }
        }

        if (!unmapped.isEmpty()) {
            // Count only - the characters themselves may be password material.
            Log.w(TAG, unmapped.size() + " character(s) have no mapping in the active layout");
        }

        return new Conversion(outputStream.toByteArray(), unmapped);
    }

    /**
     * Append the scancodes for a literal key name (e.g. "return", "tabulator") to a buffer.
     *
     * @return true if the key exists in the active layout.
     */
    public boolean appendKey(ByteArrayOutputStream out, String keyName) {
        try {
            out.write(kbdKeyInterpreter.getScancode(keyName));
            return true;
        } catch (NoSuchElementException e) {
            Log.w(TAG, "'" + keyName + "' mapping not found");
        } catch (IOException e) {
            Log.e(TAG, "appendKey failed", e);
        }
        return false;
    }

    public byte[] getSingleKey(String keyName) {
        try {
            return kbdKeyInterpreter.getScancode(keyName);
        } catch (NoSuchElementException e) {
            Log.w(TAG, "'" + keyName + "' mapping not found");
        }

        return new byte[0];
    }

    public byte[] getReturn() {
        return getSingleKey("return");
    }

    public byte[] getTabulator() {
        return getSingleKey("tabulator");
    }
}
