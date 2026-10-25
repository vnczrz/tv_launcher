package tv.voxclean.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.time.ZoneId;
import java.util.function.Supplier;

/**
 * The screensaver's canvas: ColdHarbor's motion (ColdHarborView.m) in 1920x1080 design pixels. Letters enter from
 * off-screen right one by one, settle centred, hold together, then accelerate off to the left; then the next line.
 */
final class SaverView extends View {
    private static final double STAGGER_S = 0.15, HOLD_S = 1.0, ENTRY_PX_S = 300, EXIT_SPEED = 1.5;
    /** About 30 fps: the spec's 20-45 fps band, light on a 2 GB TV. */
    private static final long FRAME_MS = 33;
    private static final int WAIT = 0, IN = 1, HOLD = 2, OUT = 3;

    private final float scale;
    private final float entrySpeed, trackingEm;
    private final Paint letterPaint = new Paint(Paint.ANTI_ALIAS_FLAG), clockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Supplier<String> lines;

    private String text = "";
    private float[] x = new float[0], target = new float[0];
    private float[] alpha = new float[0];
    private int[] state = new int[0];
    private double[] since = new double[0];
    private double lastFrame;
    private String clockText;
    private long clockMinute = -1;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            step();
            invalidate();
            postOnAnimationDelayed(this, FRAME_MS);
        }
    };

    SaverView(Context context, Typeface light, VoxConfig config) {
        super(context);
        scale = context.getResources().getDisplayMetrics().widthPixels / 1920f;
        entrySpeed = config.saverEntrySpeed;
        trackingEm = config.saverTrackingEm;
        setBackgroundColor(context.getColor(R.color.saver_page));
        int ink = context.getColor(R.color.saver_ink);
        letterPaint.setTypeface(light);
        letterPaint.setTextSize(px(64));
        letterPaint.setColor(ink);
        clockPaint.setTypeface(light);
        clockPaint.setTextSize(px(26));
        clockPaint.setColor(ink);
        clockPaint.setTextAlign(Paint.Align.RIGHT);
    }

    private float px(float designPx) {
        return designPx * scale;
    }

    private static double now() {
        return SystemClock.uptimeMillis() / 1000.0;
    }

    /** Starts the loop with this source of lines, each asked for when the previous one has left. */
    void start(Supplier<String> lines) {
        this.lines = lines;
        removeCallbacks(frame);
        setUp(lines.get());
        lastFrame = now();
        postOnAnimation(frame);
    }

    void stop() {
        removeCallbacks(frame);
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    private void setUp(String line) {
        text = line;
        int n = line.length();
        x = new float[n];
        target = new float[n];
        alpha = new float[n];
        state = new int[n];
        since = new double[n];
        float advance = px(64) * trackingEm;
        float total = n == 0 ? 0 : (n - 1) * advance + letterPaint.measureText(line, n - 1, n);
        float right = getWidth() > 0 ? getWidth() : px(1920);
        float startX = (right - total) / 2;
        double t = now();
        for (int i = 0; i < n; i++) {
            x[i] = right + px(100) + i * px(30);
            target[i] = startX + i * advance;
            state[i] = WAIT;
            since[i] = t + i * STAGGER_S;
        }
    }

    private void step() {
        double t = now();
        double dt = Math.min(0.1, t - lastFrame);
        lastFrame = t;
        boolean allGone = true;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            double inState = t - since[i];
            switch (state[i]) {
                case WAIT:
                    if (inState >= 0) {
                        state[i] = IN;
                        since[i] = t;
                    }
                    allGone = false;
                    break;
                case IN:
                    alpha[i] = (float) Math.min(1, inState * 2);
                    float d = target[i] - x[i];
                    float stepPx = (float) (px((float) ENTRY_PX_S) * dt * entrySpeed);
                    x[i] += Math.signum(d) * Math.min(Math.abs(d), stepPx);
                    if (Math.abs(x[i] - target[i]) < 1) {
                        x[i] = target[i];
                        if (i == n - 1) {
                            // The last letter has landed: every letter holds from now, together.
                            for (int j = 0; j < n; j++) {
                                state[j] = HOLD;
                                since[j] = t;
                                alpha[j] = 1;
                            }
                        }
                    }
                    allGone = false;
                    break;
                case HOLD:
                    if (inState >= HOLD_S) {
                        state[i] = OUT;
                        since[i] = t;
                    }
                    allGone = false;
                    break;
                default:
                    double p = inState * 1.5 * EXIT_SPEED;
                    x[i] = target[i] - (float) (p * p * px(500));
                    if (x[i] < -px(50)) alpha[i] = 0;
                    else allGone = false;
            }
        }
        if (allGone && lines != null) setUp(lines.get());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float baseline = getHeight() / 2f - (letterPaint.ascent() + letterPaint.descent()) / 2f;
        for (int i = 0; i < text.length(); i++) {
            if (alpha[i] <= 0) continue;
            letterPaint.setAlpha(Math.round(alpha[i] * 255));
            canvas.drawText(text, i, i + 1, x[i], baseline, letterPaint);
        }
        long millis = System.currentTimeMillis();
        if (!ClockText.valid(millis)) return;
        long minute = millis / 60_000;
        if (minute != clockMinute) {
            clockMinute = minute;
            clockText = ClockText.format(millis, ZoneId.systemDefault());
        }
        canvas.drawText(clockText, getWidth() - px(96), getHeight() - px(80), clockPaint);
    }
}
