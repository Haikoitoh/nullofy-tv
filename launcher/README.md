# Nullofy, the launcher

Plain Java, no Gradle, no libraries: one custom-drawn view (`NullofyView.java`) on a 1920×1080 canvas scaled to
the screen. About 175 KB installed, about 30 MB of memory at Home. Android 7.0 (SDK 24) and newer.

| File | What it does |
|---|---|
| `HomeActivity.java` | The Home screen activity |
| `NullofyView.java` | Everything you see: list, row, tiles, panel, Settings, Art, Aesthetic background |
| `Apps.java` | Installed apps, their order, hidden apps, Watch Next rows, HDMI inputs |
| `Art.java` | Picture sources (`assets/sources.json`), Safe Pictures and the never-shown list |
| `HomeService.java` | The Home-button service (accessibility; Home key and stock home screen only) |
| `NullofyDream.java` | The screensaver (bouncing clock / text, or Art) |
| `Watchdog.java` | After 3 crashes in 5 minutes the Home button goes back to the TV's own home until Nullofy is opened |

## Build and install

```bash
launcher/build.sh       # needs ANDROID_HOME (build-tools 34.0.0, platforms;android-34) and a JDK 17+
launcher/install.sh     # see the main README
```

## Settings (the Nullofy Settings tile on Home)

| Setting | Choices |
|---|---|
| Mode | Dark, Day, Aesthetic |
| Layout | Vertical (default), Horizontal |
| Icon style | Icon + name, Big tiles |
| Icon size | Regular, Medium, Large |
| Font size | Regular, Large, Extra large |
| Accent | Lime, Mint, Cyan, Blue, Violet, Magenta, Ember, Amber, White (Black in Day); Auto in Aesthetic (black or white from the picture), chosen for you when you switch to Aesthetic |
| Picture refresh (Aesthetic) | 1, 3, 5 or 10 minutes |
| App order | Most used, A–Z |
| Clock | Follow the TV, 24 h, 12 h; Time labels (AM/PM) |
| Idle → Art | Off, 5–30 minutes |
| Screensaver | Clock, Clock + text, Text only, Art; text, speed, Multi Color, Show date, Preview |
| Safe Pictures | On (default) / Off |
| Off Grid | No network use at all |
| Hidden apps | Show or hide any app on Home |
| Default Home | Nullofy, or the TV's default (the Home-button service stands aside) |

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Art and Aesthetic pictures, Continue Watching posters (https only; none in Off Grid) |
| `READ_EPG_DATA`, `READ_TV_LISTINGS` | Other apps' Continue Watching rows (Android asks once) |
| `REQUEST_DELETE_PACKAGES` | The panel's Uninstall opens Android's own uninstall dialog |
| Accessibility service (you turn it on) | The Home button on Google TV |

## Picture sources

`assets/sources.json` lists every source and its rules: public-domain or openly licensed pictures only, official
APIs only (no scraping, no keys), https only, images only from the listed hosts. A picture is chosen by a random
number, never by name. **Safe Pictures** (`safe`) skips pictures whose metadata names nudity and leaves out a few
painters; the **never** list (bloody kills, gore, carcasses) applies always. Both are word lists in the same file:
they catch most, not all.

## Translations

`res/values-*/strings.xml`, one folder per language. They started as machine drafts; corrections from native
speakers are very welcome. Android falls back to English for a missing string.
