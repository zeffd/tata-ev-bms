package com.tataev.bms;

import java.io.IOException;

/**
 * TEST DOUBLE - not shipped.
 *
 * The real ElmClient needs Android Bluetooth classes. The security-critical
 * allowlist was extracted to CommandGuard precisely so it CAN be tested for
 * real. This double answers silence (null) from a healthy "socket" by default,
 * and can be told to fail like a dead RFCOMM link so DidScanner's link-loss
 * detection is testable: silence and death must be distinguishable.
 */
final class ElmClient {

    /** Test control: after this many successful calls, every call throws. */
    int dieAfter = Integer.MAX_VALUE;
    private int calls;

    private void tick() throws IOException {
        if (++calls > dieAfter) throw new IOException("link dead");
    }

    String request(String req, long timeoutMs) throws IOException {
        tick();
        return null;
    }

    UdsCodec.Response readDid(String did) throws IOException {
        tick();
        return null;
    }

    String responseId() {
        return "78D";
    }
}
