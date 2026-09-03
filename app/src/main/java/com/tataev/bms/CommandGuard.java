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
            "ATSP6",        // ISO 15765-4, CAN 11-bit, 500 kbaud
            "ATAT1",        // adaptive timing
            "ATCRA",        // bare = CLEAR the receive filter - hear every ECU
                            // during broadcast discovery (adapter-local)
            "ATFCSD300000", // flow-control data
            "ATFCSM1",      // flow-control mode
    };

    /** AT commands that take a 3-hex-digit CAN id. */
    private static final String[] ALLOWED_AT_WITH_ID = {
            "ATSH",         // set request header
            "ATCRA",        // receive filter
            "ATFCSH",       // flow-control header
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
            if (c.length() == prefix.length() + 3 && c.startsWith(prefix)
                    && isHex(c.substring(prefix.length()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * ASCII hex only.
     *
     * Character.digit() accepts every Unicode digit, so a fullwidth "７" reads
     * as 7 and would pass - then go out over the wire as multi-byte UTF-8 that the
     * ELM327 cannot parse. A guard that validates something other than the bytes
     * actually transmitted is not a guard.
     */
    /**
     * A request id an owner may type: exactly three hex digits, at most 7F7.
     * The ECU replies on request + 8, and 7F8 + 8 = 800 no longer fits the
     * 11-bit id the adapter filters on - the reply would never be seen.
     */
    static boolean isRequestId(String id) {
        if (id == null || id.length() != 3 || !isHex(id)) return false;
        return Integer.parseInt(id, 16) <= 0x7F7;
    }

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
