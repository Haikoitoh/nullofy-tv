package tv.nullofy.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

public class HomeActivity extends Activity {
    private NullofyView view;
    /** Nullofy has stayed up 10 s: a Home takeover paused by the crash watchdog comes back. */
    private final Runnable stable = () -> Watchdog.opened(this);

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Watchdog.install(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        view = new NullofyView(this);
        setContentView(view);
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        // Watch Next rows of other apps (Android 11+); asked once
        String tv = "android.permission.READ_TV_LISTINGS";
        if (android.os.Build.VERSION.SDK_INT >= 30   // the permission only exists from Android 11; older TVs read Watch Next without it
                && checkSelfPermission(tv) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(new String[] { tv }, 1);
    }

    @Override protected void onResume() {
        super.onResume();
        view.reload(true);   // back on Home: re-read apps, re-rank, re-centre
        view.requestFocus();
        view.removeCallbacks(stable);
        view.postDelayed(stable, 10000);
        // am start -n tv.nullofy.launcher/.HomeActivity --ez art true   (--ez art_offline true: test the no-internet wallpapers)
        // (no extra carries text: any app can start Home, so none may put its own words on the screen)
        Intent i = getIntent();
        if (i.getBooleanExtra("art", false) || i.getBooleanExtra("art_offline", false)) {
            view.startArt(i.getBooleanExtra("art_offline", false));
            i.removeExtra("art");
            i.removeExtra("art_offline");
        }
    }

    @Override protected void onPause() {
        view.removeCallbacks(stable);
        super.onPause();
    }

    @Override protected void onStop() {
        view.leaveArt();     // no fetching in the background
        // HomeService keeps this process in the foreground, so Android never sends a "hidden" trim: do it here
        view.trim(TRIM_MEMORY_UI_HIDDEN);
        super.onStop();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        view.goHome();       // Home pressed while already here
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        view.trim(level);
    }

    @Override protected void onDestroy() {
        view.stop();
        super.onDestroy();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        return view.key(code, e) || super.onKeyDown(code, e);
    }

    @Override public void onBackPressed() {
        view.back();         // never leave Home with Back
    }
}
