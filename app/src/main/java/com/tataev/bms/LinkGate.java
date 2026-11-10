package com.tataev.bms;

import java.util.Locale;

/**
 * What may be written to the adapter next, decided from what it has said so
 * far. {@link ElmClient#raw} does the I/O and asks this before and after every
 * write; the decisions live here, pure, so the self-test can table them - the
 * {@link ConnectPlan} of every exchange.
 *
 * Three gates, each closing a way a request could reach the wrong module or
 * arrive as a different command:
 * <ul>
 * <li><b>Header.</b> No UDS request until the adapter has answered OK to
 *     ATSH785 since its last reset. A reset ELM327 is back on 7DF, the
 *     functional broadcast, which every module on the car answers - and
 *     airbag, ESP and parking-brake controllers are exactly the kind that
 *     light a lamp when pushed into a diagnostic session.</li>
 * <li><b>Reset.</b> An ELM327 that resets itself (brown-out, Bluetooth module
 *     reboot) prints its banner. Seen in stray bytes, or in the reply to
 *     anything but ATZ, it means the header is gone and the link must be
 *     rebuilt through the full setup.</li>
 * <li><b>Prompt.</b> '&gt;' is the adapter saying it is idle. A character
 *     written while it is busy interrupts it and is dropped, so the rest of
 *     the line lands on an idle adapter as a command the guard never saw:
 *     "22F197" becomes "2F197".</li>
 * </ul>
 */
final class LinkGate {

    enum Verdict {
        /** Write the command, or use the reply. */
        PROCEED,
        /** The adapter still owes a prompt: write a lone space, read until '>', ask again. */
        RESYNC,
        /** The adapter reset itself and is back on the broadcast header: rebuild the link. */
        RESET,
        /** A request for the car before the adapter confirmed the controller's address. */
        UNADDRESSED,
        /** No prompt came back from the resync: the adapter is still busy, or gone. */
        BUSY
    }

    private boolean headerVerified;
    private boolean promptOwed;
    /**
     * The end of everything read so far. A read's deadline, or a drain, can
     * land in the middle of a banner: "\r\rEL" in one text, "M327 v1.5\r\r>" in
     * the next, and neither half alone names a reset.
     */
    private String tail = "";
    private static final int TAIL_KEEP = 16;

    /** A fresh link: nothing confirmed, nothing owed. */
    void reset() {
        headerVerified = false;
        promptOwed = false;
        tail = "";
    }

    /**
     * A banner in this text, or straddling the end of the last one.
     *
     * Only a CONFIRMED header can be lost to a reset: before ATSH785 is
     * answered OK no UDS can go out (UNADDRESSED), so a banner there is no
     * news - and some adapters greet on connect, which put ATZ's own banner in
     * the next drain and failed every connect as a "reset".
     */
    private boolean resetSeen(String text) {
        String joined = tail + (text == null ? "" : text);
        tail = joined.length() > TAIL_KEEP ? joined.substring(joined.length() - TAIL_KEEP) : joined;
        return headerVerified && isResetBanner(joined);
    }

    boolean headerVerified() {
        return headerVerified;
    }

    boolean promptOwed() {
        return promptOwed;
    }

    /**
     * Before a write.
     *
     * @param drained whatever arrived since the last exchange ended: a late
     *                reply and its prompt, or a reset banner
     */
    Verdict beforeWrite(String cmd, String drained) {
        // A banner beats a prompt: a reset adapter prints both, and its prompt
        // only says it is idle on the wrong header. ATZ is about to reset it
        // anyway, so a banner before ATZ is no news.
        if (resetSeen(drained) && !isAtz(cmd)) {
            headerVerified = false;
            return Verdict.RESET;
        }
        if (hasPrompt(drained)) promptOwed = false;
        if (!isAdapterCommand(cmd) && !headerVerified) return Verdict.UNADDRESSED;
        return promptOwed ? Verdict.RESYNC : Verdict.PROCEED;
    }

    /** After the resync space: what came back within the window. */
    Verdict afterResync(String text) {
        if (resetSeen(text)) {
            headerVerified = false;
            return Verdict.RESET;
        }
        if (!hasPrompt(text)) return Verdict.BUSY;   // still owed
        promptOwed = false;
        return Verdict.PROCEED;
    }

    /** After a command was written and its reply read, prompt included if it came. */
    Verdict afterReply(String cmd, String text) {
        promptOwed = !hasPrompt(text);
        String c = normalise(cmd);
        boolean reset = resetSeen(text);
        if (c.equals("ATZ")) {
            headerVerified = false;     // the reset is the point; the banner is expected
            tail = "";
            return Verdict.PROCEED;
        }
        if (reset) {
            headerVerified = false;
            return Verdict.RESET;
        }
        // The only way the header becomes verified: this exact exchange, answered OK.
        // From here on any banner is a new one, so the setup chatter is forgotten.
        if (c.equals("ATSH" + BmsFields.BMS_REQUEST)) {
            headerVerified = hasOkLine(text);
            if (headerVerified) tail = "";
        }
        return Verdict.PROCEED;
    }

    /**
     * The ELM327's reset greeting ("ELM327 v1.5"), or its low-voltage reset
     * notice. Neither can appear in a UDS reply, which the adapter prints as
     * hex, nor in any of its status words (NO DATA, CAN ERROR, STOPPED, ?).
     */
    static boolean isResetBanner(String text) {
        if (text == null) return false;
        String up = text.toUpperCase(Locale.ROOT);
        return up.contains("ELM327") || up.contains("LV RESET");
    }

    private static boolean hasPrompt(String text) {
        return text != null && text.indexOf('>') >= 0;
    }

    private static boolean hasOkLine(String text) {
        if (text == null) return false;
        for (String line : text.replace('>', '\n').replace('\r', '\n').split("\n")) {
            if (line.trim().equalsIgnoreCase("OK")) return true;
        }
        return false;
    }

    private static boolean isAdapterCommand(String cmd) {
        return normalise(cmd).startsWith("AT");
    }

    private static boolean isAtz(String cmd) {
        return normalise(cmd).equals("ATZ");
    }

    /** The form {@link CommandGuard} checks: spaces out, upper case. */
    private static String normalise(String cmd) {
        return cmd == null ? "" : cmd.replace(" ", "").toUpperCase(Locale.ROOT);
    }
}
