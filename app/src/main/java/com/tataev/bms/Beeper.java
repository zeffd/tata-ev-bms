package com.tataev.bms;

/**
 * Makes the alert noise.
 *
 * A one-method seam, so {@link Alerter} - the logic that decides a cell group is
 * failing - is plain Java with no Android imports and can be exercised by the
 * self-test. It is the only safety-relevant code in the app, and it was the only
 * logic the self-test could not reach.
 */
interface Beeper {
    void beep();

    void release();
}
