package dev.stefanofrigerio.hanzipath;

/** One step of the path: a character, or a part (氵, 亻) taught for what it means in one. */
class Item {
    String h, kind, en;
    String[] py, parts, used;
    String[][] words;     // [word, pinyin, meaning, HSK level]
    int strokes, at;
    String etType, etHint, etSemantic, etPhonetic;

    boolean isPart() { return "part".equals(kind); }

    String reading() { return py.length > 0 ? py[0] : ""; }
}
