package tv.voxclean.launcher;

import android.animation.ObjectAnimator;
import android.view.View;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** The one focus motion: grow and darken. */
final class Motion {
    /**
     * voxel-icon's --spring-soft: no overshoot, fast start, long settle, about 0.3 s. A critically damped spring
     * matches its curve with omega ~ 25 rad/s, so stiffness = omega^2.
     */
    static final float SOFT_STIFFNESS = 630f;
    static final long COLOR_MS = 250, FADE_MS = 300;

    private Motion() {
    }

    /** Springs the view's scale to target; animate false jumps there (a rebind, not a focus move). */
    static void scale(View v, float target, boolean animate) {
        SpringAnimation x = spring(v, DynamicAnimation.SCALE_X, R.id.spring_x);
        SpringAnimation y = spring(v, DynamicAnimation.SCALE_Y, R.id.spring_y);
        if (!animate) {
            x.cancel();
            y.cancel();
            v.setScaleX(target);
            v.setScaleY(target);
            return;
        }
        x.animateToFinalPosition(target);
        y.animateToFinalPosition(target);
    }

    private static SpringAnimation spring(View v, DynamicAnimation.ViewProperty property, int tag) {
        SpringAnimation s = (SpringAnimation) v.getTag(tag);
        if (s == null) {
            s = new SpringAnimation(v, property, 1f);
            s.getSpring().setStiffness(SOFT_STIFFNESS).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY);
            v.setTag(tag, s);
        }
        return s;
    }

    static void textColor(TextView t, int color, boolean animate) {
        ObjectAnimator running = (ObjectAnimator) t.getTag(R.id.color_anim);
        if (running != null) running.cancel();
        if (!animate) {
            t.setTextColor(color);
            return;
        }
        ObjectAnimator a = ObjectAnimator.ofArgb(t, "textColor", t.getCurrentTextColor(), color).setDuration(COLOR_MS);
        t.setTag(R.id.color_anim, a);
        a.start();
    }

    static void alpha(View v, float target, boolean animate) {
        v.animate().cancel();
        if (animate) v.animate().alpha(target).setDuration(FADE_MS).start();
        else v.setAlpha(target);
    }
}
