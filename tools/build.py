#!/usr/bin/env python3
"""Builds the path: data/path.json and data/strokes-N.json, from the sources.

The path is the order things are taught in. Take the most common characters
(Jun Da's frequency list), and before each one, teach every part it is made of
that hasn't been taught yet, and the parts of those parts: 想 brings 木, 目, 相
and 心 first. A part that is a common character itself comes as a character;
one that isn't (氵, 亻, 扌) comes as a part, with what it means in a
character. Strokes alone (丿, 丶) aren't taught: they mean nothing yet.

Sources (fetched once into ~/.cache/hanzi-path, or --src DIR):
  Make Me a Hanzi's dictionary.txt and graphics.txt (skishore/makemeahanzi):
    decomposition, etymology, pinyin, definition (LGPL); strokes (Arphic PL)
  Jun Da's character frequency list (lingua.mtsu.edu, modern Chinese corpus)
In the repo (sources/): sources/parts.json (hand-written meanings of
parts, by hand) and hsk.json (HSK 2.0 words, MIT, see hsk-LICENSE.txt).

What a character's card says it means is Make Me a Hanzi's definition, cut
to its first senses; a part's is parts.json's when it has one.

Usage: tools/build.py [--chars 500] [--src DIR]
"""
import argparse
import json
import os
import re
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.realpath(__file__)))
CACHE = os.path.expanduser("~/.cache/hanzi-path")
MMH = "https://raw.githubusercontent.com/skishore/makemeahanzi/master/"
JUNDA = "https://lingua.mtsu.edu/chinese-computing/statistics/char/list.php?Which=MO"
IDC = set(chr(c) for c in range(0x2FF0, 0x2FFC))
# the path opens with these: pictures you can see in them, a stroke or a few,
# before the common characters' parts (的 alone would open it with 冂 and 勹)
STARTERS = "一二三十人口日月山水火木大小中女子"
CHUNK = 60          # path items per strokes file: the app fetches one as it gets near
WORDS_PER_CHAR = 3
# meanings a learner knows a part by, where neither the garden nor Make Me a
# Hanzi has a good one (theirs: Korean names, a dictionary's whole sense)
OWN = {"丷": "horns", "乂": "cut", "冂": "borders", "尢": "lame", "凵": "box", "卜": "divine",
       "巳": "snake", "壬": "burden", "戊": "halberd", "乇": "sprout", "亼": "gather",
       "丬": "half tree", "冋": "border", "刖": "cut foot", "咅": "spit", "卄": "twenty",
       "廿": "twenty", "卅": "thirty", "帀": "around", "厃": "look up", "矦": "target",
       "丩": "tangle", "㐬": "flow", "⺳": "net", "敕": "decree", "禸": "track", "丙": "third",
       "迶": "walk", "廴": "stride", "㠯": "use", "娄": "drag", "幺": "tiny", "兀": "stool",
       "攴": "tap", "罙": "deep", "冏": "window", "肰": "dog meat", "亥": "pig"}


def fetch(src, name, url):
    path = os.path.join(src, name)
    if not os.path.exists(path):
        os.makedirs(src, exist_ok=True)
        print("fetching", url)
        with urllib.request.urlopen(url, timeout=600) as r, open(path, "wb") as f:
            f.write(r.read())
    return path


def jsonl(path):
    with open(path, encoding="utf-8") as f:
        return {d["character"]: d for d in map(json.loads, f)}


def frequency(path):
    """Characters, the most common first."""
    text = open(path, "rb").read().decode("gb18030", errors="replace")
    body = text.split("<pre>", 1)[1].replace("<br>", "\n")
    return re.findall(r"^\d+\t(\S)\t", body, re.M)


def short(definition, senses=2):
    """'good; well; proper; good to' -> 'good; well'"""
    parts = [p.strip() for p in re.split(r"[;,]", definition or "") if p.strip()]
    return "; ".join(parts[:senses])


def components(entry):
    return [c for c in entry.get("decomposition", "") if c not in IDC and c not in "？"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--chars", type=int, default=500)
    ap.add_argument("--src", default=CACHE)
    a = ap.parse_args()

    dic = jsonl(fetch(a.src, "dictionary.txt", MMH + "dictionary.txt"))
    gfx = jsonl(fetch(a.src, "graphics.txt", MMH + "graphics.txt"))
    freq = frequency(fetch(a.src, "junda.html", JUNDA))
    garden = json.load(open(os.path.join(ROOT, "sources/parts.json"), encoding="utf-8"))["parts"]
    hsk = json.load(open(os.path.join(ROOT, "sources/hsk.json"), encoding="utf-8"))["levels"]

    rank = {c: i for i, c in enumerate(freq)}
    top = [c for c in freq if c in dic and c in gfx][:a.chars]
    common = set(top)

    def teachable(c):
        if c not in gfx or c not in dic:
            return False
        if c in common:
            return True
        # a part: more than a stroke, and something it means
        return len(gfx[c]["strokes"]) >= 2 and bool(garden.get(c) or dic[c].get("definition"))

    order, seen = [], set()

    def visit(c, depth=0, whole=False):
        if c in seen or depth > 6:
            return
        seen.add(c)
        for p in ([] if whole else components(dic[c])):
            if p != c and teachable(p):
                visit(p, depth + 1)
        order.append(c)

    for c in STARTERS:
        common.add(c)
        visit(c, whole=True)   # a picture: taught whole, its strokes aren't parts yet
    for c in top:
        visit(c)

    pos = {c: i for i, c in enumerate(order)}

    # words: HSK words made only of what's been taught by then, lowest level first
    words = {}
    for lvl in sorted(hsk, key=int):
        for w, py, en in hsk[lvl]:
            if len(w) < 2 or not all(ch in pos for ch in w):
                continue
            at = max(pos[ch] for ch in w)        # readable from this point on the path
            lst = words.setdefault(order[at], [])
            if len(lst) < WORDS_PER_CHAR:
                lst.append([w, py, en, int(lvl)])

    users = {}
    for c in order:
        for p in set(components(dic[c])):
            if p in pos and p != c:
                users.setdefault(p, []).append(c)

    items = []
    for c in order:
        d = dic[c]
        g = garden.get(c)
        kind = "char" if (c in common or c in rank and rank[c] < 1500) else "part"
        py = d.get("pinyin") or ([g[0]] if g else [])
        if c in OWN:
            meaning = OWN[c]
        elif kind == "part" and g:
            meaning = g[1]
        else:
            meaning = short(d.get("definition")) or (g[1] if g else "")
            if len(meaning) > 32:
                meaning = short(d.get("definition"), 1)
        it = {"h": c, "k": kind, "py": py[:2], "en": meaning,
              "parts": [p for p in dict.fromkeys(components(d)) if p in pos and p != c],
              "n": len(gfx[c]["strokes"])}
        e = d.get("etymology")
        if e:
            it["et"] = {k: v for k, v in e.items() if k in ("type", "hint", "semantic", "phonetic")}
        if c in words:
            it["w"] = words[c]
        if c in users:
            it["used"] = users[c][:6]
        items.append(it)

    out = os.path.join(ROOT, "data")
    os.makedirs(out, exist_ok=True)
    for f in os.listdir(out):
        if f.startswith("strokes-"):
            os.remove(os.path.join(out, f))
    path = {"source": "tools/build.py: Make Me a Hanzi (LGPL / Arphic PL), Jun Da's frequency list, "
                      "sources/parts.json, HSK 2.0 words (MIT)",
            "chunk": CHUNK, "items": items}
    with open(os.path.join(out, "path.json"), "w", encoding="utf-8") as f:
        json.dump(path, f, ensure_ascii=False, separators=(",", ":"))
    for i in range(0, len(order), CHUNK):
        chunk = {c: {"strokes": gfx[c]["strokes"], "medians": gfx[c]["medians"]} for c in order[i:i + CHUNK]}
        with open(os.path.join(out, f"strokes-{i // CHUNK}.json"), "w", encoding="utf-8") as f:
            json.dump(chunk, f, ensure_ascii=False, separators=(",", ":"))

    parts = sum(1 for it in items if it["k"] == "part")
    print(f"{len(items)} on the path: {len(items) - parts} characters, {parts} parts; "
          f"{(len(items) + CHUNK - 1) // CHUNK} stroke files")


if __name__ == "__main__":
    main()
