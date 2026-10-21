package tv.nullofy.launcher;

import android.animation.ValueAnimator;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * The whole launcher UI, drawn on one canvas in a 1920x1080 design space: the usage-ordered home
 * carousel (Icon + name or Big tiles), the Right-hand panel, carousel menus for Settings, and Art mode.
 */
final class NullofyView extends View {
    private static final int HOME = 0, PANEL = 1, SETTINGS = 2, CHOICE = 3, HIDDEN = 4, ART = 5;
    private static final int W = 1920, H = 1080;

    private static final String[] ART_RATES = { "0.5", "1", "5", "15", "30", "60" };
    /** Slow zoom runs over the last part of each picture's time, growing along Fibonacci: 5, 8, 13, 21, 34, 55 %. */
    private static final float[] ZOOM_SHARE = { .05f, .08f, .13f, .21f, .34f, .55f };
    private static final String[] SS_MODES = { "clock", "both", "text", "art" };
    private static final String[] SS_SPEEDS = { "s", "m", "f" };
    private static final String[] IDLE = { "0", "5", "10", "15", "30" };
    /** Aesthetic: minutes between pictures behind Home. */
    private static final String[] WALL_RATES = { "1", "3", "5", "10" };
    private final String[] WALL_RATE_LABELS;
    /** Nine accents, each far enough from the others to tell apart (CIEDE2000 > 20). */
    private static final String[] ACCENT_NAMES = { "Lime", "Mint", "Cyan", "Blue", "Violet", "Magenta", "Ember", "Amber", "White" };
    private static final int[] ACCENTS = { 0xFFC8FF3D, 0xFF3DFFB0, 0xFF5CE1FF, 0xFF4D7CFF, 0xFFA88BFF, 0xFFFF4FD8, 0xFFFF6A2B, 0xFFFFC23D, 0xFFF2F5F7 };
    /** Day mode: the same hues darkened to about 4:1 or better on the light background. */
    private static final int[] ACCENTS_DAY = { 0xFF4D7300, 0xFF00784F, 0xFF0078A0, 0xFF2A52D9, 0xFF6A45D6, 0xFFC4189C, 0xFFB33C0A, 0xFF8A5800, 0xFF0B0D10 };
    private static final String[] MODES = { "dark", "light", "aesthetic" };
    /** Accent names on screen (ACCENT_NAMES are the saved values and stay English). */
    private static final int[] ACCENT_LABEL_IDS = { R.string.acc_lime, R.string.acc_mint, R.string.acc_cyan, R.string.acc_blue,
            R.string.acc_violet, R.string.acc_magenta, R.string.acc_ember, R.string.acc_amber, R.string.acc_white };
    /** Labels in the TV's language (res/values-xx), built once. */
    private final String[] ART_RATE_LABELS, SS_MODE_LABELS, SS_SPEED_LABELS, IDLE_LABELS, MODE_LABELS;

    private final Apps repo;
    /** What this TV has: Fire OS hides Default Home; no Watch Next provider hides Continue Watching. */
    private final boolean fireTv, watchNext;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Typeface sans, mono;
    /** Geist at 400, 420 … 500: focused text eases from Regular to Medium as it reaches the middle. */
    private final Typeface[] weights = new Typeface[6];
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF rf = new RectF();

    // home carousel
    private List<Apps.App> apps = new ArrayList<>();
    private int screen = HOME, focus, home;
    private float pos, target;
    private ValueAnimator anim;
    private boolean stayInSettings;

    // Right panel: action names, {key, label, value} setting rows, or Watch Next rows
    private final List<Object> items = new ArrayList<>();
    private int panelFocus;

    // carousel menus (Settings, a choice list, Hidden apps)
    private int mfocus, settingsFocus, choiceReturn;
    private float mpos, mtarget;
    private ValueAnimator manim;
    private String choiceKey;
    private String[] choiceVals, choiceLabels;
    private List<Apps.App> allApps = new ArrayList<>();

    // art mode: one picture at a time from the web, cross-faded; keeps the TV awake
    private final Art art;
    private final Object artLock = new Object();
    private Art.Piece artCur, artPrev;
    private float artFade = 1;
    private Thread artThread;
    private boolean offline, forceOffline;
    private long offlineSince, artShownAt, lastKey = SystemClock.uptimeMillis();
    private long pressAt;
    private float clockDx, clockDy;      // the clock wanders a few pixels each minute (burn-in)

    // theme
    private int bg, ink, ink2, ink3, lab, hair, acc, accLn, fill;   // lab: small text, at least 4.5:1; ink3: ticks and lines only
    private BitmapShader dots;
    private String themeKey = "";

    NullofyView(Context c) {
        super(c);
        repo = new Apps(c);
        art = new Art(c);
        fireTv = Apps.fireTv(c);
        watchNext = Apps.hasWatchNext(c);
        ART_RATE_LABELS = new String[] { c.getString(R.string.n_sec, 30), c.getString(R.string.n_min, 1), c.getString(R.string.n_min, 5),
                c.getString(R.string.n_min, 15), c.getString(R.string.n_min, 30), c.getString(R.string.one_hour) };
        SS_MODE_LABELS = new String[] { c.getString(R.string.clock), c.getString(R.string.ss_clock_text), c.getString(R.string.ss_text_only),
                c.getString(R.string.art) };
        SS_SPEED_LABELS = new String[] { c.getString(R.string.slow), c.getString(R.string.speed_medium), c.getString(R.string.fast) };
        IDLE_LABELS = new String[] { c.getString(R.string.off), c.getString(R.string.n_min, 5), c.getString(R.string.n_min, 10),
                c.getString(R.string.n_min, 15), c.getString(R.string.n_min, 30) };
        WALL_RATE_LABELS = new String[WALL_RATES.length];
        for (int i = 0; i < WALL_RATES.length; i++) WALL_RATE_LABELS[i] = c.getString(R.string.n_min, Integer.parseInt(WALL_RATES[i]));
        MODE_LABELS = new String[] { c.getString(R.string.mode_dark), c.getString(R.string.mode_day), c.getString(R.string.mode_aesthetic) };
        prefs = repo.prefs;
        art.safe = on("safe", true);
        sans = font(c, "fonts/Geist.ttf", Typeface.SANS_SERIF);
        mono = font(c, "fonts/GeistMono.ttf", Typeface.MONOSPACE);
        for (int i = 0; i < weights.length; i++) {
            weights[i] = sans;   // Android 7: no variable fonts, Regular everywhere
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                try {
                    weights[i] = new Typeface.Builder(c.getAssets(), "fonts/Geist.ttf")
                            .setFontVariationSettings("'wght' " + (400 + 20 * i)).build();
                } catch (Exception ignored) { }
            }
        }
        setFocusable(true);
        setFocusableInTouchMode(true);
        tick.run();
    }

    private static Typeface font(Context c, String path, Typeface fallback) {
        try { return Typeface.createFromAsset(c.getAssets(), path); } catch (Exception e) { return fallback; }
    }

    // ------------------------------------------------------------------ settings values
    private String pref(String k, String d) { return prefs.getString(k, d); }
    /** A UI string in the TV's language. */
    private String t(int id) { return getContext().getString(id); }
    /** Upper case in the TV's language (Turkish i, German ß …). */
    private static String up(String s) { return s.toUpperCase(Locale.getDefault()); }
    /** A stored number, or the default when the value is missing or not a number. */
    private static int num(String v, int d) { try { return Integer.parseInt(v); } catch (NumberFormatException e) { return d; } }
    private void put(String k, String v) { prefs.edit().putString(k, v).apply(); }
    private boolean big() { return "big".equals(pref("style", "icon")); }
    /** Icon size: 0 Regular (default), 1 Medium, 2 Large. */
    private int iconSize() { String v = pref("size", "s"); return v.equals("l") ? 2 : v.equals("m") ? 1 : 0; }
    /** Font size: Large scales text by 1.25, Extra large by 1.5 (the right panel stops at 1.25 so it still fits). */
    private float fs() { String v = pref("font", "r"); return v.equals("xl") ? 1.5f : v.equals("l") ? 1.25f : 1; }
    private float pfs() { return Math.min(1.25f, fs()); }
    /** Text Breathing: Your Text eases down to 50% brightness and back. Older builds stored 100/75/50. */
    private boolean breathing() { return !"100".equals(pref("artbreath", "50")); }
    private boolean zoomOn() { return "on".equals(pref("artzoom", "on")); }
    private boolean byUse() { return !"az".equals(pref("order", "use")); }
    /** Off Grid: no web requests at all (Art shows Your Text, no posters, Aesthetic uses the bundled pictures). */
    private boolean offGrid() { return "on".equals(pref("offgrid", "off")); }
    /** Layout: the vertical carousel (default) or a horizontal Dock along the bottom. */
    private boolean dock() { return "dock".equals(pref("layout", "list")); }

    /** Until changed in Settings, the clock follows the TV's own 12/24-hour setting. */
    private boolean clock24() {
        String v = pref("clock", "");
        return v.isEmpty() ? android.text.format.DateFormat.is24HourFormat(getContext()) : v.equals("24");
    }

    /** Auto (Aesthetic only) is saved as "Auto"; it picks black or white from the picture, and is White elsewhere. */
    private int accentIndex() {
        String v = pref("accent", "Lime");
        if (v.equals("Auto")) return ACCENT_NAMES.length - 1;
        return Math.max(0, Arrays.asList(ACCENT_NAMES).indexOf(v));
    }

    /** An unknown saved mode falls back to Dark. */
    private String mode() {
        String m = pref("mode", "dark");
        return Arrays.asList(MODES).contains(m) ? m : "dark";
    }
    private boolean aesthetic() { return mode().equals("aesthetic"); }
    /** Aesthetic picture refresh in minutes (1, 3, 5 or 10; 3 until changed). */
    private String wallRate() { String v = pref("wallrate", "3"); return Arrays.asList(WALL_RATES).contains(v) ? v : "3"; }

    /** In Day the White accent is drawn black, so it is called Black there. */
    private String accentName(int i) { return t(i == ACCENT_NAMES.length - 1 && mode().equals("light") ? R.string.acc_black : ACCENT_LABEL_IDS[i]); }
    private String[] accentLabels() {
        String[] l = new String[ACCENT_NAMES.length];
        for (int i = 0; i < l.length; i++) l[i] = t(ACCENT_LABEL_IDS[i]);
        l[l.length - 1] = accentName(l.length - 1);
        return l;
    }
    /** The accent row's value: Auto, or the accent's name. */
    private String accentLabel() { return autoAccent() ? t(R.string.acc_auto) : accentName(accentIndex()); }
    private boolean autoAccent() { return aesthetic() && "Auto".equals(pref("accent", "Lime")); }
    /** Auto over a mostly bright picture: black accent and text on light glass. */
    private boolean lightWall() { return autoAccent() && wall != null && (wallFade < .5f && wallPrev != null ? wallPrevLight : wallLight); }

    private static String label(String[] vals, String[] labels, String v) {
        int i = Arrays.asList(vals).indexOf(v);
        return labels[Math.max(0, i)];
    }

    // ------------------------------------------------------------------ lifecycle
    void reload(boolean recenter) {
        int[] h = new int[1];
        apps = repo.ordered(byUse(), h);
        home = h[0];
        if (stayInSettings) stayInSettings = false;
        else if (recenter && screen != ART) screen = HOME;
        if (recenter || focus >= apps.size()) jump(home);
        invalidate();
    }

    void goHome() {
        leaveArt();
        screen = HOME;
        jump(home);
        invalidate();
    }

    void stop() { ui.removeCallbacksAndMessages(null); }

    private final Random rnd = new Random();

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            clockDx = rnd.nextInt(13) - 6;
            clockDy = rnd.nextInt(9) - 4;
            invalidate();
            ui.postDelayed(this, 60000 - System.currentTimeMillis() % 60000 + 50);
        }
    };

    /** Idle → Art: Home left untouched for the chosen minutes starts Art (Google's screensaver is off). */
    private final Runnable idle = new Runnable() {
        @Override public void run() {
            int min = num(pref("idle", "10"), 10);
            if (min > 0 && screen != ART && hasWindowFocus() && SystemClock.uptimeMillis() - lastKey > min * 60000L) openArt();
            ui.postDelayed(this, 30000);
        }
    };

    /**
     * Home gets the screen back (from an app, the TV's screensaver or a dialog): the idle time starts again here,
     * so Idle → Art never fires the moment you return.
     */
    @Override public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) lastKey = SystemClock.uptimeMillis();
    }

    @Override protected void onDetachedFromWindow() {
        if (dreaming) { leaveArt(); stop(); }
        super.onDetachedFromWindow();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ui.removeCallbacks(idle);   // attached again: never run two idle timers
        ui.postDelayed(idle, 30000);
    }

    // ------------------------------------------------------------------ input
    boolean key(int code, KeyEvent e) {
        lastKey = SystemClock.uptimeMillis();
        boolean up = code == KeyEvent.KEYCODE_DPAD_UP, down = code == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
        boolean left = code == KeyEvent.KEYCODE_DPAD_LEFT, right = code == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (ok) pressAt = lastKey;   // the focus fill flashes briefly on OK
        switch (screen) {
            case HOME:
                if (dock()) {   // Dock: Left/Right move along the row, Up opens the panel
                    if (left || right) { move(left ? -1 : 1); return true; }
                    if (ok) { open(cur()); return true; }
                    if (up && cur() != null) { openPanel(); return true; }
                    if (code == KeyEvent.KEYCODE_MENU) { openSettings(); return true; }
                    return down;
                }
                if (up || down) { move(up ? -1 : 1); return true; }
                if (ok) { open(cur()); return true; }
                if (right && cur() != null) { openPanel(); return true; }
                if (code == KeyEvent.KEYCODE_MENU) { openSettings(); return true; }
                return left;
            case PANEL:
                if (up || down) {
                    panelFocus = Math.max(0, Math.min(items.size() - 1, panelFocus + (up ? -1 : 1)));
                    invalidate();
                    return true;
                }
                if (ok) { panelAction(); return true; }
                if (left) { back(); return true; }
                return right;
            case SETTINGS: case CHOICE: case HIDDEN:
                if (up || down) { mmove(up ? -1 : 1); return true; }
                if (ok) { menuOk(); return true; }
                if (left) { back(); return true; }
                return right;
            case ART:
                if (right) { synchronized (artLock) { artSkip = true; artLock.notifyAll(); } }   // next picture now
                else leaveArt();
                return true;
        }
        return false;
    }

    void back() {
        switch (screen) {
            case ART: leaveArt(); return;
            case PANEL: screen = HOME; break;
            case SETTINGS: reload(false); screen = HOME; break;
            case CHOICE: returnFromChoice(); break;
            case HIDDEN: reload(false); openMenu(SETTINGS, settingsFocus); break;
        }
        invalidate();
    }

    private Apps.App cur() { return apps.isEmpty() ? null : apps.get(wrap(focus, apps.size())); }

    private static int wrap(int i, int n) {
        n = Math.max(1, n);
        return ((i % n) + n) % n;
    }

    private void jump(int i) {
        if (anim != null) anim.cancel();
        focus = wrap(i, apps.size());
        pos = target = focus;
        dropFar();
    }

    private void move(int dir) {
        if (apps.isEmpty()) return;
        focus = wrap(focus + dir, apps.size());
        target += dir;
        if (anim != null) anim.cancel();
        anim = slide(pos, target, v -> pos = v);
        dropFar();
    }

    /** Apps this far from the focus (either way, wrapping) keep their renders; the rest are freed and redrawn when they come back. */
    private static final int KEEP_RENDERS = 8;

    private void dropFar() {
        int n = apps.size();
        if (n <= 2 * KEEP_RENDERS + 1) return;
        for (int i = 0; i < n; i++) {
            int d = Math.abs(i - focus);
            if (Math.min(d, n - d) <= KEEP_RENDERS) continue;
            Apps.App a = apps.get(i);
            if (a.iconBmp != null) { a.iconBmp.recycle(); a.iconBmp = null; }
            if (a.tileBmp != null) { a.tileBmp.recycle(); a.tileBmp = null; }
        }
    }

    private interface Setter { void set(float v); }

    private ValueAnimator slide(float from, float to, Setter s) {
        ValueAnimator a = ValueAnimator.ofFloat(from, to);
        a.setDuration(160);
        a.setInterpolator(new DecelerateInterpolator(1.6f));
        a.addUpdateListener(x -> { s.set((float) x.getAnimatedValue()); invalidate(); });
        a.start();
        return a;
    }

    private void open(Apps.App a) {
        if (a == null) return;
        repo.countLaunch(a.pkg);
        if (Apps.ART.equals(a.pkg)) { openArt(); return; }
        if (Apps.INPUTS.equals(a.pkg)) { openPanel(); return; }   // the list of inputs is the panel
        if (a.internal()) { openSettings(); return; }
        start(repo.launchIntent(a));
    }

    private void start(Intent i) {
        if (i == null) return;
        try { getContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (ActivityNotFoundException | SecurityException ignored) { }
    }

    // ------------------------------------------------------------------ Right panel
    private void openPanel() {
        Apps.App a = cur();
        items.clear();
        if (Apps.ART.equals(a.pkg)) {
            items.add(R.string.start);
            String src = pref("artsrc", "mix");
            items.add(new String[] { "artsrc", t(R.string.source), offGrid() ? t(R.string.off_grid) : label(art.sources, art.labels, src) });
            if (src.equals("paintings")) items.add(new String[] { "artera", t(R.string.era), label(art.eras, art.eraLabels, pref("artera", "all")) });
            String txt = pref("arttext", "");
            items.add(new String[] { "arttext", t(R.string.your_text), txt.isEmpty() ? t(R.string.not_set) : (txt.length() > 12 ? txt.substring(0, 11) + "…" : txt) });
            items.add(new String[] { "artrate", t(R.string.refresh), label(ART_RATES, ART_RATE_LABELS, pref("artrate", "5")) });
            // drawn under an "Avoid Screen Burn" header; OK flips them in place
            items.add(new String[] { "artbreath", t(R.string.text_breathing), t(breathing() ? R.string.on : R.string.off) });
            items.add(new String[] { "artzoom", t(R.string.slow_zoom), t(zoomOn() ? R.string.on : R.string.off) });
        } else if (Apps.INPUTS.equals(a.pkg)) {
            inputs = repo.inputs();
            for (int i = 0; i < inputs.size(); i++) {
                Apps.Input in = inputs.get(i);
                String st = in.state == android.media.tv.TvInputManager.INPUT_STATE_CONNECTED ? t(R.string.connected)
                        : in.state == android.media.tv.TvInputManager.INPUT_STATE_CONNECTED_STANDBY ? t(R.string.standby)
                        : in.state == android.media.tv.TvInputManager.INPUT_STATE_DISCONNECTED ? t(R.string.not_connected) : "";
                // a named device (HDMI-CEC) shows its name and state only, so the name is not cut short; the port is shown when there is no state
                items.add(new String[] { "input:" + i, in.label, st.isEmpty() ? in.detail : st });
            }
            items.add(R.string.all_inputs);   // the TV's own input picker
        } else if (a.internal()) {
            items.add(R.string.open_settings);
            items.add(new String[] { "mode", t(R.string.mode), label(MODES, MODE_LABELS, mode()) });
            items.add(new String[] { "layout", t(R.string.layout), t(dock() ? R.string.layout_dock : R.string.layout_vertical) });
            if (aesthetic()) items.add(new String[] { "wallrate", t(R.string.wall_refresh), label(WALL_RATES, WALL_RATE_LABELS, wallRate()) });
            items.add(new String[] { "accent", t(R.string.accent), accentLabel() });
            items.add(new String[] { "style", t(R.string.icon_style), t(big() ? R.string.big_tiles : R.string.icon_name) });
            items.add(R.string.system_settings);
        } else {
            List<Apps.Resume> rs = repo.resume(a.pkg);
            if (!rs.isEmpty() && !offGrid()) new Thread(() -> {   // Off Grid: no posters, the app's tile instead
                for (Apps.Resume r : rs) { r.art = repo.art(r.poster, r.pkg); postInvalidate(); }
            }).start();
            items.add(R.string.open);
            items.addAll(rs);
            items.add(R.string.app_info);
            if (!a.settingsApp) items.add(R.string.remove_home);   // the TV's Settings stays: it is the way back
            if (!a.system) items.add(R.string.uninstall);
        }
        panelFocus = Math.min(panelFocus, items.size() - 1);
        if (screen != CHOICE) panelFocus = 0;
        screen = PANEL;
        invalidate();
    }

    private List<Apps.Input> inputs = new ArrayList<>();

    private void panelAction() {
        Apps.App a = cur();
        Object it = items.get(panelFocus);
        if (it instanceof Apps.Resume) {
            repo.countLaunch(a.pkg);
            Intent i = null;
            try { i = Apps.resumeIntent(((Apps.Resume) it).intentUri, a.pkg); } catch (Exception ignored) { }
            if (i != null) start(i); else open(a);
        } else if (it instanceof String[] && "artzoom".equals(((String[]) it)[0])) {
            put("artzoom", zoomOn() ? "off" : "on");
            screen = CHOICE;
            openPanel();
        } else if (it instanceof String[] && "artbreath".equals(((String[]) it)[0])) {
            put("artbreath", breathing() ? "100" : "50");
            screen = CHOICE;
            openPanel();
        } else if (it instanceof String[] && ((String[]) it)[0].startsWith("input:")) {
            Apps.Input in = inputs.get(Integer.parseInt(((String[]) it)[0].substring(6)));
            repo.countLaunch(a.pkg);
            start(Apps.inputIntent(in));
        } else if (it.equals(R.string.all_inputs)) {
            start(new Intent("com.android.tv.action.VIEW_INPUTS"));
        } else if (it instanceof String[]) {
            choiceFor(((String[]) it)[0], PANEL);
        } else if (it.equals(R.string.open) || it.equals(R.string.start)) {
            open(a);
        } else if (it.equals(R.string.open_settings)) {
            openSettings();
        } else if (it.equals(R.string.system_settings)) {
            start(new Intent(Settings.ACTION_SETTINGS));
        } else if (it.equals(R.string.app_info)) {
            start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)));
        } else if (it.equals(R.string.uninstall)) {
            start(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)));
        } else {   // Remove from Home
            repo.setHidden(a.pkg, true);
            screen = HOME;
            reload(false);
        }
    }

    // ------------------------------------------------------------------ carousel menus
    private void openSettings() { openMenu(SETTINGS, 0); }

    private void openMenu(int which, int f) {
        screen = which;
        if (manim != null) manim.cancel();
        mfocus = f;
        mpos = mtarget = f;
        invalidate();
    }

    private void mmove(int dir) {
        int n = menuRows().size();
        if (n == 0) return;
        mfocus = wrap(mfocus + dir, n);
        mtarget += dir;
        if (manim != null) manim.cancel();
        manim = slide(mpos, mtarget, v -> mpos = v);
    }

    /** {key, label, value} rows of the current menu. */
    private List<String[]> menuRows() {
        List<String[]> r = new ArrayList<>();
        if (screen == SETTINGS) {
            r.add(new String[] { "style", t(R.string.icon_style), t(big() ? R.string.big_tiles : R.string.icon_name) });
            r.add(new String[] { "layout", t(R.string.layout), t(dock() ? R.string.layout_dock : R.string.layout_vertical) });
            r.add(new String[] { "size", t(R.string.icon_size), t(new int[] { R.string.regular, R.string.medium, R.string.large }[iconSize()]) });
            r.add(new String[] { "font", t(R.string.font_size), t(fs() > 1.3f ? R.string.extra_large : fs() > 1 ? R.string.large : R.string.regular) });
            r.add(new String[] { "mode", t(R.string.mode), label(MODES, MODE_LABELS, mode()) });
            if (aesthetic()) r.add(new String[] { "wallrate", t(R.string.wall_refresh), label(WALL_RATES, WALL_RATE_LABELS, wallRate()) });
            r.add(new String[] { "accent", t(R.string.accent), accentLabel() });
            r.add(new String[] { "order", t(R.string.app_order), t(byUse() ? R.string.most_used : R.string.a_z) });
            r.add(new String[] { "clock", t(R.string.clock), t(clock24() ? R.string.h24 : R.string.h12) });
            r.add(new String[] { "ampm", t(R.string.time_labels), onOff("ampm", false) });
            r.add(new String[] { "idle", t(R.string.idle_art), label(IDLE, IDLE_LABELS, pref("idle", "10")) });
            r.add(new String[] { "ssmode", t(R.string.screensaver), label(SS_MODES, SS_MODE_LABELS, pref("ssmode", "clock")) });
            r.add(new String[] { "sstext", t(R.string.ss_text), pref("sstext", "").isEmpty() ? t(R.string.not_set) : (pref("sstext", "").length() > 12 ? pref("sstext", "").substring(0, 11) + "…" : pref("sstext", "")) });
            r.add(new String[] { "ssspeed", t(R.string.ss_speed), label(SS_SPEEDS, SS_SPEED_LABELS, pref("ssspeed", "m")) });
            r.add(new String[] { "sscolor", t(R.string.multi_color), onOff("sscolor", true) });
            r.add(new String[] { "ssdate", t(R.string.show_date), onOff("ssdate", false) });
            r.add(new String[] { "sspreview", t(R.string.preview_ss), "›" });
            r.add(new String[] { "offgrid", t(R.string.off_grid), onOff("offgrid", false) });
            r.add(new String[] { "safe", t(R.string.safe_pictures), onOff("safe", true) });
            if (!fireTv) r.add(new String[] { "defhome", t(R.string.default_home), homeIsNullofy() ? "Nullofy" : t(R.string.tv_default) });
            r.add(new String[] { "hidden", t(R.string.hidden_apps), String.valueOf(repo.hidden().size()) });
            r.add(new String[] { "system", t(R.string.system_settings), "›" });
        } else if (screen == CHOICE) {
            String cur = currentChoice();
            for (int i = 0; i < choiceVals.length; i++)
                r.add(new String[] { choiceVals[i], choiceLabels[i], choiceVals[i].equals(cur) ? t(R.string.current) : "" });
        } else if (screen == HIDDEN) {
            Set<String> h = repo.hidden();
            for (Apps.App a : allApps) r.add(new String[] { a.pkg, a.label, t(h.contains(a.pkg) ? R.string.hidden : R.string.shown) });
        }
        return r;
    }

    /** On/off rows (Time Labels, Multi Color, Show Date): OK flips them in place. */
    private static final List<String> TOGGLES = Arrays.asList("ampm", "sscolor", "ssdate", "offgrid", "safe");
    /** Toggles that are on until turned off: Multi Color and Safe Pictures. */
    private static final List<String> ON_BY_DEFAULT = Arrays.asList("sscolor", "safe");
    private boolean on(String key, boolean d) { return "on".equals(pref(key, d ? "on" : "off")); }
    private String onOff(String key, boolean d) { return t(on(key, d) ? R.string.on : R.string.off); }

    private String currentChoice() {
        if ("clock".equals(choiceKey)) return clock24() ? "24" : "12";
        if ("accent".equals(choiceKey)) return autoAccent() ? "Auto" : ACCENT_NAMES[accentIndex()];
        if ("size".equals(choiceKey)) return pref("size", "s");
        if ("artrate".equals(choiceKey)) return pref("artrate", "5");
        if ("idle".equals(choiceKey)) return pref("idle", "10");
        if ("ssmode".equals(choiceKey)) return pref("ssmode", "clock");
        if ("ssspeed".equals(choiceKey)) return pref("ssspeed", "m");
        if ("wallrate".equals(choiceKey)) return wallRate();
        return pref(choiceKey, choiceVals[0]);
    }

    private void menuOk() {
        List<String[]> rows = menuRows();
        if (rows.isEmpty()) return;
        String k = rows.get(mfocus)[0];
        if (screen == CHOICE) {
            boolean intoAesthetic = choiceKey.equals("mode") && "aesthetic".equals(choiceVals[mfocus]) && !aesthetic();
            put(choiceKey, choiceVals[mfocus]);
            if (intoAesthetic) put("accent", "Auto");   // Aesthetic starts on Auto (black or white from the picture); any accent can still be picked after
            if (choiceKey.equals("layout")) wallLight = decideLight(dock() ? wallShareDock : wallShareList);   // Auto looks behind the apps
            themeKey = "";
            clearBitmaps();
            reload(false);
            returnFromChoice();
            if (choiceKey.equals("defhome")) homeChosen();
        } else if (screen == HIDDEN) {
            repo.setHidden(k, !repo.hidden().contains(k));
        } else if (k.equals("hidden")) {
            settingsFocus = mfocus;
            allApps = new ArrayList<>();
            for (Apps.App a : repo.all()) if (!a.internal()) allApps.add(a);
            Collections.sort(allApps, (x, y) -> x.label.compareToIgnoreCase(y.label));
            openMenu(HIDDEN, 0);
        } else if (TOGGLES.contains(k)) {
            put(k, on(k, ON_BY_DEFAULT.contains(k)) ? "off" : "on");
            if (k.equals("safe")) art.safe = on("safe", true);
            if (k.equals("offgrid") || k.equals("safe")) { wallAt = 0; wallTry = 0; }   // Aesthetic swaps its picture at once
        } else if (k.equals("sspreview")) {
            stayInSettings = true;   // Back from the preview returns here, not to Home
            start(new Intent(getContext(), DreamPreview.class));
        } else if (k.equals("system")) {
            stayInSettings = true;
            start(new Intent(Settings.ACTION_SETTINGS));
        } else {
            settingsFocus = mfocus;
            choiceFor(k, SETTINGS);
        }
        invalidate();
    }

    /** Opens the option list for a setting; OK applies it and goes back to where it was opened from. */
    private void choiceFor(String key, int from) {
        choiceReturn = from;
        String[] v, l;
        switch (key) {
            case "style": v = new String[] { "icon", "big" }; l = new String[] { t(R.string.icon_name), t(R.string.big_tiles) }; break;
            case "size": v = new String[] { "s", "m", "l" }; l = new String[] { t(R.string.regular), t(R.string.medium), t(R.string.large) }; break;
            case "font": v = new String[] { "r", "l", "xl" }; l = new String[] { t(R.string.regular), t(R.string.large), t(R.string.extra_large) }; break;
            case "mode": v = MODES; l = MODE_LABELS; break;
            case "layout": v = new String[] { "list", "dock" }; l = new String[] { t(R.string.layout_vertical), t(R.string.layout_dock) }; break;
            case "order": v = new String[] { "use", "az" }; l = new String[] { t(R.string.most_used), t(R.string.a_z) }; break;
            case "clock": v = new String[] { "24", "12" }; l = new String[] { t(R.string.h24), t(R.string.h12) }; break;
            case "artsrc": v = art.sources; l = art.labels; break;
            case "artera": v = art.eras; l = art.eraLabels; break;
            case "artrate": v = ART_RATES; l = ART_RATE_LABELS; break;
            case "idle": v = IDLE; l = IDLE_LABELS; break;
            case "wallrate": v = WALL_RATES; l = WALL_RATE_LABELS; break;
            case "defhome": v = new String[] { "nullofy", "tv" }; l = new String[] { "Nullofy", t(R.string.tv_default) }; break;
            case "ssmode": v = SS_MODES; l = SS_MODE_LABELS; break;
            case "ssspeed": v = SS_SPEEDS; l = SS_SPEED_LABELS; break;
            case "sstext": typeText("sstext", t(R.string.ss_text), null); return;
            case "arttext": typeText("arttext", t(R.string.your_text), "artsrc"); return;
            default:   // accent; Aesthetic adds Auto at the top
                v = ACCENT_NAMES;
                l = accentLabels();
                if (aesthetic()) {
                    List<String> vv = new ArrayList<>(Arrays.asList(v)), ll = new ArrayList<>(Arrays.asList(l));
                    vv.add(0, "Auto");
                    ll.add(0, t(R.string.acc_auto));
                    v = vv.toArray(new String[0]);
                    l = ll.toArray(new String[0]);
                }
        }
        choiceKey = key;
        choiceVals = v;
        choiceLabels = l;
        screen = CHOICE;
        openMenu(CHOICE, Math.max(0, Arrays.asList(v).indexOf(currentChoice())));
    }

    private void returnFromChoice() {
        if (choiceReturn == PANEL) { screen = CHOICE; openPanel(); }
        else openMenu(SETTINGS, settingsFocus);
    }

    /** Your own text as the Art wallpaper (up to Art.TEXT_MAX characters), typed with the TV keyboard. */
    private void typeText(String key, String title, String showKey) {
        android.widget.EditText e = new android.widget.EditText(getContext());
        e.setSingleLine(true);
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        e.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(Art.TEXT_MAX) });
        e.setText(pref(key, ""));
        e.setSelection(e.getText().length());
        e.setHint(getContext().getString(R.string.up_to, Art.TEXT_MAX));
        android.widget.TextView count = new android.widget.TextView(getContext());
        count.setGravity(android.view.Gravity.END);
        count.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        count.setText(String.format(Locale.getDefault(), "%d/%d", e.length(), Art.TEXT_MAX));
        e.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                count.setText(String.format(Locale.getDefault(), "%d/%d", s.length(), Art.TEXT_MAX));
            }
        });
        android.widget.LinearLayout box = new android.widget.LinearLayout(getContext());
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(e);
        box.addView(count);
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(getContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(t(R.string.save), (d, w) -> {
                    String v = e.getText().toString().trim();
                    put(key, v);
                    if (showKey != null && !v.isEmpty()) put(showKey, "text");   // typing Your Text means: show it
                    if (key.equals("sstext") && !v.isEmpty() && pref("ssmode", "clock").equals("clock")) put("ssmode", "both");
                    if (choiceReturn == PANEL) { screen = CHOICE; openPanel(); }
                    else openMenu(SETTINGS, settingsFocus);
                })
                .setNegativeButton(t(R.string.cancel), null)
                .create();
        e.setOnEditorActionListener((v, id, ev) -> { dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); dlg.dismiss(); return true; });
        dlg.show();
    }

    // ------------------------------------------------------------------ theme
    private void theme() {
        String mode = mode();
        boolean lw = lightWall();
        String key = mode + accentIndex() + autoAccent() + lw;
        if (key.equals(themeKey)) return;
        themeKey = key;
        boolean light = mode.equals("light");
        acc = (light || lw ? ACCENTS_DAY : ACCENTS)[accentIndex()];
        accLn = acc;
        if (light || lw) {          // Day: a softer white, easier in a dim room (Auto over a bright picture uses its inks)
            bg = 0xFFE4E7E4; ink = 0xFF0B0D10; ink2 = 0xFF4A5258; ink3 = 0xFF7A8288; lab = 0xFF575E65; hair = 0x1A000000;
            fill = (acc & 0x00FFFFFF) | (0x16 << 24);
        } else {
            bg = 0xFF06080B; ink = 0xFFF2F5F7; ink2 = 0xFF9AA3AB; ink3 = 0xFF5B636B; lab = 0xFF868E96; hair = 0x17FFFFFF;
            fill = (acc & 0x00FFFFFF) | (0x0C << 24);
        }
        wallOn = mode.equals("aesthetic");
        Bitmap d = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);   // 48px dot grid
        Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);
        dp.setColor(light ? 0x12000000 : 0x0EFFFFFF);
        new Canvas(d).drawCircle(24, 24, 1.3f, dp);
        dots = new BitmapShader(d, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
    }

    // ------------------------------------------------------------------ Aesthetic mode
    private boolean wallOn, wallBusy;
    private Art.Piece wall, wallPrev;   // wallPrev: the picture fading out
    private Bitmap wallBlur, wallPrevBlur;
    private long wallAt, wallTry;
    private float wallLum, wallLumPrev, wallFade = 1;   // 0..1 average brightness of the picture
    private boolean wallLight, wallPrevLight;   // Auto: the picture is mostly bright behind the apps
    private float wallShareDock, wallShareList;
    private ValueAnimator wallAnim;
    private int offlineNext = new Random().nextInt(3);

    /** Average brightness (Rec. 709 luma) of the small blurred picture. */
    private static float luma(Bitmap b) {
        int[] px = new int[b.getWidth() * b.getHeight()];
        b.getPixels(px, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
        double sum = 0;
        for (int v : px) sum += .2126 * ((v >> 16) & 255) + .7152 * ((v >> 8) & 255) + .0722 * (v & 255);
        return (float) (sum / px.length / 255);
    }

    /** Share of bright pixels (luma over half) in a part of the blurred picture, which is already a screen-shaped crop. */
    private static float brightShare(Bitmap b, float l, float t, float r, float bt) {
        int w = b.getWidth(), h = b.getHeight(), x0 = (int) (l * w), y0 = (int) (t * h), x1 = (int) (r * w), y1 = (int) (bt * h);
        int[] px = new int[(x1 - x0) * (y1 - y0)];
        b.getPixels(px, 0, x1 - x0, x0, y0, x1 - x0, y1 - y0);
        int bright = 0;
        for (int v : px) if (.2126 * ((v >> 16) & 255) + .7152 * ((v >> 8) & 255) + .0722 * (v & 255) > 127.5) bright++;
        return bright / (float) px.length;
    }

    /** Auto: black when most pixels behind the apps are bright, white when most are dark; near an even split it keeps the last choice. */
    private boolean decideLight(float share) { return share > .55f || (share >= .45f && wallLight); }

    /** The picture's brightness, blended while one picture fades into the next. */
    private float lum() { return wallPrev != null && wallFade < 1 ? wallLumPrev + (wallLum - wallLumPrev) * wallFade : wallLum; }

    /**
     * Extra glass tint so text keeps its contrast: dark over bright pictures for white text, light over darker
     * pictures for Auto's black text.
     */
    private float brightTint() {
        float l = lum();
        return .42f * Math.max(0, Math.min(1, lightWall() ? (.75f - l) / .4f : (l - .3f) / .4f));
    }

    /**
     * Aesthetic: a Mix picture sits behind Home, with frosted-glass cards for the list, panel and menus.
     * A new picture every 1, 3, 5 or 10 minutes (Picture Refresh), cross-faded in 1.5 s; kept in memory only.
     */
    private void wallDrift() {
        long now = SystemClock.uptimeMillis();
        long every = num(wallRate(), 3) * 60000L;
        if (wallBusy || (wall != null && now - wallAt < every) || now - wallTry < 60000) return;
        wallBusy = true;
        wallTry = now;
        boolean grid = offGrid(), keep = wall != null && !wall.bundled;   // a web picture already up stays when offline
        new Thread(() -> {
            Art.Piece web = grid ? null : art.next("mix", "all", "", true);
            if (!grid) art.saveOffline();   // the offline pictures are fetched once, while the web can be reached
            Art.Piece pc = web != null || keep ? web : art.offline(offlineNext++);   // the saved pictures: Off Grid, or no web yet
            Bitmap blur = pc == null ? null : blurred(pc.bmp);
            float lum = blur == null ? 0 : luma(blur);
            // the Dock sits on the bottom 30% of the screen; the vertical list on the left (x 120..885 of 1920)
            float sDock = blur == null ? 0 : brightShare(blur, 0, .7f, 1, 1), sList = blur == null ? 0 : brightShare(blur, .06f, 0, .46f, 1);
            post(() -> {
                wallBusy = false;
                if (pc == null) return;   // offline: keep the last picture, or plain Dark
                if (!wallOn) { recycle(pc); blur.recycle(); return; }
                dropPrev();
                if (wall != null) {   // the old picture fades out under the new one
                    wallPrev = wall;
                    wallPrevBlur = wallBlur;
                    wallLumPrev = wallLum;
                    wallPrevLight = wallLight;
                }
                wall = pc;
                wallBlur = blur;
                wallLum = lum;
                wallShareDock = sDock;
                wallShareList = sList;
                wallLight = decideLight(dock() ? sDock : sList);
                wallAt = SystemClock.uptimeMillis();
                if (wallPrev != null) {
                    wallFade = 0;
                    wallAnim = ValueAnimator.ofFloat(0, 1).setDuration(1500);
                    wallAnim.setInterpolator(new DecelerateInterpolator());
                    wallAnim.addUpdateListener(a -> { wallFade = (float) a.getAnimatedValue(); invalidate(); });
                    wallAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                        @Override public void onAnimationEnd(android.animation.Animator a) { if (wallFade >= 1) dropPrev(); }
                    });
                    wallAnim.start();
                }
                invalidate();
            });
        }).start();
    }

    /** Ends a cross-fade and frees the picture that faded out. */
    private void dropPrev() {
        if (wallAnim != null) { ValueAnimator a = wallAnim; wallAnim = null; a.cancel(); }
        wallFade = 1;
        recycle(wallPrev);
        if (wallPrevBlur != null) wallPrevBlur.recycle();
        wallPrev = null;
        wallPrevBlur = null;
    }

    private void dropWall() {
        dropPrev();
        recycle(wall);
        if (wallBlur != null) wallBlur.recycle();
        wall = null;
        wallBlur = null;
        wallAt = 0;
        wallTry = 0;
    }

    /** A 1920x1080 cover crop of the picture shrunk to 240x135 and box-blurred three times: the frosted glass. */
    private static Bitmap blurred(Bitmap src) {
        final int bw = 240, bh = 135;
        Bitmap small = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(small);
        float s = Math.max(bw / (float) src.getWidth(), bh / (float) src.getHeight());
        c.translate((bw - src.getWidth() * s) / 2, (bh - src.getHeight() * s) / 2);
        c.scale(s, s);
        Paint sp = new Paint(Paint.FILTER_BITMAP_FLAG);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.35f);   // glass deepens the colours behind it
        sp.setColorFilter(new ColorMatrixColorFilter(cm));
        c.drawBitmap(src, 0, 0, sp);
        int[] px = new int[bw * bh], tmp = new int[bw * bh];
        small.getPixels(px, 0, bw, 0, 0, bw, bh);
        for (int pass = 0; pass < 3; pass++) {
            box(px, tmp, bw, bh, 4, true);
            box(tmp, px, bw, bh, 4, false);
        }
        small.setPixels(px, 0, bw, 0, 0, bw, bh);
        return small;
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean horiz) {
        int n = horiz ? w : h, lines = horiz ? h : w;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < n; i++) {
                int rs = 0, gs = 0, bs = 0, cnt = 0;
                for (int k = Math.max(0, i - r); k <= Math.min(n - 1, i + r); k++) {
                    int v = in[horiz ? line * w + k : k * w + line];
                    rs += (v >> 16) & 255; gs += (v >> 8) & 255; bs += v & 255; cnt++;
                }
                out[horiz ? line * w + i : i * w + line] = 0xFF000000 | ((rs / cnt) << 16) | ((gs / cnt) << 8) | (bs / cnt);
            }
        }
    }

    /** Draws the picture (or its blur) cropped to cover the whole screen. */
    private void cover(Canvas c, Bitmap b) { cover(c, b, 1); }

    private void cover(Canvas c, Bitmap b, float a) {
        p.reset();
        p.setFilterBitmap(true);
        p.setDither(true);
        p.setAlpha((int) (255 * a));
        float s = Math.max(W / (float) b.getWidth(), H / (float) b.getHeight());
        float dw = b.getWidth() * s, dh = b.getHeight() * s;
        rf.set((W - dw) / 2, (H - dh) / 2, (W + dw) / 2, (H + dh) / 2);
        c.drawBitmap(b, null, rf, p);
    }

    /** A frosted-glass card: the blurred picture, darkened, with a faint light edge. */
    private void glass(Canvas c, float l, float t, float r, float b) { glass(c, l, t, r, b, 1, 0, .26f + .7f * brightTint()); }

    /** One card per row as it fades with distance; the focused card (lit = 1) is clearer and brighter. */
    /** menu: Settings and option lists, where every row keeps a clearly frosted card behind its text, for readability. */
    private void rowGlass(Canvas c, int n, int base, float frac, int k, float rh, float inset, float x0, float w, boolean dim, boolean menu) {
        for (int d = -k; d <= k + 1; d++) {
            float off = d - frac, dist = Math.abs(off);
            if (n == 2 ? off < -.99f || off > 1.99f : dist >= n / 2f) continue;
            float op = (menu ? floorFade(dist, rh, MENU_GLASS) : fade(dist)) * (dim && dist > .5f ? .4f : 1);
            if (op <= .01f) continue;
            float cy = H / 2f + off * rh, lit = dim ? 0 : Math.max(menu ? MENU_FROST : 0, 1 - dist);
            glass(c, x0, cy - rh / 2 + inset, x0 + w, cy + rh / 2 - inset, op, lit, (menu ? .34f : .04f) + .5f * brightTint());
        }
    }

    /** Settings rows: their glass never fades below this (until the screen edge), and is always at least this frosted. */
    private static final float MENU_GLASS = 1, MENU_FROST = .45f;   // see-through glass let the sharp picture show behind the text

    /**
     * Glass: the blurred picture with a light dark tint (tint = its alpha) and a faint white frost, a sheen
     * across the top, and an edge lit from above. The focused card (lit = 1) is clearer and brighter.
     */
    private void glass(Canvas c, float l, float t, float r, float b, float op, float lit, float tint) {
        if (!wallOn || wallBlur == null) return;
        float rad = Math.min(28, (b - t) * .2f);
        int layer = op < 1 ? c.saveLayerAlpha(l - 2, t - 2, r + 2, b + 2, (int) (255 * op)) : c.save();
        Path clip = new Path();
        clip.addRoundRect(l, t, r, b, rad, rad, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        if (wallPrevBlur != null && wallFade < 1) cover(c, wallPrevBlur);
        cover(c, wallBlur, wallPrevBlur != null ? wallFade : 1);
        p.reset();
        p.setColor(alpha(lightWall() ? 0xFFFFFFFF : 0xFF000000, tint * (1 - .4f * lit)));   // Auto's light glass for black text
        c.drawRect(l, t, r, b, p);
        p.setColor(alpha(0xFFFFFFFF, .015f + .07f * lit));   // frost
        c.drawRect(l, t, r, b, p);
        p.setDither(true);
        p.setShader(new LinearGradient(0, t, 0, t + (b - t) * .55f, alpha(0xFFFFFFFF, .10f + .06f * lit), 0, Shader.TileMode.CLAMP));
        c.drawRect(l, t, r, b, p);   // sheen
        p.setShader(null);
        c.restore();
        p.reset();
        p.setAntiAlias(true);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f);
        p.setShader(new LinearGradient(0, t, 0, b, alpha(0xFFFFFFFF, .34f + .3f * lit), alpha(0xFFFFFFFF, .08f + .1f * lit), Shader.TileMode.CLAMP));
        rf.set(l + .75f, t + .75f, r - .75f, b - .75f);
        c.drawRoundRect(rf, rad, rad, p);
        p.setShader(null);
        c.restoreToCount(layer);
    }

    private static int mix(int a, int b, float t) {
        int r = (int) (Color.red(a) * (1 - t) + Color.red(b) * t);
        int g = (int) (Color.green(a) * (1 - t) + Color.green(b) * t);
        int bl = (int) (Color.blue(a) * (1 - t) + Color.blue(b) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static int alpha(int color, float a) {
        int al = Math.round(Color.alpha(color) * Math.max(0, Math.min(1, a)));
        return (color & 0x00FFFFFF) | (al << 24);
    }

    private void clearBitmaps() {
        for (Apps.App a : apps) { a.iconBmp = null; a.tileBmp = null; }
    }

    /**
     * Another app is on screen: free the hidden-apps list and a picture still fading out. The small icon and
     * tile renders stay so Home comes back at once; when Android is short of memory they and the Aesthetic
     * picture go too (a new picture comes on return).
     */
    void trim(int level) {
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return;
        dropPrev();
        if (screen != HIDDEN) allApps = new ArrayList<>();
        synchronized (NullofyView.class) {   // the Art picture fetched ahead: a full picture, not worth holding while away
            recycle(artSpare);
            artSpare = null;
            artSpareKey = null;
        }
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) return;
        clearBitmaps();
        dropWall();
    }

    // ------------------------------------------------------------------ drawing
    private float scale = 1;

    @Override protected void onDraw(Canvas c) {
        theme();
        scale = Math.min(getWidth() / (float) W, getHeight() / (float) H);
        c.drawColor(0xFF000000);
        c.save();
        c.translate((getWidth() - W * scale) / 2, (getHeight() - H * scale) / 2);
        c.scale(scale, scale);
        if (screen == ART) {
            artScreen(c);
        } else {
            background(c);
            if (screen == HOME || screen == PANEL) {
                boolean dim = screen == PANEL;
                if (dock()) {
                    dock(c, dim);
                } else {
                    rail(c, apps.size(), wrap(focus, apps.size()));
                    if (big()) {
                        if (dim) tiles(c, 160, 194, true); else tiles(c, -1, 243, false);
                    } else {
                        list(c, dim);
                    }
                }
                if (dim) panel(c);
            } else {
                menu(c);
            }
        }
        clock(c);
        c.restore();
    }

    private void background(Canvas c) {
        p.reset();
        p.setColor(bg);
        c.drawRect(0, 0, W, H, p);
        if (wallOn) {
            wallDrift();
            if (wall != null) {
                if (wallPrev != null && wallFade < 1) cover(c, wallPrev.bmp);
                cover(c, wall.bmp, wallPrev != null ? wallFade : 1);
                p.reset();
                // a scrim keeps the clock, breadcrumbs and key hints readable; darker over bright pictures
                // (Auto over a bright picture: a light veil for its black text instead)
                float l = lum();
                p.setColor(lightWall() ? alpha(0xFFFFFFFF, .12f + .3f * Math.max(0, Math.min(1, (.7f - l) / .4f)))
                        : alpha(0xFF000000, .25f + .35f * Math.max(0, Math.min(1, (l - .35f) / .4f))));
                c.drawRect(0, 0, W, H, p);
                return;
            }
        } else if (wall != null) {
            dropWall();
        }
        p.setShader(dots);
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);
    }

    private void clock(Canvas c) {
        String f = clock24() ? "HH:mm" : "h:mm";
        boolean photo = screen == ART || (wallOn && wall != null), lw = screen != ART && lightWall();
        int shadow = lw ? 0xB3FFFFFF : 0xB3000000;
        if (photo) tp.setShadowLayer(14, 0, 2, shadow);
        Date now = new Date();
        String time = new SimpleDateFormat(f, Locale.getDefault()).format(now);
        int col = screen == ART ? 0xFFDDE2E6 : lw ? ink : photo ? 0xFFF2F5F7 : ink2;
        float x = W - 144 + clockDx, y = 140 + clockDy;
        if (!clock24() && on("ampm", false)) {   // Time Labels: AM / PM after a 12-hour clock
            String ap = ampm(now);
            text(c, ap, x, y, 34, col, mono, Paint.Align.RIGHT, .08f);
            x -= textWidth(ap, 34, mono, .08f) + 16;
            if (photo) tp.setShadowLayer(14, 0, 2, shadow);
        }
        text(c, time, x, y, 80, col, mono, Paint.Align.RIGHT, 0.02f);
        tp.clearShadowLayer();
    }

    /** "AM" or "PM" (the TV's language may use its own words). */
    static String ampm(Date d) { return new SimpleDateFormat("a", Locale.getDefault()).format(d).toUpperCase(Locale.getDefault()); }

    /** Position rail: one tick per item, the focused one long and lit. */
    private void rail(Canvas c, int n, int on) {
        if (n == 0) return;
        float gap = Math.min(20, 760f / n), top = H / 2f - (n - 1) * gap / 2;
        for (int i = 0; i < n; i++) {
            boolean f = i == on;
            p.reset();
            p.setColor(f ? accLn : alpha(ink3, .7f));
            float y = top + i * gap;
            c.drawRect(64, y - (f ? 1.5f : 1), 64 + (f ? 32 : 12), y + (f ? 1.5f : 1), p);
        }
    }

    /** Icon + name carousel: the reticle stays in the middle and the list slides under it. */
    private void list(Canvas c, boolean dim) {
        int n = apps.size();
        if (n == 0) return;
        int is = iconSize();
        boolean l = is == 2;
        float rh = new float[] { 156, 208, 280 }[is], icon = new float[] { 112, 160, 216 }[is], nameSz = (l ? 84 : 64) * fs(), gap = l ? 44 : 36, lw = l ? 901 : 765, x0 = 120;
        float inset = rh * .07f;
        int base = (int) Math.floor(pos);
        float frac = pos - base;
        int k = (int) Math.ceil(540 / rh) + 1;
        if (wallOn) rowGlass(c, n, base, frac, k, rh, inset, x0, lw, dim, false);
        reticle(c, x0, H / 2f - rh / 2 + inset, x0 + lw, H / 2f + rh / 2 - inset, 28, 4, dim ? .35f : 1, true);
        for (int d = -k; d <= k + 1; d++) {
            float off = d - frac, dist = Math.abs(off);
            if (dist >= n / 2f) continue;   // never draw the same app twice
            float op = fade(dist);
            if (op <= .01f) continue;
            float fw = Math.max(0, 1 - dist);
            if (dim && fw < .5f) op *= .4f;
            Apps.App a = apps.get(wrap(base + d, n));
            float cy = H / 2f + off * rh, x = x0 + 28;
            p.reset();
            p.setFilterBitmap(true);
            p.setAlpha((int) (255 * op));
            float ic = icon * (1 + LIST_ZOOM * fw);   // the focused icon grows a little
            if (!dim) glow(c, x + icon / 2, cy, ic * .65f, fw * op);
            p.reset();
            p.setFilterBitmap(true);
            p.setAlpha((int) (255 * op));
            rf.set(x + (icon - ic) / 2, cy - ic / 2, x + (icon + ic) / 2, cy + ic / 2);
            c.drawBitmap(iconBitmap(a, icon * (1 + LIST_ZOOM)), null, rf, p);
            x += icon + gap;
            Typeface tf = weight(dim ? 0 : fw);
            text(c, fit(a.label, nameSz, x0 + lw - 80 - x, tf, -0.025f), x, cy + nameSz * .36f, nameSz,
                    alpha(lerp(ink2, dim ? ink2 : ink, fw), op), tf, Paint.Align.LEFT, -0.025f);
            text(c, "›", x0 + lw - 40, cy + nameSz * .2f, nameSz * .5f, alpha(lerp(ink3, accLn, fw), op), mono, Paint.Align.RIGHT, 0);
        }
    }

    /**
     * Dock: a horizontal carousel along the bottom, no band behind it. The focus stays in the middle and the
     * row slides under it, the apps fading out towards the screen edges like the vertical carousel.
     * Icon + Name puts each app's name under its icon; Big Tiles shows the banners only. The focused app
     * grows a little and sits on the one glass card; ticks below show where you are in the list.
     */
    private void dock(Canvas c, boolean dim) {
        int n = apps.size();
        if (n == 0) return;
        boolean b = big();
        int is = iconSize();
        float ih = b ? new float[] { 135, 162, 198 }[is] : new float[] { 120, 152, 192 }[is];
        float iw = b ? ih * 16 / 9 : ih, nameSz = 30 * fs();
        float zh = ih * (1 + DOCK_ZOOM) / 2, zw = iw * (1 + DOCK_ZOOM) / 2;   // the focused app's half size
        float below = b ? 18 : 18 + nameSz * 1.3f + 14;   // room under the focused icon inside its card
        float cx = W / 2f, cellB = H - 92, cy = cellB - below - zh, cellT = cy - zh - 18;
        // card half widths: the focused card is a landscape rectangle (about 1.45:1) so its name has room;
        // the others are narrower, and the pitch keeps a gap between the two
        float rw = b ? zw + 16 : Math.max(zw + 22, (cellB - cellT) * .72f), nw = b ? iw / 2 + 12 : ih * 1.05f;
        float pitch = Math.max(iw + (b ? ih * .6f : ih), rw + nw + 14);   // at least a full icon of air between apps
        float reach = cx / 2.6f;   // fade() is gone by ~2.7, so the last apps melt into the screen edges
        int base = (int) Math.floor(pos);
        float frac = pos - base;
        int k = (int) Math.ceil(cx / pitch) + 1;
        if (wallOn && wallBlur != null) {   // a faint frosted card behind every app; the focused one grows into the full card
            for (int d = -k; d <= k + 1; d++) {
                float off = d - frac, dist = Math.abs(off);
                if (dist >= n / 2f) continue;
                float op = fade(dist * pitch / reach), fw = Math.max(0, 1 - dist), half = nw + (rw - nw) * fw;
                float gop = (fw + (1 - fw) * DOCK_FROST * op) * (dim ? .5f : 1);
                if (gop <= .01f) continue;
                float x = cx + off * pitch;
                glass(c, x - half, cellT, x + half, cellB, gop, dim ? 0 : fw, .04f + .5f * brightTint());
            }
        }
        reticle(c, cx - rw, cellT, cx + rw, cellB, 26, 4, dim ? .35f : 1, !b);   // under the apps, so its fill never veils a logo
        for (int d = -k; d <= k + 1; d++) {
            float off = d - frac, dist = Math.abs(off);
            if (dist >= n / 2f) continue;   // never draw the same app twice
            float op = fade(dist * pitch / reach);
            if (op <= .01f) continue;
            float fw = Math.max(0, 1 - dist), z = 1 + DOCK_ZOOM * fw;
            if (dim && fw < .5f) op *= .4f;
            float x = cx + off * pitch, hw = iw * z / 2, hh = ih * z / 2;
            if (!dim && !b) glow(c, x, cy, Math.max(hw, hh) * 1.3f, fw * op);   // banners fill their card: no round glow behind them
            p.reset();
            p.setFilterBitmap(true);
            p.setAlpha((int) (255 * op));
            rf.set(x - hw, cy - hh, x + hw, cy + hh);
            Apps.App a = apps.get(wrap(base + d, n));
            if (b) banner(c, tileBitmap(a), rf, op);
            else c.drawBitmap(iconBitmap(a, ih * (1 + DOCK_ZOOM)), null, rf, p);
            if (!b) {
                Typeface tf = weight(dim ? 0 : fw);
                text(c, fit(a.label, nameSz, 2 * (nw + (rw - nw) * fw) - 28, tf, -0.02f), x, cy + hh + 18 + nameSz * .9f, nameSz,
                        alpha(lerp(ink2, dim ? ink2 : ink, fw), op), tf, Paint.Align.CENTER, -0.02f);
            }
        }
        dockTicks(c, n, wrap(focus, n), cx, H - 46);
    }

    private final Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.Matrix bm = new android.graphics.Matrix();

    /**
     * A TV banner with corners rounded to match the glass card and focus frame around it (their 28 px radius,
     * less the ~16 px gap), drawn through a shader so the corners stay smooth.
     */
    private void banner(Canvas c, Bitmap b, RectF r, float op) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        bm.setRectToRect(new RectF(0, 0, b.getWidth(), b.getHeight()), r, android.graphics.Matrix.ScaleToFit.FILL);
        s.setLocalMatrix(bm);
        bp.setShader(s);
        bp.setAlpha((int) (255 * op));
        float rad = Math.min(12, r.height() * .08f);
        c.drawRoundRect(r, rad, rad, bp);
        bp.setShader(null);
    }

    /** A soft accent glow behind the focused app's logo, fading to nothing around it. */
    private void glow(Canvas c, float x, float y, float r, float op) {
        if (op <= .01f) return;
        p.reset();
        p.setAntiAlias(true);
        p.setDither(true);
        p.setShader(new RadialGradient(x, y, r, new int[] { alpha(acc, .55f * op), alpha(acc, .22f * op), 0 },
                new float[] { 0, .55f, 1 }, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r, p);
        p.setShader(null);
    }

    /** The focused Dock app (and list icon) grows by this much. */
    private static final float DOCK_ZOOM = .1f, LIST_ZOOM = .08f;
    /** Strength of the frosted card behind each Dock app that is not focused. */
    private static final float DOCK_FROST = .35f;   // 15% was asked for, but it barely shows over bright pictures

    /** Position ticks under the Dock: one per app, the focused one long and lit (the rail, laid flat). */
    private void dockTicks(Canvas c, int n, int on, float cx, float y) {
        if (n < 2) return;
        float gap = Math.min(22, 900f / n), left = cx - (n - 1) * gap / 2;
        for (int i = 0; i < n; i++) {
            boolean f = i == on;
            float x = left + i * gap, hw = f ? 14 : 5;
            p.reset();
            p.setColor(f ? accLn : alpha(ink2, .55f));   // brighter than the rail's ticks: they sit on the picture
            c.drawRect(x - hw, y - (f ? 1.5f : 1), x + hw, y + (f ? 1.5f : 1), p);
        }
    }

    /** Big tiles: TV banners only, no text. x < 0 centres the column. */
    private void tiles(Canvas c, float x, float th, boolean dim) {
        int n = apps.size();
        if (n == 0) return;
        float tw = th * 16 / 9, rh = th + 48;
        if (x < 0) x = (W - tw) / 2;
        int base = (int) Math.floor(pos);
        float frac = pos - base;
        int k = (int) Math.ceil(540 / rh) + 1;
        for (int d = -k; d <= k + 1; d++) {
            float off = d - frac, dist = Math.abs(off);
            if (dist >= n / 2f) continue;
            float op = dist <= 1 ? 1 - .45f * dist : dist <= 2 ? .55f - .27f * (dist - 1) : dist <= 3 ? .28f - .18f * (dist - 2) : Math.max(0, .1f - .1f * (dist - 3));
            if (dim && dist > .5f) op *= .4f;
            if (op <= .01f) continue;
            float z = 1 + LIST_ZOOM * Math.max(0, 1 - dist), cy = H / 2f + off * rh;   // the focused tile grows a little
            p.reset();
            p.setFilterBitmap(true);
            p.setAlpha((int) (255 * op));
            rf.set(x + tw / 2 - tw * z / 2, cy - th * z / 2, x + tw / 2 + tw * z / 2, cy + th * z / 2);
            banner(c, tileBitmap(apps.get(wrap(base + d, n))), rf, op);
        }
        float zx = tw * LIST_ZOOM / 2, zy = th * LIST_ZOOM / 2;
        reticle(c, x - 12 - zx, H / 2f - th / 2 - 12 - zy, x + tw + 12 + zx, H / 2f + th / 2 + 12 + zy, 30, 4, dim ? .35f : 1, false);
    }

    private void panel(Canvas c) {
        Apps.App a = cur();
        if (a == null) return;
        float x = 1080, y = 160, w = 640;
        glass(c, x - 32, y - 32, x + w + 32, Math.min(H - 16, panelBottom + 20));   // sized from the last frame
        text(c, fit(up(a.label), 26 * pfs(), w, mono, .12f), x, y + 24, 26 * pfs(), lab, mono, Paint.Align.LEFT, .12f);
        y += 50;
        boolean wn = false, plainApp = !a.internal();
        for (int i = 0; i < items.size(); i++) {
            Object it = items.get(i);
            boolean f = i == panelFocus;
            if (it instanceof Apps.Resume) {
                if (!wn) { wn = true; text(c, up(t(R.string.continue_watching)), x, y + 34, 26 * pfs(), lab, mono, Paint.Align.LEFT, .12f); y += 50; }
                Apps.Resume r = (Apps.Resume) it;
                float op = f ? 1 : .55f;
                p.reset();
                p.setFilterBitmap(true);
                p.setAlpha((int) (255 * op));
                rf.set(x, y + 8, x + 208, y + 125);
                c.drawBitmap(r.art != null ? r.art : tileBitmap(a), null, rf, p);
                if (f) reticle(c, x - 10, y - 2, x + 218, y + 135, 22, 4, 1, false);
                float tx = x + 236;
                text(c, fit(r.title, 34, w - 236, weight(f ? 1 : 0), -0.02f), tx, y + 50, 34, alpha(ink, op), weight(f ? 1 : 0), Paint.Align.LEFT, -0.02f);
                text(c, fit(r.meta, 26, w - 236, mono, .02f), tx, y + 86, 26, alpha(lab, op), mono, Paint.Align.LEFT, .02f);
                int on = Math.round(r.progress * 14);
                for (int sg = 0; sg < 14; sg++) {
                    p.reset();
                    p.setColor(sg < on ? alpha(accLn, op) : hair);
                    c.drawRect(tx + sg * 20, y + 103, tx + sg * 20 + 16, y + 108, p);
                }
                y += 137;
                continue;
            }
            if (plainApp && i == 1 && !wn && watchNext) {   // this app publishes nothing to Watch Next
                text(c, up(t(R.string.continue_watching)), x, y + 34, 26 * pfs(), lab, mono, Paint.Align.LEFT, .12f);
                text(c, t(R.string.not_shared), x, y + 34 + 42 * pfs(), 32 * pfs(), ink2, weight(0), Paint.Align.LEFT, -0.01f);
                y += 60 + 52 * pfs();
            }
            String key = it instanceof String[] ? ((String[]) it)[0] : "";
            if (key.equals("artbreath")) { text(c, up(t(R.string.avoid_burn)), x, y + 48, 26 * pfs(), lab, mono, Paint.Align.LEFT, .12f); y += 62; }
            if (f) reticle(c, x, y + 4, x + w, y + 80, 24, 4, 1, true);
            String name = it instanceof String[] ? ((String[]) it)[1] : t((Integer) it);
            float sz = 44 * pfs();
            text(c, fit(name, sz, w - 56 - (it instanceof String[] ? textWidth(up(((String[]) it)[2]), 30 * pfs(), mono, .06f) + 40 : 0), weight(f ? 1 : 0), -0.025f), x + 28, y + 42 + sz * .36f, sz, f ? ink : ink2, weight(f ? 1 : 0), Paint.Align.LEFT, -0.025f);
            if (it instanceof String[]) value(c, (String[]) it, x + w - 24, y + 54, f, 30 * pfs(), false);
            p.reset();
            p.setColor(hair);
            c.drawRect(x, y + 84, x + w, y + 85, p);
            y += 88;
        }
        if (y != panelBottom) { panelBottom = y; invalidate(); }
    }

    private float panelBottom = H - 90;

    /** Settings, an option list or Hidden apps, as a carousel like Home. */
    private void menu(Canvas c) {
        List<String[]> rows = menuRows();
        int n = rows.size();
        String crumb = screen == SETTINGS ? t(R.string.settings) : screen == HIDDEN ? t(R.string.settings) + " / " + t(R.string.hidden_apps)
                : (choiceReturn == PANEL ? cur().label : t(R.string.settings)) + " / " + labelOfChoice();
        text(c, up(crumb), 144, 110, 28 * pfs(), lab, mono, Paint.Align.LEFT, .12f);
        keyHints(c, screen == SETTINGS ? new String[] { "OK", up(t(R.string.hint_change)), "BACK", up(t(R.string.hint_home)) }
                : screen == HIDDEN ? new String[] { "OK", up(t(R.string.hint_show_hide)), "BACK", up(t(R.string.hint_done)) }
                : new String[] { "OK", up(t(R.string.hint_apply)), "BACK", up(t(R.string.hint_cancel)) });
        if (n == 0) return;
        rail(c, n, mfocus);
        float rh = 116 * fs(), x0 = 120, w = 765;
        int base = (int) Math.floor(mpos);
        float frac = mpos - base;
        if (wallOn) rowGlass(c, n, base, frac, 5, rh, 8, x0, w, false, true);
        reticle(c, x0, H / 2f - rh / 2 + 8, x0 + w, H / 2f + rh / 2 - 8, 28, 4, 1, true);
        for (int d = -5; d <= 6; d++) {
            float off = d - frac, dist = Math.abs(off);
            if (n == 2 ? off < -.99f || off > 1.99f : dist >= n / 2f) continue;   // each row once
            float op = floorFade(dist, rh, MENU_FLOOR);   // far rows stay faintly visible
            if (op <= .01f) continue;
            float fw = Math.max(0, 1 - dist), cy = H / 2f + off * rh;
            String[] r = rows.get(wrap(base + d, n));
            // the screensaver rows sit between a "SCREENSAVER ───" header line and a closing line
            boolean first = screen == SETTINGS && "ssmode".equals(r[0]), last = screen == SETTINGS && "sspreview".equals(r[0]);
            if (first || last) {
                float gy = first ? cy - rh / 2 : cy + rh / 2, lx = x0 + 28;
                if (first) {
                    String h = up(t(R.string.screensaver));
                    float hs = 22 * fs();
                    text(c, h, lx, gy + hs * .36f, hs, alpha(lab, op), mono, Paint.Align.LEFT, .12f);
                    lx += textWidth(h, hs, mono, .12f) + 18;
                }
                p.reset();
                p.setColor(alpha(lab, op * .6f));
                c.drawRect(lx, gy - 1, x0 + w - 28, gy + 1, p);
            }
            float sh = 0;
            float sz = 54 * fs(), ly = cy + sz * .35f + sh;
            text(c, fit(r[1], sz, w - 108 - textWidth(up(r[2]), 30 * fs(), mono, .06f), weight(fw), -0.025f), x0 + 28, ly, sz, alpha(lerp(ink2, ink, fw), op), weight(fw), Paint.Align.LEFT, -0.025f);
            value(c, r, x0 + w - 40, cy + 11 * fs() + sh, fw > .5f, 30 * fs(), true, op);
        }
    }

    /** Settings and option lists: rows past the fade stay this faint, so the rest of the list is still hinted. */
    private static final float MENU_FLOOR = .12f;

    /** fade(), with rows past it held at floor until they near the title and key hints (gone by 440 px from the middle). */
    private static float floorFade(float dist, float rh, float floor) {
        return Math.max(fade(dist), floor * Math.max(0, Math.min(1, (440 - dist * rh) / 100)));
    }

    /** Row fade: neighbours stay at 70% (still above 4.5:1), the next ones at 30%, then gone. */
    private static float fade(float dist) { return dist <= 1 ? 1 - .3f * dist : Math.max(0, .7f - .4f * (dist - 1)); }

    private void value(Canvas c, String[] r, float x, float y, boolean on, float size, boolean menu) { value(c, r, x, y, on, size, menu, 1); }

    /** A row's value, right-aligned in mono; accent rows get a colour swatch. */
    private void value(Canvas c, String[] r, float x, float y, boolean on, float size, boolean menu, float op) {
        String v = up(r[2]);
        int sw = -1;
        boolean choice = screen == CHOICE && menu && "accent".equals(choiceKey);
        boolean auto = ("accent".equals(r[0]) && autoAccent()) || (choice && "Auto".equals(r[0]));
        if ("accent".equals(r[0])) sw = accentIndex();
        else if (choice) sw = Arrays.asList(ACCENT_NAMES).indexOf(r[0]);
        if (sw >= 0 || auto) {
            float tw = v.isEmpty() ? 0 : textWidth(v, size, mono, .08f) + 16, l = x - tw - size, t = y - size * .8f, b = y + size * .2f;
            p.reset();
            if (auto) {   // Auto: half black, half white
                p.setColor(alpha(0xFF0B0D10, op));
                c.drawRect(l, t, l + size / 2, b, p);
                p.setColor(alpha(0xFFF2F5F7, op));
                c.drawRect(l + size / 2, t, x - tw, b, p);
            } else {
                p.setColor(alpha(acc == ACCENTS_DAY[accentIndex()] ? ACCENTS_DAY[sw] : ACCENTS[sw], op));
                c.drawRect(l, t, x - tw, b, p);
            }
        }
        text(c, v, x, y, size, alpha(on ? accLn : ink2, op), mono, Paint.Align.RIGHT, .06f);
    }

    /** Key hints as small key caps, in ink2; they fade out after 5 s without a key press. */
    private void keyHints(Canvas c, String[] kv) {
        long idle = SystemClock.uptimeMillis() - lastKey;
        float op = idle < 5000 ? 1 : Math.max(0, 1 - (idle - 5000) / 800f);
        if (op <= 0) return;
        postInvalidateDelayed(idle < 5000 ? 5000 - idle : 32);
        float x = 144, y = 1004, sz = 28 * pfs();
        for (int i = 0; i < kv.length; i += 2) {
            float kw = textWidth(kv[i], sz * .8f, mono, .04f) + 24;
            p.reset();
            p.setAntiAlias(true);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setColor(alpha(ink2, op));
            rf.set(x, y - sz * .95f, x + kw, y + sz * .3f);
            c.drawRoundRect(rf, 6, 6, p);
            text(c, kv[i], x + 12, y - sz * .05f, sz * .8f, alpha(ink2, op), mono, Paint.Align.LEFT, .04f);
            x += kw + 14;
            text(c, kv[i + 1], x, y, sz, alpha(ink2, op), mono, Paint.Align.LEFT, .06f);
            x += textWidth(kv[i + 1], sz, mono, .06f) + 48;
        }
    }

    private String labelOfChoice() {
        switch (choiceKey) {
            case "style": return t(R.string.icon_style);
            case "layout": return t(R.string.layout);
            case "size": return t(R.string.icon_size);
            case "font": return t(R.string.font_size);
            case "mode": return t(R.string.mode);
            case "accent": return t(R.string.accent);
            case "order": return t(R.string.app_order);
            case "clock": return t(R.string.clock);
            case "artsrc": return t(R.string.source);
            case "artera": return t(R.string.era);
            case "artrate": return t(R.string.refresh);
            case "wallrate": return t(R.string.wall_refresh);
            case "defhome": return t(R.string.default_home);
            case "idle": return t(R.string.idle_art);
            case "ssmode": return t(R.string.screensaver);
            case "ssspeed": return t(R.string.ss_speed);
            default: return choiceKey;
        }
    }

    // ------------------------------------------------------------------ art mode
    private float artPrevZoom = 1, artLastZoom = 1;
    /** Set when this view is the screensaver: called instead of showing the offline ring. */
    private Runnable onArtOffline;
    private boolean dreaming;

    /** Screensaver use: Art full screen; when no picture can be fetched, offline replaces this view (the clock). */
    void dream(Runnable offline) {
        dreaming = true;
        onArtOffline = offline;
        startArt(false);
    }

    /**
     * Default Home: "nullofy" (Nullofy takes the Home button; its HomeService steps in on firmware that ignores
     * Android's setting, as this TCL does) or "tv" (the TV's own home: HomeService stands aside). Saved as "defhome".
     */
    private boolean homeIsNullofy() { return !"tv".equals(pref("defhome", "nullofy")); }

    /** After a Default Home choice, Android's own Home setting is brought in line where it would still disagree. */
    private void homeChosen() {
        boolean held = Apps.holdsHomeRole(getContext());
        if (homeIsNullofy() ? held : !held) return;
        stayInSettings = true;
        if (homeIsNullofy()) askDefaultHome();
        else start(new Intent(Settings.ACTION_HOME_SETTINGS));   // Android still names Nullofy: pick the TV's home there
    }

    /** Android's own Home setting: the Home role request (Android 10+), else the TV's home picker. */
    private void askDefaultHome() {
        Context c = getContext();
        if (android.os.Build.VERSION.SDK_INT >= 29 && c instanceof android.app.Activity) {
            android.app.role.RoleManager rm = c.getSystemService(android.app.role.RoleManager.class);
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) {
                try { ((android.app.Activity) c).startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), 2); return; }
                catch (RuntimeException ignored) { }
            }
        }
        start(new Intent(Settings.ACTION_HOME_SETTINGS));
    }

    void startArt(boolean testOffline) {
        forceOffline = testOffline;
        if (screen != ART) openArt();
    }

    /** Which pictures Art is showing now; a picture fetched for other settings is not used. */
    private String artKey() {
        return forceOffline + "|" + offGrid() + "|" + pref("artsrc", "mix") + "|" + pref("artera", "all") + "|" + pref("arttext", "");
    }

    private Art.Piece fetchArt() {
        return forceOffline ? null : art.next(offGrid() ? "text" : pref("artsrc", "mix"), pref("artera", "all"), pref("arttext", ""));
    }

    /** The picture fetched ahead and not shown when Art was left: Art opens with it at once next time (memory only). */
    private static Art.Piece artSpare;
    private static String artSpareKey;
    private volatile boolean artSkip;

    /**
     * Art loop. The next picture is fetched and decoded while the current one is on screen, so Refresh and
     * Right swap at once instead of waiting for the network.
     */
    private void openArt() {
        screen = ART;
        setKeepScreenOn(true);   // overrides the TV's screensaver / display sleep while art is up
        artCur = artPrev = null;
        offline = false;
        artSkip = false;
        String key = artKey();
        Art.Piece warm;
        synchronized (NullofyView.class) {
            warm = key.equals(artSpareKey) ? artSpare : null;
            if (warm == null) recycle(artSpare);
            artSpare = null;
            artSpareKey = null;
        }
        artThread = new Thread(() -> {
            Art.Piece ready = warm;
            while (!Thread.currentThread().isInterrupted()) {
                Art.Piece pc = ready != null ? ready : fetchArt();
                ready = null;
                if (Thread.currentThread().isInterrupted()) { recycle(pc); break; }
                long shownAt = SystemClock.uptimeMillis();
                post(() -> showArt(pc));
                long wait = pc == null ? 30000 : artPeriod();
                if (pc != null) ready = fetchArt();   // the next one loads now, while this one is on screen
                if (Thread.currentThread().isInterrupted()) break;
                synchronized (artLock) {
                    try {
                        long left = wait - (SystemClock.uptimeMillis() - shownAt);
                        if (!artSkip && left > 0) artLock.wait(left);
                    } catch (InterruptedException e) { break; }
                    artSkip = false;
                }
            }
            synchronized (NullofyView.class) {   // keep the unused picture for the next Art
                recycle(artSpare);
                artSpare = ready;
                artSpareKey = key;
            }
        });
        artThread.start();
        invalidate();
    }

    /** A new picture cross-fades in; the one before is wiped. No picture means no network: the waiting wallpapers. */
    private void showArt(Art.Piece pc) {
        if (screen != ART) { recycle(pc); return; }
        if (pc == null && onArtOffline != null) { onArtOffline.run(); return; }   // screensaver: the clock instead
        if (pc == null) {
            recycle(artCur);
            recycle(artPrev);
            artCur = artPrev = null;
            if (!offline) { offline = true; offlineSince = SystemClock.uptimeMillis(); }
            invalidate();
            return;
        }
        offline = false;
        recycle(artPrev);
        artPrev = artCur;
        artPrevZoom = artLastZoom;   // the old picture keeps the zoom it had reached, so nothing jumps
        artLastZoom = 1;
        artCur = pc;
        artShownAt = SystemClock.uptimeMillis();
        ValueAnimator f = ValueAnimator.ofFloat(0, 1);
        f.setDuration(1800);
        f.addUpdateListener(a -> { artFade = (float) a.getAnimatedValue(); invalidate(); });
        f.start();
    }

    private long artPeriod() {
        double m;
        try { m = Double.parseDouble(pref("artrate", "5")); } catch (NumberFormatException e) { m = 5; }
        return (long) (60000 * Math.max(.5, Math.min(60, m)));
    }

    /**
     * Slow zoom: still for most of the picture's time, then a gentle push-in (to 106%) over the last
     * 5–55% (Fibonacci by cadence), at least 6 s so it never looks jumpy. Keeps photos from sitting static.
     */
    private float zoom(long shownAt) {
        if (!zoomOn()) return 1;
        long period = artPeriod();
        int i = Math.max(0, Arrays.asList(ART_RATES).indexOf(pref("artrate", "5")));
        long win = Math.max(6000, (long) (period * ZOOM_SHARE[i]));
        long t = SystemClock.uptimeMillis() - shownAt - (period - win);
        if (t <= 0) { postInvalidateDelayed(-t + 16); return 1; }   // wake up when the zoom window opens
        float k = Math.min(1, t / (float) win);
        postInvalidateOnAnimation();
        return 1 + .06f * k * k * (3 - 2 * k);
    }

    void leaveArt() {
        if (artThread == null) return;
        artThread.interrupt();
        artThread = null;
        setKeepScreenOn(false);
        recycle(artCur);
        recycle(artPrev);
        artCur = artPrev = null;
        offline = forceOffline = false;
        screen = HOME;
        invalidate();
    }

    private static void recycle(Art.Piece pc) { if (pc != null && pc.bmp != null) pc.bmp.recycle(); }

    private void artScreen(Canvas c) {
        if (offline) { waiting(c); return; }
        if (artCur != null && artCur.text != null) {
            c.drawColor(0xFF000000);   // Your Text is always on plain black
            if (artPrev != null && artPrev.text != null && artFade < 1) artText(c, artPrev, 1 - artFade);
            artText(c, artCur, artFade);
            postInvalidateDelayed(500);   // the float moves well under a pixel per frame; twice a second is plenty
            return;
        }
        c.drawColor(0xFF000000);
        if (artCur == null) {
            text(c, up(t(R.string.loading_art)), W / 2f, H / 2f, 28, 0xFF868E96, mono, Paint.Align.CENTER, .12f);
            return;
        }
        boolean fading = artPrev != null && artPrev.bmp != null && artFade < 1;
        // the old picture holds its last zoom; it fades out too when either one is shown entire (on black)
        if (fading) artPiece(c, artPrev, artPrev.contain || artCur.contain ? 1 - artFade : 1, artPrevZoom);
        float z = zoom(artShownAt);
        artLastZoom = z;
        artPiece(c, artCur, artFade, z);
        // soft top and bottom shade so the clock and credit read on any picture
        p.reset();
        p.setDither(true);   // no banding in the dark shades
        p.setShader(new LinearGradient(0, 0, 0, 240, 0x80000000, 0, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, 240, p);
        p.setShader(new LinearGradient(0, H - 340, 0, H, 0, 0xB3000000, Shader.TileMode.CLAMP));
        c.drawRect(0, H - 340, W, H, p);
        p.setShader(null);
        // artist / photographer credit only, never the picture's name
        if (fading) {   // the old credit fades out as the new one fades in
            text(c, fit(artPrev.by, 40, 1500, weight(0), -0.02f), 96, H - 112, 40, alpha(0xFFF2F5F7, 1 - artFade), weight(0), Paint.Align.LEFT, -0.02f);
            text(c, fit(artPrev.credit, 28, 1500, mono, .08f), 96, H - 62, 28, alpha(0xFFB4BBC2, 1 - artFade), mono, Paint.Align.LEFT, .08f);
        }
        text(c, fit(artCur.by, 40, 1500, weight(0), -0.02f), 96, H - 112, 40, alpha(0xFFF2F5F7, artFade), weight(0), Paint.Align.LEFT, -0.02f);
        text(c, fit(artCur.credit, 28, 1500, mono, .08f), 96, H - 62, 28, alpha(0xFFB4BBC2, artFade), mono, Paint.Align.LEFT, .08f);
    }

    /** "Your text": large, centred, at most 4 lines, drifting slightly between refreshes. */
    private void artText(Canvas c, Art.Piece pc, float a) {
        android.text.StaticLayout l = pc.layout;   // fitting the size takes up to 16 layouts: done once per piece
        if (l == null) l = pc.layout = layoutText(pc.text);
        // radial drift: out from the centre and back every 4 min, at most 56 px, each time in a new
        // direction (the angle turns once every 17 min), so it stays centred and never sits still
        double now = SystemClock.uptimeMillis() / 1000.0;
        float rr = 56 * (float) (.5 - .5 * Math.cos(now * 2 * Math.PI / 240));
        double an = now * 2 * Math.PI / 1009;
        float dx = rr * (float) Math.cos(an), dy = rr * (float) Math.sin(an);
        float lo = breathing() ? .5f : 1;   // breathing: brightness eases down to 50% and back each minute
        float b = 1 - (1 - lo) * (float) (.5 - .5 * Math.cos(now * 2 * Math.PI / 60));
        float top = (H - l.getHeight()) / 2f + dy;
        t2(l, a * b);
        c.save();
        c.translate((W - 1500) / 2f + dx, top);
        l.draw(c);
        c.restore();
        p.reset();
        p.setColor(alpha(accLn, a * b));
        c.drawRect(W / 2f - 24 + dx, top + l.getHeight() + 36, W / 2f + 24 + dx, top + l.getHeight() + 39, p);
    }

    private android.text.StaticLayout layoutText(String text) {
        android.text.TextPaint t = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        t.setTypeface(weight(0));
        t.setColor(0xFFF2F5F7);
        t.setLetterSpacing(-0.02f);
        android.text.StaticLayout l = null;
        for (float size = 150; size >= 56; size -= 6) {
            t.setTextSize(size);
            l = android.text.StaticLayout.Builder.obtain(text, 0, text.length(), t, 1500)
                    .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).setLineSpacing(0, 1.05f).build();
            if (l.getLineCount() <= 4 && l.getHeight() <= 640) break;
        }
        return l;
    }

    private void t2(android.text.StaticLayout l, float a) { l.getPaint().setColor(alpha(0xFFF2F5F7, a)); }

    /** Photos fill the screen; paintings and the whole Earth are shown entire, on black, with a margin. */
    private void artPiece(Canvas c, Art.Piece pc, float a, float z) {
        float bw = pc.bmp.getWidth(), bh = pc.bmp.getHeight();
        float s = (pc.contain ? Math.min((W - 160) / bw, (H - 260) / bh) : Math.max(W / bw, H / bh)) * (pc.contain ? 1 + (z - 1) * .6f : z);
        float dw = bw * s, dh = bh * s, top = pc.contain ? (H - 40 - dh) / 2 : (H - dh) / 2;
        p.reset();
        p.setFilterBitmap(true);
        p.setAlpha((int) (255 * a));
        rf.set((W - dw) / 2, top, (W + dw) / 2, top + dh);
        c.drawBitmap(pc.bmp, null, rf, p);
    }

    /**
     * No internet: the Nullofy ring with a dot stepping round it (top, left, bottom, right: four frames,
     * like an old loading sign), while Nullofy quietly retries every 30 s.
     */
    private void waiting(Canvas c) {
        background(c);
        long t = SystemClock.uptimeMillis() - offlineSince;
        float cx = W / 2f, cy = H / 2f - 60, r = 110;
        p.reset();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(5);
        p.setColor(ink);
        c.drawCircle(cx, cy, r, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(accLn);
        int f = (int) (t / 700) % 4;            // top, left, bottom, right
        float[][] at = { { 0, -1 }, { -1, 0 }, { 0, 1 }, { 1, 0 } };
        c.drawCircle(cx + at[f][0] * r, cy + at[f][1] * r, 14, p);
        text(c, up(t(R.string.waiting_net)), cx, cy + r + 110, 28, ink2, mono, Paint.Align.CENTER, .16f);
        postInvalidateDelayed(700 - t % 700);
    }

    // ------------------------------------------------------------------ primitives
    /** Four L-shaped corner marks, optionally with a faint accent fill: the whole focus language. */
    private void reticle(Canvas c, float l, float t, float r, float b, float len, float w, float op, boolean withFill) {
        p.reset();
        if (wallOn && wall != null) {   // Aesthetic: rounded, softly lit selection to match the glass cards
            float rad = Math.min(28, (b - t) * .2f);
            float flash = op < 1 ? 0 : Math.max(0, 1 - (SystemClock.uptimeMillis() - pressAt) / 180f);
            if (flash > 0) postInvalidateOnAnimation();
            rf.set(l, t, r, b);
            p.setAntiAlias(true);
            p.setColor(alpha(acc, op * (.22f + .3f * flash)));
            c.drawRoundRect(rf, rad, rad, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(w);
            p.setColor(alpha(accLn, op));
            rf.inset(w / 2, w / 2);
            c.drawRoundRect(rf, rad, rad, p);
            return;
        }
        float flash = op < 1 ? 0 : 1 - (SystemClock.uptimeMillis() - pressAt) / 180f;
        if (flash > 0) {
            p.setColor(alpha(acc, .28f * flash));
            c.drawRect(l, t, r, b, p);
            postInvalidateOnAnimation();
        } else if (withFill) {
            p.setColor(alpha(fill, op));
            c.drawRect(l, t, r, b, p);
        }
        p.setColor(alpha(accLn, op));
        c.drawRect(l, t, l + len, t + w, p);
        c.drawRect(l, t, l + w, t + len, p);
        c.drawRect(r - len, t, r, t + w, p);
        c.drawRect(r - w, t, r, t + len, p);
        c.drawRect(l, b - w, l + len, b, p);
        c.drawRect(l, b - len, l + w, b, p);
        c.drawRect(r - len, b - w, r, b, p);
        c.drawRect(r - w, b - len, r, b, p);
    }

    private void text(Canvas c, String s, float x, float y, float size, int color, Typeface tf, Paint.Align al, float spacing) {
        tp.setTypeface(tf);
        tp.setTextSize(size);
        tp.setColor(color);
        tp.setTextAlign(al);
        tp.setLetterSpacing(spacing);
        if (wallOn && wall != null && screen != ART) {   // Aesthetic: soft shadow so text reads on any picture (a light glow under Auto's black text)
            float a = Color.alpha(color) / 255f;
            tp.setShadowLayer(10, 0, 2, alpha(lightWall() ? 0xB3FFFFFF : 0xB3000000, a * a));
            c.drawText(s, x, y, tp);
            tp.clearShadowLayer();
            return;
        }
        c.drawText(s, x, y, tp);
    }

    /** 0 = Regular 400, 1 = Medium 500. */
    private Typeface weight(float t) {
        if (fs() > 1) t = .5f + .5f * t;   // Large sizes also start from a slightly heavier weight
        return weights[Math.round(Math.max(0, Math.min(1, t)) * (weights.length - 1))];
    }

    private float textWidth(String s, float size, Typeface tf, float spacing) {
        tp.setTypeface(tf);
        tp.setTextSize(size);
        tp.setLetterSpacing(spacing);
        return tp.measureText(s);
    }

    private String fit(String s, float size, float max, Typeface tf, float spacing) {
        if (s == null) return "";
        if (textWidth(s, size, tf, spacing) <= max) return s;
        String t = s;
        while (t.length() > 1 && textWidth(t + "…", size, tf, spacing) > max) t = t.substring(0, t.length() - 1);
        return t.trim() + "…";
    }

    private static int lerp(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Official square icon, rounded 20%, cached at screen resolution. Nullofy's own entries get drawn glyphs. */
    private Bitmap iconBitmap(Apps.App a, float size) {
        int px = Math.max(8, Math.round(size * scale));
        if (a.iconBmp != null && a.iconBmpSize == px) return a.iconBmp;
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Path clip = new Path();
        clip.addRoundRect(0, 0, px, px, px * .2f, px * .2f, Path.Direction.CW);
        c.clipPath(clip);
        Drawable ic = a.internal() ? null : repo.icon(a);
        if (ic == null) {
            c.drawColor(0xFF1A1F25);
            glyph(c, a, px / 2f, px / 2f, px);
        } else {
            draw(c, ic, 0, 0, px, px);
        }
        a.iconBmp = b;
        a.iconBmpSize = px;
        return b;
    }

    /** Official 16:9 TV banner; apps without one get a plain tile with their icon and name. */
    private Bitmap tileBitmap(Apps.App a) {
        int h = Math.round(243 * scale), w = Math.round(h * 16 / 9f);
        if (a.tileBmp != null && a.tileBmpH == h) return a.tileBmp;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Path clip = new Path();
        clip.addRoundRect(0, 0, w, h, 6 * scale, 6 * scale, Path.Direction.CW);
        c.clipPath(clip);
        Drawable bn = a.internal() ? null : repo.banner(a);
        if (bn != null) {
            draw(c, bn, 0, 0, w, h);
        } else {
            c.drawColor(0xFF1A1F25);
            Paint e = new Paint(Paint.ANTI_ALIAS_FLAG);
            e.setStyle(Paint.Style.STROKE);
            e.setStrokeWidth(Math.max(1, scale));
            e.setColor(0x1FFFFFFF);
            c.drawRect(0, 0, w, h, e);
            float is = h * .36f;
            Drawable ic = a.internal() ? null : repo.icon(a);
            if (ic == null) glyph(c, a, w / 2f, h * .40f, h * .62f);
            else {   // drawn here, not through iconBitmap(): its cache holds the list's size
                int s = Math.round(is), l = Math.round((w - s) / 2f), t = Math.round(h * .40f - s / 2f);
                c.save();
                Path ip = new Path();
                ip.addRoundRect(l, t, l + s, t + s, s * .2f, s * .2f, Path.Direction.CW);
                c.clipPath(ip);
                draw(c, ic, l, t, l + s, t + s);
                c.restore();
            }
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setTypeface(sans);
            t.setTextSize(h * .12f);
            t.setColor(0xFFF2F5F7);
            t.setTextAlign(Paint.Align.CENTER);
            c.drawText(a.label, w / 2f, h * .80f, t);
        }
        a.tileBmp = b;
        a.tileBmpH = h;
        return b;
    }

    private static void draw(Canvas c, Drawable d, int l, int t, int r, int b) {
        Rect old = d.copyBounds();
        d.setBounds(l, t, r, b);
        d.draw(c);
        d.setBounds(old);
    }

    /** Nullofy's own icons: a framed landscape (Art), a screen with an incoming arrow (Inputs) and three faders (settings). The knobs and sun take the accent. */
    private void glyph(Canvas c, Apps.App a, float cx, float cy, float size) {
        boolean art = Apps.ART.equals(a.pkg), inp = Apps.INPUTS.equals(a.pkg);
        int l = Math.round(cx - size / 2), t = Math.round(cy - size / 2), r = Math.round(cx + size / 2), b = Math.round(cy + size / 2);
        Drawable ink = getContext().getDrawable(art ? R.drawable.art_ink : inp ? R.drawable.inp_ink : R.drawable.set_ink).mutate();
        Drawable dot = getContext().getDrawable(art ? R.drawable.art_accent : inp ? R.drawable.inp_accent : R.drawable.set_accent).mutate();
        ink.setTint(0xFFF2F5F7);
        dot.setTint(ACCENTS[accentIndex()]);   // the bright accent: the tile is always dark
        draw(c, ink, l, t, r, b);
        draw(c, dot, l, t, r, b);
    }
}
