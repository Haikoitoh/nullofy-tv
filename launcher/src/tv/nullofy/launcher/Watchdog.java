package tv.nullofy.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Crash watchdog. Every crash in Nullofy's process is counted (times only, in the private "nullofy" prefs);
 * after 3 crashes within 5 minutes HomeService stops taking the Home key and stops replacing the stock home
 * screen, so a broken build cannot trap the TV in a crash loop. The pause ends when Nullofy is opened again
 * (from the TV's app list, or by Android's own Home setting): HomeActivity calls opened().
 */
final class Watchdog {
    private static final int CRASHES = 3;
    private static final long WINDOW = 5 * 60000L;
    private static boolean installed;

    private Watchdog() { }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("nullofy", Context.MODE_PRIVATE); }

    /** Counts this process's crashes; safe to call from every entry point (once per process takes effect). */
    static synchronized void install(Context c) {
        if (installed) return;
        installed = true;
        Context app = c.getApplicationContext();
        Thread.UncaughtExceptionHandler next = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try { crashed(app); } catch (Throwable ignored) { }
            if (next != null) next.uncaughtException(t, e);
            else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10); }
        });
    }

    /** Records a crash; the 3rd within 5 minutes pauses the Home takeover. commit(): the process is about to die. */
    private static void crashed(Context c) {
        long now = System.currentTimeMillis();
        StringBuilder keep = new StringBuilder();
        int recent = 1;
        for (String s : prefs(c).getString("crashes", "").split(",")) {
            long t;
            try { t = Long.parseLong(s); } catch (NumberFormatException e) { continue; }
            if (now - t < WINDOW && t <= now) { recent++; keep.append(t).append(','); }
        }
        keep.append(now);
        SharedPreferences.Editor e = prefs(c).edit().putString("crashes", keep.toString());
        if (recent >= CRASHES) e.putBoolean("homePaused", true);
        e.commit();
    }

    /** True while the Home takeover is paused after repeated crashes. */
    static boolean paused(Context c) { return prefs(c).getBoolean("homePaused", false); }

    /** Nullofy was opened and is running: the Home takeover comes back (recent crash times are kept). */
    static void opened(Context c) {
        if (paused(c)) prefs(c).edit().putBoolean("homePaused", false).apply();
    }
}
