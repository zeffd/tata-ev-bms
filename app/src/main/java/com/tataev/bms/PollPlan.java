package com.tataev.bms;

/**
 * The one decision two-speed polling has to get right, kept pure so the
 * self-test can table it.
 *
 * A FAST cycle asks only for the six pack-map roles and never for SOC, so the
 * only usable role it can carry is the minimum cell voltage. That makes it no
 * judge of whether this MODEL is mapped: a Tata EV whose SOC, pack-volt and
 * current DIDs match the Nexon's but whose cell DIDs do not looked, from a fast
 * cycle, exactly like an unmapped car, and the service stopped itself with
 * "BMS answered, but no known DIDs" a second and a half after connecting - on a
 * car the single-speed poll had kept on the dashboard with SOC, volts and amps.
 *
 * Only a SLOW (full) cycle may judge unmapped-ness. A fast cycle that is not
 * usable either drops the link to full polls (the cell roles have never decoded
 * here, so this model does not serve them), rides out a transient (they have),
 * or, when nothing at all came back, hands the empty-poll judgement its turn.
 */
final class PollPlan {

    private PollPlan() { }

    enum Fast {
        /** The cell roles decoded; carry on. */
        PROCEED,
        /** Nothing usable and the cell roles never decoded on this link: full polls from now on. */
        DROP_TO_SINGLE,
        /** Nothing usable, but the cell roles used to decode here: a transient, skip the sample. */
        SKIP,
        /** Nothing came back at all from a link that used to answer: let the empty-poll rule judge. */
        EMPTY
    }

    static Fast judgeFast(boolean usable, boolean rawEmpty, boolean cellsEverDecoded) {
        if (usable) return Fast.PROCEED;
        if (!cellsEverDecoded) return Fast.DROP_TO_SINGLE;
        return rawEmpty ? Fast.EMPTY : Fast.SKIP;
    }

    /**
     * A batched read got no reply. That used to end the group: asking a mute
     * ECU one DID at a time only waits out three more timeouts. But an ECU that
     * ignores the multi-DID FORM looks exactly the same, and such ECUs exist:
     * single reads answer, three-at-once gets silence. So the group is mute
     * only when the single reads are silent as well.
     */
    static boolean groupMute(boolean batchSilent, int singlesAnswered) {
        return batchSilent && singlesAnswered == 0;
    }

    /** Twice is a pattern: stop batching on this link, as an explicit NRC 0x13 already does. */
    static boolean dropBatching(int silentBatchesWithSingles) {
        return silentBatchesWithSingles >= 2;
    }
}
