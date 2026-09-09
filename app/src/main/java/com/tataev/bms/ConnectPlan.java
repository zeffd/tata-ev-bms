package com.tataev.bms;

/**
 * The one decision the connect path has to get right, kept pure so the
 * self-test can table it - the {@link PollPlan} of connecting.
 *
 * An empty reply to the controller's name read has three causes, and only the
 * first of them is the adapter's fault: the RFCOMM link is dead; a clone's
 * factory receive window swallowed the reply from 78D before it was ever
 * printed; or a slow clone prints NO DATA only after our own read window has
 * closed. Deciding from the reply alone therefore told a Nexon owner whose car
 * was merely asleep to "check it is plugged in, powered and paired", and the
 * card that asks whether the car is switched on was never raised at all.
 *
 * The banner separates the cases: {@link ElmClient#initAdapter} has just read
 * the chip's own ATZ greeting, so an adapter that introduced itself a moment ago
 * is alive, and its silence belongs to the car.
 */
final class ConnectPlan {

    private ConnectPlan() { }

    /** What one identification read of the battery controller came back as. */
    enum Probe {
        /** The adapter itself printed nothing: unplugged, unpowered, or the link is dead. */
        ADAPTER_SILENT,
        /** The adapter answered (NO DATA / CAN ERROR / nothing from 78D) but the controller did not. */
        NO_REPLY,
        /** A UDS frame came back from 78D - a name, or a refusal; either proves the controller. */
        ANSWERED
    }

    /**
     * @param adapterAlive did the adapter give up a reset banner on this link?
     * @param reply        the read's reply text, echo and prompt already stripped
     * @param isUdsFrame   did that text decode as a UDS reply on the expected id?
     */
    static Probe classify(boolean adapterAlive, String reply, boolean isUdsFrame) {
        if (reply == null || reply.trim().isEmpty()) {
            return adapterAlive ? Probe.NO_REPLY : Probe.ADAPTER_SILENT;
        }
        return isUdsFrame ? Probe.ANSWERED : Probe.NO_REPLY;
    }
}
