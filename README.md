# hanzi path 汉字之路

Learn Chinese characters the way they're built: every part before the first
common character that uses it. 想 comes after 木, 目, 相 and 心, so when you
meet it you already know what it's made of.

An Android app that updates itself, and the same thing as a web page. Plain
files, no build, no dependencies: the app is one HTML page, one stylesheet,
two scripts, inside a small WebView shell (`android/`, built without Gradle). The path is made once by a stdlib Python
script.

## Run it

On the phone (once, on the cable, USB debugging allowed; the SDK in
`$ANDROID_HOME`, default `/opt/android-sdk`):

```
android/install
```

In a browser:

```
python3 -m http.server 8000      # then open http://localhost:8000
```

## Updates

The installed app keeps itself up to date. Every push to `main` runs
`.github/workflows/release.yml`, which puts the release `latest` up with:

- `web-N.zip`: the web app (N, the commits on main). The app downloads it
  in the background and offers a reload; the next start uses it anyway.
  Nearly every change is this: no reinstall.
- `hanzi-path-C.apk`: the app itself (C, the commits that touched
  `android/`), only when the shell changed. The app installs it over
  itself: the first time Android asks you to allow "Install unknown apps"
  and to confirm; after that it's silent.

It checks when it comes to the front, at most every 3 hours (Settings →
Updates → *Check now* for at once). Set up once:

1. **The signing key**, so the release's APK can replace the installed one:
   the same key `android/assemble` signs with on your machine.
   ```
   base64 -w0 ~/.android/debug.keystore | gh secret set ANDROID_KEYSTORE -R stfrigerio/hanzi-path-app
   ```
   Without it, the workflow releases only the web copy (still enough for
   almost every update).
2. **A token for the app**: the repo is private. On GitHub, Settings →
   Developer settings → Fine-grained tokens: only `hanzi-path`, *Contents:
   Read-only*. Paste it in the app, Settings → Updates → *Save token*. It's
   kept in the app's private storage, never in the repo.

## Progress

Kept in a database: SQLite in the app (`databases/hanzi.db`, included in
Android's backup), IndexedDB in a browser (`store.js`). Cards (FSRS's
stability and difficulty), your stories, settings, and a log of every grade
given (for fitting FSRS to you later). Progress from the first version
(localStorage) moves in by itself. Settings → *Copy progress* gives all of it
as text; *Load pasted* takes it back, on any device.

## How it works

- **Learn** a batch (5 by default): the strokes animate in a 米字格, it's
  said aloud (Android's Chinese voice; a browser's if it has one), the
  reading in the tones' inks (1 blue, 2 green, 3 peach, 4 pink, the garden's),
  what it's made of, why it looks like this (Make Me a Hanzi's etymology: which
  part gives the meaning, which the sound), a box for your own story, and the
  HSK words you can now read with what you know. *Write it* has you trace it.
- **Check**: right after the batch, a round of questions. That's the first
  grade, so a new item starts on what you showed you know.
- **Review**: FSRS-4.5, a small scheduler with three grades (again, unsure, knew). Reviews ask you
  to read (character → reading and meaning), write (meaning → draw it; the
  grade is suggested from your mistakes) or a mix. *Undo* takes back the
  last grade.
- **Home** says what's known, the days in a row, today's reviews and
  what's due by tomorrow.
- **The path** on the home screen is every item in order: yellow is next,
  pink is learning, blue is known, outlined pink is wilting (due and slipping).
  Tap any one to look it up.

Progress stays in the browser (`localStorage`). Settings → *Copy progress*
gives it as text to keep or move; *Load pasted* takes it back.

## The path

`tools/build.py` makes `data/path.json` and `data/strokes-*.json`:

1. A few pictures first, taught whole (一 二 三 十 人 口 日 月 山 水 火 木 大
   小 中 女 子), then the 500 most common characters (Jun Da's frequency list).
2. Each one's decomposition (Make Me a Hanzi). Before a character, every part
   of it not yet taught, recursively. A part that is a common character comes
   as a character; one that isn't (氵 亻 扌 讠) comes as a *part*, with what it
   means in a character. Single strokes aren't taught.
3. Meanings of parts: the hand-written `sources/parts.json` first, then
   `OWN` in build.py, then Make Me a Hanzi's definition.
4. Words: HSK words made only of what's been taught by then, the lowest
   level first, three per character.

Result: 700 items, 572 characters and 128 parts.

```
python3 tools/build.py [--chars 500]     # sources fetched once into ~/.cache/hanzi-path
```

## Next

- Mnemonics written for you (an LLM, from the parts' meanings and the
  etymology), shown under *Your story* until you write your own.
- Words as cards of their own, once their characters are known.
- FSRS's weights fitted to the review log.
- Sync progress between devices.

## Sources and licences

| What | From | Licence |
|---|---|---|
| decomposition, etymology, pinyin, definitions | [Make Me a Hanzi](https://github.com/skishore/makemeahanzi) `dictionary.txt` | LGPL-3.0 |
| stroke shapes and order (`data/strokes-*.json`) | Make Me a Hanzi `graphics.txt` | Arphic Public License |
| stroke animation and tracing (`vendor/`) | [Hanzi Writer](https://github.com/chanind/hanzi-writer) 3.7.2 | MIT |
| frequency order | Jun Da, Modern Chinese character frequency list | research use |
| HSK words (`sources/hsk.json`) | [hskhsk.com](https://github.com/glxxyz/hskhsk.com) | MIT (`sources/hsk-LICENSE.txt`) |
| part meanings (`sources/parts.json`) | by hand | ours |
