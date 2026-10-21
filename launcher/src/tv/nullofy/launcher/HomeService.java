package tv.nullofy.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fallback Home-button takeover. Android's own Home setting comes first: when Nullofy is the default home
 * app, the Home key is left alone and Android opens Nullofy itself. Some firmware (Google TV on TCL) ignores
 * that setting and opens its stock home anyway; when that is seen right after a Home press, the service
 * remembers it ("homeIgnored") and from then on takes the Home key itself. It also replaces a stock home
 * screen whenever one comes to the front. Off until the user enables it in Accessibility settings.
 */
public class HomeService extends AccessibilityService {
    /** Stock home screens Nullofy replaces: Google TV, older Android TV, Fire TV. */
    private static final java.util.Set<String> STOCK_HOMES = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher", "com.google.android.leanbacklauncher",
            "com.amazon.tv.launcher"));
    private long homePressedAt;

    private SharedPreferences prefs() { return getSharedPreferences("nullofy", Context.MODE_PRIVATE); }

    /** Android delivers Home to Nullofy by itself: it is the default home and the firmware has not been seen ignoring that. */
    private boolean androidHandlesHome() { return Apps.isDefaultHome(this) && !prefs().getBoolean("homeIgnored", false); }

    /** Default Home set to the TV's own home in Nullofy's Settings: the service stands aside entirely. */
    private boolean tvHome() { return "tv".equals(prefs().getString("defhome", "nullofy")); }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        Watchdog.install(this);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (Watchdog.paused(this) || tvHome()) return;   // crashed 3 times in 5 minutes: the stock home stays until Nullofy is opened
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || !STOCK_HOMES.contains(String.valueOf(e.getPackageName()))) return;
        if (Apps.isDefaultHome(this) && SystemClock.uptimeMillis() - homePressedAt < 3000)
            prefs().edit().putBoolean("homeIgnored", true).apply();   // this firmware ignores the Home setting: take the key from now on
        openHome();
    }

    @Override protected boolean onKeyEvent(KeyEvent k) {
        if (k.getKeyCode() != KeyEvent.KEYCODE_HOME || Watchdog.paused(this) || tvHome()) return false;
        if (k.getAction() == KeyEvent.ACTION_DOWN) homePressedAt = SystemClock.uptimeMillis();
        if (NullofyDream.on()) {   // screensaver on: wake straight to Home (both halves of the press are taken)
            if (k.getAction() == KeyEvent.ACTION_UP && NullofyDream.end()) openHome();
            return true;
        }
        if (androidHandlesHome()) return false;   // the lighter path: Android opens Nullofy
        if (k.getAction() == KeyEvent.ACTION_UP) openHome();
        return true;
    }

    private void openHome() {
        Intent i = new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    @Override public void onInterrupt() { }
}
