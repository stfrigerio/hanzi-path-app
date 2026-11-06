# hanzi path 汉字之路

Learn Chinese characters the way they're built: every part before the first
common character that uses it. 想 comes after 木, 目, 相 and 心, so when you
meet it you already know what it's made of.

A phone-first web app (a PWA: add it to the home screen, it works offline).
Plain files, no build, no dependencies. The path is made once by a stdlib
Python script; the app is one HTML page, one stylesheet, one script.

## Run it

```
python3 -m http.server 8000      # then open http://localhost:8000
```

On the phone, open `http://<this machine's IP>:8000` on the same Wi-Fi. Offline
mode (the service worker) needs https or localhost, so for the real install
put the folder on any static host (GitHub Pages works as is).

## How it works

- **Learn** a batch (5 by default): the strokes animate in a 米字格, the
  reading in the tones' inks (1 blue, 2 green, 3 peach, 4 pink, the garden's),
  what it's made of, why it looks like this (Make Me a Hanzi's etymology: which
  part gives the meaning, which the sound), a box for your own story, and the
  HSK words you can now read with what you know. *Write it* has you trace it.
- **Check**: right after the batch, a round of questions. That's the first
  grade, so a new item starts on what you showed you know.
- **Review**: FSRS-4.5, a small scheduler with three grades (again, unsure, knew). Reviews ask you
  to read (character → reading and meaning), write (meaning → draw it; the
  grade is suggested from your mistakes) or a mix.
- **The path** on the home screen is every item in order: yellow is next,
  pink is learning, blue is known, outlined pink is wilting (due and slipping).
  Tap any one to look it up.

Progress stays in the browser (`localStorage`). Settings → *Copy progress*
gives it as text to keep or move; *Load pasted* takes it back.

## The path

`tools/build.py` makes `data/path.json` and `data/strokes-*.json`:

1. The 500 most common characters (Jun Da's frequency list).
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
