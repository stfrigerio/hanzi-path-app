#!/usr/bin/env python3
"""Builds the path: data/path.json, data/strokes-N.json and data/sentences.json.

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

Sentences (data/sentences.json): Tatoeba's Mandarin sentences with an English
translation (CC BY 2.0 FR, fetched into the cache too), only those in simplified
characters that are all on the path; a few for each place on the path, from where
they become readable. Their pinyin from the words in them (HSK's, CC-CEDICT's).

Usage: tools/build.py [--chars 1000] [--more 2000] [--src DIR] [--sample N] [--why N] [--sentences N]
"""
import argparse
import bz2
import gzip
import json
import os
import random
import re
import tarfile
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
       "㐌": "snake", "龺": "sunrise", "禹": "Yu, who tamed the flood",
       # the second thousand's
       "龹": "roll", "矣": "(done, literary)", "毌": "pierce", "玨": "two jades", "囷": "granary",
       "亨": "prosperous", "尧": "Yao (a sage king)"}
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
    "中": "middle; center; in", "干": "to do; to work; dry", "切": "close to; eager; earnest",
    # the path's second thousand (2026-11-07): the dictionary's rarer first senses (店 inn,
    # 筑 lute, 峰 "mountain-like"), its housekeeping left in ("Taiwan pr", "classifier for"),
    # surnames and states said as what they are, and what HSK's words use them for
    "镇": "town; to calm; to press down", "篇": "piece of writing; sheet", "馆": "hall; building (library, hotel)",
    "遍": "all over; a time (once through)", "洞": "cave; hole", "番": "a time; a turn; foreign",
    "森": "forest; dense", "戏": "play; drama; game", "访": "to visit; to inquire", "店": "shop; store; inn",
    "鬼": "ghost; spirit; devil", "曲": "bent; crooked; song", "典": "classic; ceremony; canon",
    "萨": "sa (sound); Bodhisattva", "曹": "Cao (a surname)", "雄": "male; heroic; grand",
    "乙": "second (of ten); B", "拥": "to hug; to crowd; to have", "杨": "poplar; Yang (a surname)",
    "虚": "empty; false; weak", "析": "to analyze; to separate", "秀": "elegant; outstanding; show",
    "透": "to pass through; thorough", "替": "to replace; for; instead of", "套": "set (of things); cover",
    "毕": "to finish; to complete", "促": "to urge; to promote; hurried", "甲": "first (of ten); shell; armor",
    "麻": "hemp; sesame; numb", "潜": "to dive; hidden; latent", "闪": "to flash; to dodge",
    "折": "to break; to fold; discount", "哲": "wise; sage; philosophy", "络": "net; web; to wind",
    "抵": "to resist; to reach; to offset", "筑": "to build; to construct", "郑": "Zheng (a surname); solemn",
    "尊": "to respect; honorable; senior", "昆": "elder brother; numerous", "雅": "elegant; refined",
    "染": "to dye; to catch (illness)", "残": "remaining; damaged; cruel", "培": "to cultivate; to foster",
    "迷": "lost; to confuse; fan", "宇": "universe; space; house", "孟": "Meng (Mencius); eldest",
    "盟": "alliance; oath; league", "赵": "Zhao (a state, a surname)", "蒋": "Jiang (a surname)",
    "操": "to grasp; to operate; exercise", "耶": "ye (sound); 耶稣 Jesus", "订": "to book; to order; to agree",
    "彩": "color; colorful; prize", "抽": "to draw out; to smoke", "赞": "to praise; to support",
    "魔": "devil; demon; magic", "汇": "to gather; to remit; collection", "啦": "(了 and 啊 together)",
    "莱": "lai (sound); a wild herb", "夺": "to seize; to take away", "裁": "to cut (cloth); to judge",
    "课": "class; lesson; subject", "夹": "to clip; to pinch; folder", "拜": "to bow to; to pay a visit",
    "巧": "skillful; clever; by chance", "韩": "Korea; Han (a state)", "冒": "to give off; to risk",
    "惯": "used to; habit; to spoil", "戴": "to wear (on the head)", "犹": "still; as if; like",
    "乘": "to ride; to take (a bus); times", "绍": "to carry on; 介绍 to introduce",
    "厚": "thick; deep; generous", "纵": "vertical; to let go; even if", "讯": "news; message; to ask",
    "涉": "to wade; to involve", "刊": "to print; periodical", "役": "service; labor; battle",
    "玛": "agate; ma (in names)", "患": "trouble; illness; to suffer", "签": "to sign; label; tag",
    "倾": "to lean; to tip over; to pour", "牌": "card; sign; brand", "陷": "trap; to sink; to frame",
    "咱": "we (you and I)", "菜": "vegetable; dish (of food)", "疾": "disease; fast; to hate",
    "缘": "fate; cause; edge", "杜": "to stop; to prevent; Du", "奶": "milk; breast; grandma",
    "仪": "instrument; ceremony; bearing", "驻": "to stay; to be stationed", "孔": "hole; Kong (Confucius)",
    "宜": "suitable; proper; should", "淡": "pale; light; bland", "愈": "the more (愈...愈); to heal",
    "宿": "to lodge; dormitory; old", "缩": "to shrink; to draw back", "挑": "to choose; to carry (on a pole)",
    "幕": "curtain; screen; act (of a play)", "映": "to reflect; to show (a film)", "泰": "safe; peaceful; Thailand",
    "暂": "temporary; for now", "胞": "womb; born of one mother", "婆": "old woman; grandmother",
    "腿": "leg; thigh", "津": "ford; saliva", "晓": "dawn; to know; to tell", "偶": "by chance; idol; even (number)",
    "辑": "to collect; to edit", "恢": "to restore (恢复); vast", "赖": "to rely on; to blame",
    "辆": "(measure word: vehicles)", "废": "to abolish; useless; waste", "矿": "mine; ore",
    "伏": "to lie prone; to hide; to give in", "鸡": "chicken", "倍": "times (twice); -fold",
    "秦": "Qin (dynasty)", "盾": "shield", "租": "to rent; rent", "骑": "to ride (a horse, a bike)",
    "摄": "to absorb; to photograph", "凭": "to rely on; based on; proof", "抢": "to snatch; to rob; to rush",
    "绪": "thread; mood; beginning", "粗": "thick; coarse; rough", "幻": "illusion; fantasy",
    "菲": "luxuriant; the Philippines", "汤": "soup; hot water", "荡": "to sway; to swing; to wash",
    "悉": "to know; all", "蒂": "stem (of a fruit)", "薄": "thin; slight; meager",
    "幅": "width; (measure word: pictures)", "岂": "how? (rhetorical)", "凯": "triumphant; victory song",
    "颗": "(measure word: small round things)", "赏": "to reward; to admire", "漫": "to overflow; free; long",
    "郎": "young man; (old) official", "腰": "waist; lower back", "舒": "to stretch; comfortable",
    "眉": "eyebrow", "恋": "love; to long for", "挺": "very; quite; straight", "辞": "to resign; to take leave; words",
    "艘": "(measure word: boats)", "伍": "ranks; company; five", "峰": "peak; summit",
    "券": "ticket; coupon; bond", "趋": "to hurry; to tend to; trend", "阅": "to read; to review",
    "汗": "sweat", "墨": "ink", "彭": "Peng (a surname)", "慧": "wise; intelligent",
    "佩": "to admire; to wear (at the belt)", "扑": "to pounce; to throw oneself at",
    "惜": "to cherish; to regret; pity", "兼": "both; at once; double", "帕": "kerchief; handkerchief",
    "驶": "to drive; to sail", "届": "session (of a meeting); to fall due", "惠": "kindness; favor; benefit",
    "闲": "idle; free time; leisure", "邪": "evil; wicked", "滚": "to roll; to boil",
    "奏": "to play (music); to report", "颜": "face; color", "绩": "achievement; merit; to spin",
    "锁": "lock; to lock", "戒": "to guard against; to give up; ring", "驾": "to drive; to harness",
    "漂": "pretty (漂亮); to float; to drift", "朵": "(measure word: flowers, clouds)", "仔": "careful (仔细); young",
    "俩": "two (people); both", "践": "to carry out; to tread", "籍": "record; book; nationality",
    "浓": "thick; strong (tea); dense", "逻": "to patrol; logic (逻辑)", "脉": "pulse; vein",
    "帐": "tent; curtain; account", "贷": "loan; to lend", "躺": "to lie down", "寄": "to send (by post); to entrust",
    "扶": "to support; to help up", "邓": "Deng (a surname)", "奈": "how; but (无奈)",
    "澳": "bay; Australia; Macao", "嘛": "(obviously!; ...then)", "凝": "to congeal; to concentrate",
    "辖": "to govern; linchpin", "魏": "Wei (a state, a surname)", "慌": "flustered; panicky",
    "祝": "to wish; to pray for", "械": "tool; machine; weapon", "挤": "to squeeze; to crowd",
    "揭": "to uncover; to expose", "悟": "to realize; to awaken", "锋": "sharp edge; point; front",
    "猫": "cat", "阔": "wide; broad; rich", "筹": "to raise (funds); to plan; chip",
    "沈": "to sink; Shen (a surname)", "猎": "to hunt; hunting", "瑟": "a large zither",
    "逊": "modest; inferior to", "欺": "to deceive; to bully", "吨": "ton", "琴": "zither; piano (钢琴)",
    "恼": "angry; annoyed", "燕": "swallow (the bird)", "仲": "second (of brothers); middle",
    "疼": "to ache; sore; to dote on", "卢": "Lu (a surname)", "仗": "battle; to rely on; weapons",
    "冯": "Feng (a surname)", "敦": "kindhearted; sincere", "纠": "to correct; to entangle",
    "凌": "to rise high; to insult; ice", "敲": "to knock; to strike", "赔": "to compensate; to lose (money)",
    "熊": "bear", "赋": "to bestow; tax; poetic essay", "秩": "order", "烂": "rotten; soft; worn out",
    "袁": "Yuan (a surname)", "肝": "liver", "猪": "pig", "丑": "ugly; clown",
    "纽": "knob; button; tie", "玻": "glass (玻璃)", "夸": "to praise; to boast",
    "押": "to pawn; to detain; to escort", "疲": "tired; weary", "醉": "drunk",
    "弥": "to fill; full; all over", "档": "file; records; grade", "捷": "quick; victory",
    "岗": "post; sentry; hillock", "癌": "cancer", "肚": "belly; stomach", "涨": "to rise (prices, water)",
    "坛": "altar; platform; forum", "郭": "outer city wall; Guo", "牺": "sacrifice (牺牲)",
    "廉": "honest; cheap", "契": "contract; deed; to carve", "拼": "to piece together; to spell",
    "捉": "to catch; to grab", "覆": "to cover; to overturn", "刷": "brush; to brush",
    "唤": "to call; to call out", "赢": "to win; to beat", "搭": "to build; to take (a ride)",
    "俊": "handsome; talented", "庞": "huge; Pang (a surname)", "锐": "sharp; keen",
    "玲": "tinkling of jade; clever", "惟": "only; but", "呵": "to breathe out; ha!",
    "酷": "cruel; extreme; cool", "掠": "to rob; to sweep past", "妖": "demon; bewitching",
    "挣": "to earn; to struggle free", "汪": "vast (water); Wang", "枚": "(measure word: coins, small things)",
    "傅": "teacher; tutor (师傅)", "帅": "handsome; commander", "璃": "glass (玻璃)",
    "愚": "foolish; stupid", "滴": "drop; to drip", "疏": "sparse; distant; to dredge",
    "姿": "posture; appearance", "盯": "to stare at", "哦": "oh!", "腔": "cavity; tune; accent",
    "郁": "lush; gloomy", "溃": "to burst; to collapse", "糟": "bad; messy (糟糕); dregs",
    "葛": "kudzu; Ge (a surname)", "忌": "to avoid; to envy; taboo", "鸿": "wild goose; great",
    "鹏": "roc (a mythical bird)", "纲": "outline; guiding principle", "茨": "thatch; caltrop",
    "膀": "shoulder (肩膀); wing", "贤": "worthy; virtuous", "毅": "resolute; firm",
    "函": "letter; envelope", "茫": "vast; boundless; vague", "薛": "Xue (a surname)",
    "豫": "to hesitate (犹豫); happy", "拾": "to pick up; ten (in writing)", "睁": "to open (the eyes)",
    "晴": "clear (weather); sunny", "填": "to fill; to fill in", "扮": "to dress up as; to play (a role)",
    "罩": "cover; to cover", "柴": "firewood", "愉": "happy; pleased", "喂": "hey; hello; to feed",
    "傻": "foolish; silly", "奸": "treacherous; traitor; evil", "悦": "happy; pleased",
    "拦": "to block; to stop", "饱": "full (from eating)", "乾": "heaven (☰); male principle",
}


# "Why it looks like this": Make Me a Hanzi's etymology, made into a line or two a learner
# reads (why()). Their hints are notes, not sentences: a pictophonetic one is what the
# meaning part means in this character ("sitting man" for 匕 in 此), the others a picture's
# description with clauses after semicolons ("…; 乍 also provides the pronunciation").
# Typos and run-together text in theirs:
HINT_FIX = [("blanklets", "blankets"), ("pronuncation", "pronunciation"), ("couryard", "courtyard"),
            ("eachother", "each other"), ("Th sun", "The sun"), (" ,", ","), ("side-by-side", "side by side")]
# a meaning part's sense, by (part, hint), where theirs misleads: 攵 is a hand that strikes
# (their "script" and "rap" are 攴's other names), 肖's 'flesh - "in the flesh"'
GLOSS = {("攵", "script"): "strike", ("攵", "rap"): "strike", ("攵", "tap"): "strike",
         ("⺼", 'flesh - "in the flesh"'): "flesh", ("冫", "water in a spirit level"): "water in a level",
         ("非", "people back-to-back"): "two people back to back", ("毒", "a poisonous plant"): "a poisonous plant"}
# whole lines by hand, where the hint can't be made to read well
WHY = {
    "声": "Simplified form of 聲, a stone chime 声 struck by a hand 殳 and heard by the ear 耳.",
    "冏": "An old form of 炯 \"bright\": a window letting in the light.",
    "曷": "A variant of 何 \"what\".",
    "他": "One more person 亻, an \"also\" 也 person.",
    "夭": "A person with the head tilted: leaning forward, running, full of energy.",
    "北": "Two people 匕 sitting back to back. Borrowed for its sound to mean \"north\".",
    "亻": "A short form of 人, written at a character's left.",
    "亭": "亠, 口 and 冖 make a picture of a pavilion; 丁 (dīng) gives the sound.",
    "少": "Grains of sand; 小 (xiǎo) gives both the sound and the meaning.",
    "屋": "To stop 至 under a roof 尸 (here a form of 厂).",
    "军": "Soldiers 冖 (a bent 力) in a cart 车.",
    "率": "A string 幺 vibrating in an instrument 亠 十.",
    # the second thousand's
    "私": "Grain 禾 kept for oneself 厶; 厶 (sī) gives the meaning and the sound.",
    "扩": "扌 (hand) gives the meaning and 广 (guǎng) the sound: simplified from 擴.",
    "赛": "贝 (money) gives the meaning; the top, cut from 塞 (sài), the sound.",
    "俩": "Two 两 people 亻; 两 (liǎng) also gives the sound.",
    "帕": "巾 (cloth) gives the meaning and 白 (bái) the sound.",
    "麦": "Simplified form of 麥; grains 來 ready to be harvested 夂.",
    "卑": "A hand 又 (altered) holding an empty shell 甲 (altered).",
    "宾": "A soldier 兵 quartered under one's roof 宀.",
}
# a sound part's reading where Make Me a Hanzi's has no tone (卜 "bo": bǔ, as in 补 bǔ)
SOUND = {"卜": "bǔ"}
HAN = r"[⺀-⿿㐀-鿿\U00020000-\U0002ffff]"


def tidy(hint):
    """Their hint with its spaces, typos and run-together characters put right."""
    s = re.sub(r"\s+", " ", (hint or "").replace("\xa0", " ")).strip()
    for a, b in HINT_FIX:
        s = s.replace(a, b)
    s = re.sub(rf"([A-Za-z])({HAN})", r"\1 \2", s)                 # "person卩" -> "person 卩"
    s = re.sub(rf"({HAN})([A-Za-z])", r"\1 \2", s)                 # "阝of" -> "阝 of"
    return s


def sentence(s):
    s = s.strip().rstrip(";,")
    if not s:
        return ""
    s = s[0].upper() + s[1:]
    return s if s[-1] in ".?!" else s + "."


def why(c, e, items, dic):
    """A plain line or two: what the picture shows, which part gives the meaning, which the
    sound (with its reading, as the app shows it). Empty when Make Me a Hanzi has nothing."""
    if c in WHY:
        return WHY[c]

    def py(h):
        # the reading the app shows first, unless it's toneless (了 le, 卜 bo): a part gives
        # a sound with a tone (疗 liáo from 了 liǎo)
        if h in SOUND:
            return SOUND[h]
        p = list((items.get(h) or {}).get("py") or []) + list((dic.get(h) or {}).get("pinyin") or [])
        toned = [r for r in p if re.search("[āáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜ]", r)]
        return (toned or p or [""])[0]

    def said(h):
        return f"{h} ({py(h)})" if py(h) else h

    def first(h):
        it = items.get(h)
        return re.split(r"[;,]", it["en"])[0].strip() if it and it.get("en") else ""

    kind, hint = e.get("type"), tidy(e.get("hint"))
    if kind == "pictophonetic":
        s, p = e.get("semantic"), e.get("phonetic")
        if not s and not p:
            return sentence(hint) if hint else ""
        gloss, rest = hint, ""
        if "; " in gloss:
            gloss, rest = gloss.split("; ", 1)
        gloss = GLOSS.get((s, gloss), gloss) or first(s) if s else ""
        mean = f"{s} ({gloss})" if s and gloss else (s or "")
        if s and p == s:
            out = f"{mean} gives the meaning and the sound."
        elif s and p:
            out = f"{mean} gives the meaning and {said(p)} the sound."
        elif s:
            out = f"{mean} gives the meaning."
        else:
            out = f"{said(p)} gives the sound."
        return out + (" " + sentence(rest) if rest else "")
    if not hint:
        return ""
    out = []
    for clause in hint.split("; "):
        m = re.match(rf"^({HAN}) (also )?provides the (pronunciation|meaning)( and (meaning|pronunciation))?$", clause)
        if m and out:
            h, also, what = m.group(1), m.group(2), m.group(3)
            if what == "meaning":
                out.append(sentence(f"{h} ({first(h)}) gives the meaning" if first(h) else f"{h} gives the meaning"))
            elif also and out[-1].rstrip(".").endswith(" " + h):
                # the part named last: "…for the first time 乍 (zhà), which also gives the sound."
                out[-1] = out[-1].rstrip(".") + (f" ({py(h)})" if py(h) else "") + ", which also gives the sound."
            else:
                out.append(sentence(f"{said(h)} {'also ' if also else ''}gives the sound"))
            continue
        m = re.match(r"^(compare|see|variant of|cursive version of|phonetic loan for) (.*)$", clause)
        if m and out:
            word = {"compare": "Compare", "see": "See", "variant of": "A variant of",
                    "cursive version of": "A cursive form of", "phonetic loan for": "Borrowed for its sound to mean"}
            out.append(sentence(f"{word[m.group(1)]} {m.group(2)}"))
            continue
        if out:
            out[-1] = out[-1].rstrip(".") + "; " + clause + "."
        else:
            out.append(sentence(clause))
    line = " ".join(out)
    line = re.sub(r"^Visual abbreviation of", "A short form of", line)
    return line


def why_before(c, e, items):
    """The line as the app built it before why(): for --why's before and after."""
    s, p, hint = e.get("semantic"), e.get("phonetic"), e.get("hint")
    b = ""
    if e.get("type") == "pictophonetic" and (s or p):
        if s:
            b += s + (" " + items[s]["en"] if s in items else "") + " gives the meaning"
        if p:
            b += (", " if b else "") + p + (f" ({items[p]['py'][0]})" if p in items and items[p]["py"] else "") + " the sound"
        b += "."
        if hint:
            b += " " + hint + "."
    elif hint:
        b = hint + "."
    return b


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
        "干": "gàn",     # gàn "to do" (干什么, 干活) is met first in speech; gān (干净) as "also"
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
        "挑": "tiāo",    # to choose (挑选, 挑 alone in speech); tiǎo in 挑战 is the "also"
        }


def tone(syl):
    return unicodedata.normalize("NFC", syl.lower())


def toneless(syl):
    """'lǚ' -> 'lü', 'hǎo' -> 'hao'."""
    return unicodedata.normalize("NFC", "".join(
        ch for ch in unicodedata.normalize("NFD", tone(syl)) if ord(ch) < 0x300 or ord(ch) == 0x308))


def tone_of(syl):
    """1-4 from a syllable's mark, 5 for the neutral tone."""
    for ch in unicodedata.normalize("NFD", syl):
        t = {0x304: 1, 0x301: 2, 0x30C: 3, 0x300: 4}.get(ord(ch))
        if t:
            return t
    return 5


# words said with a neutral tone that a source gives toned (CC-CEDICT's first entry,
# or its only one): the reading HSK or CC-CEDICT also give, and the Xiandai Hanyu
# Cidian's light tone. Applied where the toneless syllables match, so 东西 dōng xī
# "east and west" becomes dōng xi "thing", the commoner by far
NEUTRAL = {"东西": "dōng xi", "意思": "yì si", "时候": "shí hou", "认识": "rèn shi",
           "知道": "zhī dao", "告诉": "gào su", "朋友": "péng you", "喜欢": "xǐ huan",
           "客气": "kè qi", "衣服": "yī fu", "地方": "dì fang", "事情": "shì qing",
           "先生": "xiān sheng", "明白": "míng bai", "故事": "gù shi",
           "生意": "shēng yi", "本事": "běn shi", "工夫": "gōng fu", "买卖": "mǎi mai"}
# directional complements, said light after a verb (站起来 zhàn qǐ lai) and as a verb
# themselves (回来 huí lai): the second syllable loses its tone
COMPLEMENT = {"过来": "lai", "起来": "lai", "出来": "lai", "回来": "lai", "下来": "lai",
              "出去": "qu", "回去": "qu"}
DIGIT = set("零〇一二三四五六七八九十")       # 一 before one is counting: 一二, 一九八一
NUMERAL = DIGIT | set("百千万两")              # and after a number: 十一, 一百一
DROPS = set("是有做给举拿买找生了")             # verbs said with 个 for 一个: 是个, 有个


def light(w, syl):
    """A word's syllables with the neutral tones NEUTRAL and COMPLEMENT know."""
    if w in NEUTRAL and [toneless(s) for s in syl] == [toneless(s) for s in NEUTRAL[w].split()]:
        return NEUTRAL[w].split()
    if w[-2:] in COMPLEMENT and toneless(syl[-1]) == COMPLEMENT[w[-2:]]:
        return syl[:-1] + [COMPLEMENT[w[-2:]]]
    return syl


def spoken(han, syl, ends=(), stops=(), heads=()):
    """Dictionary tones to the ones said, a syllable per character of han: 不 bú before a
    4th tone and bu in 是不是; 一 yí before a 4th tone, yì before the others, yī counting
    (十一, 第一, 一二, 一月), alone or ending a word (统一, in ends), yi in 看一看; 个 ge
    as a measure word: after a number or 这, 那, 哪, 几, 每, 某 (一个, 这个), and after a
    verb that drops the 一 (是个好人, 有个问题) unless 个 starts a word (有个性). ends and
    heads: where a word of two or more ends and starts; stops: the characters a
    punctuation mark follows. The 3rd-tone change (你好 ní hǎo) isn't
    written: a learner reads it from the two 3rds."""
    out = list(syl)
    n = len(han)

    def near(i, d):
        """The character before (d -1) or after (d 1) han[i] in the same clause, or ""."""
        j = i + d
        if j < 0 or j >= n or (d > 0 and i in stops) or (d < 0 and j in stops):
            return ""
        return han[j]

    for i, c in enumerate(han):
        if c == "个" and tone(syl[i]) == "gè" and (near(i, -1) in NUMERAL | set("这那哪几每某") or
                                                   near(i, -1) in DROPS and i not in heads):
            out[i] = "ge"
    for i, c in enumerate(han):
        prev, nxt = near(i, -1), near(i, 1)
        if c == "一" and tone(syl[i]) == "yī":
            if not nxt or i in ends or prev in NUMERAL or nxt in DIGIT or prev in ("第", "期", "拜") \
                    or nxt in ("月", "号") or han[i + 1:i + 3] == "年级":
                continue                        # counting, an ordinal, a date, alone, a word's end
            if prev == nxt:
                out[i] = "yi"                   # 看一看
            elif nxt == "个" or tone_of(syl[i + 1]) == 4:
                out[i] = "yí"
            elif tone_of(syl[i + 1]) < 4:
                out[i] = "yì"
        elif c == "不" and tone(syl[i]) == "bù" and nxt:
            if prev == nxt and prev != "不":
                out[i] = "bu"                   # 是不是
            elif tone_of(syl[i + 1]) == 4:
                out[i] = "bú"
    return out


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


# ── sentences: reading practice (Tatoeba, CC BY 2.0 FR) ──
TATOEBA = "https://downloads.tatoeba.org/exports/"
SENTENCES_PER_ITEM = 3
SHORTEST, LONGEST = 4, 20        # characters in a sentence, punctuation not counted
# punctuation a sentence may have, as its pinyin writes it: closing marks stick to the
# syllable before them, opening ones to the one after
CLOSE = {"，": ",", "。": ".", "？": "?", "！": "!", "、": ",", "：": ":", "；": ";", "”": "\"", "’": "'",
         "…": "…", "》": "\"", "）": ")", "」": "\"", "—": "—", "·": "·"}
OPEN = {"“": "\"", "‘": "'", "《": "\"", "（": "(", "「": "\""}
ASCII = {",": "，", ".": "。", "?": "？", "!": "！", ":": "：", ";": "；", "(": "（", ")": "）"}
# sentences not for a first reader, whatever their characters
HARSH = re.compile(r"\b(sex\w*|naked|kill\w*|murder\w*|suicide|rap(e|ed|ist)|corpse|penis|vagina|"
                   r"prostitut\w*|fuck\w*|shit|damn|bitch|whore|guns?|drugs?|drunk)\b", re.I)
SYLLABLE = re.compile("^[a-zü" + "".join(v for vs in TONES.values() for v in vs) + "]+$")


def tsv(path):
    """Tatoeba's export of a language: {id: text}."""
    out = {}
    with bz2.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) == 3:
                out[int(p[0])] = p[2].strip()
    return out


def links(path, wanted):
    """{Mandarin id: [ids of its translations]}, from links.csv (every pair, both ways)."""
    out = {}
    with tarfile.open(path, "r:bz2") as t:
        for m in t:
            if not m.name.endswith("links.csv"):
                continue
            for line in t.extractfile(m):
                a, _, b = line.partition(b"\t")
                if int(a) in wanted:
                    out.setdefault(int(a), []).append(int(b))
    return out


def lexicon(ced, hsk, onpath):
    """{word: (its syllables, an HSK word)}: HSK's words with their own pinyin, then
    CC-CEDICT's (not names), two to four characters of the path: a sentence's pinyin is
    read from the words in it."""
    lex = {}
    for lvl in sorted(hsk, key=int):
        for w, py, en in hsk[lvl]:
            syl = [tone(s) for s in py.replace("'", " ").split()]
            if 2 <= len(w) <= 4 and len(syl) == len(w) and w not in lex and all(c in onpath for c in w):
                lex[w] = (syl, True)
    for w, es in ced.items():
        if not 2 <= len(w) <= 4 or w in lex or not all(c in onpath for c in w):
            continue
        es = [e for e in es if e[0][:1].islower() and senses([e])]
        if es:
            syl = [tone(s) for s in es[0][0].split()]
            if len(syl) == len(w) and all(SYLLABLE.match(s) for s in syl):
                lex[w] = (syl, False)
    return lex


def said(han, lex, main, stops=()):
    """A sentence's syllables, one per character: split into the fewest words the lexicon
    knows (more of HSK's on a tie), each read as it's said in that word (了解 liǎo, 睡觉
    jiào); a character alone by its main reading, but de for 地 after a doubled word (慢慢地)
    and for 得 before how something's done (说得很好). Then as it's spoken (spoken(), light()):
    不是 bú shì, 一个 yí ge, 东西 dōng xi. stops: the characters a punctuation mark follows."""
    n = len(han)
    best = [(0, 0, None)] + [None] * n      # (words, -characters in HSK words, (start, syllables))
    for i in range(1, n + 1):
        for j in range(max(0, i - 4), i):
            w = han[j:i]
            if best[j] is None:
                continue
            if len(w) == 1:
                syl, inhsk = [main[w]], False
            elif w in lex:
                syl, inhsk = lex[w]
            else:
                continue
            cand = (best[j][0] + 1, best[j][1] - (len(w) if inhsk else 0), (j, syl))
            if best[i] is None or cand[:2] < best[i][:2]:
                best[i] = cand
    words, i = [], n
    while i > 0:
        j, syl = best[i][2]
        words.insert(0, (han[j:i], syl))
        i = j
    out, ends, heads = [], set(), set()
    for w, syl in words:
        k = len(out)
        if w == "地" and k >= 2 and han[k - 1] == han[k - 2]:
            syl = ["de"]
        elif w == "得" and 0 < k < n - 1 and han[k + 1] in "很太非真好快慢多少清越更最不":
            syl = ["de"]
        if len(w) > 1:
            heads.add(k)
            ends.add(k + len(w) - 1)
        out += light(w, syl)
    return spoken(han, out, ends, stops, heads)


def sentences(src, items, ced, hsk):
    """[[sentence, pinyin, English, the path index it's readable from]]: Tatoeba's Mandarin
    sentences in simplified characters, every one of them on the path, with an English
    translation; at each place on the path the few that become readable there (the
    shortest, unlike each other and every English already taken). The pinyin is a syllable
    per character, in order, with the sentence's punctuation stuck to them, so the app can
    find a character's."""
    cmn = tsv(fetch(src, "cmn_sentences.tsv.bz2", TATOEBA + "per_language/cmn/cmn_sentences.tsv.bz2"))
    pos = {it["h"]: i for i, it in enumerate(items)}
    main = {it["h"]: it["py"][0] for it in items if it["py"]}
    # characters only a traditional text writes (們, 試): a sentence with one is traditional
    trad = {e[2] for w, es in ced.items() if len(w) == 1 for e in es if e[2] != w} - set(ced) - set(pos)

    cand, seen = {}, set()
    for i, text in sorted(cmn.items()):
        s = "".join(ASCII.get(c, c) for c in text.replace(" ", "").replace("　", ""))
        if s in seen:
            continue
        seen.add(s)
        han = [c for c in s if c not in CLOSE and c not in OPEN]
        if SHORTEST <= len(han) <= LONGEST and all(c in main and c not in trad for c in han):
            cand[i] = s
    eng = tsv(fetch(src, "eng_sentences.tsv.bz2", TATOEBA + "per_language/eng/eng_sentences.tsv.bz2"))
    tr = links(fetch(src, "links.tar.bz2", TATOEBA + "links.tar.bz2"), set(cand))

    lex = lexicon(ced, hsk, set(main))
    at = {}
    for i, s in cand.items():
        en = [eng[j] for j in sorted(tr.get(i, [])) if j in eng]
        if not en or any(HARSH.search(e) for e in en) or len(en[0]) > 90:
            continue
        han = "".join(c for c in s if c not in CLOSE and c not in OPEN)
        at.setdefault(max(pos[c] for c in han), []).append((len(han), i, s, han, en[0]))

    out, taken = [], set()
    for p in sorted(at):
        picked = []
        for n, i, s, han, en in sorted(at[p]):
            key = en.lower().strip(" .!?")
            if key in taken or any(han[:2] == h[:2] or len(set(han) & set(h)) > .6 * len(set(han) | set(h))
                                   for _, h, _ in picked):
                continue
            picked.append((s, han, en))
            taken.add(key)
            if len(picked) == SENTENCES_PER_ITEM:
                break
        for s, han, en in picked:
            stops, k = set(), 0
            for c in s:
                if c in OPEN or c in CLOSE:
                    if k:
                        stops.add(k - 1)
                else:
                    k += 1
            syl = said(han, lex, main, stops)
            toks, k, pre = [], 0, ""
            for c in s:
                if c in OPEN:
                    pre += OPEN[c]
                elif c in CLOSE:
                    if toks:
                        toks[-1] += CLOSE[c]
                else:
                    toks.append(pre + syl[k])
                    pre, k = "", k + 1
            out.append([s, " ".join(toks), en, p])
    return out


def components(entry):
    return [c for c in entry.get("decomposition", "") if c not in IDC and c not in "？"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--chars", type=int, default=1000, help="the characters the path was first made of")
    ap.add_argument("--more", type=int, default=2000, help="the characters it goes on to, by frequency")
    ap.add_argument("--src", default=CACHE)
    ap.add_argument("--sample", type=int, default=0, help="print N meanings, before and after")
    ap.add_argument("--sentences", type=int, default=0, help="print N sentences with their pinyin")
    ap.add_argument("--why", type=int, default=0, help="print the first N items' etymology lines, before and after")
    a = ap.parse_args()

    dic = jsonl(fetch(a.src, "dictionary.txt", MMH + "dictionary.txt"))
    gfx = jsonl(fetch(a.src, "graphics.txt", MMH + "graphics.txt"))
    freq = frequency(fetch(a.src, "junda.html", JUNDA))
    ced = cedict(fetch(a.src, "cedict_1_0_ts_utf-8_mdbg.txt.gz", CEDICT))
    garden = json.load(open(os.path.join(ROOT, "sources/parts.json"), encoding="utf-8"))["parts"]
    hsk = json.load(open(os.path.join(ROOT, "sources/hsk.json"), encoding="utf-8"))["levels"]

    rank = {c: i for i, c in enumerate(freq)}
    used, alone = usage(ced, hsk, rank)
    every = [c for c in freq if c in dic and c in gfx]
    top, more = every[:a.chars], every[a.chars:a.more]
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

    def make(order, common):
        """The items for a path in this order, common the characters taught as characters."""
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
        # as they're said: 不对 bú duì, 一样 yí yàng, 东西 dōng xi
        for lst in words.values():
            for wd in lst:
                syl = wd[1].split()
                if len(syl) == len(wd[0]):
                    wd[1] = " ".join(spoken(wd[0], light(wd[0], syl), {len(wd[0]) - 1}))

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
                it["et"] = {k: v for k, v in e.items() if k in ("type", "semantic", "phonetic")}
            if c in words:
                it["w"] = words[c]
            if c in users:
                it["used"] = users[c][:6]
            items.append(it)

        # why it looks like this: a plain line from the etymology, once every reading is known
        at = {it["h"]: it for it in items}
        for it in items:
            e = dic[it["h"]].get("etymology") or {}
            w = why(it["h"], e, at, dic)
            if w:
                it.setdefault("et", {})["why"] = w
        return items, before

    # the path as it was first walked (the first --chars characters): its order and every
    # item's fields stay as they are, people's progress is keyed by them. Then the path goes
    # on (--more): the next characters by frequency, each after the parts it needs that
    # aren't on the path yet, built the same way with the whole path known; only the new
    # items are taken from it, so an early item gains no words, uses or parts
    items, before = make(order, set(common))
    walked = len(order)
    common.update(more)
    for c in more:
        visit(c)
    rest, later = make(order, set(common))
    items += rest[walked:]
    before.update({c: later[c] for c in order[walked:]})
    at = {it["h"]: it for it in items}

    if a.why:
        for i, it in enumerate(items[:a.why]):
            e = dic[it["h"]].get("etymology") or {}
            print(f"{i} {it['h']}\n  before: {why_before(it['h'], e, at)}\n  after:  {it.get('et', {}).get('why', '')}")

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

    sents = sentences(a.src, items, ced, hsk)
    with open(os.path.join(out, "sentences.json"), "w", encoding="utf-8") as f:
        json.dump({"source": "Tatoeba (tatoeba.org), CC BY 2.0 FR; pinyin by tools/build.py", "s": sents},
                  f, ensure_ascii=False, separators=(",", ":"))
    if a.sentences:
        for s in random.Random(3).sample(sents, min(a.sentences, len(sents))):
            print(f"{s[3]:4} {s[0]}  {s[1]}  {s[2]}")

    parts = sum(1 for it in items if it["k"] == "part")
    hskw = sum(1 for it in items for w in it.get("w", []) if w[3])
    cedw = sum(1 for it in items for w in it.get("w", []) if not w[3])
    print(f"{len(items)} on the path: {len(items) - parts} characters, {parts} parts; "
          f"words: {hskw} HSK, {cedw} from CC-CEDICT; {(len(items) + CHUNK - 1) // CHUNK} stroke files; "
          f"{len(sents)} sentences, {sum(1 for s in sents if s[3] < 300)} readable by no. 300")


if __name__ == "__main__":
    main()
