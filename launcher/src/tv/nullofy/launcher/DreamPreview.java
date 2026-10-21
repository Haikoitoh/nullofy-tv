package tv.nullofy.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/** Shows the screensaver as an ordinary screen (Nullofy settings → Preview screensaver); any key closes it. */
public class DreamPreview extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Watchdog.install(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        NullofyDream.show(this, v -> {
            setContentView(v);
            v.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        });
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) { finish(); return true; }
}
