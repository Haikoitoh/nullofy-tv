package tv.nullofy.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Installed apps, their order, hidden apps, and Watch Next rows. */
final class Apps {
    static final String SETTINGS = "nullofy:settings", ART = "nullofy:art", INPUTS = "nullofy:inputs";

    static final class App {
        String pkg, label;
        ActivityInfo info;     // icon and banner are loaded from it only while a render is made, then dropped
        int rank;              // 1 = opened most often from Nullofy
        boolean system;        // preinstalled: no Uninstall
        boolean settingsApp;   // the TV's own Settings: never "Remove from Home"
        Bitmap iconBmp, tileBmp;
        int iconBmpSize, tileBmpH;
        long updated;          // the package's lastUpdateTime when its icon and banner were loaded
        boolean internal() { return pkg.startsWith("nullofy:"); }
    }

    static final class Resume {
        String title, meta, intentUri, poster, pkg;
        long when;
        float progress;
        volatile Bitmap art;
    }

    private final Context ctx;
    final SharedPreferences prefs;
    /** Apps from the last read: Home comes back often, so an unchanged app keeps its icon, banner and renders. */
    private Map<String, App> last = new LinkedHashMap<>();

    Apps(Context ctx) {
        this.ctx = ctx;
        prefs = ctx.getSharedPreferences("nullofy", Context.MODE_PRIVATE);
    }

    /** Every launchable app (TV entries first), plus Nullofy's own Art and Settings entries. */
    List<App> all() {
        PackageManager pm = ctx.getPackageManager();
        Map<String, App> map = new LinkedHashMap<>();
        ResolveInfo st = pm.resolveActivity(new Intent(android.provider.Settings.ACTION_SETTINGS), 0);
        String settingsPkg = st == null ? "" : st.activityInfo.packageName;
        for (String cat : new String[] { Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER }) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                ActivityInfo ai = ri.activityInfo;
                if (ai.packageName.equals(ctx.getPackageName()) || map.containsKey(ai.packageName)) continue;
                long upd = updated(pm, ai.packageName);
                App a = last.get(ai.packageName);
                boolean same = a != null && upd != 0 && a.updated == upd;
                if (!same) a = new App();
                a.pkg = ai.packageName;
                a.updated = upd;
                a.label = String.valueOf(ri.loadLabel(pm)).trim();
                if (a.label.isEmpty()) a.label = String.valueOf(ai.applicationInfo.loadLabel(pm)).trim();
                if (a.label.isEmpty()) a.label = a.pkg.substring(a.pkg.lastIndexOf('.') + 1);
                a.settingsApp = a.pkg.equals(settingsPkg) || a.pkg.startsWith("com.android.tv.settings");
                a.system = (ai.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
                a.info = ai;
                map.put(a.pkg, a);
            }
        }
        if (!inputs().isEmpty()) {
            App in = new App();
            in.pkg = INPUTS;
            in.label = ctx.getString(R.string.inputs);
            in.system = true;
            map.put(in.pkg, in);
        }
        App art = new App();
        art.pkg = ART;
        art.label = ctx.getString(R.string.art);
        art.system = true;
        map.put(art.pkg, art);
        App s = new App();
        s.pkg = SETTINGS;
        s.label = ctx.getString(R.string.nullofy_settings);
        s.system = true;
        map.put(s.pkg, s);
        last = map;
        return new ArrayList<>(map.values());
    }

    /** The app's icon, loaded now and not kept: the launcher keeps only its own small render of it. */
    Drawable icon(App a) {
        if (a.info == null) return null;
        try { return a.info.loadIcon(ctx.getPackageManager()); } catch (Exception e) { return null; }
    }

    /** The app's 16:9 TV banner (activity first, then application), or null. Not kept, like icon(). */
    Drawable banner(App a) {
        if (a.info == null) return null;
        try {
            PackageManager pm = ctx.getPackageManager();
            Drawable d = a.info.loadBanner(pm);
            return d != null ? d : a.info.applicationInfo.loadBanner(pm);
        } catch (Exception e) {
            return null;
        }
    }

    private static long updated(PackageManager pm, String pkg) {
        try { return pm.getPackageInfo(pkg, 0).lastUpdateTime; } catch (Exception e) { return 0; }
    }

    /** A TV input: an HDMI port (named after the device on it, when HDMI-CEC reports one) or the TV tuner. */
    static final class Input {
        String id, label, detail, port;
        boolean tuner;
        /** TvInputManager.INPUT_STATE_* of the port, or -1 when unknown (the tuner). */
        int state = -1;
    }

    /**
     * The TV's hardware inputs, from TvInputManager (no permission needed). Software inputs such as AirPlay
     * are left out; an HDMI-CEC device takes its port's place and shows the port as its detail.
     */
    List<Input> inputs() {
        List<Input> out = new ArrayList<>();
        TvInputManager tm = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tm == null) return out;
        List<TvInputInfo> all;
        try { all = tm.getTvInputList(); } catch (RuntimeException e) { return out; }
        Map<String, TvInputInfo> child = new LinkedHashMap<>();
        for (TvInputInfo t : all) if (t.getParentId() != null && t.isPassthroughInput()) child.put(t.getParentId(), t);
        for (TvInputInfo t : all) {
            boolean hw = t.getId().matches(".*/HW\\d+$") || t.getType() == TvInputInfo.TYPE_HDMI;
            if (t.getParentId() != null || !(t.isPassthroughInput() || hw)) continue;
            Input in = new Input();
            TvInputInfo dev = child.get(t.getId());
            String port = label(t);
            in.id = (dev != null ? dev : t).getId();
            in.tuner = !t.isPassthroughInput();
            in.label = dev != null ? label(dev) : in.tuner ? ctx.getString(R.string.live_tv) : port;
            in.detail = dev != null ? port : "";
            in.port = port;
            if (!in.tuner) {
                // a device on the port (HDMI-CEC) reports its own state; a bare port's "standby" is left blank,
                // since some TVs (TCL) report standby for every port, plugged in or not
                try { in.state = tm.getInputState((dev != null ? dev : t).getId()); } catch (RuntimeException ignored) { }
                if (dev == null && in.state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY) in.state = -1;
            }
            out.add(in);
        }
        Collections.sort(out, (x, y) -> x.tuner != y.tuner ? (x.tuner ? -1 : 1) : x.port.compareToIgnoreCase(y.port));   // Live TV, then HDMI 1, 2 …
        return out;
    }

    private String label(TvInputInfo t) {
        CharSequence c = t.loadCustomLabel(ctx);
        if (c == null || c.toString().trim().isEmpty()) c = t.loadLabel(ctx);
        return c == null ? t.getId() : c.toString().trim();
    }

    /** Opens an input in the TV's own player: HDMI by its passthrough channel, the tuner as Live TV. */
    static Intent inputIntent(Input in) {
        return in.tuner
                ? new Intent(Intent.ACTION_VIEW).setDataAndType(TvContract.Channels.CONTENT_URI, "vnd.android.cursor.dir/channel")
                : new Intent(Intent.ACTION_VIEW).setDataAndType(TvContract.buildChannelUriForPassthroughInput(in.id), "vnd.android.cursor.item/channel");
    }

    /** True when Android's own Home setting (the default home app, or the Home role) already points at Nullofy. */
    /** Amazon Fire TV (Fire OS): Amazon keeps its own home screen, so the Home setting is not offered there. */
    static boolean fireTv(Context c) { return c.getPackageManager().hasSystemFeature("amazon.hardware.fire_tv"); }

    /** The TV has the Watch Next provider (Android TV 8+; not confirmed on Fire OS). Without it the panel shows no Continue Watching. */
    static boolean hasWatchNext(Context c) {
        try { return c.getPackageManager().resolveContentProvider("android.media.tv", 0) != null; } catch (RuntimeException e) { return false; }
    }

    static boolean isDefaultHome(Context c) {
        ResolveInfo r = c.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        return r != null && r.activityInfo != null && c.getPackageName().equals(r.activityInfo.packageName);
    }

    /**
     * Android's Home setting names Nullofy (the Home role on Android 10+). Not the same as isDefaultHome: some
     * firmware (this TCL) keeps opening its stock home even then.
     */
    static boolean holdsHomeRole(Context c) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.app.role.RoleManager rm = c.getSystemService(android.app.role.RoleManager.class);
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) return rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME);
        }
        return isDefaultHome(c);
    }

    Set<String> hidden() { return new HashSet<>(prefs.getStringSet("hidden", Collections.<String>emptySet())); }

    void setHidden(String pkg, boolean hide) {
        Set<String> h = hidden();
        if (hide) h.add(pkg); else h.remove(pkg);
        prefs.edit().putStringSet("hidden", h).apply();
    }

    /** The only thing Nullofy remembers about use: how often you opened each app from it. Stays on the TV. */
    void countLaunch(String pkg) { prefs.edit().putInt("n:" + pkg, prefs.getInt("n:" + pkg, 0) + 1).apply(); }

    /**
     * Visible apps in carousel order. "Most used": rank 1 in the middle, rank 2 above, rank 3 below,
     * rank 4 above … so the list reads … r4 r2 [r1] r3 r5 …  homeIndex[0] gets the index of rank 1.
     */
    List<App> ordered(boolean byUse, int[] homeIndex) {
        Set<String> hide = hidden();
        List<App> apps = new ArrayList<>();
        for (App a : all()) if (!hide.contains(a.pkg)) apps.add(a);
        Collections.sort(apps, (x, y) -> {
            int c = byUse ? prefs.getInt("n:" + y.pkg, 0) - prefs.getInt("n:" + x.pkg, 0) : 0;
            return c != 0 ? c : x.label.compareToIgnoreCase(y.label);
        });
        int n = apps.size();
        for (int i = 0; i < n; i++) apps.get(i).rank = i + 1;
        homeIndex[0] = 0;
        if (!byUse || n == 0) return apps;
        App[] seq = new App[n];
        for (int r = 0; r < n; r++) seq[n / 2 + (r % 2 == 1 ? -(r + 1) / 2 : r / 2)] = apps.get(r);
        homeIndex[0] = n / 2;
        List<App> out = new ArrayList<>(n);
        Collections.addAll(out, seq);
        return out;
    }

    Intent launchIntent(App a) {
        PackageManager pm = ctx.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(a.pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(a.pkg);
    }

    /**
     * The app's 3 latest Watch Next rows. Needs READ_TV_LISTINGS (asked once). The TV provider refuses
     * any selection or sort from non-system apps, so all rows are read and filtered here.
     */
    List<Resume> resume(String pkg) {
        List<Resume> out = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(Uri.parse("content://android.media.tv/watch_next_program"), null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (!pkg.equals(str(c, "package_name"))) continue;
                Resume r = new Resume();
                r.when = lng(c, "last_engagement_time_utc_millis");
                r.title = str(c, "title");
                String s = str(c, "season_display_number"), e = str(c, "episode_display_number");
                long pos = lng(c, "last_playback_position_millis"), dur = lng(c, "duration_millis");
                StringBuilder m = new StringBuilder();
                if (s != null) m.append('S').append(s.length() == 1 ? "0" : "").append(s).append(" · ");
                if (e != null) m.append('E').append(e.length() == 1 ? "0" : "").append(e).append(" · ");
                if (dur > pos) m.append((dur - pos) / 60000).append(" MIN LEFT");
                r.meta = m.toString();
                r.progress = dur > 0 ? Math.min(1f, pos / (float) dur) : 0f;
                r.intentUri = str(c, "intent_uri");
                r.poster = str(c, "poster_art_uri");
                r.pkg = pkg;
                if (r.title != null) out.add(r);
            }
        } catch (Exception ignored) { }
        Collections.sort(out, (x, y) -> Long.compare(y.when, x.when));
        return out.size() > 3 ? new ArrayList<>(out.subList(0, 3)) : out;
    }

    /**
     * A Watch Next row's intent, made safe to start: the row is written by another app, so it may only open
     * that app (never Nullofy's own screens or a third app), and it cannot hand out URI permissions.
     * Returns null when the row points anywhere else.
     */
    static Intent resumeIntent(String uri, String pkg) throws java.net.URISyntaxException {
        if (uri == null) return null;
        Intent i = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
        i.setSelector(null);
        i.setFlags(i.getFlags() & ~(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
        if (i.getComponent() != null && !pkg.equals(i.getComponent().getPackageName())) return null;
        if (i.getPackage() != null && !pkg.equals(i.getPackage())) return null;
        if (i.getComponent() == null) i.setPackage(pkg);
        return i;
    }

    /**
     * Poster art, decoded small (about 336 px wide). Runs off the UI thread. The URI comes from another app's
     * Watch Next row, so only https and content/resource URIs are read, with timeouts and a 8 MB limit.
     * content: and android.resource: must belong to pkg, the app that wrote the row: a row cannot make
     * Nullofy read another app's provider with Nullofy's own permissions.
     */
    Bitmap art(String uri, String pkg) {
        if (uri == null || pkg == null) return null;
        Uri u = Uri.parse(uri);
        String scheme = String.valueOf(u.getScheme()).toLowerCase(java.util.Locale.ROOT);
        boolean web = scheme.equals("https");
        if (!web && !scheme.equals("content") && !scheme.equals("android.resource")) return null;   // no file:, no http:
        if (scheme.equals("android.resource") && !pkg.equals(u.getAuthority())) return null;
        if (scheme.equals("content")) {
            android.content.pm.ProviderInfo pi = u.getAuthority() == null ? null : ctx.getPackageManager().resolveContentProvider(u.getAuthority(), 0);
            if (pi == null || !pkg.equals(pi.packageName)) return null;
        }
        java.net.HttpURLConnection http = null;
        try {
            InputStream in;
            if (web) {
                http = (java.net.HttpURLConnection) new URL(uri).openConnection();
                http.setConnectTimeout(10000);
                http.setReadTimeout(15000);
                in = http.getInputStream();
            } else {
                in = ctx.getContentResolver().openInputStream(Uri.parse(uri));
            }
            byte[] data;
            try (InputStream close = in) { data = readAll(in, 8 << 20); }
            if (data == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int s = Math.max(1, Integer.highestOneBit(Math.max(1, o.outWidth / 336)));
            while ((long) (o.outWidth / s) * (o.outHeight / s) > 1920L * 1080) s *= 2;   // very tall images too
            o.inSampleSize = s;
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            return null;
        } finally {
            if (http != null) http.disconnect();
        }
    }

    /** The whole stream, or null when it is larger than max. */
    private static byte[] readAll(InputStream in, int max) throws java.io.IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) {
            b.write(buf, 0, n);
            if (b.size() > max) return null;
        }
        return b.toByteArray();
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    private static long lng(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }
}
