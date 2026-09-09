package com.tataev.bms;

/**
 * The read-only contract, in one testable place.
 *
 * The whole "this app cannot write to your car" claim rests on this allowlist, so
 * it lives apart from {@link ElmClient} - which needs Android Bluetooth classes
 * and therefore cannot be exercised on a plain JVM. Pure Java here means the
 * guarantee is covered by the self-test.
 */
final class CommandGuard {

    private CommandGuard() { }

    /** Everything this app is permitted to transmit. */
    private static final String[] ALLOWED_REQUESTS = {
            "1001",    // back to the default session
            "1003",    // extended session (mode switch; writes nothing)
            "3E00",    // TesterPresent keep-alive (no-op)
    };

    /**
     * The exact AT commands this app sends, spaces already stripped.
     *
     * "starts with AT" used to be enough, on the reasoning that AT is
     * adapter-local and never reaches the car. That reasoning is wrong: ATRTR
     * transmits a CAN frame, ATMA/ATMR put the adapter on the bus, ATSW changes
     * the wakeup behaviour, and ATPP..SV writes the adapter's programmable
     * parameters PERMANENTLY. No user input reaches this guard today, so nothing
     * was actually at risk - but "allowlist" was a claim the code did not keep,
     * and an enumerated list is one the self-test can check.
     */
    private static final String[] ALLOWED_AT = {
            "ATZ",          // reset
            "ATE0",         // echo off
            "ATL0",         // linefeeds off
            "ATS0",         // spaces off
            "ATH1",         // headers on - we must see which ECU replied
            "ATCAF1",       // CAN auto-formatting on
            "ATSP6",        // ISO 15765-4, CAN 11-bit, 500 kbaud - the ONLY wire
                            // this app speaks, and the adapter's own default, so
                            // persisting it to EEPROM changes nothing for the
                            // next tool. Every other protocol select is
                            // deliberately ABSENT: the persisting forms would
                            // leave the dongle on another wire for the next
                            // tool, and the probing forms went with the ladder.
            "ATAT1",        // adaptive timing
            "ATCRA",        // bare = CLEAR the receive filter (adapter-local)
            "ATFCSD300000", // flow-control data
            "ATFCSM1",      // flow-control mode
    };

    /** AT commands that take a CAN id: 3 hex digits. */
    private static final String[] ALLOWED_AT_WITH_ID = {
            "ATSH",         // set request header
            "ATCRA",        // receive filter
            "ATFCSH",       // flow-control header
            "ATCF",         // receive filter, the pre-v1.3 form (adapter-local)
            "ATCM",         // receive mask for it (adapter-local)
    };

    /**
     * @return true for an allowed adapter command or an explicitly allowed read.
     *
     * Rejects embedded CR/LF first: the ELM327 treats CR as a command terminator,
     * so "22F190\r2E010203" would otherwise pass a prefix check and then execute
     * a WriteDataByIdentifier as a second line.
     */
    static boolean isAllowed(String cmd) {
        if (cmd == null) return false;
        if (cmd.indexOf('\r') >= 0 || cmd.indexOf('\n') >= 0) return false;
        // WHAT IS CHECKED MUST BE WHAT IS SENT. Everything below inspects a
        // case-folded, space-stripped COPY, while ElmClient.raw() transmits the
        // original over US_ASCII. Any character whose upper case is an ASCII
        // letter breaks that equivalence: LATIN SMALL LETTER LONG S folds to
        // "S", so "atſh785" would be checked as the allowed "ATSH785" and sent
        // as "at?h785". Refusing everything outside printable ASCII first means
        // the checked string and the transmitted bytes can never diverge. Every
        // command this app sends is ASCII, so nothing legitimate is lost.
        for (int i = 0; i < cmd.length(); i++) {
            char ch = cmd.charAt(i);
            if (ch < 0x20 || ch > 0x7E) return false;
        }

        String c = cmd.replace(" ", "").toUpperCase(java.util.Locale.ROOT);
        // An empty command writes a bare CR, which the ELM327 treats as "repeat
        // the previous command" - an effect this guard cannot inspect.
        if (c.isEmpty()) return false;
        if (c.startsWith("AT")) return isAllowedAt(c);

        // ReadDataByIdentifier, the only service that fetches data. The rest of
        // the string is DIDs, which must be hex.
        if (c.startsWith("22")) {
            String dids = c.substring(2);
            // One to three DIDs of four hex digits each: the framing this app
            // sends, and the most a single ISO-TP request frame carries.
            return !dids.isEmpty() && dids.length() % 4 == 0 && dids.length() <= 12
                    && isHex(dids);
        }

        for (String ok : ALLOWED_REQUESTS) {
            if (c.equals(ok)) return true;
        }
        return false;
    }

    private static boolean isAllowedAt(String c) {
        for (String ok : ALLOWED_AT) {
            if (c.equals(ok)) return true;
        }
        for (String prefix : ALLOWED_AT_WITH_ID) {
            if (c.startsWith(prefix) && isCanId(c.substring(prefix.length()))) {
                return true;
            }
        }
        return false;
    }

    /** A CAN id is three hex digits, the 11-bit form. Nothing else. */
    private static boolean isCanId(String id) {
        return id.length() == 3 && isHex(id);
    }

    /**
     * ASCII hex only.
     *
     * Character.digit() accepts every Unicode digit, so a fullwidth "７" reads
     * as 7 and would pass - then go out over the wire as multi-byte UTF-8 that the
     * ELM327 cannot parse. A guard that validates something other than the bytes
     * actually transmitted is not a guard.
     */
    static boolean isHex(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9')
                    || (c >= 'A' && c <= 'F')
                    || (c >= 'a' && c <= 'f');
            if (!ok) return false;
        }
        return true;
    }
}
