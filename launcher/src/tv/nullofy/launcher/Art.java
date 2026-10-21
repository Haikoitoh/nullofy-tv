package tv.nullofy.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Art mode pictures: one random image at a time, only from sources whose pictures are explicitly free to use,
 * fetched through their official public APIs (no keys, no scraping). The sources, their API endpoints and the
 * allowed image hosts live in assets/sources.json, so a source is added or removed there, not here. Every
 * picture is picked by a random number (a SecureRandom offset, page or index into the API's results), never by
 * an image name. Art pictures are never written to disk: each lives in memory while it is shown and is recycled
 * after. The only files are the three small offline pictures (saveOffline), fetched once and kept in private storage.
 * Call off the UI thread; safe to use from two threads at once (Art mode and the Aesthetic background).
 *   NASA Image Library and NASA EPIC: NASA's own work, US public domain (third-party credits are skipped)
 *   Wikimedia Commons: free licences only (CC BY / CC BY-SA / public domain); author and licence shown
 *   The Met and the Art Institute of Chicago: public-domain works, images released as CC0
 */
final class Art {
    static final int TEXT_MAX = 40;
    /** Decoded pictures never exceed this many pixels (a 4K frame), whatever the server sends. */
    private static final long MAX_PIXELS = 3840L * 2160;
    /** Photos shown full screen must be close to 16:9: at least 3:2 wide (4:3 and portrait would crop badly). */
    private static final float MIN_RATIO = 1.45f;

    /** Source ids and labels for the menus: "mix" first, then sources.json in order, then "text" (Your Text). */
    final String[] sources, labels;
    /** Painting eras: "all" first, then the eras of the "paintings" source. */
    final String[] eras, eraLabels;

    private final Map<String, JSONObject> byId = new ConcurrentHashMap<>();
    private final List<String> mix = new ArrayList<>(), aestheticMix = new ArrayList<>();
    private final JSONObject apis;
    private final Set<String> imageHosts = new HashSet<>();
    private final SecureRandom rnd = new SecureRandom();
    private final Map<String, Integer> totals = new ConcurrentHashMap<>();
    private volatile JSONArray epicDays;
    private final Map<String, String> eraName = new ConcurrentHashMap<>();
    private final String unknownAuthor;
    private final java.io.File offlineDir;
    private final JSONArray offline;
    private volatile boolean offlineSaved;
    /** Safe Pictures: skip pictures whose metadata names nudity (sources.json "safe"); on unless turned off in Settings. */
    volatile boolean safe = true;
    private final java.util.regex.Pattern unsafe;
    /** Never shown, whatever the settings: animals killing or eating animals, carcasses, blood (sources.json "never"). */
    private final java.util.regex.Pattern never;
    private final Set<String> skipPainters = new HashSet<>();

    /** One case-insensitive pattern matching any of the words or phrases, as whole words; null for an empty list. */
    private static java.util.regex.Pattern words(JSONArray words) {
        StringBuilder rx = new StringBuilder();
        for (int i = 0; words != null && i < words.length(); i++) {
            if (words.optString(i).trim().isEmpty()) continue;
            rx.append(rx.length() == 0 ? "" : "|").append(java.util.regex.Pattern.quote(words.optString(i).trim().toLowerCase(Locale.ROOT)));
        }
        // whole words only ("venus" but not "venusberg"), in any case and with accents as written
        return rx.length() == 0 ? null : java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])(" + rx + ")(?![\\p{L}\\p{N}])",
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
    }

    /**
     * A label in the TV's language: the string resource name (src_space, era_baroque …) when a translation
     * exists, otherwise the label written in sources.json. A new source works without any translation.
     */
    private static String tr(Context c, String res, String fallback) {
        int id = c.getResources().getIdentifier(res.replaceAll("[^a-z0-9_]", "_"), "string", c.getPackageName());
        return id != 0 ? c.getString(id) : fallback;
    }

    Art(Context c) {
        JSONObject cfg;
        try (InputStream in = c.getAssets().open("sources.json")) {
            cfg = new JSONObject(new String(readAll(in, 1 << 20), "UTF-8"));
        } catch (Exception e) {
            cfg = new JSONObject();   // a broken file leaves only Your Text
        }
        apis = cfg.optJSONObject("apis") != null ? cfg.optJSONObject("apis") : new JSONObject();
        offlineDir = new java.io.File(c.getFilesDir(), "offline");
        offline = cfg.optJSONArray("offline") != null ? cfg.optJSONArray("offline") : new JSONArray();
        JSONArray hosts = cfg.optJSONArray("imageHosts");
        for (int i = 0; hosts != null && i < hosts.length(); i++) imageHosts.add(hosts.optString(i).toLowerCase(Locale.ROOT));
        JSONObject sf = cfg.optJSONObject("safe");
        JSONArray skip = sf == null ? null : sf.optJSONArray("skipPainters");
        unsafe = words(sf == null ? null : sf.optJSONArray("words"));
        never = words(cfg.optJSONObject("never") == null ? null : cfg.optJSONObject("never").optJSONArray("words"));
        for (int i = 0; skip != null && i < skip.length(); i++) skipPainters.add(skip.optString(i).toLowerCase(Locale.ROOT));

        List<String> ids = new ArrayList<>(), names = new ArrayList<>(), eIds = new ArrayList<>(), eNames = new ArrayList<>();
        unknownAuthor = c.getString(R.string.unknown_author);
        ids.add("mix"); names.add(c.getString(R.string.src_mix));
        eIds.add("all"); eNames.add(c.getString(R.string.era_all));
        JSONArray list = cfg.optJSONArray("sources");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject s = list.optJSONObject(i);
            if (s == null) continue;
            String id = s.optString("id"), api = s.optString("api");
            if (id.isEmpty() || id.equals("mix") || id.equals("text") || byId.containsKey(id)) continue;
            if (!api.equals("paintings") && !isHttps(apis.optString(api))) continue;   // unknown or non-https API: skipped
            byId.put(id, s);
            ids.add(id);
            names.add(tr(c, "src_" + id, s.optString("label", id)));
            if (s.optBoolean("mix")) mix.add(id);
            if (s.optBoolean("mix") && s.optBoolean("aesthetic", true)) aestheticMix.add(id);
            JSONArray er = s.optJSONArray("eras");
            for (int k = 0; api.equals("paintings") && er != null && k < er.length(); k++) {
                JSONObject e = er.optJSONObject(k);
                if (e == null || e.optString("id").isEmpty()) continue;
                String eid = e.optString("id"), name = tr(c, "era_" + eid, e.optString("label", eid));
                eIds.add(eid);
                eNames.add(name);
                eraName.put(eid, name);
            }
        }
        ids.add("text"); names.add(c.getString(R.string.your_text));
        sources = ids.toArray(new String[0]);
        labels = names.toArray(new String[0]);
        eras = eIds.toArray(new String[0]);
        eraLabels = eNames.toArray(new String[0]);
    }

    static final class Piece {
        Bitmap bmp;            // null for "Your text"
        String by, credit, text;
        boolean contain;       // paintings and the whole Earth: shown entire, on black
        boolean bundled;       // one of the offline pictures (saved on the TV, not fetched now)
        android.text.StaticLayout layout;   // Your Text, laid out once (not on every frame)
    }

    /** The offline picture of entry o in private storage (a plain file name only, never a path). */
    private java.io.File offlineFile(JSONObject o) {
        String f = o.optString("file");
        return f.matches("[a-z0-9_]+\\.webp") ? new java.io.File(offlineDir, f) : null;
    }

    /**
     * Offline picture i (wraps over the ones already saved), for Aesthetic mode when Off Grid or offline; null
     * when none is saved yet. Never touches the network.
     */
    Piece offline(int i) {
        List<JSONObject> saved = new ArrayList<>();
        for (int k = 0; k < offline.length(); k++) {
            JSONObject o = offline.optJSONObject(k);
            java.io.File f = o == null ? null : offlineFile(o);
            if (f != null && f.isFile()) saved.add(o);
        }
        if (saved.isEmpty()) return null;
        JSONObject o = saved.get(((i % saved.size()) + saved.size()) % saved.size());
        try (InputStream in = new java.io.FileInputStream(offlineFile(o))) {
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inPreferredConfig = Bitmap.Config.RGB_565;
            Piece p = new Piece();
            p.bmp = BitmapFactory.decodeStream(in, null, opt);
            if (p.bmp == null) return null;
            p.bmp.prepareToDraw();
            p.by = o.optString("by");
            p.credit = o.optString("credit");
            p.bundled = true;
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Fetches the offline pictures that are not saved yet (public domain, from an allowed image host), shrinks
     * each to 512x288 and keeps it as WebP in private storage: about 95 KB in all, instead of in the APK.
     * Once all are saved this returns at once. Call off the UI thread, and never when Off Grid is on.
     */
    synchronized void saveOffline() {
        if (offlineSaved) return;
        boolean all = true;
        Set<String> listed = new HashSet<>();
        for (int k = 0; k < offline.length(); k++) {
            JSONObject o = offline.optJSONObject(k);
            if (o != null) listed.add(o.optString("file"));
        }
        java.io.File[] old = offlineDir.listFiles();   // pictures no longer in sources.json are removed
        for (int k = 0; old != null && k < old.length; k++) if (!listed.contains(old[k].getName())) old[k].delete();
        for (int k = 0; k < offline.length(); k++) {
            JSONObject o = offline.optJSONObject(k);
            java.io.File f = o == null ? null : offlineFile(o);
            if (f == null || f.isFile()) continue;
            Bitmap src = null, small = null;
            try {
                src = image(o.optString("url"), false);
                if (src == null) { all = false; continue; }
                small = Bitmap.createBitmap(512, 288, Bitmap.Config.ARGB_8888);
                // 3% trimmed from every edge first (some museum photos have a dark border), then a cover crop
                float sw = src.getWidth() * .94f, sh = src.getHeight() * .94f;
                float s = Math.max(512f / sw, 288f / sh);
                float w = 512 / s, h = 288 / s, x = (src.getWidth() - w) / 2, y = (src.getHeight() - h) / 2;
                new android.graphics.Canvas(small).drawBitmap(src, new android.graphics.Rect((int) x, (int) y, (int) (x + w), (int) (y + h)),
                        new android.graphics.Rect(0, 0, 512, 288), new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
                offlineDir.mkdirs();
                java.io.File tmp = new java.io.File(offlineDir, f.getName() + ".tmp");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                    small.compress(android.os.Build.VERSION.SDK_INT >= 30 ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP, 60, out);
                }
                if (!tmp.renameTo(f)) { tmp.delete(); all = false; }   // whole file or none
            } catch (Exception e) {
                all = false;
            } finally {
                if (src != null) src.recycle();
                if (small != null) small.recycle();
            }
        }
        offlineSaved = all;
    }

    /** Next piece, or null when every attempt failed (no network, or the sources are down). */
    Piece next(String source, String era, String text) { return next(source, era, text, false); }

    /** aesthetic: for the picture behind Home, where Mix leaves out sources marked "aesthetic": false (Earth). */
    Piece next(String source, String era, String text, boolean aesthetic) {
        List<String> mix = aesthetic ? aestheticMix : this.mix;
        if ("text".equals(source)) {
            Piece p = new Piece();
            p.text = text == null || text.trim().isEmpty() ? "nullofy" : text.trim();
            if (p.text.length() > TEXT_MAX) p.text = p.text.substring(0, TEXT_MAX);
            return p;
        }
        if (!byId.containsKey(source)) source = "mix";   // a source removed from sources.json falls back to Mix
        if ("mix".equals(source) && mix.isEmpty()) return null;
        for (int attempt = 0; attempt < 10; attempt++) {   // a few tries: pictures that are not wide enough are passed over
            JSONObject s = byId.get("mix".equals(source) ? mix.get(rnd.nextInt(mix.size())) : source);
            try {
                Piece p;
                switch (s.optString("api")) {
                    case "nasa": p = nasa(pick(s.optJSONArray("terms")), s.optString("credit", "SPACE"), s.optJSONArray("exclude")); break;
                    case "epic": p = epic(s.optString("credit", "EARTH")); break;
                    case "commons": p = commons(s.optString("prefix", "") + pick(s.optJSONArray("terms")), s.optString("credit"), false); break;
                    case "paintings": p = painting(s.optJSONArray("eras"), era); break;
                    default: p = null;
                }
                if (p != null && p.bmp != null) return p;
                if (p != null) recycle(p);
            } catch (Exception ignored) { }
        }
        return null;
    }

    // ---- paintings: one painter of the chosen era, from The Met, the Art Institute of Chicago or Commons
    private Piece painting(JSONArray list, String era) throws Exception {
        if (list == null || list.length() == 0) return null;
        JSONObject e = null;
        for (int i = 0; i < list.length(); i++) if (list.getJSONObject(i).optString("id").equals(era)) e = list.getJSONObject(i);
        if (e == null) e = list.getJSONObject(rnd.nextInt(list.length()));   // "all", or an era no longer listed
        String term = painter(e.optJSONArray("painters")), label = (eraName.containsKey(e.optString("id")) ? eraName.get(e.optString("id")) : e.optString("label")).toUpperCase(Locale.getDefault());
        boolean prints = e.optBoolean("prints");
        switch (pick(e.optJSONArray("apis"))) {
            case "met": return met(term, e.optInt("metDepartment", 11), label);
            case "aic": return aic(term, prints, label);
            case "commons": return commons("deepcat:\"Featured pictures of paintings\" " + term, label, true);
            default: return null;
        }
    }

    /** A random painter of the era; with Safe Pictures, never one of "skipPainters" (unless that leaves none). */
    private String painter(JSONArray all) {
        if (!safe || all == null) return pick(all);
        JSONArray ok = new JSONArray();
        for (int i = 0; i < all.length(); i++) if (!skipPainters.contains(all.optString(i).toLowerCase(Locale.ROOT))) ok.put(all.optString(i));
        return pick(ok.length() > 0 ? ok : all);
    }

    /** Safe Pictures: true when any of these metadata texts names nudity. */
    private boolean unsafe(String... texts) {
        for (String t : texts) {
            if (t == null) continue;
            if (never != null && never.matcher(t).find()) return true;   // always, Safe Pictures on or off
            if (safe && unsafe != null && unsafe.matcher(t).find()) return true;
        }
        return false;
    }

    /** The strings of a JSON list (or of one field of each object in it), joined for unsafe(). */
    private static String join(JSONArray a, String field) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            b.append(o != null ? o.optString(field) : a.optString(i)).append(" · ");
        }
        return b.toString();
    }

    private Piece met(String term, int dept, String label) throws Exception {
        String base = api("met") + "?hasImages=true&isPublicDomain=true&departmentId=" + dept + "&q=" + enc(term);
        int n = total("met:" + dept + term, () -> new JSONObject(get(base + "&limit=1")).getInt("total"));
        if (n == 0) return null;
        JSONArray ids = new JSONObject(get(base + "&limit=1&offset=" + rnd.nextInt(Math.min(n, 1000)))).getJSONArray("objectIDs");
        JSONObject o = new JSONObject(get(api("metObj") + "/" + ids.getLong(0)));   // a number, never a name
        String artist = o.optString("artistDisplayName", "");
        if (!o.optBoolean("isPublicDomain") || !artist.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT))) return null;
        if (unsafe(o.optString("title"), o.optString("objectName"), join(o.optJSONArray("tags"), "term"))) return null;   // tags: "Nudes", "Female Nudes" …
        Piece p = new Piece();
        p.by = artist;
        p.credit = label + " · " + o.optString("objectDate", "") + " · THE MET · CC0";
        p.contain = true;
        p.bmp = image(o.optString("primaryImage"), true);
        if (p.bmp == null) p.bmp = image(o.optString("primaryImageSmall"), true);   // originals over 16 MB
        return p;
    }

    private Piece aic(String term, boolean prints, String label) throws Exception {
        // built as JSON objects, so a painter's name can never break out of the query
        JSONArray must = new JSONArray()
                .put(new JSONObject().put("term", new JSONObject().put("is_public_domain", true)))
                .put(new JSONObject().put("exists", new JSONObject().put("field", "image_id")))
                .put(new JSONObject().put("match_phrase", new JSONObject().put("artist_title", term)));
        if (!prints) must.put(new JSONObject().put("term", new JSONObject().put("artwork_type_id", 1)));
        JSONObject bool = new JSONObject().put("must", must);
        boolean s = safe;
        if (s) {   // Safe Pictures: the search itself leaves out works whose subjects or terms are nudes
            JSONArray not = new JSONArray();
            for (String f : new String[] { "subject_titles", "term_titles", "title" })
                for (String w : new String[] { "nude", "nudes", "nudity" }) not.put(new JSONObject().put("match", new JSONObject().put(f, w)));
            bool.put("must_not", not);
        }
        String q = new JSONObject().put("query", new JSONObject().put("bool", bool)).toString();
        String base = api("aic") + "?limit=1&fields=artist_title,date_display,image_id,title,subject_titles,term_titles&params=" + enc(q);
        int n = total("aic:" + term + prints + s, () -> new JSONObject(get(base)).getJSONObject("pagination").getInt("total"));
        if (n == 0) return null;
        JSONObject it = new JSONObject(get(base + "&page=" + (1 + rnd.nextInt(Math.min(n, 1000))))).getJSONArray("data").getJSONObject(0);
        if (unsafe(it.optString("title"), join(it.optJSONArray("subject_titles"), null), join(it.optJSONArray("term_titles"), null))) return null;
        String id = it.getString("image_id");
        if (!id.matches("[A-Za-z0-9-]{1,64}")) return null;   // an IIIF id only; nothing that could change the URL
        Piece p = new Piece();
        p.by = it.optString("artist_title");
        p.credit = label + " · " + it.optString("date_display", "") + " · ART INSTITUTE OF CHICAGO · CC0";
        p.contain = true;
        p.bmp = image(api("aicImg") + "/" + id + "/full/1686,/0/default.jpg", true);
        return p;
    }

    // ---- NASA Image and Video Library: NASA's own pictures only
    private Piece nasa(String q, String credit, JSONArray exclude) throws Exception {
        boolean title = q.startsWith("title:");   // search NASA's titles only: far fewer office and lab photos
        String base = api("nasa") + "?media_type=image&" + (title ? "title=" + enc(q.substring(6)) : "q=" + enc(q));
        int pages = Math.max(1, Math.min(100, (total("nasa:" + q, () -> new JSONObject(get(base)).getJSONObject("collection")
                .getJSONObject("metadata").getInt("total_hits")) + 99) / 100));
        JSONArray items = new JSONObject(get(base + "&page=" + (1 + rnd.nextInt(pages)))).getJSONObject("collection").getJSONArray("items");
        List<JSONObject> ok = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject d = items.getJSONObject(i).getJSONArray("data").getJSONObject(0);
            String by = d.optString("secondary_creator", ""), name = d.optString("title", "").toLowerCase(Locale.ROOT);
            boolean skip = unsafe(name);
            for (int k = 0; exclude != null && k < exclude.length(); k++) skip |= name.contains(exclude.optString(k).toLowerCase(Locale.ROOT));
            if (!skip && (by.isEmpty() || by.contains("NASA"))) ok.add(items.getJSONObject(i));
        }
        // up to 4 candidates: each one's small thumbnail is measured first, and only a wide one (near 16:9) is fetched large
        JSONObject it = null;
        for (int tries = 0; tries < 4 && !ok.isEmpty() && it == null; tries++) {
            JSONObject c = ok.remove(rnd.nextInt(ok.size()));
            if (ratio(c.getJSONArray("links").getJSONObject(0).getString("href")) >= MIN_RATIO) it = c;
        }
        if (it == null) return null;
        String by = it.getJSONArray("data").getJSONObject(0).optString("secondary_creator", "");
        Piece p = new Piece();
        p.by = by.isEmpty() || by.length() > 60 ? "NASA" : by;
        p.credit = credit + " · NASA · PUBLIC DOMAIN";
        p.bmp = image(it.getJSONArray("links").getJSONObject(0).getString("href").replaceAll("~\\w+\\.jpg$", "~large.jpg"), false);
        return p;
    }

    // ---- NASA EPIC: the whole sunlit Earth from a million miles away, on a random day since 2015
    private Piece epic(String credit) throws Exception {
        JSONArray days = epicDays;
        if (days == null) epicDays = days = new JSONArray(get(api("epic") + "/all"));
        String day = days.getJSONObject(rnd.nextInt(days.length())).getString("date");
        if (!day.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        JSONArray a = new JSONArray(get(api("epic") + "/date/" + day));
        JSONObject it = a.getJSONObject(rnd.nextInt(a.length()));
        String name = it.getString("image");
        if (!name.matches("[A-Za-z0-9_]{1,80}")) return null;
        Piece p = new Piece();
        p.by = "NASA DSCOVR EPIC";
        p.credit = credit + " · " + day + " · PUBLIC DOMAIN";
        p.contain = true;
        p.bmp = image(api("epicImg") + "/" + day.replace('-', '/') + "/jpg/" + name + ".jpg", true);
        return p;
    }

    // ---- Wikimedia Commons search: random hit, 1920 px rendition, author and licence from the file's metadata
    private Piece commons(String q, String label, boolean painting) throws Exception {
        String api = api("commons") + "?format=json&action=query";
        int n = total("commons:" + q, () -> new JSONObject(get(api + "&list=search&srnamespace=6&srlimit=1&srinfo=totalhits&srsearch=" + enc(q)))
                .getJSONObject("query").getJSONObject("searchinfo").getInt("totalhits"));
        if (n == 0) return null;
        // the file's categories too ("Predation", "Birds eating fish", "Nude paintings" …): they are what unsafe() checks
        JSONObject pages = new JSONObject(get(api + "&generator=search&gsrnamespace=6&gsrlimit=1&prop=imageinfo|categories&cllimit=max"
                + "&iiprop=url|size|extmetadata&iiextmetadatafilter=Artist|LicenseShortName|ImageDescription|ObjectName&iiurlwidth=1920&gsroffset="
                + rnd.nextInt(Math.min(n, 9999)) + "&gsrsearch=" + enc(q))).getJSONObject("query").getJSONObject("pages");
        JSONObject page = pages.getJSONObject(pages.keys().next());
        JSONObject info = page.getJSONArray("imageinfo").getJSONObject(0);
        JSONObject em = info.optJSONObject("extmetadata");
        if (unsafe(page.optString("title"), join(page.optJSONArray("categories"), "title"), meta(em, "ImageDescription"), meta(em, "ObjectName"))) return null;
        if (!painting && info.optInt("width") < info.optInt("height") * MIN_RATIO) return null;   // keep photos near 16:9
        JSONObject meta = info.optJSONObject("extmetadata");
        String by = meta(meta, "Artist"), lic = meta(meta, "LicenseShortName");
        Piece p = new Piece();
        p.by = by == null || by.length() > 60 ? unknownAuthor : by;
        p.credit = (label + " · " + (lic == null ? "" : lic + " · ") + "WIKIMEDIA COMMONS").toUpperCase(Locale.ROOT);
        p.contain = painting;
        p.bmp = image(info.getString("thumburl"), painting);
        return p;
    }

    /** Plain text of an extmetadata field (values are HTML, sometimes with hidden Wikidata markup and links). */
    private static String meta(JSONObject m, String key) {
        JSONObject f = m == null ? null : m.optJSONObject(key);
        String v = f == null ? null : f.optString("value", "").replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim();
        if (v == null) return null;
        int q = v.indexOf("label QS:");
        if (q > 0) v = v.substring(0, q);
        v = v.replaceAll("\\(?https?://\\S+\\)?", "").trim();
        return v.isEmpty() ? null : v;
    }

    // ---- plumbing
    private interface Count { int get() throws Exception; }

    private int total(String key, Count c) throws Exception {
        Integer n = totals.get(key);
        if (n == null) totals.put(key, n = Math.max(0, c.get()));
        return n;
    }

    /** A random entry of a JSON list of strings ("" when the list is missing or empty). */
    private String pick(JSONArray a) {
        return a == null || a.length() == 0 ? "" : a.optString(rnd.nextInt(a.length()));
    }

    private String api(String name) throws Exception {
        String u = apis.optString(name);
        if (!isHttps(u)) throw new Exception("no https endpoint for " + name);
        return u;
    }

    private static boolean isHttps(String u) { return u != null && u.startsWith("https://"); }

    /** Only https pictures from the hosts listed in sources.json. */
    private boolean allowedImage(String url) {
        try {
            URL u = new URL(url);
            return "https".equals(u.getProtocol()) && imageHosts.contains(u.getHost().toLowerCase(Locale.ROOT)) && u.getUserInfo() == null;
        } catch (Exception e) {
            return false;
        }
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }

    private static String get(String url) throws Exception {
        byte[] b = bytes(url, 4 << 20, null);
        if (b == null) throw new Exception("response too large");
        return new String(b, "UTF-8");
    }

    /**
     * Whole response in memory (never on disk); https only; null when it is larger than max. hosts: when set,
     * the final URL after any redirect must still be on one of them (a redirect cannot lead off the list).
     */
    private static byte[] bytes(String url, int max, Set<String> hosts) throws Exception {
        if (!isHttps(url)) throw new Exception("https only");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(30000);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(true);   // HttpURLConnection never follows a redirect from https to http
        c.setRequestProperty("User-Agent", "NullofyLauncher/1.0.0 (Android TV; open source)");
        try (InputStream in = c.getInputStream()) {
            URL end = c.getURL();
            if (!"https".equals(end.getProtocol())) return null;
            if (hosts != null && !hosts.contains(end.getHost().toLowerCase(Locale.ROOT))) return null;
            if (c.getContentLength() > max) return null;
            return readAll(in, max);
        }   // no disconnect(): a fully read, closed stream keeps the connection open for the next request (no new TLS handshake)
    }

    private static byte[] readAll(InputStream in, int max) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[32768];
        for (int n; (n = in.read(buf)) > 0; ) {
            b.write(buf, 0, n);
            if (b.size() > max) return null;
        }
        return b.toByteArray();
    }

    /** Width / height of a (small) picture, read from its header only; 0 when it cannot be read. */
    private float ratio(String url) {
        try {
            if (!allowedImage(url)) return 0;
            byte[] d = bytes(url, 2 << 20, imageHosts);
            if (d == null) return 0;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(d, 0, d.length, o);
            return o.outHeight > 0 ? o.outWidth / (float) o.outHeight : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Decoded at the smallest size that still fills (or, for contain, fits) 1920x1080. RGB_565 halves memory. */
    private Bitmap image(String url, boolean contain) throws Exception {
        if (url == null || url.isEmpty() || !allowedImage(url)) return null;
        byte[] d = bytes(url, 16 << 20, imageHosts);
        if (d == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(d, 0, d.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int s = 1;
        while (contain ? (o.outWidth / (s * 2) >= 1920 || o.outHeight / (s * 2) >= 1080)
                       : (o.outWidth / (s * 2) >= 1920 && o.outHeight / (s * 2) >= 1080)) s *= 2;
        // very long or tall images: keep shrinking until the decoded picture fits the pixel budget
        while ((long) (o.outWidth / s) * (o.outHeight / s) > MAX_PIXELS) s *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = s;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeByteArray(d, 0, d.length, o);
        if (b == null) return null;
        // inSampleSize only halves, so a picture can still be up to 4x the screen's pixels: scale it to exactly fill (or fit)
        float k = contain ? Math.min(1920f / b.getWidth(), 1080f / b.getHeight()) : Math.max(1920f / b.getWidth(), 1080f / b.getHeight());
        if (k < .95f) {
            Bitmap fit = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true);
            if (fit != b) b.recycle();
            b = fit;
        }
        b.prepareToDraw();   // upload to the GPU now, off the UI thread, so the fade starts smoothly
        return b;
    }

    private static void recycle(Piece p) { if (p.bmp != null) p.bmp.recycle(); }
}
