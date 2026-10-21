# Contributing

Thank you. The most useful things right now:

1. **A list for your TV.** Copy the closest file in `debloat/lists/`, name it `<brand>-<platform>-<android>.txt`,
   put your model and Android version in the header, and sort packages into tiers:
   - `[safe]` only for packages you disabled and then used the TV for a few days without missing anything
     (live TV, HDMI, the remote's Home / Input / voice buttons, casting if you use it, the apps you use);
   - `[unused]` when it only removes a feature, named in the note;
   - `[ask]` for everything else, with "(untested)" when you did not try it.
   Never add a package that the TV needs to boot, show live TV or HDMI, pair the remote, connect to Wi-Fi or run
   Settings; add those to `keep.txt` instead.
2. **Reports.** Open an issue with your TV model, Android version, what worked and what didn't. Leave out your
   IP address, serial number and account names.
3. **Translations.** Fix a string in `launcher/res/values-<lang>/strings.xml`.
4. **Picture sources.** Public-domain or openly licensed only, through an official API, https, no API keys, no
   scraping. Add them to `launcher/assets/sources.json`.

Code style: match the surrounding code; plain Java, no new libraries. Check that `launcher/build.sh` still
builds and that the APK still installs and opens on a TV before sending a pull request.
