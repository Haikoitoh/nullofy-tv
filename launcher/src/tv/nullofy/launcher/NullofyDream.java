package tv.nullofy.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.service.dreams.DreamService;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * Nullofy's screensaver: the clock (and optionally your own text) drifts across a black screen in a straight
 * line and bounces off the edges like the DVD logo on old TVs, starting in a random direction. Settings
 * live in Nullofy Settings (keys ssmode, sstext, ssspeed, sscolor, ssdate, ampm in the "nullofy" preferences).
 */
public class NullofyDream extends DreamService {
    /** The screensaver now on screen, so HomeService can end it when Home is pressed. */
    private static volatile java.lang.ref.WeakReference<NullofyDream> running = new java.lang.ref.WeakReference<>(null);

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        running = new java.lang.ref.WeakReference<>(this);
    }

    @Override public void onDreamingStopped() {
        if (running.get() == this) running = new java.lang.ref.WeakReference<>(null);
        super.onDreamingStopped();
    }

    /**
     * Ends Nullofy's screensaver, if it is on; true when it was. Home is taken by HomeService, so it never
     * reaches the screensaver the way Back does.
     */
    static boolean on() { return running.get() != null; }

    static boolean end() {
        NullofyDream d = running.get();
        if (d == null) return false;
        running = new java.lang.ref.WeakReference<>(null);
        if (android.os.Build.VERSION.SDK_INT >= 29) d.wakeUp(); else d.finish();
        return true;
    }

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Watchdog.install(this);
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(art(this));   // pictures at full brightness; the clock dimmed
        show(this, this::setContentView);
    }

    interface Host { void show(View v); }

    /** Screensaver mode Art, and the web allowed (Off Grid off). */
    static boolean art(Context c) {
        SharedPreferences p = c.getSharedPreferences("nullofy", Context.MODE_PRIVATE);
        return "art".equals(p.getString("ssmode", "clock")) && !"on".equals(p.getString("offgrid", "off"));
    }

    /**
     * The screensaver's content, shared with DreamPreview: Art (the same pictures, sources and burn-in care as
     * Art mode) or the bouncing clock. Art falls back to the clock when no picture can be fetched.
     */
    static void show(Context c, Host h) {
        if (art(c)) {
            NullofyView v = new NullofyView(c);
            h.show(v);
            v.dream(() -> h.show(new Bounce(c)));
        } else {
            h.show(new Bounce(c));
        }
    }

    static final class Bounce extends View {
        private static final int[] COLORS = { 0xFFF2F5F7, 0xFFC8FF3D, 0xFF3DFFB0, 0xFF5CE1FF, 0xFFA88BFF, 0xFFFF4FD8, 0xFFFF6A2B, 0xFFFFC23D };
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Typeface mono, sans;
        private final SharedPreferences prefs;
        private final Random rnd = new Random();
        private float x, y, vx, vy;
        private int color;
        private long last;

        Bounce(Context c) {
            super(c);
            prefs = c.getSharedPreferences("nullofy", Context.MODE_PRIVATE);
            mono = font(c, "fonts/GeistMono.ttf", Typeface.MONOSPACE);
            sans = font(c, "fonts/Geist.ttf", Typeface.SANS_SERIF);
            setBackgroundColor(0xFF000000);
            double a = rnd.nextDouble() * 2 * Math.PI;   // any direction, but never nearly flat or upright
            while (Math.abs(Math.sin(a)) < .25 || Math.abs(Math.cos(a)) < .25) a = rnd.nextDouble() * 2 * Math.PI;
            vx = (float) Math.cos(a);
            vy = (float) Math.sin(a);
            x = -1;
        }

        private static Typeface font(Context c, String path, Typeface fb) {
            try { return Typeface.createFromAsset(c.getAssets(), path); } catch (Exception e) { return fb; }
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float k = h / 1080f;
            String mode = prefs.getString("ssmode", "clock");
            if (mode.equals("art")) mode = "clock";   // Art could not load: the plain clock
            String text = prefs.getString("sstext", "").trim();
            if (text.length() > Art.TEXT_MAX) text = text.substring(0, Art.TEXT_MAX);
            boolean showClock = !mode.equals("text") || text.isEmpty();
            boolean showText = !text.isEmpty() && !mode.equals("clock");
            boolean multi = !"off".equals(prefs.getString("sscolor", "on"));          // Multi Color: new colour per bounce
            boolean showDate = showClock && "on".equals(prefs.getString("ssdate", "off"));   // Show Date: "Mon  20  Oct", en-spaced
            String v = prefs.getString("clock", "");
            boolean h24 = v.isEmpty() ? android.text.format.DateFormat.is24HourFormat(getContext()) : v.equals("24");
            Date now = new Date();
            String time = new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now);
            String ap = !h24 && "on".equals(prefs.getString("ampm", "off")) ? NullofyView.ampm(now) : "";   // Time Labels
            String date = showDate ? new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now) : "";

            float ts = 150 * k, as = 56 * k, ds = 52 * k, xs = 54 * k, gap = ap.isEmpty() ? 0 : 20 * k;
            tp.setTypeface(mono);
            tp.setTextSize(ts);
            float timeW = tp.measureText(time);
            tp.setTextSize(as);
            float apW = ap.isEmpty() ? 0 : tp.measureText(ap);
            float tw = showClock ? timeW + gap + apW : 0;
            tp.setTypeface(sans);
            tp.setTextSize(ds);
            float dw = showDate ? tp.measureText(date) : 0;
            tp.setTextSize(xs);
            while (showText && tp.measureText(text) > w * .6f && xs > 20 * k) { xs -= 2; tp.setTextSize(xs); }
            float xw = showText ? tp.measureText(text) : 0;
            float bw = Math.max(tw, Math.max(dw, xw));
            float bh = (showClock ? ts * .78f : 0) + (showDate ? ds * 1.5f : 0) + (showText ? xs * 1.5f : 0);

            long t = SystemClock.uptimeMillis();
            float dt = last == 0 ? 0 : Math.min(.1f, (t - last) / 1000f);
            last = t;
            float speed = (prefs.getString("ssspeed", "m").equals("s") ? 60 : prefs.getString("ssspeed", "m").equals("f") ? 200 : 110) * k;
            if (x < 0) { x = rnd.nextInt(Math.max(1, (int) (w - bw))); y = rnd.nextInt(Math.max(1, (int) (h - bh))); }
            x += vx * speed * dt;
            y += vy * speed * dt;
            boolean hit = false;
            if (x < 0) { x = 0; vx = Math.abs(vx); hit = true; }
            if (x + bw > w) { x = w - bw; vx = -Math.abs(vx); hit = true; }
            if (y < 0) { y = 0; vy = Math.abs(vy); hit = true; }
            if (y + bh > h) { y = h - bh; vy = -Math.abs(vy); hit = true; }
            if (hit && multi) color = (color + 1 + rnd.nextInt(COLORS.length - 1)) % COLORS.length;   // a new colour on every bounce

            float cy = y;
            tp.setColor(multi ? COLORS[color] : COLORS[0]);
            if (showClock) {
                float lx = x + (bw - tw) / 2;
                tp.setTypeface(mono);
                tp.setTextAlign(Paint.Align.LEFT);
                tp.setTextSize(ts);
                c.drawText(time, lx, cy + ts * .78f, tp);
                if (!ap.isEmpty()) {
                    tp.setTextSize(as);
                    c.drawText(ap, lx + timeW + gap, cy + ts * .78f, tp);   // AM / PM after the time, on its baseline
                }
                cy += ts * .78f;
            }
            tp.setTextAlign(Paint.Align.CENTER);
            if (showDate) {
                tp.setTypeface(sans);
                tp.setTextSize(ds);
                c.drawText(date, x + bw / 2, cy + ds * 1.3f, tp);
                cy += ds * 1.5f;
            }
            if (showText) {
                tp.setTypeface(sans);
                tp.setTextSize(xs);
                c.drawText(text, x + bw / 2, cy + xs * 1.2f, tp);
            }
            postInvalidateOnAnimation();
        }
    }
}
