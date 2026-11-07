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
  CC-CEDICT (mdbg.net, CC BY-SA 4.0): meanings and words
In the repo (sources/): sources/parts.json (hand-written meanings of
parts, by hand) and hsk.json (HSK 2.0 words, MIT, see hsk-LICENSE.txt).

A character's readings: the commonest first, by how many words say it so (HSK's the
most, then CC-CEDICT's, weighted by how common their characters are), READ where
the count is wrong (了 le); the second shown as "also" when it's said fairly often.

What a character's card says it means: MEANS below if it's there, else
CC-CEDICT's senses for its main reading, cleaned and cut short (plain()),
else Make Me a Hanzi's definition cut to its first senses. A part's is OWN's,
then parts.json's.

Words: HSK words made only of what's been taught by then, the lowest level
first; a character with none gets CC-CEDICT's commonest words instead
(level 0, which the app shows without a level).

Usage: tools/build.py [--chars 1000] [--src DIR] [--sample N]
"""
import argparse
import gzip
import json
import os
import random
import re
import unicodedata
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.realpath(__file__)))
CACHE = os.path.expanduser("~/.cache/hanzi-path")
MMH = "https://raw.githubusercontent.com/skishore/makemeahanzi/master/"
JUNDA = "https://lingua.mtsu.edu/chinese-computing/statistics/char/list.php?Which=MO"
CEDICT = "https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz"
IDC = set(chr(c) for c in range(0x2FF0, 0x2FFC))
# the path opens with these: pictures you can see in them, a stroke or a few,
# before the common characters' parts (的 alone would open it with 冂 and 勹)
STARTERS = "一二三十人口日月山水火木大小中女子"
CHUNK = 60          # path items per strokes file: the app fetches one as it gets near
WORDS_PER_CHAR = 3
MEANING = 28        # longest meaning a card shows, in letters
# meanings a learner knows a part by, where neither the garden nor Make Me a
# Hanzi has a good one (theirs: Korean names, a dictionary's whole sense)
OWN = {"丷": "horns", "乂": "cut", "冂": "borders", "尢": "lame", "凵": "box", "卜": "divine",
       "巳": "snake", "壬": "burden", "戊": "halberd", "乇": "sprout", "亼": "gather",
       "丬": "half tree", "冋": "border", "刖": "cut foot", "咅": "spit", "卄": "twenty",
       "廿": "twenty", "卅": "thirty", "帀": "around", "厃": "look up", "矦": "target",
       "丩": "tangle", "㐬": "flow", "⺳": "net", "敕": "decree", "禸": "track", "丙": "third",
       "迶": "walk", "廴": "stride", "㠯": "use", "娄": "drag", "幺": "tiny", "兀": "stool",
       "攴": "tap", "罙": "deep", "冏": "window", "肰": "dog meat", "亥": "pig",
       "㐌": "snake", "龺": "sunrise", "禹": "Yu, who tamed the flood"}
# what a character means, where CC-CEDICT's senses read badly however they're cut:
# particles, and characters whose first senses aren't the ones a learner meets first
MEANS = {
    # particles and grammar words: what they do, in brackets
    "的": "of; 's (possessive)", "了": "(done, changed); to finish", "着": "(-ing, ongoing action)",
    "们": "(plural of people)", "个": "(measure word); individual", "么": "(suffix: what, so, how)",
    "吗": "(yes-no question)", "呢": "(and...?; still)", "吧": "(let's...; ...right?)",
    "啊": "ah!; oh!", "呀": "ah; oh (surprise)", "阿": "(prefix for names, kin)",
    "之": "of (literary); him; it", "乎": "(literary) at; in; from", "地": "earth; ground; (-ly)",
    "第": "(ordinal: -th); order", "被": "by (passive); quilt", "次": "time (once, twice); next",
    # the sense a learner meets first, where the dictionary's first ones are rarer
    "二": "two", "子": "child; son; seed", "白": "white; plain; in vain",
    "不": "not; no", "才": "only then; just; talent", "在": "at; in; to be at", "这": "this; these",
    "来": "to come", "王": "king; monarch", "京": "capital city", "尤": "especially; outstanding",
    "就": "then; at once; just", "会": "can; to meet; meeting", "丁": "adult man; fourth",
    "可": "can; may; but", "对": "right; correct; towards", "生": "to be born; life; raw",
    "目": "eye; item", "年": "year", "里": "inside; in",
    "所": "place; that which", "然": "so; like that; right", "方": "square; direction; side",
    "经": "to pass through; classic", "者": "one who; -er", "现": "now; to appear",
    "没": "not; have not; none", "动": "to move; to act", "还": "still; also; yet",
    "进": "to enter; to advance", "好": "good; well; proper", "理": "reason; logic; to manage",
    "头": "head; top; first", "无": "none; no; without", "关": "to close; to shut; pass",
    "点": "point; dot; o'clock", "美": "beautiful; pretty", "话": "words; speech; talk",
    "代": "to replace; era; generation", "表": "surface; to show; watch", "给": "to give; for; to",
    "度": "degree; to spend (time)", "士": "scholar; soldier", "解": "to untie; to explain",
    "题": "topic; problem; title", "故": "reason; old; on purpose", "辛": "hot (spicy); hard",
    "期": "period; time; phase", "午": "noon", "便": "convenient; then", "元": "first; yuan (money)",
    "死": "to die; dead", "规": "rule; compass", "望": "to hope; to gaze", "确": "true; certain",
    "专": "special; focused", "候": "to wait; time; season",
    "济": "to help; to cross a river", "央": "center; middle", "单": "single; bill; list",
    "字": "character; word", "周": "week; circle; all around", "团": "group; round; lump",
    "况": "situation; moreover", "亚": "Asia; second", "约": "to arrange; approximately",
    "复": "again; to repeat; to return", "息": "breath; news; to rest", "照": "to shine; to photograph",
    "企": "enterprise; to long for", "功": "merit; achievement", "农": "farming; farmer",
    "装": "to dress up; to load", "冬": "winter", "尺": "Chinese foot; ruler",
    "号": "number; day; mark", "洛": "Luo (a river)", "推": "to push; to shove",
    "兴": "interest; excitement", "苏": "to revive", "朝": "dynasty; towards; court",
    "刻": "to carve; moment", "弹": "bullet; ball; shell", "股": "share; thigh; strand",
    "预": "in advance; to prepare", "谁": "who", "阳": "sun; positive; yang", "血": "blood",
    "良": "good; fine", "游": "to swim; to tour", "龙": "dragon", "汉": "Han Chinese; man",
    "哈": "ha!; to breathe out", "修": "to repair; to build", "妈": "mom; mother",
    "刘": "Liu (a surname)", "伊": "he; she (literary)", "乱": "chaos; disorder; random",
    "佛": "Buddha", "圣": "holy; sage", "著": "to write; famous; to show", "铁": "iron",
    "左": "left", "份": "part; share; portion", "背": "back; to recite", "阵": "a spell; battle line",
    "鸟": "bird", "托": "to support; to entrust", "胡": "reckless; beard; foreign",
    "款": "sum (of money); style", "版": "edition; printing block", "曼": "graceful; long",
    "欧": "Europe; ohm", "核": "core; nucleus; pit", "露": "dew; to show", "沙": "sand",
    "吴": "Wu (a state, a surname)", "剑": "sword", "楼": "building; floor",
    "毫": "fine hair; the least bit", "鲁": "rash; dull", "幸": "lucky; fortunate",
    "钟": "clock; bell", "剧": "drama; play; severe", "春": "spring", "乡": "countryside; village",
    "烟": "smoke; cigarette; tobacco", "奥": "mysterious; profound", "唐": "Tang (dynasty)",
    "俄": "Russia; suddenly", "宋": "Song (dynasty)", "阴": "overcast; shade; yin",
    "园": "garden; park", "堂": "hall; cousin", "旁": "side; beside; other", "顶": "top; peak; very",
    "须": "must; beard", "突": "sudden; to dash", "语": "language; speech", "考": "to test; to check",
    "节": "festival; joint; section", "态": "attitude; state", "志": "will; aim; record",
    "跟": "to follow; with; heel", "批": "to criticize; batch", "黄": "yellow",
    "项": "item; back of neck", "热": "hot; heat; to heat", "育": "to raise; to educate",
    "属": "to belong to; category", "刚": "just; exactly", "战": "war; to fight",
    "产": "to produce; product", "信": "letter; to believe", "世": "world; life; generation",
    "常": "often; always; normal", "先": "first; before", "别": "don't; other; to leave",
    "结": "knot; to tie; to form", "太": "too; very; greatest", "场": "place; field; scene",
    "管": "to manage; tube; to care", "指": "finger; to point", "克": "gram; to overcome",
    "许": "to allow; perhaps", "允": "to permit; fair", "形": "shape; form; to appear",
    "科": "branch of study; section", "则": "rule; then", "象": "elephant; image; shape",
    "程": "journey; rule; procedure", "张": "to spread; sheet", "领": "neck; collar; to lead",
    "共": "together; common; total", "今": "now; today", "导": "to lead; to guide",
    "那": "that; those", "用": "to use", "最": "most", "些": "some; a few",
    "样": "kind; manner; pattern", "等": "to wait; class; etc.", "或": "or; maybe; perhaps",
    "河": "river", "米": "rice; meter", "细": "thin; fine; detailed", "影": "shadow; image; film",
    "严": "strict; stern", "致": "to cause; to send",
    # the second reading of all of them (2026-11-07): the dictionary's phrasing ("to fall
    # or drop"), a first sense few meet (带 girdle, 穿 to bore through), and the meaning
    # of the main reading where counting words changed it (干 gān, 甚 shèn)
    "甚": "very; extremely; even", "罗": "net; gauze; to spread out", "差": "difference; gap; error",
    "户": "household; door", "家": "home; family; expert", "种": "kind; type; seed; to plant",
    "同": "same; together; with", "当": "to act as; ought; when", "井": "a well",
    "部": "part; section; department", "巴": "to cling to; to long for", "弟": "younger brother",
    "公": "public; common; male", "丈": "husband; zhang (3.3 m)", "青": "green; blue; young",
    "占": "to occupy; to take up", "业": "business; industry; studies", "应": "should; ought to",
    "亲": "relative; dear; to kiss", "化": "to change; to melt; -ize", "论": "theory; to discuss",
    "队": "team; group; line", "非": "not; wrong; non-", "居": "to live; residence",
    "带": "belt; to carry; to bring", "联": "to unite; to join; to link", "办": "to do; to handle",
    "调": "to transfer; tune; tone", "华": "China; Chinese; splendid", "城": "city; city wall",
    "付": "to pay; to hand over", "消": "to vanish; to remove", "研": "to research; to grind",
    "称": "to call; to weigh; to state", "委": "to entrust; committee", "查": "to check; to look into",
    "找": "to look for; to find", "显": "to show; obvious", "注": "to pour; to note; to focus",
    "落": "to fall; to drop; to set", "响": "loud; sound; to ring", "希": "to hope; rare",
    "革": "leather; to change", "划": "to plan; to mark off", "绝": "to cut off; absolutely",
    "害": "to harm; harm; evil", "久": "long (time); for long", "免": "to avoid; to exempt",
    "哪": "which; where", "排": "row; to line up; to arrange", "莫": "do not; none; no",
    "冲": "to rush; to dash; to rinse", "卡": "card; to block; to get stuck", "判": "to judge; to decide",
    "阶": "step; stairs; rank", "沉": "to sink; heavy; deep", "读": "to read; to study",
    "航": "to sail; to fly; boat", "穿": "to wear; to pierce", "靠": "to lean on; to rely on",
    "顾": "to look after; to visit", "讨": "to ask for; to discuss", "括": "to include; to tie up",
    "封": "to seal; envelope; to confer", "销": "to sell; to cancel; to melt", "赶": "to hurry; to catch up",
    "忽": "suddenly; to neglect", "录": "to record; record", "庭": "courtyard; hall; law court",
    "含": "to contain; to hold in mouth", "灭": "to put out; to destroy", "博": "broad; rich; learned",
    "中": "middle; center; in", "干": "dry; to concern", "切": "close to; eager; earnest",
}


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


TONES = {"a": "āáǎà", "e": "ēéěè", "i": "īíǐì", "o": "ōóǒò", "u": "ūúǔù", "ü": "ǖǘǚǜ"}


def marks(numbered):
    """'ni3 hao3' -> 'nǐ hǎo', 'lu:4' -> 'lǜ'; 5 (the neutral tone) has no mark."""
    out = []
    for syl in numbered.split():
        syl = syl.replace("u:", "ü").replace("U:", "Ü")
        m = re.match(r"^([A-Za-zü]+)([1-5])$", syl)
        if not m:
            out.append(syl)
            continue
        s, t = m.group(1), int(m.group(2))
        low = s.lower()
        if t < 5 and low != "r":
            if "a" in low:
                at = low.find("a")
            elif "e" in low:
                at = low.find("e")
            elif "ou" in low:
                at = low.find("o")
            else:
                at = max(low.rfind(v) for v in "iouü")
            if at >= 0:
                mark = TONES[low[at]][t - 1]
                s = s[:at] + (mark.upper() if s[at].isupper() else mark) + s[at + 1:]
        out.append(s)
    return " ".join(out)


def cedict(path):
    """{simplified: [(pinyin with marks, [senses], traditional)]}, in the file's order."""
    out = {}
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            m = re.match(r"^(\S+) (\S+) \[([^\]]*)\] /(.*)/\s*$", line)
            if m:
                trad, simp, py, defs = m.groups()
                out.setdefault(simp, []).append((marks(py), defs.split("/"), trad))
    return out


# senses that aren't a meaning: names, cross-references, dictionary housekeeping,
# and the registers a learner doesn't meet first (literary, dialect, Taiwan)
NOISE = re.compile(
    r"^(\(\w+\) )?(surname|(old |archaic |unofficial |erhua |japanese |korean |ancient )?variant of|"
    r"used in|see |also (pr|written|called)|abbr|cl:|pr\. |lit\. |"
    r"(the )?(\w+ )?radical|kangxi|(\w+ )?character|name of|nickname|phonetic|"
    r"transliteration|[a-z]+ (dynasty|province|county|river)|"
    r"\((dialect|archaic|literary|classical|old|tw|cantonese|onom\.|onom|chinese medicine|"
    r"physics|chemistry|math\.|math|buddhism|honorific|euphemism|slang|internet slang)\))", re.I)
# senses about grammar rather than meaning: kept only when nothing else is left
GRAMMAR = re.compile(r"particle|marker|\bused (after|before|to|with|as|in|at)\b|indicating|"
                     r"classifier|suffix|prefix|interjection|\bexpressing\b|\bplural\b", re.I)


UNSEEMLY = re.compile(r"vulva|vagin|penis|genital|prostitut|mistress|brothel|sexual|intercourse|"
                      r"corpse|deceased|vulgar|slang|derog|offensive|insult", re.I)


def clean(sense):
    """'(bound form) machine' -> 'machine'; '(of sb or sth) to be (located) at' -> 'to be at'."""
    s = re.sub(r"[^\s()\[\]]*\[[^\]]*\]", "", sense)                        # 的[de5], 瞭|了[liao3]
    while re.search(r"\([^()]*\)", s):
        s = re.sub(r"\s*\([^()]*\)", "", s)
    s = re.sub(r"\bsb\b", "someone", re.sub(r"\bsth\b", "something", s))
    return re.sub(r"\s+", " ", s).strip(" ,;.:-")


def split(sense):
    """'a (b; c); d' -> ['a (b; c)', 'd']: at the semicolons outside brackets."""
    out, depth, cur = [], 0, ""
    for ch in sense:
        depth += ch == "("
        depth -= ch == ")"
        if ch == ";" and depth <= 0:
            out.append(cur)
            cur = ""
        else:
            cur += ch
    return [p.strip() for p in out + [cur] if p.strip()]


# parts of senses that aren't a meaning on their own: numerals (2), prefixes (giga-),
# proper names (River, Asia), notes ('opposite: dark'), the calendar's branches and stems
BARE = re.compile(r"^[\d.]+$|-$|^[A-Z][a-z]+$|^the [A-Z]|\.\.\.|:|(?i:\b(terrestrial|earthly|heavenly) (branch|stem)\b|"
                  r"chemical element|\bunit of\b)")


def bare(p):
    p = re.sub(r"[^\s()\[\]]*\[[^\]]*\]", "", p)
    while re.search(r"\([^()]*\)", p):
        p = re.sub(r"\s*\([^()]*\)", "", p)
    return bool(BARE.search(p.strip()))


def senses(entries):
    """An entry's senses that are meanings, each ';' part on its own, in order."""
    out = []
    for e in entries:
        for d in e[1]:
            if d.strip() and not NOISE.search(d.strip()):
                out += [p for p in split(d) if not bare(p)]
    return out


def gist(text):
    return set(re.findall(r"[a-z]{3,}", (text or "").lower())) - {"the", "and", "for", "one", "sth"}


def plain(entries, reading=None, limit=MEANING, hint=""):
    """A short meaning a learner reads, from CC-CEDICT's entries for a character or word:
    the main reading's, names, cross-references and grammar notes dropped, cut to fit.
    hint (Make Me a Hanzi's definition) picks between the traditional characters a
    simplified one stands for: 后 is 后 empress and 後 after, its hint says 'after'."""
    same = entries
    if reading:
        r = reading.lower()
        same = [e for e in entries if e[0] == reading] or [e for e in entries if e[0].lower() == r]
    # proper names (capitalised pinyin) last; then the entry the hint agrees with; then
    # the one with the most real senses
    h = gist(hint)
    same = sorted(same, key=lambda e: (e[0][:1].isupper(), -len(h & gist(" ".join(e[1]))),
                                       -len(senses([e]))))
    ss = senses(same)
    meant = [s for s in ss if not GRAMMAR.search(s)]
    pick = []
    for s in meant or ss:
        s = clean(s if meant else re.sub(r"^\((.*)\)$", r"\1", s))
        if not s or s in pick or "~" in s or len(s) > limit:
            continue
        if len(pick) == 3 or len(pick) == 2 and len("; ".join(pick + [s])) > 18:
            break           # a third sense only when the three are short (ox; cow; bull)
        if len("; ".join(pick + [s])) > limit:
            if pick:
                break
            continue
        pick.append(s)
    return "; ".join(pick)


# a character's main reading where counting words gets it wrong: the particles are said
# in nearly every sentence, though fewer dictionary words have them
READ = {"了": "le",      # le after every finished action; liǎo only in 了解, 了不起...
        "的": "de",      # de in every other sentence; dí, dì only in 的确, 目的
        "着": "zhe",     # the ongoing -ing; zháo, zhuó in a few words (着急, 着手)
        "地": "dì",      # dì in hundreds of words (地方, 地图...); de, the -ly, as "also"
        "儿": "ér",      # the r of 哪儿, 一点儿 is a sound added to another word, not a reading
        # and words a learner meets in the first weeks, said far more than the dictionary's
        # count of words shows: the count leans to the written language's compounds
        "为": "wèi",     # 为什么, 因为, 为了 (HSK 1-3); wéi in 认为, 成为, 以为 is the "also"
        "还": "hái",     # still, also: among the hundred commonest words; huán is to give back
        "应": "yīng",    # 应该 should; yìng in 反应, 答应, 适应
        "更": "gèng",    # more; gēng in 更新, 更改
        "佛": "fó",      # Buddha: CC-CEDICT has fó only as a name (Fó), and fú is 仿佛's
        }


def tone(syl):
    return unicodedata.normalize("NFC", syl.lower())


def toneless(syl):
    """'lǚ' -> 'lü', 'hǎo' -> 'hao'."""
    return unicodedata.normalize("NFC", "".join(
        ch for ch in unicodedata.normalize("NFD", tone(syl)) if ord(ch) < 0x300 or ord(ch) == 0x308))


def usage(ced, hsk, rank):
    """{character: {reading: how much it's used}}, and the (character, reading)s that are
    HSK words alone (吗 ma, 得 de): every word a character is in, counted
    for the reading it has there. An HSK word counts the most (its own pinyin, the lower
    the level the more); any other CC-CEDICT word by how common its other characters are
    (Jun Da's rank stands in for how common the word is), names and idioms less."""
    def common(ch):
        r = rank.get(ch, 99999)
        return 1 if r < 500 else .6 if r < 1500 else .3 if r < 3000 else .1 if r < 6000 else .03

    out = {}

    def count(w, syls, weight, others):
        if len(syls) != len(w):
            return
        for i, ch in enumerate(w):
            n = weight
            if others:
                for j, o in enumerate(w):
                    n *= common(o) if j != i else 1
            k = out.setdefault(ch, {})
            k[tone(syls[i])] = k.get(tone(syls[i]), 0) + n

    inhsk, alone = set(), set()
    for lvl in hsk:
        for w, py, en in hsk[lvl]:
            inhsk.add(w)
            if len(w) == 1:
                alone.add((w, tone(py)))
            count(w, py.replace("'", " ").split(), 40 / int(lvl), False)
    for w, es in ced.items():
        if w in inhsk or len(w) < 2:
            continue
        for e in es:
            if not senses([e]):
                continue            # a variant's or a cross-reference's entry
            weight = (.5 if len(w) > 3 else 1) * (.3 if e[0][:1].isupper() else 1)
            count(w, e[0].split(), weight, True)
    return out, alone


def readings(c, mmh, entries, used, alone):
    """A character's readings, the commonest first, at most two: Make Me a Hanzi's and
    CC-CEDICT's for the character alone (not a surname's or a variant's), ordered by how
    much the words that use each say it so. A toneless syllable in a word (孩子 zi) counts
    for the toned reading it's the weak form of (zǐ), unless it's a word of its own (吗 ma,
    an HSK word alone, or READ's). A second reading only when it's said in a fair share."""
    cand = list(dict.fromkeys(READ.get(c, "").split() + [tone(p) for p in mmh] +
                              [tone(e[0]) for e in entries if e[0][:1].islower() and " " not in e[0]
                               and senses([e])]))
    if not cand:
        return []
    score = dict.fromkeys(cand, 0.0)

    def weak(r):
        own = (c, r) in alone or READ.get(c) == r
        toned = [x for x in cand if x != r and toneless(x) == r]
        return toned[0] if toneless(r) == r and toned and not own else None

    for r, n in used.get(c, {}).items():
        if weak(r):
            score[weak(r)] += n
        elif r in score:
            score[r] += n
    order = sorted(cand, key=lambda r: (r != READ.get(c), bool(weak(r)), -score[r], cand.index(r)))
    first = order[0]
    rest = [r for r in order[1:] if score[r] >= max(.1 * score[first], 1) or r in mmh[:2]]
    return [first] + rest[:1]


def short(definition, senses=2):
    """'good; well; proper; good to' -> 'good; well'"""
    parts = [p.strip() for p in re.split(r"[;,]", definition or "") if p.strip()]
    return "; ".join(parts[:senses])


def components(entry):
    return [c for c in entry.get("decomposition", "") if c not in IDC and c not in "？"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--chars", type=int, default=1000)
    ap.add_argument("--src", default=CACHE)
    ap.add_argument("--sample", type=int, default=0, help="print N meanings, before and after")
    a = ap.parse_args()

    dic = jsonl(fetch(a.src, "dictionary.txt", MMH + "dictionary.txt"))
    gfx = jsonl(fetch(a.src, "graphics.txt", MMH + "graphics.txt"))
    freq = frequency(fetch(a.src, "junda.html", JUNDA))
    ced = cedict(fetch(a.src, "cedict_1_0_ts_utf-8_mdbg.txt.gz", CEDICT))
    garden = json.load(open(os.path.join(ROOT, "sources/parts.json"), encoding="utf-8"))["parts"]
    hsk = json.load(open(os.path.join(ROOT, "sources/hsk.json"), encoding="utf-8"))["levels"]

    rank = {c: i for i, c in enumerate(freq)}
    used, alone = usage(ced, hsk, rank)
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

    def kind(c):
        return "char" if (c in common or c in rank and rank[c] < 1500) else "part"

    # words: HSK words made only of what's been taught by then, lowest level first
    words, hskwords = {}, set()
    for lvl in sorted(hsk, key=int):
        for w, py, en in hsk[lvl]:
            hskwords.add(w)
            if len(w) < 2 or not all(ch in pos for ch in w):
                continue
            at = max(pos[ch] for ch in w)        # readable from this point on the path
            lst = words.setdefault(order[at], [])
            if len(lst) < WORDS_PER_CHAR:
                lst.append([w, py, en, int(lvl)])

    # beyond HSK: a character with no HSK word gets CC-CEDICT's words made of what's been
    # taught by then. CC-CEDICT has no frequencies; a word that many longer entries are
    # built on (中国 in 中国人, 中国话...) is a common one
    inside = {}
    for w in ced:
        for n in (2, 3):
            for i in range(len(w) - n + 1):
                if len(w) > n:
                    inside[w[i:i + n]] = inside.get(w[i:i + n], 0) + 1
    extra = {}
    for w, es in ced.items():
        if not 2 <= len(w) <= 3 or w in hskwords or not all(ch in pos for ch in w) or len(set(w)) < len(w):
            continue
        es = [e for e in es if not e[0][:1].isupper()]      # names of people and places
        if not es or inside.get(w, 0) < 3:
            continue
        # a word a learner meets: not one whose first sense is literary or archaic (玉门),
        # and none a first reader of it would blush at (小三, 烟花)
        if any(NOISE.search(e[1][0]) for e in es) or any(UNSEEMLY.search(" ".join(e[1])) for e in es):
            continue
        en = plain(es, limit=32)
        if not en:
            continue
        last = order[max(pos[ch] for ch in w)]
        if last in words or kind(last) != "char":
            continue
        e = sorted(es, key=lambda e: -len(senses([e])))[0]
        others = max(pos[ch] for ch in w if ch != last)
        extra.setdefault(last, []).append(((len(w) != 2, -inside[w], others), [w, e[0], en, 0]))
    for c, lst in extra.items():
        words[c] = [w for _, w in sorted(lst)[:WORDS_PER_CHAR]]

    users = {}
    for c in order:
        for p in set(components(dic[c])):
            if p in pos and p != c:
                users.setdefault(p, []).append(c)

    items, before = [], {}
    for c in order:
        d = dic[c]
        g = garden.get(c)
        k = kind(c)
        py = d.get("pinyin") or ([g[0]] if g else [])
        if k == "char":
            py = readings(c, py, ced.get(c, []), used, alone) or py
        old = short(d.get("definition")) or (g[1] if g else "")
        if len(old) > 32:
            old = short(d.get("definition"), 1)
        if c in OWN:
            meaning = OWN[c]
        elif k == "part" and g:
            meaning = g[1]
        elif c in MEANS:
            meaning = MEANS[c]
        else:
            meaning = (plain(ced.get(c, []), py[0], hint=d.get("definition")) if py else "") or old
        before[c] = old
        it = {"h": c, "k": k, "py": py[:2], "en": meaning,
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

    if a.sample:
        chars = [it for it in items if it["k"] == "char"]
        for it in random.Random(7).sample(chars, min(a.sample, len(chars))):
            print(f"{it['h']} {'/'.join(it['py'])[:12]:12} {before[it['h']][:34]:34} -> {it['en']}")

    out = os.path.join(ROOT, "data")
    os.makedirs(out, exist_ok=True)
    for f in os.listdir(out):
        if f.startswith("strokes-"):
            os.remove(os.path.join(out, f))
    path = {"source": "tools/build.py: Make Me a Hanzi (LGPL / Arphic PL), Jun Da's frequency list, "
                      "CC-CEDICT (CC BY-SA 4.0), sources/parts.json, HSK 2.0 words (MIT)",
            "chunk": CHUNK, "items": items}
    with open(os.path.join(out, "path.json"), "w", encoding="utf-8") as f:
        json.dump(path, f, ensure_ascii=False, separators=(",", ":"))
    for i in range(0, len(order), CHUNK):
        chunk = {c: {"strokes": gfx[c]["strokes"], "medians": gfx[c]["medians"]} for c in order[i:i + CHUNK]}
        with open(os.path.join(out, f"strokes-{i // CHUNK}.json"), "w", encoding="utf-8") as f:
            json.dump(chunk, f, ensure_ascii=False, separators=(",", ":"))

    parts = sum(1 for it in items if it["k"] == "part")
    hskw = sum(1 for it in items for w in it.get("w", []) if w[3])
    cedw = sum(1 for it in items for w in it.get("w", []) if not w[3])
    print(f"{len(items)} on the path: {len(items) - parts} characters, {parts} parts; "
          f"words: {hskw} HSK, {cedw} from CC-CEDICT; {(len(items) + CHUNK - 1) // CHUNK} stroke files")


if __name__ == "__main__":
    main()
