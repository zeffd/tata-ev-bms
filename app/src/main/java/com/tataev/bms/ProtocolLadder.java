package com.tataev.bms;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The order in which the app looks for a battery controller when the default
 * (11-bit CAN at 500 kbaud, Tata's 0x7xx block) hears nothing battery-shaped.
 *
 * Every rung is an ISO 15765-4 flavour the ELM327 selects with one command. The
 * 29-bit rungs try the addresses Tata's own catalogs use before any broadcast,
 * because a sweep of 29-bit space is not feasible over Bluetooth. Pure Java:
 * the order is the whole design, so it is tested.
 *
 * ATSP vs ATTP: ATSP writes the choice to the adapter's EEPROM, so it OUTLIVES
 * the app - a dongle left on 250 kbaud by a failed probe then greets every other
 * tool the owner plugs in with a bus that does not answer. Rung 1 keeps ATSP6
 * because that IS the adapter's sensible default and the byte-for-byte path the
 * Nexon has always been read on; every rung the ladder merely PROBES with uses
 * ATTP, "try protocol", which lasts only until the next reset. The app sends its
 * protocol on every initAdapter anyway, so nothing depends on it persisting.
 */
final class ProtocolLadder {

    private ProtocolLadder() { }

    static final class Rung {
        final String atsp;
        final String name;
        /** Hex digits in a CAN id as the adapter prints it: 3 or 8. */
        final int idLen;
        final List<String> candidates;
        final List<String> functional;

        Rung(String atsp, String name, int idLen, List<String> candidates, List<String> functional) {
            this.atsp = atsp;
            this.name = name;
            this.idLen = idLen;
            this.candidates = candidates;
            this.functional = functional;
        }
    }

    private static final List<String> FUNCTIONAL_11 = Collections.singletonList("7DF");
    private static final List<String> FUNCTIONAL_29 =
            Collections.unmodifiableList(Arrays.asList("18DB33F1", "1BDB33F1"));

    private static final List<Rung> RUNGS = Collections.unmodifiableList(Arrays.asList(
            new Rung("ATSP6", "CAN 11-bit 500k", 3, BmsFields.BMS_CANDIDATES, FUNCTIONAL_11),
            new Rung("ATTP7", "CAN 29-bit 500k", 8, BmsFields.BMS_CANDIDATES_29, FUNCTIONAL_29),
            new Rung("ATTP8", "CAN 11-bit 250k", 3, BmsFields.BMS_CANDIDATES_250K, FUNCTIONAL_11),
            new Rung("ATTP9", "CAN 29-bit 250k", 8, BmsFields.BMS_CANDIDATES_29, FUNCTIONAL_29)
    ));

    static List<Rung> rungs() {
        return RUNGS;
    }

    /**
     * The rung for a saved protocol command; the first rung for anything
     * unknown.
     *
     * A saved "ATSP7".."ATSP9" is accepted as the same rung as "ATTP7".."ATTP9":
     * a phone that stored a rung while those probes still persisted must reopen
     * its 29-bit or 250 kbaud address on the SAME wire, not drop silently to
     * 11-bit 500k and fail twice before the ladder recovers. The rung's own
     * command is what gets sent, so the returned form never writes the EEPROM.
     */
    static Rung forProtocol(String atsp) {
        String s = (atsp == null ? "" : atsp.trim()).toUpperCase(java.util.Locale.ROOT);
        if (s.length() == 5 && s.startsWith("ATSP") && s.charAt(4) >= '7' && s.charAt(4) <= '9') {
            s = "ATTP" + s.charAt(4);
        }
        for (Rung r : RUNGS) if (r.atsp.equals(s)) return r;
        return RUNGS.get(0);
    }

    /**
     * Which protocol to open a saved address on. The saved protocol wins; with
     * none saved the shape of the id decides, so a 29-bit address found before
     * protocols were persisted still opens correctly.
     */
    static String atspForId(String requestId, String saved) {
        if (saved != null && !saved.trim().isEmpty()) return forProtocol(saved).atsp;
        return requestId != null && requestId.length() == 8 ? "ATTP7" : "ATSP6";
    }
}
