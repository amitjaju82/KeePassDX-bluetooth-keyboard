/**
 * Authorizer
 *
 *  Licensed under GNU General Public License 3.0.
 *
 * @license GPL-3.0 <https://opensource.org/licenses/GPL-3.0>
 */

package net.tjado.bluetooth;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import net.tjado.authorizer.UsbHidKbd;
import net.tjado.authorizer.UsbHidKbd_en_US;

/**
 * Tests for the Bluetooth HID Auto-Type transmission path.
 *
 * <p>These cover the bug where the last key of a longer password stayed logically pressed on
 * the host: the every-report invariant is that a key-down is always followed by an all-keys-up
 * report, and that the sequence ends in an explicit zero state no matter what the stack did.
 */
public class HidKeyboardTransmissionTest
{
    private static final int REPORT_SIZE = HidKeyboardReportSequence.REPORT_SIZE;
    private static final byte[] ZERO = new byte[REPORT_SIZE];

    private final UsbHidKbd keyboard = new UsbHidKbd_en_US();

    // ---------------------------------------------------------------- helpers

    /** Mirrors OutputBluetoothKeyboard.convertTextToScancode() without the Android bits. */
    private byte[] scancodes(String text)
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < text.length(); i++) {
            byte[] sc = keyboard.getScancode(String.valueOf(text.charAt(i)));
            out.write(sc, 0, sc.length);
        }
        return out.toByteArray();
    }

    /** Records everything that reached the "stack", with configurable refusals. */
    private static final class FakeSink implements HidReportTransmitter.ReportSink
    {
        final List<byte[]> sentReports = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        long sleptMillis = 0;

        /** Refuse the first attempt of every report (simulates transient congestion). */
        boolean refuseFirstAttempt = false;
        /** Refuse everything (simulates a dead / permanently congested channel). */
        boolean refuseAlways = false;

        private byte[] lastOffered = null;
        private int attemptsOnLast = 0;
        int refusals = 0;

        @Override
        public boolean sendReport(byte[] report)
        {
            if (refuseAlways) {
                refusals++;
                return false;
            }

            if (refuseFirstAttempt) {
                if (!Arrays.equals(report, lastOffered) || attemptsOnLast == 0) {
                    lastOffered = report.clone();
                    attemptsOnLast = 1;
                    refusals++;
                    return false;
                }
                attemptsOnLast = 0;
            }

            // Copy: the production code must not hand us a buffer it mutates later.
            sentReports.add(report.clone());
            return true;
        }

        @Override
        public void sleep(long millis)
        {
            sleptMillis += millis;
        }

        @Override
        public void log(String message)
        {
            logs.add(message);
        }
    }

    private static boolean isZero(byte[] r)
    {
        return Arrays.equals(r, ZERO);
    }

    /**
     * The core invariants every transmitted sequence must satisfy, whatever the input was.
     */
    private void assertWellFormed(List<byte[]> sent, int expectedKeystrokes)
    {
        assertFalse("nothing was transmitted", sent.isEmpty());

        for (byte[] r : sent) {
            assertEquals("every report is exactly one HID report long",
                         REPORT_SIZE, r.length);
        }

        // Requirement: the sequence ends in an explicit no-keys-pressed state.
        assertTrue("sequence must end with an all-keys-up report",
                   isZero(sent.get(sent.size() - 1)));

        int keyDowns = 0;
        for (int i = 0; i < sent.size(); i++) {
            byte[] r = sent.get(i);
            if (isZero(r)) {
                continue;
            }
            keyDowns++;

            assertTrue("key-down at index " + i + " must be followed by a report",
                       i + 1 < sent.size());
            assertTrue("key-down at index " + i + " must be followed by an all-keys-up report",
                       isZero(sent.get(i + 1)));
        }

        assertEquals("one key-down per character", expectedKeystrokes, keyDowns);

        // Requirement: no modifier may survive the sequence.
        assertEquals("modifiers must be released at the end",
                     0, sent.get(sent.size() - 1)[HidKeyboardReportSequence.MODIFIER_INDEX]);
    }

    private FakeSink type(String password)
    {
        FakeSink sink = new FakeSink();
        new HidReportTransmitter(sink).transmit(scancodes(password));
        assertWellFormed(sink.sentReports, password.length());
        return sink;
    }

    private static String repeat(String unit, int targetLength)
    {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < targetLength) {
            sb.append(unit);
        }
        return sb.substring(0, targetLength);
    }

    // ------------------------------------------------- password length matrix

    @Test
    public void password5Characters()
    {
        type("abc12");
    }

    @Test
    public void password13Characters()
    {
        // Just below the length at which the stuck-key bug was reported.
        String pw = repeat("aB3", 13);
        assertEquals(13, pw.length());
        type(pw);
    }

    @Test
    public void password14Characters()
    {
        // The reported threshold: the burst that used to overrun the L2CAP buffer.
        String pw = repeat("aB3", 14);
        assertEquals(14, pw.length());
        type(pw);
    }

    @Test
    public void password20Characters()
    {
        type(repeat("aB3", 20));
    }

    @Test
    public void password50Characters()
    {
        type(repeat("aB3", 50));
    }

    // ------------------------------------------------------- character shapes

    @Test
    public void repeatedCharactersAreSeparatedByAReleaseReport()
    {
        FakeSink sink = type("aaaaaaaaaaaaaaaa");

        // Without an interleaved release the host would see no state change and swallow every
        // repeat - and keep the key held down.
        for (int i = 1; i < sink.sentReports.size(); i++) {
            byte[] prev = sink.sentReports.get(i - 1);
            byte[] cur = sink.sentReports.get(i);
            if (!isZero(cur)) {
                assertFalse("two identical key-downs must not be adjacent (index " + i + ")",
                            Arrays.equals(prev, cur));
            }
        }
    }

    @Test
    public void upperLowerCaseTransitions()
    {
        FakeSink sink = type("aAbBcCdDeEfFgG");

        boolean sawShifted = false;
        boolean sawUnshifted = false;
        for (byte[] r : sink.sentReports) {
            if (isZero(r)) {
                continue;
            }
            if (r[HidKeyboardReportSequence.MODIFIER_INDEX] != 0) {
                sawShifted = true;
            } else {
                sawUnshifted = true;
            }
        }
        assertTrue("expected shifted reports", sawShifted);
        assertTrue("expected unshifted reports", sawUnshifted);
    }

    @Test
    public void numbersAndSymbols()
    {
        type("0123456789");
        type("!@#$%^&*()");
        type("-_=+[]{};:,.?/");
    }

    @Test
    public void shiftHeavyStringNeverLeavesAModifierStuck()
    {
        FakeSink sink = type("ABCDEFGHIJKLMNOPQRSTUVWXYZ");

        // After every shifted key-down the very next report must clear the modifier byte.
        for (int i = 0; i < sink.sentReports.size() - 1; i++) {
            if (sink.sentReports.get(i)[HidKeyboardReportSequence.MODIFIER_INDEX] != 0) {
                assertEquals("modifier must be cleared right after a shifted key",
                             0,
                             sink.sentReports.get(i + 1)[
                                     HidKeyboardReportSequence.MODIFIER_INDEX]);
            }
        }
    }

    @Test
    public void mixedRealisticPassword()
    {
        type("Tr0ub4dor&3-Xk#9Qz");
    }

    // ------------------------------------------------------- sequencing rules

    @Test
    public void expandInsertsAReleaseAfterEveryKeyDown()
    {
        List<byte[]> reports = HidKeyboardReportSequence.expand(scancodes("abc"));
        assertEquals(6, reports.size());
        assertTrue(isZero(reports.get(1)));
        assertTrue(isZero(reports.get(3)));
        assertTrue(isZero(reports.get(5)));
    }

    @Test
    public void expandIgnoresATrailingPartialReport()
    {
        byte[] raw = scancodes("ab");
        byte[] truncated = Arrays.copyOf(raw, raw.length + 3);

        assertEquals(2, HidKeyboardReportSequence.completeReportCount(truncated));
        assertEquals(3, HidKeyboardReportSequence.trailingPartialBytes(truncated));
        assertEquals(4, HidKeyboardReportSequence.expand(truncated).size());
    }

    @Test
    public void expandReturnsFreshArraysNotSharedBuffers()
    {
        List<byte[]> reports = HidKeyboardReportSequence.expand(scancodes("aa"));
        reports.get(0)[2] = (byte) 0x7f;
        assertFalse("reports must not alias each other",
                    Arrays.equals(reports.get(0), reports.get(2)));
    }

    @Test
    public void emptyInputTransmitsNothingButStillReleases()
    {
        FakeSink sink = new FakeSink();
        new HidReportTransmitter(sink).transmit(new byte[0]);

        assertFalse(sink.sentReports.isEmpty());
        for (byte[] r : sink.sentReports) {
            assertTrue("an empty Auto-Type must only ever release keys", isZero(r));
        }
    }

    @Test
    public void nullInputIsSafe()
    {
        FakeSink sink = new FakeSink();
        new HidReportTransmitter(sink).transmit(null);
        assertTrue(isZero(sink.sentReports.get(sink.sentReports.size() - 1)));
    }

    // ------------------------------------------------------- pacing and retry

    @Test
    public void reportsArePaced()
    {
        FakeSink sink = type("abcdefghijklmn");

        long minimum = (long) (sink.sentReports.size() - 1)
                       * HidReportTransmitter.INTER_REPORT_DELAY_MS;
        assertTrue("reports must be paced, slept " + sink.sleptMillis + "ms",
                   sink.sleptMillis >= minimum);
    }

    @Test
    public void congestedStackIsRetriedAndSequenceStillCompletes()
    {
        FakeSink sink = new FakeSink();
        sink.refuseFirstAttempt = true;

        String pw = repeat("aB3", 20);
        HidReportTransmitter.Result result =
                new HidReportTransmitter(sink).transmit(scancodes(pw));

        // Every report was refused once and had to be retried, yet nothing was lost.
        assertTrue("refusals should have occurred", sink.refusals > 0);
        assertTrue("retries should have been counted", result.retries > 0);
        assertEquals("no report may be dropped when a retry succeeds", 0, result.dropped);
        assertTrue(result.releasedCleanly);
        assertWellFormed(sink.sentReports, pw.length());
    }

    @Test
    public void aPermanentlyRefusingStackStillAttemptsToReleaseTheKeys()
    {
        FakeSink sink = new FakeSink();
        sink.refuseAlways = true;

        HidReportTransmitter.Result result =
                new HidReportTransmitter(sink).transmit(scancodes("abcdefghijklmn"));

        assertTrue("sequence must be reported as aborted", result.aborted);
        assertFalse("release could not succeed on a dead channel", result.releasedCleanly);
        // Nothing got through, but the release was attempted with the full retry budget so a
        // channel that recovers still gets an all-keys-up report.
        assertTrue("release must be attempted repeatedly",
                   sink.refusals >= HidReportTransmitter.MAX_KEY_UP_ATTEMPTS);
    }

    @Test
    public void aDefensiveReleaseFollowsTheFinalRelease()
    {
        FakeSink sink = type("abc");

        int trailingZeros = 0;
        for (int i = sink.sentReports.size() - 1; i >= 0; i--) {
            if (!isZero(sink.sentReports.get(i))) {
                break;
            }
            trailingZeros++;
        }
        assertTrue("expected a defensive second all-keys-up report, found " + trailingZeros,
                   trailingZeros >= 2);
    }

    @Test
    public void transmitterNeverLogsReportContent()
    {
        FakeSink sink = type("Tr0ub4dor&3");

        for (String message : sink.logs) {
            for (byte[] r : sink.sentReports) {
                assertFalse("log message must not contain report bytes: " + message,
                            message.contains(hex(r)));
            }
        }
    }

    private static String hex(byte[] in)
    {
        StringBuilder sb = new StringBuilder();
        for (byte b : in) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // --------------------------------------------------- report state storage

    @Test
    public void keyboardReportHandsOutPrivateCopies()
    {
        KeyboardReport report = new KeyboardReport();
        byte[] scancode = {0x02, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00};

        byte[] forTheStack = report.setValue(scancode);
        assertArrayEquals(scancode, forTheStack);

        // Mutating the caller's buffer must not change a report already handed to the stack.
        scancode[2] = 0x05;
        assertEquals(0x04, forTheStack[2]);

        // And the stored state must not be reachable through the returned array either.
        forTheStack[2] = 0x06;
        assertEquals(0x04, report.getReport()[2]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void keyboardReportRejectsWrongSizedScancodes()
    {
        new KeyboardReport().setValue(new byte[]{0x00, 0x01});
    }
}
