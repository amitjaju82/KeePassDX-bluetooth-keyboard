/**
 * Authorizer
 *
 *  Licensed under GNU General Public License 3.0.
 *
 * @license GPL-3.0 <https://opensource.org/licenses/GPL-3.0>
 */

package net.tjado.bluetooth;

import java.util.List;

/**
 * Paces and retries the transmission of HID keyboard input reports.
 *
 * <p>{@code BluetoothHidDevice.sendReport()} is <em>not</em> a queued API. When the L2CAP
 * interrupt channel towards the host is congested the Bluetooth stack discards the report and
 * {@code sendReport()} returns {@code false}; there is no retransmission and no completion
 * callback. Sending a whole Auto-Type sequence back to back therefore silently loses reports
 * once the burst exceeds the channel's buffer quota. When the lost report happens to be a
 * key-up, the host keeps the key logically pressed and auto-repeats it - which is exactly the
 * "last character repeats forever" symptom.
 *
 * <p>This class fixes that by
 * <ul>
 *   <li>pacing the reports at the interval the app itself advertises in its HID QoS record,</li>
 *   <li>honouring the {@code sendReport()} return value and retrying a refused report,</li>
 *   <li>spending extra retries on key-up reports, because a lost release is the failure that
 *       actually hurts, and</li>
 *   <li>always terminating with an all-keys-up report, plus a second defensive one, even when
 *       the sequence was aborted by an error.</li>
 * </ul>
 *
 * <p>Deliberately free of any Android dependency so the pacing and retry rules can be unit
 * tested on the JVM.
 */
public final class HidReportTransmitter
{
    /**
     * Delay between two consecutive reports, in milliseconds.
     *
     * <p>Derived from this app's own keyboard QoS record in {@link Constants}: a token rate of
     * 800 byte/s with a 9-byte token bucket, and an advertised latency of 11250 us. One report
     * on the wire is 9 bytes (1 report ID + 8 payload), so the negotiated sustained rate is
     * 800/9 = 88.9 reports/s, i.e. one report every 11.25 ms - the same figure as the
     * advertised latency. 12 ms is the smallest whole millisecond value that still honours
     * that contract, so it is the smallest pacing this code can rely on rather than an
     * arbitrary round number. Reports refused despite the pacing are retried below.
     */
    public static final long INTER_REPORT_DELAY_MS = 12L;

    /** Extra wait before retrying a report the stack refused (congestion back-off). */
    public static final long CONGESTION_BACKOFF_MS = 12L;

    /** Delay before the second, defensive all-keys-up report. */
    public static final long DEFENSIVE_RELEASE_DELAY_MS = 30L;

    /** Attempts for a normal key-down report. */
    public static final int MAX_KEY_DOWN_ATTEMPTS = 5;

    /** Attempts for a key-up report. A lost release is the bug this class exists to prevent. */
    public static final int MAX_KEY_UP_ATTEMPTS = 10;

    /** Everything the transmitter needs from its environment. */
    public interface ReportSink
    {
        /**
         * Put a single 8-byte report on the wire.
         *
         * @return the underlying {@code BluetoothHidDevice.sendReport()} result: {@code false}
         *         means the report was refused/discarded and never reached the host.
         */
        boolean sendReport(byte[] report);

        /** Pause the calling thread. */
        void sleep(long millis) throws InterruptedException;

        /**
         * Diagnostic message. Implementations must never be handed report content, character
         * data or any other secret; messages are counters and status only.
         */
        void log(String message);
    }

    /** Outcome of a transmission, for logging and testing. Carries no report content. */
    public static final class Result
    {
        /** Reports that reached the stack successfully. */
        public final int sent;
        /** Reports the stack refused on every attempt. */
        public final int dropped;
        /** Individual send attempts that were refused (including ones a retry recovered). */
        public final int retries;
        /** True if the final all-keys-up report was accepted by the stack. */
        public final boolean releasedCleanly;
        /** True if the transmission was cut short (interrupt or unrecoverable refusal). */
        public final boolean aborted;

        Result(int sent, int dropped, int retries, boolean releasedCleanly, boolean aborted)
        {
            this.sent = sent;
            this.dropped = dropped;
            this.retries = retries;
            this.releasedCleanly = releasedCleanly;
            this.aborted = aborted;
        }

        @Override
        public String toString()
        {
            return "sent=" + sent + " dropped=" + dropped + " retries=" + retries +
                   " releasedCleanly=" + releasedCleanly + " aborted=" + aborted;
        }
    }

    private final ReportSink sink;

    public HidReportTransmitter(ReportSink sink)
    {
        this.sink = sink;
    }

    /**
     * Transmit a raw scancode stream as a fully paced key-down/key-up sequence.
     *
     * <p>Returns only after the host has been left in an all-keys-up state (as far as the
     * stack reports it). Blocking, and therefore must not be called on the main thread.
     */
    public Result transmit(byte[] rawScancodes)
    {
        int partial = HidKeyboardReportSequence.trailingPartialBytes(rawScancodes);
        if (partial != 0) {
            sink.log("scancode stream is not a whole number of reports, ignoring " +
                     partial + " trailing byte(s)");
        }

        List<byte[]> reports = HidKeyboardReportSequence.expand(rawScancodes);
        sink.log("transmitting " + reports.size() + " HID reports at " +
                 INTER_REPORT_DELAY_MS + "ms pacing");

        int sent = 0;
        int dropped = 0;
        int retries = 0;
        boolean aborted = false;

        try {
            for (int i = 0; i < reports.size(); i++) {
                if (i > 0) {
                    sink.sleep(INTER_REPORT_DELAY_MS);
                }

                byte[] report = reports.get(i);
                boolean keyUp = HidKeyboardReportSequence.isAllKeysUp(report);
                int maxAttempts = keyUp ? MAX_KEY_UP_ATTEMPTS : MAX_KEY_DOWN_ATTEMPTS;

                int attempts = send(report, maxAttempts);
                retries += attempts - 1;

                if (attempts > 0) {
                    sent++;
                } else {
                    dropped++;
                    // A refused key-down would be typed as a lost character; a refused key-up
                    // is what leaves a key stuck. Either way the stack is not keeping up, so
                    // stop feeding it and go straight to the release below.
                    sink.log("report " + (i + 1) + "/" + reports.size() +
                             " refused after " + maxAttempts + " attempts, aborting sequence");
                    aborted = true;
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            aborted = true;
            sink.log("transmission interrupted");
        }

        boolean releasedCleanly = releaseAllKeys(aborted);

        Result result = new Result(sent, dropped, retries, releasedCleanly, aborted);
        sink.log("transmission finished: " + result);
        return result;
    }

    /**
     * Unconditionally drive the host back to an all-keys-up state. Sent twice: the first
     * report ends the sequence, the second is a defensive repeat in case the first was lost
     * on the air after the stack had already accepted it.
     */
    private boolean releaseAllKeys(boolean aborted)
    {
        boolean released = send(HidKeyboardReportSequence.allKeysUp(), MAX_KEY_UP_ATTEMPTS) > 0;
        if (!released) {
            sink.log("final all-keys-up report was refused by the stack");
        }

        try {
            sink.sleep(DEFENSIVE_RELEASE_DELAY_MS);
            boolean defensive = send(HidKeyboardReportSequence.allKeysUp(),
                                     MAX_KEY_UP_ATTEMPTS) > 0;
            released |= defensive;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sink.log("interrupted before the defensive all-keys-up report");
        }

        if (aborted) {
            sink.log("sequence was aborted; keys released: " + released);
        }
        return released;
    }

    /**
     * @return the number of attempts it took (1-based) or 0 if every attempt was refused.
     */
    private int send(byte[] report, int maxAttempts)
    {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (sink.sendReport(report)) {
                return attempt;
            }
            if (attempt < maxAttempts) {
                try {
                    sink.sleep(CONGESTION_BACKOFF_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return 0;
                }
            }
        }
        return 0;
    }
}
