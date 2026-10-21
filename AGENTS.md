# For AI agents

If a person asks you to set up, debloat or restore their TV with this repository, follow [PROMPT.md](PROMPT.md)
from "Rules (always)" on: it is the contract (rules, steps, checks, recovery). The scripts are in `debloat/` and
`launcher/`; each starts with a usage comment.

Short version of the rules: snapshot first; Safe tier needs one yes, every other package its own yes in the chat;
never touch `debloat/keep.txt`, never uninstall system apps; check every step on the real TV; stop on
`unauthorized` or FAILED; keep the person's IP, serial and accounts out of commits.

When editing the code: the launcher is plain Java with no Gradle (`launcher/build.sh`), one custom-drawn view
(`NullofyView.java`). Translations live in `launcher/res/values-*/strings.xml`. Picture sources are data in
`launcher/assets/sources.json` (public-domain or openly licensed sources through official APIs only, https,
no keys, no scraping).
