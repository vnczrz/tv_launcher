package tv.voxclean.launcher;

import java.util.Random;

/**
 * The wake intro's beats: uneven keystrokes, a breath between words, a longer stop before punctuation (the owner's
 * mockup). Times are milliseconds from the start of the intro.
 */
final class Typing {
    /** The cursor blinks before the first key. */
    static final int LEAD_MS = 520;
    static final int KEY_MIN_MS = 55, KEY_SPREAD_MS = 75, HESITATION_MS = 90;
    static final double HESITATION_CHANCE = 0.12;
    static final int WORD_MIN_MS = 120, WORD_SPREAD_MS = 60;
    static final int PUNCT_MIN_MS = 220, PUNCT_SPREAD_MS = 80;
    /** After the last key: the cursor blinks, then goes; the line sits alone for a beat; then Home fades in. */
    static final int BLINK_AFTER_MS = 1300, BEAT_MS = 400, FADE_MS = 600;

    private Typing() {
    }

    /** @return when each character appears: times[i] shows the first i + 1 characters */
    static int[] schedule(String text, Random random) {
        int[] times = new int[text.length()];
        double t = LEAD_MS;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            t += KEY_MIN_MS + random.nextDouble() * KEY_SPREAD_MS;
            if (random.nextDouble() < HESITATION_CHANCE) t += HESITATION_MS;
            if (c == ' ') t += WORD_MIN_MS + random.nextDouble() * WORD_SPREAD_MS;
            if (i + 1 < text.length() && ".?,!".indexOf(text.charAt(i + 1)) >= 0) {
                t += PUNCT_MIN_MS + random.nextDouble() * PUNCT_SPREAD_MS;
            }
            times[i] = (int) Math.round(t);
        }
        return times;
    }

    /** When the last key lands (the lead alone for an empty line). */
    static int typed(int[] times) {
        return times.length == 0 ? LEAD_MS : times[times.length - 1];
    }
}
