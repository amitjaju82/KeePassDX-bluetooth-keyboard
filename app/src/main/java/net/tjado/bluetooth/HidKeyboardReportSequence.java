/**
 * Authorizer
 *
 *  Licensed under GNU General Public License 3.0.
 *
 * @license GPL-3.0 <https://opensource.org/licenses/GPL-3.0>
 */

package net.tjado.bluetooth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Turns the raw scancode stream produced by
 * {@code OutputBluetoothKeyboard.convertTextToScancode()} into the exact, ordered list of
 * 8-byte HID keyboard input reports that has to be put on the wire.
 *
 * <p>The scancode stream contains one 8-byte <em>key down</em> report per character
 * (byte 0 = modifier bitmask, byte 1 = reserved, bytes 2..7 = key array). It contains no
 * key-up reports at all, so a matching all-zero <em>key up</em> report has to be inserted
 * after every key-down report. If such a release is missing (or is lost on the way to the
 * host) the host keeps the key logically pressed and starts auto-repeating it.
 *
 * <p>This class is deliberately free of any Android dependency so that the sequencing rules
 * can be unit tested on the JVM.
 */
public final class HidKeyboardReportSequence
{
    /** Size of a boot-protocol keyboard input report, without the report ID. */
    public static final int REPORT_SIZE = 8;

    /** Index of the modifier bitmask (Ctrl/Shift/Alt/GUI) inside a report. */
    public static final int MODIFIER_INDEX = 0;

    private HidKeyboardReportSequence() {}

    /** @return a fresh all-keys-up (and all-modifiers-up) report. */
    public static byte[] allKeysUp()
    {
        return new byte[REPORT_SIZE];
    }

    /** @return true if {@code report} releases every key <em>and</em> every modifier. */
    public static boolean isAllKeysUp(byte[] report)
    {
        if (report == null || report.length != REPORT_SIZE) {
            return false;
        }
        for (byte b : report) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Expand a raw scancode stream into the full wire sequence.
     *
     * <p>Every key-down report is followed by an all-keys-up report, and the sequence is
     * always terminated by an all-keys-up report. Modifiers are released by the very same
     * all-zero report, so a modifier can never stay stuck either.
     *
     * @param rawScancodes concatenated 8-byte key-down reports; may be null or empty.
     * @return an immutable-in-practice list of freshly allocated 8-byte reports. Empty only
     *         if {@code rawScancodes} carried no complete report.
     */
    public static List<byte[]> expand(byte[] rawScancodes)
    {
        List<byte[]> reports = new ArrayList<>();
        if (rawScancodes == null) {
            return reports;
        }

        int reportCount = rawScancodes.length / REPORT_SIZE;
        for (int i = 0; i < reportCount; i++) {
            int start = i * REPORT_SIZE;
            byte[] keyDown = Arrays.copyOfRange(rawScancodes, start, start + REPORT_SIZE);

            // A key-down report that presses nothing carries no information; the release
            // that terminates the sequence covers it.
            if (isAllKeysUp(keyDown)) {
                continue;
            }

            reports.add(keyDown);
            reports.add(allKeysUp());
        }

        // Guarantee a terminating release even for input that pressed nothing at all.
        if (reports.isEmpty() && reportCount > 0) {
            reports.add(allKeysUp());
        }

        return reports;
    }

    /**
     * Number of whole reports carried by a raw scancode stream. A trailing partial block is
     * not a valid report and is reported separately by {@link #trailingPartialBytes}.
     */
    public static int completeReportCount(byte[] rawScancodes)
    {
        return (rawScancodes == null) ? 0 : rawScancodes.length / REPORT_SIZE;
    }

    /** @return the number of bytes at the end of the stream that do not form a whole report. */
    public static int trailingPartialBytes(byte[] rawScancodes)
    {
        return (rawScancodes == null) ? 0 : rawScancodes.length % REPORT_SIZE;
    }
}
