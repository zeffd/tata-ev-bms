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

    /** What a cycle that decoded nothing usable means. */
    enum Unusable {
        /** Nothing came back: the empty-poll rule, two in a row reconnect. */
        EMPTY,
        /** The controller answered, but nothing it said decoded: this model needs mapping. */
        UNMAPPED
    }

    /**
     * Only data reaches the reading, never a refusal. So a BMS that answers every
     * known DID with NRC 0x31, as an unmapped model does, looked exactly like
     * silence: the empty-poll rule reconnected, the probe counted the refused
     * name read as an answer, and the loop went round forever without ever
     * showing the mapping card. A refusal is the controller answering.
     *
     * Only a FULL cycle may say so, for the reason {@link #judgeFast} gives. A
     * fast cycle gets here only through its EMPTY, and stays with that rule.
     *
     * @param refusals final refusals this cycle; busy and pending are "not now",
     *                 not "no", and are not counted
     */
    static Unusable judgeUnusable(boolean fullCycle, boolean rawEmpty, int refusals) {
        if (!rawEmpty) return Unusable.UNMAPPED;
        return fullCycle && refusals > 0 ? Unusable.UNMAPPED : Unusable.EMPTY;
    }

    /**
     * The refusals {@link #judgeUnusable} counts: NRC 0x31, "this DID is not
     * served here", is the only one that says the map is wrong. A waking
     * controller answering 0x22 "not now" reads the same DIDs a moment later, and
     * must not be told it needs mapping. (A DID gated to the extended session
     * also answers 0x31 while that session is off; the UNMAPPED message points
     * at the session switch for that case.)
     */
    static boolean isMappingRefusal(int nrc) {
        return nrc == 0x31;
    }

    /**
     * Links in a row that came up but never produced a usable reading.
     *
     * The connect-failure count cannot see these: the link DID come up, so it
     * was reset on every reconnect, and a controller that answers the probe but
     * never a usable poll reconnected for the whole wake-lock lease. Only a
     * usable reading earns the budget back; a link coming up proves nothing.
     *
     * Three: the no-reply rule gives a car that is not awake yet one more
     * connect, and a controller that has at least answered gets one beyond that.
     */
    static final class FruitlessLinks {
        static final int BUDGET = 3;
        private int count;

        void linkUp() {
            count++;
        }

        void usableReading() {
            count = 0;
        }

        boolean spent() {
            return count >= BUDGET;
        }

        /**
         * The link that just ended never read anything: back off before the next.
         * A link that was reading - a Bluetooth drop mid-drive - reconnects at
         * once, so the log's gap is the reconnect and not a pause on top of it.
         */
        boolean lastLinkFruitless() {
            return count > 0;
        }
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

    /**
     * The cycle floor: the fastest a new cycle may start after the last one did.
     * 250 ms is the adapter's own pace and what every earlier version polled at;
     * 500 ms halves the traffic on the bus for a pack that changes over
     * seconds; 1 s halves it again. A cycle that takes longer waits nothing.
     */
    static final int DEFAULT_POLL_MS = 500;
    static final int[] POLL_CHOICES_MS = {250, 500, 1000};

    /** The stored floor if it is one Settings offers, else the default. */
    static int pollFloorMs(int stored) {
        for (int ms : POLL_CHOICES_MS) {
            if (stored == ms) return ms;
        }
        return DEFAULT_POLL_MS;
    }
}
