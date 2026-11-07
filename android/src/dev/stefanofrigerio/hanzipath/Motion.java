package dev.stefanofrigerio.hanzipath;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * The app's motion, kept small: a screen comes in (rising a few dp as it fades in; home only
 * fades), an answer's rings come in after a tap, and Quiz's two haptics. Nothing moves with
 * Android's "Remove animations" on (animator duration scale 0): things are simply there.
 */
final class Motion {
    static final long ENTER = 200, ANSWER = 180;
    static final float RISE = 10;    // dp

    private Motion() {}

    interface Step { void at(float f); }

    static boolean on(Context c) {
        if (!ValueAnimator.areAnimatorsEnabled()) return false;
        return Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }

    /** f from 0 to 1 over ms, slowing as it lands; always ends at 1, even cancelled. With motion
     *  off, straight to 1 and no animator (null). */
    static ValueAnimator run(Context c, long ms, Step s) {
        if (!on(c)) { s.at(1); return null; }
        s.at(0);
        ValueAnimator a = ValueAnimator.ofFloat(0, 1);
        a.setDuration(ms);
        a.setInterpolator(new DecelerateInterpolator(1.6f));
        a.addUpdateListener(v -> s.at((float) v.getAnimatedValue()));
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator x) { s.at(1); }
        });
        a.start();
        return a;
    }

    /** A screen's entrance: it fades in, rising RISE dp unless it's going back (home). */
    static ValueAnimator enter(View v, boolean rise) {
        float by = rise ? Ink.dp(RISE) : 0;
        return run(v.getContext(), ENTER, f -> {
            v.setAlpha(f);
            v.setTranslationY(by * (1 - f));
        });
    }

    /** A tap's answer, felt: a light tick when right, a firmer double when wrong. */
    static void felt(View v, boolean right) {
        v.performHapticFeedback(right ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.REJECT);
    }
}
