package dev.stefanofrigerio.hanzipath;

/** One step of the path: a character, or a part (氵, 亻) taught for what it means in one. */
class Item {
    String h, kind, en;
    String[] py, parts, used;
    String[][] words;     // [word, pinyin, meaning, HSK level]
    int strokes, at;
    String etType, etSemantic, etPhonetic;
    String why;           // "Why it looks like this", a plain line or two (tools/build.py)

    boolean isPart() { return "part".equals(kind); }

    String reading() { return py.length > 0 ? py[0] : ""; }
}
