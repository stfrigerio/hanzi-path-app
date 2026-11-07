# hanzi path 汉字之路

Learn Chinese characters the way they're built: every part before the first
common character that uses it. 想 comes after 木, 目, 相 and 心, so when you
meet it you already know what it's made of.

A native Android app (Java, no Gradle)
that keeps itself up to date. The path is made once by a stdlib Python script.

## Install it

Once, on the cable (USB debugging allowed; the SDK in `$ANDROID_HOME`,
default `/opt/android-sdk`):

```
android/install
```

After that it updates itself.

## Updates

Every push to `main` runs `.github/workflows/release.yml`: it builds the app
and puts it up as the release `latest` (`hanzi-path-C.apk`, C the commits on
main). The app checks it when it comes to the front, at most every 3 hours
(Settings → Updates → *Check now* for at once), and installs a newer one over
itself. The first time, Android asks you to allow "Install unknown apps" for
汉字之路 and to confirm; after that it's silent.

Set up once:

1. **The signing key**, so the released APK can replace the installed one: the
   same key `android/assemble` signs with on your machine.
   ```
   base64 -w0 ~/.android/debug.keystore | gh secret set ANDROID_KEYSTORE -R stfrigerio/hanzi-path-app
   ```
2. **A token for the app**: the repo is private. On GitHub, Settings →
   Developer settings → Fine-grained tokens: only `hanzi-path`, *Contents:
   Read-only*. Paste it in the app, Settings → Updates → *Save token*. It's
   kept in the app's private storage, never in the repo.

## How it works

- **Learn** a batch (5 by default): the strokes animate on a 米字格 (tap the
  square to see them again), it's said aloud (Android's Chinese voice), the
  reading in the tones' inks (1 blue, 2 green, 3 peach, 4 pink: the garden's),
  what it's made of and what it leads to, why it looks like this (Make Me a
  Hanzi's etymology: which part gives the meaning, which the sound), and the
  words you can now read (HSK's, or CC-CEDICT's where HSK has none).
- **Check**: right after the batch, a round of questions. That's the first
  grade, so a new item starts on what you showed you know.
- **Review**: FSRS-4.5, a small scheduler with three grades (again, unsure, knew). A round takes
  the most overdue first, 40 at most (Settings → *Reviews per round*: 20, 40,
  60, all); its end says how many are still due, with *Another round*, or when
  the next fall due.
- **First run**: home's Today card says in three lines how the path goes, until
  *Got it* or the first batch learnt.
- **Questions** (the check and reviews): the character and four answers, each
  a reading and a meaning. Once an item has been seen twice, a review may ask
  it the other way round (its reading and meaning, four characters to pick
  from) or say it aloud (four characters; only with *Say it aloud* on). The
  wrong three are close (characters sharing a
  part with it, or near it on the path). The tap grades it: right in under 8
  seconds is knew, right but slower unsure, wrong again (it comes back at the
  end of the round). Then the answer in full, and Next. *Undo* takes back the
  last grade.
- **Home**: today in four lines (due now, new left, days in a row, known of
  met) with Review and Learn, the week's reviews as a bar per day, and the
  path as a garden: yellow next, pink learning, blue known, outlined pink
  wilting (due and slipping). It shows the rows around where you are; *Show
  the whole path* opens the rest. Tap any item to look it up.

## Progress

SQLite, in the app (`databases/hanzi.db`, included in Android's backup):
cards (FSRS's stability and difficulty), your stories, settings, and a log of
every grade given (for fitting FSRS to you later). Settings → *Copy progress*
puts all of it on the clipboard as text; *Load from clipboard* takes it back.

## The code

| Path | What |
|---|---|
| `android/src/…/Main.java` | the activity: screens, back, the bars |
| `Home`, `Learn`, `Quiz`, `Settings`, `Cards` | the screens, built in code |
| `HanziView` | the practice square: strokes and their animation |
| `GardenView` | the path on the home screen |
| `Progress` | FSRS and the database |
| `PathData`, `Glyph` | the path and its stroke shapes (from `data/`) |
| `Ink`, `Ui`, `Flow` | the look: the garden's colours, the faces, pills, cards |
| `Updater`, `Installed` | updates from GitHub's release |
| `Widget` (`res/layout/widget_*`) | the home-screen widget: what's due, a character from the path |
| `android/assemble` | the build: javac, d8, aapt2, apksigner |

## The path

`tools/build.py` makes `data/path.json` and `data/strokes-*.json`:

1. A few pictures first, taught whole (一 二 三 十 人 口 日 月 山 水 火 木 大
   小 中 女 子), then the 1000 most common characters (Jun Da's frequency list).
2. Each one's decomposition (Make Me a Hanzi). Before a character, every part
   of it not yet taught, recursively. A part that is a common character comes
   as a character; one that isn't (氵 亻 扌 讠) comes as a *part*, with what it
   means in a character. Single strokes aren't taught.
3. Meanings of characters: a short, plain one from CC-CEDICT, for the
   character's main reading, without surnames, variants, cross-references and
   grammar notes, cut under 28 letters; `MEANS` in build.py where the rules
   can't make one that reads well (particles: 的 "of; 's (possessive)").
   Meanings of parts: `OWN` in build.py, then the hand-written `sources/parts.json`, then Make Me a Hanzi's definition.
4. Words: HSK words made only of what's been taught by then, the lowest
   level first, three per character. A character no HSK word reaches gets up
   to three of CC-CEDICT's words instead, made of what's been taught by then:
   two characters first, the ones longer entries are most built on (a common
   word's sign), shown without a level.

Result: 1251 items, 1041 characters and 210 parts; 1908 HSK words and 226
from CC-CEDICT.

```
python3 tools/build.py [--chars 1000] [--sample 60]   # sources fetched once into ~/.cache/hanzi-path
```

## Next

- Mnemonics written for you (an LLM, from the parts' meanings and the
  etymology).
- Words as cards of their own, once their characters are known.
- FSRS's weights fitted to the review log.
- Sync progress between devices.

## Sources and licences

| What | From | Licence |
|---|---|---|
| decomposition, etymology, pinyin, definitions | [Make Me a Hanzi](https://github.com/skishore/makemeahanzi) `dictionary.txt` | LGPL-3.0 |
| stroke shapes and order (`data/strokes-*.json`) | Make Me a Hanzi `graphics.txt` | Arphic Public License |
| faces (`android/fonts/`) | IBM Plex Mono and Sans; Ma Shan Zheng (cut to 汉字之路好行练) | OFL |
| frequency order | Jun Da, Modern Chinese character frequency list | research use |
| meanings of characters, words beyond HSK | [CC-CEDICT](https://cc-cedict.org/) via [MDBG](https://www.mdbg.net/chinese/dictionary?page=cc-cedict) | CC BY-SA 4.0 |
| HSK words (`sources/hsk.json`) | [hskhsk.com](https://github.com/glxxyz/hskhsk.com) | MIT (`sources/hsk-LICENSE.txt`) |
| part meanings (`sources/parts.json`) | by hand | ours |
