# Debloat kit

Every script starts with a usage comment. They need `adb` and bash; set `TV=<ip>:5555` for network debugging.
Nothing is uninstalled: packages are disabled for user 0 (`pm disable-user`) and `restore.sh` turns them back on.

| Script | Changes the TV? | What it does |
|---|---|---|
| `snapshot.sh` | No | Saves the state before the first change in `~/.nullofy-tv/<serial>/` (kept; `--again` replaces it) |
| `scan.sh [list]` | No | Shows what a list would change, tier by tier, and the packages no list knows (`uncatalogued.txt`) |
| `disable.sh LIST` | Yes, after asking | Safe tier: one question for the group. Other tiers: one question per package. `--dry-run`, `--tier`, `--packages` |
| `restore.sh` | Yes | Everything back as the snapshot found it; asks before uninstalling Nullofy |
| `lib.sh` | — | Shared helpers |

## Lists

`lists/<brand>-<platform>-<android>.txt`. A list is tiers of packages, one per line with a short note:

```
[safe]
com.example.adsapp        the maker's ad service
[unused]
com.android.printspooler  printing
[ask]
com.example.casting       casting from the maker's phone app (untested)
```

- **safe**: ads, promos, tracking, telemetry. Tested on the model in the header: nothing a person uses breaks.
- **unused**: safe unless you use the feature in the note. Tested.
- **ask**: a real choice, or not tested on that model yet; mark those "(untested)".

`keep.txt` is the never-touch list: the scripts refuse those packages whatever a list says. A trailing `*`
matches a prefix.

## Lists so far

| List | Tested on |
|---|---|
| `tcl-google-tv-14.txt` | TCL Smart TV Pro, Google TV, Android 14 (SDK 34), MediaTek |

Your TV is not here? Run `scan.sh` with no list, look up what it finds, and send a new list
(see [CONTRIBUTING.md](../CONTRIBUTING.md)).
