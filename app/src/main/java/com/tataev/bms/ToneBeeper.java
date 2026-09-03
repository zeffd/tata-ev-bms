package com.tataev.bms;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * The real beeper: a tone on the ALARM stream, and a vibration.
 *
 * Deliberately NOT STREAM_NOTIFICATION: that stream is silenced by ringer-silent
 * and by Do Not Disturb, which is a normal way to drive. The whole reason this
 * alert exists is that you cannot watch a screen at the wheel, so an alert that a
 * common phone setting mutes is worse than no alert - the driver believes they
 * are being watched over when they are not. The vibration is for the phone in a
 * pocket or a cradle with the volume down.
 */
final class ToneBeeper implements Beeper {

    private static final long[] PATTERN = {0, 300, 150, 300};

    private final Context ctx;
    private ToneGenerator tone;
    private Vibrator vibrator;
    private boolean released;

    /** The context is only used lazily: a Service is not attached at field-init time. */
    ToneBeeper(Context ctx) {
        this.ctx = ctx;
    }

    @Override
    public synchronized void beep() {
        if (released) return;
        try {
            if (tone == null) {
                tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            }
            tone.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 900);
        } catch (RuntimeException ignored) {
            // a device that refuses the tone generator must not take the app down
        }
        try {
            if (vibrator == null) {
                vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(PATTERN, -1));
            } else {
                vibrator.vibrate(PATTERN, -1);
            }
        } catch (RuntimeException ignored) {
            // no vibrator, or no permission on an odd build: the tone already played
        }
    }

    @Override
    public synchronized void release() {
        released = true;
        if (tone != null) {
            try {
                tone.release();
            } catch (RuntimeException ignored) {
            }
            tone = null;
        }
    }
}
